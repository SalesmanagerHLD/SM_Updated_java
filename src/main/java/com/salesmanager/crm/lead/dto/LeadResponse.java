package com.salesmanager.crm.lead.dto;

import com.salesmanager.crm.lead.Lead;
import com.salesmanager.crm.lead.LeadStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only full projection of a Lead. Every master-data reference is kept as a raw id (not
 * a resolved label) - same convention as EmployeeResponse's designationId/cityId - the
 * frontend resolves these against its own master-data dropdown lookups.
 */
public record LeadResponse(
        UUID id,
        UUID organizationId,
        String companyName,
        UUID industryId,
        String industryOther,
        UUID businessTypeId,
        String businessTypeOther,
        UUID leadSourceId,
        String leadSourceOther,
        BigDecimal turnover,
        String contactPerson,
        UUID designationId,
        String designationOther,
        String contactNo,
        String email,
        UUID stateId,
        String stateOther,
        UUID cityId,
        String cityOther,
        String address,
        String requirements,
        UUID interestLevelId,
        String interestLevelOther,
        String currentProductSolution,
        String budgetRange,
        Boolean decisionMakerIdentified,
        String objections,
        String remarks,
        LocalDate nextFollowupDate,
        LocalDate expectedCloseDate,
        UUID lostReasonId,
        String lostReasonOther,
        LeadStatus status,
        UUID ownerId,
        UUID createdBy,
        Set<UUID> productIds,
        Set<UUID> interestReasonIds,
        String productsOther,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static LeadResponse from(Lead lead) {
        return new LeadResponse(
                lead.getId(),
                lead.getOrganizationId(),
                lead.getCompanyName(),
                lead.getIndustryId(),
                lead.getIndustryOther(),
                lead.getBusinessTypeId(),
                lead.getBusinessTypeOther(),
                lead.getLeadSourceId(),
                lead.getLeadSourceOther(),
                lead.getTurnover(),
                lead.getContactPerson(),
                lead.getDesignationId(),
                lead.getDesignationOther(),
                lead.getContactNo(),
                lead.getEmail(),
                lead.getStateId(),
                lead.getStateOther(),
                lead.getCityId(),
                lead.getCityOther(),
                lead.getAddress(),
                lead.getRequirements(),
                lead.getInterestLevelId(),
                lead.getInterestLevelOther(),
                lead.getCurrentProductSolution(),
                lead.getBudgetRange(),
                lead.getDecisionMakerIdentified(),
                lead.getObjections(),
                lead.getRemarks(),
                lead.getNextFollowupDate(),
                lead.getExpectedCloseDate(),
                lead.getLostReasonId(),
                lead.getLostReasonOther(),
                lead.getStatus(),
                lead.getOwnerId(),
                lead.getCreatedBy(),
                lead.getProductIds(),
                lead.getInterestReasonIds(),
                lead.getProductsOther(),
                lead.getCreatedAt(),
                lead.getUpdatedAt());
    }
}
