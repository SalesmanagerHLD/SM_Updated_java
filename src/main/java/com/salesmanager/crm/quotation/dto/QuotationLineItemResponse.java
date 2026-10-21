package com.salesmanager.crm.quotation.dto;

import com.salesmanager.crm.quotation.QuotationLineItem;
import java.math.BigDecimal;
import java.util.UUID;

public record QuotationLineItemResponse(
        UUID id,
        UUID productId,
        String hsnSac,
        String description,
        BigDecimal quantity,
        String unit,
        BigDecimal unitPrice,
        BigDecimal discountPercent,
        BigDecimal taxRatePercent,
        BigDecimal lineSubtotal,
        BigDecimal lineDiscountAmount,
        BigDecimal lineTaxableAmount,
        BigDecimal lineCgstAmount,
        BigDecimal lineIgstAmount,
        BigDecimal lineSgstAmount,
        BigDecimal lineTotal,
        int sortOrder) {

    public static QuotationLineItemResponse from(QuotationLineItem item) {
        return new QuotationLineItemResponse(
                item.getId(),
                item.getProductId(),
                item.getHsnSac(),
                item.getDescription(),
                item.getQuantity(),
                item.getUnit(),
                item.getUnitPrice(),
                item.getDiscountPercent(),
                item.getTaxRatePercent(),
                item.getLineSubtotal(),
                item.getLineDiscountAmount(),
                item.getLineTaxableAmount(),
                item.getLineCgstAmount(),
                item.getLineIgstAmount(),
                item.getLineSgstAmount(),
                item.getLineTotal(),
                item.getSortOrder());
    }
}
