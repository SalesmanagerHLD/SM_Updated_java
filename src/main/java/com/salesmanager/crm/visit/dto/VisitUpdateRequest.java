package com.salesmanager.crm.visit.dto;

import com.salesmanager.crm.common.validation.NotPastDate;
import com.salesmanager.crm.visit.VisitType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

/**
 * All fields optional/nullable - only fields actually present (non-null) are applied by
 * VisitService#update, same partial-update style as LeadUpdateRequest. status is
 * deliberately NOT here - only PATCH /visits/{id}/status can change it, via
 * VisitStatusUpdateRequest.
 *
 * purposeId/designationId/stateId/cityId/interestLevelId each have a mutually-exclusive
 * *Other free-text counterpart - see VisitCreateRequest's javadoc. VisitService#update applies
 * whichever of a pair is supplied and clears the other.
 */
public record VisitUpdateRequest(
        @NotPastDate
        LocalDate visitDate,

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
        LocalDate nextVisitDate) {
}
