package com.salesmanager.crm.employee.dto;

/**
 * One row that could not be imported. {@code rowNumber} is the 1-based row number as it appears
 * in the spreadsheet/CSV itself (row 1 is the header, so the first data row is row 2).
 */
public record EmployeeImportRowError(int rowNumber, String email, String message) {
}
