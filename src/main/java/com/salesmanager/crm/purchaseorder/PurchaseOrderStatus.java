package com.salesmanager.crm.purchaseorder;

/**
 * RECEIVED -> ACCEPTED is a manual step. PARTLY_BILLED / FULLY_BILLED are derived from the lines'
 * billed quantities and move forward and back as invoices are created and cancelled.
 * CANCELLED is terminal.
 */
public enum PurchaseOrderStatus {
    RECEIVED, ACCEPTED, PARTLY_BILLED, FULLY_BILLED, CANCELLED;

    public boolean isBillable() {
        return this == ACCEPTED || this == PARTLY_BILLED;
    }
}
