package com.salesmanager.crm.purchaseorder.dto;

import com.salesmanager.crm.purchaseorder.PurchaseOrderLine;
import java.math.BigDecimal;
import java.util.UUID;

public record PurchaseOrderLineResponse(
        UUID id,
        UUID productId,
        UUID quotationLineId,
        String description,
        String hsnSac,
        String unit,
        BigDecimal quantityOrdered,
        BigDecimal quantityBilled,
        BigDecimal quantityRemaining,
        BigDecimal rate,
        BigDecimal taxRatePercent,
        BigDecimal lineTaxableAmount,
        BigDecimal lineTaxAmount,
        BigDecimal lineTotal,
        /** Net rate on the linked quotation line, or null when there is none. */
        BigDecimal quotationRate,
        /** Flag only - never blocks anything. */
        boolean priceDiffersFromQuotation) {

    public static PurchaseOrderLineResponse from(PurchaseOrderLine line, BigDecimal quotationRate) {
        boolean differs = quotationRate != null && quotationRate.compareTo(line.getRate()) != 0;
        return new PurchaseOrderLineResponse(line.getId(), line.getProductId(), line.getQuotationLineId(),
                line.getDescription(), line.getHsnSac(), line.getUnit(), line.getQuantityOrdered(),
                line.getQuantityBilled(), line.getQuantityRemaining(), line.getRate(), line.getTaxRatePercent(),
                line.getLineTaxableAmount(),
                line.getLineCgstAmount().add(line.getLineSgstAmount()).add(line.getLineIgstAmount()),
                line.getLineTotal(), quotationRate, differs);
    }
}
