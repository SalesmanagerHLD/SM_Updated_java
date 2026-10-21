package com.salesmanager.crm.employee;

import com.salesmanager.crm.employee.dto.EmployeeCreateRequest;
import com.salesmanager.crm.employee.dto.EmployeeImportResultResponse;
import com.salesmanager.crm.employee.dto.EmployeeImportRowError;
import com.salesmanager.crm.leadimport.UnsupportedImportFileException;
import com.salesmanager.crm.leadimport.parser.CsvSpreadsheetParser;
import com.salesmanager.crm.leadimport.parser.ExcelSpreadsheetParser;
import com.salesmanager.crm.leadimport.parser.SpreadsheetParser;
import com.salesmanager.crm.masterdata.InvalidReferenceException;
import com.salesmanager.crm.masterdata.MasterData;
import com.salesmanager.crm.masterdata.MasterDataRepository;
import com.salesmanager.crm.masterdata.MasterType;
import com.salesmanager.crm.security.CurrentUser;
import com.salesmanager.crm.common.NotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Bulk-creates Employees from an uploaded .xlsx/.csv file. Columns are matched by HEADER NAME
 * (case/space/underscore-insensitive, see {@link #normalize}), so column order doesn't matter:
 *
 * <pre>
 * required: fullName, email, password (min 8 chars), role (ADMIN | EMPLOYEE)
 * optional: phone (10 digits), designation, state, city, managerEmail, products
 * </pre>
 *
 * designation/state/city/products are matched case-insensitively by master-data LABEL (products
 * is a ';' or '|' separated list); an unknown label is a row error, since Employee - unlike Lead -
 * has no free-text "other" fallback. managerEmail must be an existing employee OR a row EARLIER
 * in the same file (rows are created in order), which also makes hierarchy cycles impossible.
 * Each row goes through {@link EmployeeService#create}, so every single-create rule (one admin
 * per org, reference validation, ...) applies identically. A bad row never aborts the rest.
 */
@Service
public class EmployeeImportService {

    private static final Map<String, String> HEADER_ALIASES = Map.ofEntries(
            Map.entry("name", "fullname"),
            Map.entry("employeename", "fullname"),
            Map.entry("emailaddress", "email"),
            Map.entry("mobile", "phone"),
            Map.entry("phoneno", "phone"),
            Map.entry("phonenumber", "phone"),
            Map.entry("contactno", "phone"),
            Map.entry("manager", "manageremail"),
            Map.entry("product", "products"),
            Map.entry("assignedproducts", "products"));

    private final EmployeeService employeeService;
    private final EmployeeRepository employeeRepository;
    private final MasterDataRepository masterDataRepository;
    private final Validator validator;
    private final CurrentUser currentUser;
    private final ExcelSpreadsheetParser excelSpreadsheetParser;
    private final CsvSpreadsheetParser csvSpreadsheetParser;

    public EmployeeImportService(EmployeeService employeeService,
                                  EmployeeRepository employeeRepository,
                                  MasterDataRepository masterDataRepository,
                                  Validator validator,
                                  CurrentUser currentUser,
                                  ExcelSpreadsheetParser excelSpreadsheetParser,
                                  CsvSpreadsheetParser csvSpreadsheetParser) {
        this.employeeService = employeeService;
        this.employeeRepository = employeeRepository;
        this.masterDataRepository = masterDataRepository;
        this.validator = validator;
        this.currentUser = currentUser;
        this.excelSpreadsheetParser = excelSpreadsheetParser;
        this.csvSpreadsheetParser = csvSpreadsheetParser;
    }

    // noRollbackFor mirrors EmployeeService: row-level failures are reported, never rolled back.
    @Transactional(noRollbackFor = {UnsupportedImportFileException.class, InvalidReferenceException.class,
            NotFoundException.class, MultipleAdminNotAllowedException.class})
    public EmployeeImportResultResponse importEmployees(MultipartFile file) {
        List<List<String>> rows = parseFile(file);
        if (rows.isEmpty()) {
            throw new UnsupportedImportFileException("The file is empty - a header row is required");
        }

        Map<String, Integer> columns = mapHeaders(rows.get(0));
        List<String> missingHeaders = new ArrayList<>();
        for (String required : List.of("fullname", "email", "password", "role")) {
            if (!columns.containsKey(required)) {
                missingHeaders.add(required);
            }
        }
        if (!missingHeaders.isEmpty()) {
            throw new UnsupportedImportFileException("Missing required column(s): "
                    + String.join(", ", missingHeaders)
                    + ". Required columns: fullName, email, password, role");
        }

        List<List<String>> dataRows = rows.subList(1, rows.size());
        Map<MasterType, Map<String, UUID>> labelMaps = Map.of(
                MasterType.DESIGNATION, loadLabelMap(MasterType.DESIGNATION),
                MasterType.STATE, loadLabelMap(MasterType.STATE),
                MasterType.PRODUCT, loadLabelMap(MasterType.PRODUCT));
        List<MasterData> cities = masterDataRepository.findByTypeAndActive(MasterType.CITY, true, Sort.unsorted());

        List<EmployeeImportRowError> errors = new ArrayList<>();
        int imported = 0;
        for (int i = 0; i < dataRows.size(); i++) {
            int rowNumber = i + 2;
            List<String> row = dataRows.get(i);
            String email = cell(row, columns, "email");
            try {
                String error = importRow(row, columns, labelMaps, cities);
                if (error == null) {
                    imported++;
                } else {
                    errors.add(new EmployeeImportRowError(rowNumber, email, error));
                }
            } catch (InvalidReferenceException | NotFoundException | MultipleAdminNotAllowedException e) {
                errors.add(new EmployeeImportRowError(rowNumber, email, e.getMessage()));
            } catch (Exception e) {
                errors.add(new EmployeeImportRowError(rowNumber, email, "Unexpected error: " + e.getMessage()));
            }
        }
        return new EmployeeImportResultResponse(dataRows.size(), imported, errors.size(), errors);
    }

    /** Returns null on success, otherwise the row's error message. */
    private String importRow(List<String> row, Map<String, Integer> columns,
                              Map<MasterType, Map<String, UUID>> labelMaps, List<MasterData> cities) {
        String email = cell(row, columns, "email");
        String roleText = cell(row, columns, "role");
        Role role = null;
        for (Role candidate : Role.values()) {
            if (candidate.name().equalsIgnoreCase(roleText)) {
                role = candidate;
            }
        }
        if (!roleText.isBlank() && role == null) {
            return "role must be ADMIN or EMPLOYEE (got '" + roleText + "')";
        }

        if (!email.isBlank() && employeeRepository
                .findByOrganizationIdAndEmail(currentOrgId(), email).isPresent()) {
            return "An employee with this email already exists";
        }

        List<String> problems = new ArrayList<>();
        UUID designationId = lookup(cell(row, columns, "designation"), labelMaps.get(MasterType.DESIGNATION),
                "designation", problems);
        UUID stateId = lookup(cell(row, columns, "state"), labelMaps.get(MasterType.STATE), "state", problems);
        UUID cityId = lookupCity(cell(row, columns, "city"), cities, stateId, problems);

        Set<UUID> productIds = new LinkedHashSet<>();
        for (String label : cell(row, columns, "products").split("[;|]")) {
            if (!label.isBlank()) {
                UUID id = lookup(label.trim(), labelMaps.get(MasterType.PRODUCT), "product", problems);
                if (id != null) {
                    productIds.add(id);
                }
            }
        }

        UUID managerId = null;
        String managerEmail = cell(row, columns, "manageremail");
        if (!managerEmail.isBlank()) {
            managerId = employeeRepository.findByOrganizationIdAndEmail(currentOrgId(), managerEmail)
                    .map(Employee::getId).orElse(null);
            if (managerId == null) {
                problems.add("managerEmail '" + managerEmail
                        + "' is not an existing employee (a manager must already exist or appear in an earlier row)");
            }
        }

        String phone = cell(row, columns, "phone");
        EmployeeCreateRequest request = new EmployeeCreateRequest(
                cell(row, columns, "fullname"), email, phone.isBlank() ? null : phone,
                cell(row, columns, "password"), role, designationId, cityId, stateId, productIds, managerId);
        for (ConstraintViolation<EmployeeCreateRequest> v : validator.validate(request)) {
            problems.add(v.getMessage());
        }
        if (!problems.isEmpty()) {
            return String.join("; ", problems);
        }

        employeeService.create(request);
        return null;
    }

    private UUID lookup(String label, Map<String, UUID> labelMap, String field, List<String> problems) {
        if (label.isBlank()) {
            return null;
        }
        UUID id = labelMap.get(label.toLowerCase(Locale.ROOT));
        if (id == null) {
            problems.add("Unknown " + field + " '" + label + "'");
        }
        return id;
    }

    /** Prefers a city under the row's state; falls back to any city with that label. */
    private UUID lookupCity(String label, List<MasterData> cities, UUID stateId, List<String> problems) {
        if (label.isBlank()) {
            return null;
        }
        List<MasterData> matches = cities.stream()
                .filter(c -> c.getLabel().equalsIgnoreCase(label)).toList();
        if (matches.isEmpty()) {
            problems.add("Unknown city '" + label + "'");
            return null;
        }
        if (stateId != null) {
            return matches.stream().filter(c -> stateId.equals(c.getParentId())).findFirst()
                    .map(MasterData::getId)
                    .orElseGet(() -> {
                        problems.add("City '" + label + "' does not belong to the given state");
                        return null;
                    });
        }
        return matches.get(0).getId();
    }

    private Map<String, UUID> loadLabelMap(MasterType type) {
        return masterDataRepository.findByTypeAndActive(type, true, Sort.unsorted()).stream()
                .collect(Collectors.toMap(m -> m.getLabel().toLowerCase(Locale.ROOT), MasterData::getId,
                        (first, second) -> first));
    }

    private UUID currentOrgId() {
        return currentUser.get().getOrganizationId();
    }

    private Map<String, Integer> mapHeaders(List<String> headers) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String key = normalize(headers.get(i));
            columns.putIfAbsent(HEADER_ALIASES.getOrDefault(key, key), i);
        }
        return columns;
    }

    private static String normalize(String header) {
        return header == null ? "" : header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String cell(List<String> row, Map<String, Integer> columns, String key) {
        Integer index = columns.get(key);
        if (index == null || index >= row.size() || row.get(index) == null) {
            return "";
        }
        return row.get(index).trim();
    }

    private List<List<String>> parseFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new UnsupportedImportFileException("No file was uploaded");
        }
        String filename = file.getOriginalFilename();
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        SpreadsheetParser parser;
        if (lower.endsWith(".xlsx")) {
            parser = excelSpreadsheetParser;
        } else if (lower.endsWith(".csv")) {
            parser = csvSpreadsheetParser;
        } else {
            throw new UnsupportedImportFileException(
                    "Unsupported file type - only .xlsx and .csv files are supported (got '" + filename + "')");
        }
        try {
            return parser.parse(file);
        } catch (IOException e) {
            throw new UnsupportedImportFileException("Could not parse the uploaded file: " + e.getMessage());
        }
    }
}
