package com.salesmanager.crm.common;

import com.salesmanager.crm.attendance.AlreadyClockedInException;
import com.salesmanager.crm.attendance.InvalidAttendanceStateException;
import com.salesmanager.crm.employee.MultipleAdminNotAllowedException;
import com.salesmanager.crm.entitlement.FeatureNotEntitledException;
import com.salesmanager.crm.inventory.InsufficientStockException;
import com.salesmanager.crm.inventory.InvalidStockAdjustmentException;
import com.salesmanager.crm.invoicing.BillingProfileIncompleteException;
import com.salesmanager.crm.invoicing.InvalidInvoiceLineItemException;
import com.salesmanager.crm.invoicing.InvalidInvoiceStateException;
import com.salesmanager.crm.purchaseorder.InvalidPurchaseOrderStateException;
import com.salesmanager.crm.purchaseorder.OverBillingException;
import com.salesmanager.crm.purchaseorder.PurchaseOrderLineException;
import com.salesmanager.crm.quotation.InvalidQuotationLineItemException;
import com.salesmanager.crm.quotation.InvalidQuotationStateException;
import com.salesmanager.crm.invoicing.InvalidLogoException;
import com.salesmanager.crm.lead.InvalidLeadStatusException;
import com.salesmanager.crm.leadattachment.InvalidAttachmentException;
import com.salesmanager.crm.leadimport.UnsupportedImportFileException;
import com.salesmanager.crm.leave.DuplicateHolidayException;
import com.salesmanager.crm.leave.DuplicateLeaveTypeException;
import com.salesmanager.crm.leave.InsufficientLeaveBalanceException;
import com.salesmanager.crm.leave.InvalidLeaveRequestStateException;
import com.salesmanager.crm.leave.LeaveRequestConflictException;
import com.salesmanager.crm.masterdata.DuplicateMasterDataException;
import com.salesmanager.crm.masterdata.InvalidReferenceException;
import com.salesmanager.crm.theme.InvalidThemeException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                           HttpServletRequest request) {
        List<ErrorResponse.FieldErrorDetail> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> ErrorResponse.FieldErrorDetail.builder()
                        .field(fe.getField())
                        .message(fe.getDefaultMessage())
                        .build())
                .toList();

        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message("Validation failed")
                .path(request.getRequestURI())
                .fieldErrors(fieldErrors)
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.NOT_FOUND.value())
                .error(HttpStatus.NOT_FOUND.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .fieldErrors(List.of())
                .build();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(DuplicateMasterDataException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateMasterData(DuplicateMasterDataException ex,
                                                                     HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error(HttpStatus.CONFLICT.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .fieldErrors(List.of())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(DuplicateLeaveTypeException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateLeaveType(DuplicateLeaveTypeException ex,
                                                                    HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error(HttpStatus.CONFLICT.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .fieldErrors(List.of())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(DuplicateHolidayException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateHoliday(DuplicateHolidayException ex,
                                                                  HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error(HttpStatus.CONFLICT.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .fieldErrors(List.of())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(LeaveRequestConflictException.class)
    public ResponseEntity<ErrorResponse> handleLeaveRequestConflict(LeaveRequestConflictException ex,
                                                                      HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error(HttpStatus.CONFLICT.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .fieldErrors(List.of())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(InvalidLeaveRequestStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidLeaveRequestState(InvalidLeaveRequestStateException ex,
                                                                          HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error(HttpStatus.CONFLICT.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .fieldErrors(List.of())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(InvalidLeadStatusException.class)
    public ResponseEntity<ErrorResponse> handleInvalidLeadStatus(InvalidLeadStatusException ex,
                                                                    HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error(HttpStatus.CONFLICT.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .fieldErrors(List.of())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(MultipleAdminNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleMultipleAdminNotAllowed(MultipleAdminNotAllowedException ex,
                                                                          HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error(HttpStatus.CONFLICT.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .fieldErrors(List.of())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(AlreadyClockedInException.class)
    public ResponseEntity<ErrorResponse> handleAlreadyClockedIn(AlreadyClockedInException ex,
                                                                  HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error(HttpStatus.CONFLICT.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .fieldErrors(List.of())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(InvalidAttendanceStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidAttendanceState(InvalidAttendanceStateException ex,
                                                                        HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error(HttpStatus.CONFLICT.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .fieldErrors(List.of())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(InsufficientLeaveBalanceException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientLeaveBalance(InsufficientLeaveBalanceException ex,
                                                                          HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error(HttpStatus.CONFLICT.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .fieldErrors(List.of())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(InvalidReferenceException.class)
    public ResponseEntity<ErrorResponse> handleInvalidReference(InvalidReferenceException ex,
                                                                  HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message("Validation failed")
                .path(request.getRequestURI())
                .fieldErrors(List.of(ErrorResponse.FieldErrorDetail.builder()
                        .field(ex.getField())
                        .message(ex.getMessage())
                        .build()))
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(InvalidStockAdjustmentException.class)
    public ResponseEntity<ErrorResponse> handleInvalidStockAdjustment(InvalidStockAdjustmentException ex,
                                                                        HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message("Validation failed")
                .path(request.getRequestURI())
                .fieldErrors(List.of(ErrorResponse.FieldErrorDetail.builder()
                        .field(ex.getField())
                        .message(ex.getMessage())
                        .build()))
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(InvalidInvoiceLineItemException.class)
    public ResponseEntity<ErrorResponse> handleInvalidInvoiceLineItem(InvalidInvoiceLineItemException ex,
                                                                        HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message("Validation failed")
                .path(request.getRequestURI())
                .fieldErrors(List.of(ErrorResponse.FieldErrorDetail.builder()
                        .field(ex.getField())
                        .message(ex.getMessage())
                        .build()))
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(InvalidLogoException.class)
    public ResponseEntity<ErrorResponse> handleInvalidLogo(InvalidLogoException ex, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message("Validation failed")
                .path(request.getRequestURI())
                .fieldErrors(List.of(ErrorResponse.FieldErrorDetail.builder()
                        .field(ex.getField())
                        .message(ex.getMessage())
                        .build()))
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // Same rationale/status as InsufficientLeaveBalanceException - a business-state conflict
    // ("not enough stock right now"), not a malformed payload.
    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientStock(InsufficientStockException ex,
                                                                    HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error(HttpStatus.CONFLICT.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .fieldErrors(List.of())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(InvalidAttachmentException.class)
    public ResponseEntity<ErrorResponse> handleInvalidAttachment(InvalidAttachmentException ex,
                                                                    HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .fieldErrors(List.of())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(UnsupportedImportFileException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedImportFile(UnsupportedImportFileException ex,
                                                                        HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .fieldErrors(List.of())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(InvalidThemeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTheme(InvalidThemeException ex, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message("Validation failed")
                .path(request.getRequestURI())
                .fieldErrors(List.of(ErrorResponse.FieldErrorDetail.builder()
                        .field(ex.getField())
                        .message(ex.getMessage())
                        .build()))
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** A request body that isn't readable as the target type (malformed JSON, or an enum value
     * outside its allowed set) is the client's mistake - 400, not the catch-all 500. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                              HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message("Request body is malformed or contains an unsupported value")
                .path(request.getRequestURI())
                .fieldErrors(List.of())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                             HttpServletRequest request) {
        String message = "Invalid value '" + ex.getValue() + "' for parameter '" + ex.getName() + "'";
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message(message)
                .path(request.getRequestURI())
                .fieldErrors(List.of(ErrorResponse.FieldErrorDetail.builder()
                        .field(ex.getName())
                        .message(message)
                        .build()))
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // AccessDeniedException from @PreAuthorize is thrown during controller-method invocation
    // (an AOP interceptor around the bean method), which Spring MVC's own exception
    // resolution catches BEFORE it can bubble up to Spring Security's
    // ExceptionTranslationFilter/accessDeniedHandler in SecurityConfig - so without this
    // handler it falls through to the generic 500 fallback below instead of a 403, and (worse)
    // trips "response already committed" errors when ExceptionTranslationFilter later also
    // tries to handle the same exception on the way back up the filter chain.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.FORBIDDEN.value())
                .error(HttpStatus.FORBIDDEN.getReasonPhrase())
                .message("You do not have permission to perform this action")
                .path(request.getRequestURI())
                .fieldErrors(List.of())
                .build();
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    // 403, but with error = "FEATURE_NOT_ENTITLED" (not AccessDeniedException's generic
    // "Forbidden" reason phrase) so a frontend can distinguish "you personally aren't
    // allowed" from "your org hasn't licensed this feature" and render an upgrade message
    // rather than a bare permission error. ErrorResponse.status stays 403 either way.
    @ExceptionHandler(FeatureNotEntitledException.class)
    public ResponseEntity<ErrorResponse> handleFeatureNotEntitled(FeatureNotEntitledException ex,
                                                                    HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.FORBIDDEN.value())
                .error("FEATURE_NOT_ENTITLED")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .fieldErrors(List.of())
                .build();
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler({BadCredentialsException.class, AuthenticationException.class})
    public ResponseEntity<ErrorResponse> handleAuthFailure(Exception ex, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.UNAUTHORIZED.value())
                .error(HttpStatus.UNAUTHORIZED.getReasonPhrase())
                .message(ex.getMessage() != null ? ex.getMessage() : "Authentication failed")
                .path(request.getRequestURI())
                .fieldErrors(List.of())
                .build();
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    @ExceptionHandler(InvalidInvoiceStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidInvoiceState(InvalidInvoiceStateException ex, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error(HttpStatus.CONFLICT.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .fieldErrors(List.of())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(InvalidQuotationStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidQuotationState(InvalidQuotationStateException ex, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error(HttpStatus.CONFLICT.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .fieldErrors(List.of())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(BillingProfileIncompleteException.class)
    public ResponseEntity<ErrorResponse> handleBillingProfileIncomplete(BillingProfileIncompleteException ex, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.UNPROCESSABLE_ENTITY.value())
                .error(HttpStatus.UNPROCESSABLE_ENTITY.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .fieldErrors(List.of())
                .build();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(InvalidQuotationLineItemException.class)
    public ResponseEntity<ErrorResponse> handleInvalidQuotationLineItem(InvalidQuotationLineItemException ex,
                                                                          HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message("Validation failed")
                .path(request.getRequestURI())
                .fieldErrors(List.of(ErrorResponse.FieldErrorDetail.builder()
                        .field(ex.getField())
                        .message(ex.getMessage())
                        .build()))
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(InvalidPurchaseOrderStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPurchaseOrderState(InvalidPurchaseOrderStateException ex,
                                                                           HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error(HttpStatus.CONFLICT.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .fieldErrors(List.of())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(PurchaseOrderLineException.class)
    public ResponseEntity<ErrorResponse> handlePurchaseOrderLine(PurchaseOrderLineException ex,
                                                                   HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message("Validation failed")
                .path(request.getRequestURI())
                .fieldErrors(List.of(ErrorResponse.FieldErrorDetail.builder()
                        .field(ex.getField())
                        .message(ex.getMessage())
                        .build()))
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** 409. {@code error} is OVER_BILLING_OVERRIDE_REQUIRED when an admin may retry with a reason,
     * OVER_BILLING otherwise; fieldErrors has one entry per offending PO line (field = poLineId). */
    @ExceptionHandler(OverBillingException.class)
    public ResponseEntity<ErrorResponse> handleOverBilling(OverBillingException ex, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error(ex.isOverridable() ? "OVER_BILLING_OVERRIDE_REQUIRED" : "OVER_BILLING")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .fieldErrors(ex.getViolations().stream()
                        .map(v -> ErrorResponse.FieldErrorDetail.builder()
                                .field(String.valueOf(v.poLineId()))
                                .message(v.description() + ": requested " + v.requested().toPlainString()
                                        + ", remaining " + v.remaining().toPlainString())
                                .build())
                        .toList())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleFallback(Exception ex, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(OffsetDateTime.now())
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase())
                .message(ex.getMessage() != null ? ex.getMessage() : "Unexpected error")
                .path(request.getRequestURI())
                .fieldErrors(List.of())
                .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
