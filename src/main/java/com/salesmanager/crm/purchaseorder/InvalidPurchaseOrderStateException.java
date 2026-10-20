package com.salesmanager.crm.purchaseorder;

/** A status transition or edit the PO's current state doesn't allow, or a duplicate PO number.
 * Mapped to 409 Conflict by GlobalExceptionHandler. */
public class InvalidPurchaseOrderStateException extends RuntimeException {

    public InvalidPurchaseOrderStateException(String message) {
        super(message);
    }
}
