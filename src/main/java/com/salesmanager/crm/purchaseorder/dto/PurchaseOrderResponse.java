package com.salesmanager.crm.purchaseorder.dto;

import com.salesmanager.crm.invoicing.Invoice;
import com.salesmanager.crm.invoicing.InvoiceStatus;
import com.salesmanager.crm.purchaseorder.PurchaseOrder;
import com.salesmanager.crm.purchaseorder.PurchaseOrderStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record PurchaseOrderResponse(
        UUID id,
        UUID leadId,
        UUID quotationId,
        UUID customerId,
        UUID ownerId,
        String poNumber,
        LocalDate poDate,
        LocalDate deliveryDate,
        Integer paymentTermsDays,
        PurchaseOrderStatus status,
        String customerName,
        String customerGstin,
        String billingAddress,
        String shippingAddress,
        UUID placeOfSupplyStateId,
        BigDecimal taxableTotal,
        BigDecimal cgstTotal,
        BigDecimal sgstTotal,
        BigDecimal igstTotal,
        BigDecimal taxTotal,
        BigDecimal grandTotal,
        String notes,
        String cancelReason,
        OffsetDateTime cancelledAt,
        boolean hasPriceDifference,
        List<PurchaseOrderLineResponse> lineItems,
        List<LinkedInvoice> invoices,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public record LinkedInvoice(UUID id, String invoiceNumber, LocalDate invoiceDate, InvoiceStatus status,
                                 BigDecimal grandTotal, String poOverrideReason) {
        public static LinkedInvoice from(Invoice invoice) {
            return new LinkedInvoice(invoice.getId(), invoice.getInvoiceNumber(), invoice.getInvoiceDate(),
                    invoice.getStatus(), invoice.getGrandTotal(), invoice.getPoOverrideReason());
        }
    }

    public static PurchaseOrderResponse from(PurchaseOrder po, List<PurchaseOrderLineResponse> lines,
                                              List<Invoice> invoices) {
        return new PurchaseOrderResponse(po.getId(), po.getLeadId(), po.getQuotationId(), po.getCustomerId(),
                po.getOwnerId(), po.getPoNumber(), po.getPoDate(), po.getDeliveryDate(), po.getPaymentTermsDays(),
                po.getStatus(), po.getCustomerName(), po.getCustomerGstin(), po.getBillingAddress(),
                po.getShippingAddress(), po.getPlaceOfSupplyStateId(), po.getTaxableTotal(), po.getCgstTotal(),
                po.getSgstTotal(), po.getIgstTotal(), po.getTaxTotal(), po.getGrandTotal(), po.getNotes(),
                po.getCancelReason(), po.getCancelledAt(),
                lines.stream().anyMatch(PurchaseOrderLineResponse::priceDiffersFromQuotation),
                lines, invoices.stream().map(LinkedInvoice::from).toList(), po.getCreatedAt(), po.getUpdatedAt());
    }
}
