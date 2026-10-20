package com.salesmanager.crm.purchaseorder;

import lombok.Getter;

/** A malformed PO payload (no lines, bad quantity, unknown line). Mapped to 400. */
@Getter
public class PurchaseOrderLineException extends RuntimeException {

    private final String field;

    public PurchaseOrderLineException(String field, String message) {
        super(message);
        this.field = field;
    }
}
