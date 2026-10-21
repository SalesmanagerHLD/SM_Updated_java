package com.salesmanager.crm.visit.dto;

import com.salesmanager.crm.common.validation.NotPastDate;
import com.salesmanager.crm.visit.VisitStatus;
import com.salesmanager.crm.visit.VisitType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

/**
 * Only leadId/visitDate/visitType are required - everything else is optional, matching a
 * quick "just log the interaction" flow. {@code status} defaults to PLANNED in
 * VisitService#create when omitted; a client-supplied MISSED is rejected with 400 (only a
 * future scheduled job may set that). Whichever of contactPerson/designationId/contactNo/
 * email/cityId/address/budgetRange/interestLevelId are supplied here are ALSO synced back
 * onto the parent Lead - see VisitService#create's javadoc.
 *
 * purposeId/designationId/stateId/cityId/interestLevelId each have a mutually-exclusive
 * *Other free-text counterpart, for a value the operator typed because it isn't yet in master
 * data (VisitService#create enforces this via MasterDataService#validateCreatableField);
 * visitType is a plain hardcoded enum (not master-data-driven) and never gets one.
 * productsOther is a supplementary free-text note alongside productIds, not an either/or
 * fallback.
 */
public record VisitCreateRequest(
        @NotNull(message = "leadId is required")
        UUID leadId,

        @NotNull(message = "visitDate is required")
        @NotPastDate
        LocalDate visitDate,

        @NotNull(message = "visitType is required")
        VisitType visitType,

        LocalTime scheduledTime,

        UUID purposeId,

        @Size(max = 255, message = "purposeOther must be at most 255 characters")
        String purposeOther,

        UUID interestLevelId,

        @Size(max = 255, message = "interestLevelOther must be at most 255 characters")
        String interestLevelOther,

        String contactPerson,

        UUID designationId,

        @Size(max = 255, message = "designationOther must be at most 255 characters")
        String designationOther,

        String contactNo,

        @Email(message = "email must be a valid email address")
        String email,

        UUID stateId,

        @Size(max = 255, message = "stateOther must be at most 255 characters")
        String stateOther,

        UUID cityId,

        @Size(max = 255, message = "cityOther must be at most 255 characters")
        String cityOther,

        String address,

        String requirements,

        Set<UUID> productIds,

        Set<UUID> interestReasonIds,

        @Size(max = 2000, message = "productsOther must be at most 2000 characters")
        String productsOther,

        String budgetRange,

        Boolean decisionMakerIdentified,

        String objections,

        String remarks,

        @NotPastDate(message = "nextVisitDate must not be in the past")
        LocalDate nextVisitDate,

        VisitStatus status) {
}
