package com.salesmanager.crm.invoicing;

/** An invoice operation the invoice's current state doesn't allow (paying a cancelled invoice,
 * cancelling twice, overpaying). Mapped to 409 Conflict by GlobalExceptionHandler. */
public class InvalidInvoiceStateException extends RuntimeException {

    public InvalidInvoiceStateException(String message) {
        super(message);
    }
}
