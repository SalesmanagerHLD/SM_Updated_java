package com.salesmanager.crm.employee.dto;

import java.util.List;

/** {@code importedCount + errorCount} always equals {@code totalRows} (header excluded). */
public record EmployeeImportResultResponse(
        int totalRows,
        int importedCount,
        int errorCount,
        List<EmployeeImportRowError> errors) {
}
