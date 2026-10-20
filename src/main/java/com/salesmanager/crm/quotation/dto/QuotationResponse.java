package com.salesmanager.crm.quotation.dto;

import com.salesmanager.crm.quotation.Quotation;
import com.salesmanager.crm.quotation.QuotationLineItem;
import com.salesmanager.crm.quotation.QuotationStatus;
import com.salesmanager.crm.visit.VisitType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record QuotationResponse(
        UUID id,
        UUID organizationId,
        String quotationNumber,
        UUID leadId,
        UUID customerId,
        String customerName,
        String customerContactPerson,
        String customerDesignation,
        String customerPhone,
        String customerEmail,
        String customerBillingAddress,
        String customerGstin,
        UUID industryId,
        String industryOther,
        UUID cityId,
        String cityOther,
        UUID stateId,
        String stateOther,
        UUID interestLevelId,
        String interestLevelOther,
        UUID businessTypeId,
        String businessTypeOther,
        UUID ownerId,
        VisitType typeOfVisit,
        LocalDate quotationDate,
        LocalDate validTillDate,
        String referenceEnquiryNo,
        LocalDate expectedCloseDate,
        String quotationNotes,
        String termsAndConditions,
        String internalNote,
        LocalDate followUpDate,
        LocalTime followUpTime,
        UUID followUpByEmployeeId,
        String followUpNote,
        BigDecimal subtotal,
        BigDecimal discountTotal,
        BigDecimal taxableAmount,
        BigDecimal cgstTotal,
        BigDecimal sgstTotal,
        BigDecimal igstTotal,
        BigDecimal grandTotal,
        QuotationStatus status,
        UUID convertedInvoiceId,
        List<QuotationLineItemResponse> lineItems,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static QuotationResponse from(Quotation q, List<QuotationLineItem> lineItems) {
        return new QuotationResponse(
                q.getId(), q.getOrganizationId(), q.getQuotationNumber(), q.getLeadId(), q.getCustomerId(),
                q.getCustomerName(), q.getCustomerContactPerson(), q.getCustomerDesignation(), q.getCustomerPhone(),
                q.getCustomerEmail(), q.getCustomerBillingAddress(), q.getCustomerGstin(),
                q.getIndustryId(), q.getIndustryOther(), q.getCityId(), q.getCityOther(),
                q.getStateId(), q.getStateOther(), q.getInterestLevelId(), q.getInterestLevelOther(),
                q.getBusinessTypeId(), q.getBusinessTypeOther(), q.getOwnerId(), q.getTypeOfVisit(),
                q.getQuotationDate(), q.getValidTillDate(), q.getReferenceEnquiryNo(), q.getExpectedCloseDate(),
                q.getQuotationNotes(), q.getTermsAndConditions(), q.getInternalNote(),
                q.getFollowUpDate(), q.getFollowUpTime(), q.getFollowUpByEmployeeId(), q.getFollowUpNote(),
                q.getSubtotal(), q.getDiscountTotal(), q.getTaxableAmount(), q.getCgstTotal(), q.getSgstTotal(), q.getIgstTotal(),
                q.getGrandTotal(), q.getStatus(), q.getConvertedInvoiceId(),
                lineItems.stream().map(QuotationLineItemResponse::from).toList(),
                q.getCreatedAt(), q.getUpdatedAt());
    }
}
