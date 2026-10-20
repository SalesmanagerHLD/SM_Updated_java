package com.salesmanager.crm.lead.dto;

import com.salesmanager.crm.common.validation.NotPastDate;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Step-1-friendly creation: only companyName/contactPerson/contactNo/(cityId or cityOther)/
 * (leadSourceId or leadSourceOther)/(industryId or industryOther) are required, matching a
 * quick "just capture the basics" flow - everything else can be filled in later via
 * LeadUpdateRequest. Note: requirements was added here even though it's absent from the field
 * enumeration in the Phase 2 spec - it's a real column in V3__leads.sql and leaving it
 * unreachable via the API looked like an oversight rather than an intentional omission.
 *
 * industryId/businessTypeId/leadSourceId/designationId/stateId/cityId/interestLevelId each have
 * a "creatable" free-text *Other counterpart, for a value the operator typed because it isn't
 * yet in master data - the two are mutually exclusive (LeadService#create enforces this via
 * MasterDataService#validateCreatableField); productsOther is a supplementary free-text note
 * alongside productIds, not an either/or fallback, so it has no such pairing rule.
 * industryId/leadSourceId/cityId are no longer {@code @NotNull} here - each is required unless
 * its *Other counterpart is supplied instead (also enforced in LeadService#create).
 *
 * {@code logAsVisitToday} (Phase 3): when true, the touchpoint that led to creating this Lead
 * is itself logged as a completed Visit dated today (see LeadService#create /
 * visit.LeadVisitEventListener). Defaults to false/omitted - no such Visit is created.
 */
public record LeadCreateRequest(
        @NotBlank(message = "companyName is required")
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

        @NotBlank(message = "contactPerson is required")
        String contactPerson,

        UUID designationId,

        @Size(max = 255, message = "designationOther must be at most 255 characters")
        String designationOther,

        @NotBlank(message = "contactNo is required")
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

        /** Why the interest level was chosen (INTEREST_REASON ids under interestLevelId). */
        Set<UUID> interestReasonIds,

        @Size(max = 2000, message = "productsOther must be at most 2000 characters")
        String productsOther,

        Boolean logAsVisitToday,

        /**
         * Only meaningful when {@code logAsVisitToday=true} - which kind of touchpoint the
         * auto-created stub Visit represents (a phone call vs. an in-person visit). Kept as a
         * plain validated String rather than importing {@code visit.VisitType} here: the
         * {@code lead} package deliberately has no dependency on {@code visit} (see
         * {@code LeadCreatedEvent}'s class javadoc) - {@code visit.LeadVisitEventListener},
         * which already owns VisitType, converts this value itself. Defaults to "FIELD" when
         * omitted, matching this flag's pre-existing default behavior.
         */
        @Pattern(regexp = "FIELD|TELEPHONIC", message = "visitType must be FIELD or TELEPHONIC")
        String visitType) {
}
