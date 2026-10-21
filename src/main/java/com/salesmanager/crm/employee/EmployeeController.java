package com.salesmanager.crm.employee;

import com.salesmanager.crm.common.NotFoundException;
import com.salesmanager.crm.employee.dto.EmployeeCreateRequest;
import com.salesmanager.crm.employee.dto.EmployeeImportResultResponse;
import com.salesmanager.crm.employee.dto.EmployeeResponse;
import com.salesmanager.crm.employee.dto.EmployeeUpdateRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The GET endpoints below are unchanged from Phase 0 and deliberately still add NO manual
 * "WHERE organizationId = ..." filtering here or in the repository - the whole point of
 * these endpoints is to demonstrate that the Hibernate tenantFilter (and, as
 * defense-in-depth, Postgres RLS) transparently scope every query. Full Employee CRUD
 * (Phase 1) is orchestrated by EmployeeService; this controller stays thin.
 */
@RestController
@RequestMapping("/employees")
public class EmployeeController {

    private final EmployeeRepository employeeRepository;
    private final EmployeeService employeeService;
    private final EmployeeImportService employeeImportService;

    public EmployeeController(EmployeeRepository employeeRepository, EmployeeService employeeService,
                               EmployeeImportService employeeImportService) {
        this.employeeRepository = employeeRepository;
        this.employeeService = employeeService;
        this.employeeImportService = employeeImportService;
    }

    @GetMapping
    public Page<EmployeeResponse> list(Pageable pageable) {
        return employeeRepository.findAll(pageable).map(EmployeeResponse::from);
    }

    @GetMapping("/{id}")
    public EmployeeResponse getById(@PathVariable UUID id) {
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Employee not found: " + id));
        return EmployeeResponse.from(employee);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeResponse create(@Valid @RequestBody EmployeeCreateRequest request) {
        return EmployeeResponse.from(employeeService.create(request));
    }

    /** Bulk-create employees from a .xlsx/.csv file - see EmployeeImportService for the column spec. */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public EmployeeImportResultResponse importEmployees(@RequestPart("file") MultipartFile file) {
        return employeeImportService.importEmployees(file);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public EmployeeResponse update(@PathVariable UUID id, @Valid @RequestBody EmployeeUpdateRequest request) {
        return EmployeeResponse.from(employeeService.update(id, request));
    }

    @PatchMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public EmployeeResponse deactivate(@PathVariable UUID id) {
        return EmployeeResponse.from(employeeService.deactivate(id));
    }
}
