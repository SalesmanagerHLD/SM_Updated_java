package com.salesmanager.crm.visit.dto;

import com.salesmanager.crm.visit.Visit;
import com.salesmanager.crm.visit.VisitStatus;
import com.salesmanager.crm.visit.VisitType;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only full projection of a Visit. Every master-data reference is kept as a raw id (not
 * a resolved label) - same convention as LeadResponse - the frontend resolves these against
 * its own master-data dropdown lookups.
 */
public record VisitResponse(
        UUID id,
        UUID organizationId,
        UUID leadId,
        LocalDate visitDate,
        LocalTime scheduledTime,
        VisitType visitType,
        UUID purposeId,
        String purposeOther,
        UUID interestLevelId,
        String interestLevelOther,
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
        String budgetRange,
        Boolean decisionMakerIdentified,
        String objections,
        String remarks,
        LocalDate nextVisitDate,
        VisitStatus status,
        UUID createdBy,
        Set<UUID> productIds,
        Set<UUID> interestReasonIds,
        String productsOther,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static VisitResponse from(Visit visit) {
        return new VisitResponse(
                visit.getId(),
                visit.getOrganizationId(),
                visit.getLeadId(),
                visit.getVisitDate(),
                visit.getScheduledTime(),
                visit.getVisitType(),
                visit.getPurposeId(),
                visit.getPurposeOther(),
                visit.getInterestLevelId(),
                visit.getInterestLevelOther(),
                visit.getContactPerson(),
                visit.getDesignationId(),
                visit.getDesignationOther(),
                visit.getContactNo(),
                visit.getEmail(),
                visit.getStateId(),
                visit.getStateOther(),
                visit.getCityId(),
                visit.getCityOther(),
                visit.getAddress(),
                visit.getRequirements(),
                visit.getBudgetRange(),
                visit.getDecisionMakerIdentified(),
                visit.getObjections(),
                visit.getRemarks(),
                visit.getNextVisitDate(),
                visit.getStatus(),
                visit.getCreatedBy(),
                visit.getProductIds(),
                visit.getInterestReasonIds(),
                visit.getProductsOther(),
                visit.getCreatedAt(),
                visit.getUpdatedAt());
    }
}
