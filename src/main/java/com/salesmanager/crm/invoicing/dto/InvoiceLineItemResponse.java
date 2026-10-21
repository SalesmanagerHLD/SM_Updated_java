package com.salesmanager.crm.invoicing.dto;

import com.salesmanager.crm.invoicing.InvoiceLineItem;
import java.math.BigDecimal;
import java.util.UUID;

public record InvoiceLineItemResponse(
        UUID id,
        UUID productId,
        UUID poLineId,
        String hsnSac,
        String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal discountPercent,
        BigDecimal taxRatePercent,
        BigDecimal lineSubtotal,
        BigDecimal lineDiscountAmount,
        BigDecimal lineTaxAmount,
        BigDecimal lineCgstAmount,
        BigDecimal lineSgstAmount,
        BigDecimal lineIgstAmount,
        String unit,
        int sortOrder) {

    public static InvoiceLineItemResponse from(InvoiceLineItem item) {
        return new InvoiceLineItemResponse(
                item.getId(),
                item.getProductId(),
                item.getPoLineId(),
                item.getHsnSac(),
                item.getDescription(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getDiscountPercent(),
                item.getTaxRatePercent(),
                item.getLineSubtotal(),
                item.getLineDiscountAmount(),
                item.getLineTaxAmount(),
                item.getLineCgstAmount(),
                item.getLineSgstAmount(),
                item.getLineIgstAmount(),
                item.getUnit(),
                item.getSortOrder());
    }
}
