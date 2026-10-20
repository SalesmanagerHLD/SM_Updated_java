package com.salesmanager.crm.invoicing;

/** Kept in sync with the CHECK constraint on invoices.status (V12, widened in V23). A cache of
 * what the payments ledger says - set only by InvoiceService, never directly. */
public enum InvoiceStatus {
    UNPAID,
    PARTIALLY_PAID,
    PAID,
    CANCELLED;

    /** Statuses that still owe money - what the receivables/pending reports count. */
    public static final java.util.List<InvoiceStatus> OPEN_STATUSES = java.util.List.of(UNPAID, PARTIALLY_PAID);
}
