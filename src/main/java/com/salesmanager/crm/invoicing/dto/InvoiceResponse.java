package com.salesmanager.crm.invoicing.dto;

import com.salesmanager.crm.invoicing.Invoice;
import com.salesmanager.crm.invoicing.InvoiceLineItem;
import com.salesmanager.crm.invoicing.InvoiceStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record InvoiceResponse(
        UUID id,
        UUID organizationId,
        String invoiceNumber,
        UUID leadId,
        UUID ownerId,
        UUID createdBy,
        String customerName,
        String customerContactPerson,
        String customerPhone,
        String customerEmail,
        String customerAddress,
        String customerGstin,
        String shipToName,
        String shipToAddress,
        String shipToGstin,
        LocalDate invoiceDate,
        LocalDate dueDate,
        String placeOfSupply,
        boolean reverseCharge,
        UUID quotationId,
        UUID poId,
        String poNumber,
        LocalDate poDate,
        String poOverrideReason,
        BigDecimal subtotal,
        BigDecimal taxTotal,
        BigDecimal grandTotal,
        BigDecimal discountTotal,
        BigDecimal taxableAmount,
        BigDecimal cgstTotal,
        BigDecimal sgstTotal,
        BigDecimal igstTotal,
        UUID customerId,
        UUID placeOfSupplyStateId,
        String sellerName,
        String sellerGstin,
        UUID sellerStateId,
        BigDecimal amountPaid,
        BigDecimal balanceDue,
        OffsetDateTime cancelledAt,
        String cancelReason,
        InvoiceStatus status,
        String notes,
        List<InvoiceLineItemResponse> lineItems,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static InvoiceResponse from(Invoice invoice, List<InvoiceLineItem> lineItems, BigDecimal amountPaid) {
        BigDecimal paid = amountPaid != null ? amountPaid : BigDecimal.ZERO;
        BigDecimal balanceDue = invoice.getStatus() == InvoiceStatus.CANCELLED
                ? BigDecimal.ZERO : invoice.getGrandTotal().subtract(paid);
        return new InvoiceResponse(
                invoice.getId(),
                invoice.getOrganizationId(),
                invoice.getInvoiceNumber(),
                invoice.getLeadId(),
                invoice.getOwnerId(),
                invoice.getCreatedBy(),
                invoice.getCustomerName(),
                invoice.getCustomerContactPerson(),
                invoice.getCustomerPhone(),
                invoice.getCustomerEmail(),
                invoice.getCustomerAddress(),
                invoice.getCustomerGstin(),
                invoice.getShipToName(),
                invoice.getShipToAddress(),
                invoice.getShipToGstin(),
                invoice.getInvoiceDate(),
                invoice.getDueDate(),
                invoice.getPlaceOfSupply(),
                invoice.isReverseCharge(),
                invoice.getQuotationId(),
                invoice.getPoId(),
                invoice.getPoNumber(),
                invoice.getPoDate(),
                invoice.getPoOverrideReason(),
                invoice.getSubtotal(),
                invoice.getTaxTotal(),
                invoice.getGrandTotal(),
                invoice.getDiscountTotal(),
                invoice.getTaxableAmount(),
                invoice.getCgstTotal(),
                invoice.getSgstTotal(),
                invoice.getIgstTotal(),
                invoice.getCustomerId(),
                invoice.getPlaceOfSupplyStateId(),
                invoice.getSellerName(),
                invoice.getSellerGstin(),
                invoice.getSellerStateId(),
                paid,
                balanceDue,
                invoice.getCancelledAt(),
                invoice.getCancelReason(),
                invoice.getStatus(),
                invoice.getNotes(),
                lineItems.stream().map(InvoiceLineItemResponse::from).toList(),
                invoice.getCreatedAt(),
                invoice.getUpdatedAt());
    }
}
