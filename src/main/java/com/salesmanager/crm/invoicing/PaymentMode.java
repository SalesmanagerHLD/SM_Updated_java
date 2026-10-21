package com.salesmanager.crm.invoicing;

/** Kept in sync with the CHECK constraint on invoice_payments.mode in V23. */
public enum PaymentMode {
    CASH,
    UPI,
    CARD,
    BANK_TRANSFER,
    CHEQUE,
    OTHER
}
