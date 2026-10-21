package com.salesmanager.crm.purchaseorder.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A pre-filled body for POST /invoices - nothing is saved. Every line carries the PO line id and
 * the remaining quantity; the user reviews it and submits it as a normal invoice.
 */
public record InvoiceDraftResponse(
        UUID leadId,
        UUID customerId,
        UUID placeOfSupplyStateId,
        String customerName,
        String customerAddress,
        String customerGstin,
        String shipToName,
        String shipToAddress,
        LocalDate invoiceDate,
        LocalDate dueDate,
        UUID poId,
        String poNumber,
        LocalDate poDate,
        String notes,
        List<Line> lineItems) {

    public record Line(UUID poLineId, UUID productId, String description, String hsnSac, String unit,
                        BigDecimal quantity, BigDecimal unitPrice, BigDecimal taxRatePercent) {
    }
}
