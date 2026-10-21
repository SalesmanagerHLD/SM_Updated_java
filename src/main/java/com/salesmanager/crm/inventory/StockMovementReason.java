package com.salesmanager.crm.inventory;

/** Kept in sync with the CHECK constraint on stock_movements.reason in V11__inventory.sql (widened in V23). */
public enum StockMovementReason {
    MANUAL_ADJUSTMENT,
    INVOICE,
    INVOICE_CANCELLATION
}
