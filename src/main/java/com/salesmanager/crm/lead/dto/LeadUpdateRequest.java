package com.salesmanager.crm.lead.dto;

import com.salesmanager.crm.common.validation.NotPastDate;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * All fields optional/nullable - only fields actually present (non-null) are applied by
 * LeadService#update, same partial-update style as EmployeeUpdateRequest. Format constraints
 * (@Pattern/@Email/@Positive/@Size) still fire when a field IS supplied - Bean Validation only
 * skips a constraint when the value itself is null, not when the whole request omits it
 * entirely. status/lostReasonId/lostReasonOther are deliberately NOT here - only PATCH
 * /leads/{id}/status can change those, via LeadStatusUpdateRequest.
 *
 * industryId/businessTypeId/leadSourceId/designationId/stateId/cityId/interestLevelId each have
 * a mutually-exclusive *Other free-text counterpart - see LeadCreateRequest's javadoc.
 * LeadService#update applies whichever of a pair is supplied and clears the other, so a later
 * update touching only one member of a pair never leaves a stale value on the other.
 */
public record LeadUpdateRequest(
        String companyName,

        UUID industryId,

        @Size(max = 255, message = "industryOther must be at most 255 characters")
        String industryOther,

        UUID businessTypeId,

        @Size(max = 255, message = "businessTypeOther must be at most 255 characters")
        String businessTypeOther,

        UUID leadSourceId,

        @Size(max = 255, message = "leadSourceOther must be at most 255 characters")
        String leadSourceOther,

        @Positive(message = "turnover must be positive")
        BigDecimal turnover,

        String contactPerson,

        UUID designationId,

        @Size(max = 255, message = "designationOther must be at most 255 characters")
        String designationOther,

        @Pattern(regexp = "^\\d{10}$", message = "contactNo must be exactly 10 digits")
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

        UUID interestLevelId,

        @Size(max = 255, message = "interestLevelOther must be at most 255 characters")
        String interestLevelOther,

        String currentProductSolution,

        String budgetRange,

        Boolean decisionMakerIdentified,

        String objections,

        String remarks,

        @NotPastDate(message = "nextFollowupDate must not be in the past")
        LocalDate nextFollowupDate,

        @NotPastDate(message = "expectedCloseDate must not be in the past")
        LocalDate expectedCloseDate,

        Set<UUID> productIds,

        /** null = leave unchanged; an empty set clears the reasons. */
        Set<UUID> interestReasonIds,

        @Size(max = 2000, message = "productsOther must be at most 2000 characters")
        String productsOther) {
}
