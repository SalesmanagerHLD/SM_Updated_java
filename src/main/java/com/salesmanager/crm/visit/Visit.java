package com.salesmanager.crm.visit;

import com.salesmanager.crm.tenant.TenantAware;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;

/**
 * The concrete {@code @Filter} annotation lives here (not just on the mapped superclass
 * TenantAware) so the Hibernate `tenantFilter` WHERE clause is guaranteed to apply to this
 * entity's queries regardless of Hibernate version quirks around filter inheritance - same
 * pattern as Lead/Employee/MasterData.
 *
 * {@code leadId} is a raw id into the {@code leads} table (not a JPA relation), same
 * convention as Lead's own master-data reference fields - VisitService loads the parent Lead
 * via LeadRepository when it needs it (ownership checks, pre-fill sync-back). purposeId/
 * interestLevelId/designationId/cityId are raw ids into the shared master_data table -
 * MasterDataService#validateReference enforces the per-field type match at the service layer.
 */
@Entity
@Table(name = "visits")
@Filter(name = "tenantFilter", condition = "organization_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Visit extends TenantAware {

    @Column(name = "lead_id", nullable = false)
    private UUID leadId;

    @Column(name = "visit_date", nullable = false)
    private LocalDate visitDate;

    @Column(name = "scheduled_time")
    private LocalTime scheduledTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "visit_type", nullable = false, length = 20)
    private VisitType visitType;

    @Column(name = "purpose_id")
    private UUID purposeId;

    @Column(name = "interest_level_id")
    private UUID interestLevelId;

    @Column(name = "contact_person")
    private String contactPerson;

    @Column(name = "designation_id")
    private UUID designationId;

    @Column(name = "contact_no")
    private String contactNo;

    @Column
    private String email;

    @Column(name = "city_id")
    private UUID cityId;

    @Column(name = "state_id")
    private UUID stateId;

    @Column
    private String address;

    @Column
    private String requirements;

    @Column(name = "budget_range")
    private String budgetRange;

    @Column(name = "decision_maker_identified")
    private Boolean decisionMakerIdentified;

    @Column
    private String objections;

    @Column
    private String remarks;

    @Column(name = "next_visit_date")
    private LocalDate nextVisitDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private VisitStatus status = VisitStatus.PLANNED;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    /**
     * References master_data rows of type PRODUCT - same simple id-set pattern as
     * Lead.productIds/Employee.assignedProductIds.
     */
    @ElementCollection
    @CollectionTable(name = "visit_products", joinColumns = @JoinColumn(name = "visit_id"))
    @Column(name = "product_id")
    @Builder.Default
    private Set<UUID> productIds = new HashSet<>();

    /**
     * Free-text fallback for purposeId - set when the operator typed a value not yet present
     * in master data instead of picking an existing VISIT_PURPOSE row. Mutually exclusive with
     * purposeId; see MasterDataService#validateCreatableField.
     */
    @Column(name = "purpose_other")
    private String purposeOther;

    /** Free-text fallback for designationId - see purposeOther's javadoc above. */
    @Column(name = "designation_other")
    private String designationOther;

    /** Free-text fallback for stateId - see purposeOther's javadoc above. */
    @Column(name = "state_other")
    private String stateOther;

    /** Free-text fallback for cityId - see purposeOther's javadoc above. */
    @Column(name = "city_other")
    private String cityOther;

    /** Free-text fallback for interestLevelId - see purposeOther's javadoc above. */
    @Column(name = "interest_level_other")
    private String interestLevelOther;

    /**
     * Free-text supplement (not a fallback/either-or pairing like the other *Other fields
     * above) alongside productIds - see Lead#productsOther's identical javadoc.
     */
    @Column(name = "products_other")
    private String productsOther;

    /**
     * The provider's own event id (Google Calendar/Outlook), set once CalendarSyncEventListener
     * successfully creates the event - its presence is what tells the sync service "update"
     * instead of "create" on a later reschedule. Null if the owner has no calendar_connections
     * row, CALENDAR_SYNC isn't entitled for the org, or the most recent sync attempt failed (see
     * calendarSyncStatus/calendarSyncError below).
     */
    @Column(name = "external_calendar_event_id")
    private String externalCalendarEventId;

    /** SYNCED or FAILED - null means "never attempted" (no connection/entitlement at the time). */
    @Column(name = "calendar_sync_status", length = 20)
    private String calendarSyncStatus;

    /** Short human-readable reason for the most recent FAILED sync attempt - null otherwise. */
    @Column(name = "calendar_sync_error")
    private String calendarSyncError;

    /** References master_data rows of type INTEREST_REASON - why this visit has its interest
     * level. Every reason's parent must be the interestLevelId (validated in the service). */
    @ElementCollection
    @CollectionTable(name = "visit_interest_reasons", joinColumns = @JoinColumn(name = "visit_id"))
    @Column(name = "reason_id")
    @Builder.Default
    private Set<UUID> interestReasonIds = new HashSet<>();
}
