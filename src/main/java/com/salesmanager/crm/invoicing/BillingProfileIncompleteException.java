package com.salesmanager.crm.invoicing;

/** The tax type (CGST+SGST vs IGST) cannot be determined because the buyer's state is known but
 * the seller's state hasn't been set in the billing profile. Mapped to 422 by
 * GlobalExceptionHandler. */
public class BillingProfileIncompleteException extends RuntimeException {

    public BillingProfileIncompleteException(String message) {
        super(message);
    }
}
