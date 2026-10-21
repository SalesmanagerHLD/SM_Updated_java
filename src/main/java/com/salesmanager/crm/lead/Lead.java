package com.salesmanager.crm.lead;

import com.salesmanager.crm.tenant.TenantAware;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * pattern as Employee/MasterData.
 *
 * industryId/businessTypeId/leadSourceId/designationId/cityId/interestLevelId/lostReasonId
 * are all raw ids into the single shared master_data table, not JPA relations - see
 * Employee#designationId's javadoc for why. LeadService#validateReferences enforces the
 * per-field type match at the service layer.
 */
@Entity
@Table(name = "leads")
@Filter(name = "tenantFilter", condition = "organization_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Lead extends TenantAware {

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(name = "industry_id")
    private UUID industryId;

    @Column(name = "business_type_id")
    private UUID businessTypeId;

    @Column(name = "lead_source_id")
    private UUID leadSourceId;

    @Column
    private BigDecimal turnover;

    @Column(name = "contact_person", nullable = false)
    private String contactPerson;

    @Column(name = "designation_id")
    private UUID designationId;

    @Column(name = "contact_no", nullable = false)
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

    @Column(name = "interest_level_id")
    private UUID interestLevelId;

    @Column(name = "current_product_solution")
    private String currentProductSolution;

    @Column(name = "budget_range")
    private String budgetRange;

    @Column(name = "decision_maker_identified")
    private Boolean decisionMakerIdentified;

    @Column
    private String objections;

    @Column
    private String remarks;

    @Column(name = "next_followup_date")
    private LocalDate nextFollowupDate;

    @Column(name = "expected_close_date")
    private LocalDate expectedCloseDate;

    /** Set only via the Lost-lead status workflow (LeadService#updateStatus), never directly. */
    @Column(name = "lost_reason_id")
    private UUID lostReasonId;

    /**
     * Set only via the Lost-lead status workflow (LeadService#updateStatus), never directly -
     * same as lostReasonId, its mutually-exclusive free-text counterpart (see
     * MasterDataService#validateCreatableField).
     */
    @Column(name = "lost_reason_other")
    private String lostReasonOther;

    /**
     * Free-text fallback for industryId - set when the operator typed a value not yet present
     * in master data instead of picking an existing INDUSTRY row. Mutually exclusive with
     * industryId; see MasterDataService#validateCreatableField.
     */
    @Column(name = "industry_other")
    private String industryOther;

    /** Free-text fallback for businessTypeId - see industryOther's javadoc above. */
    @Column(name = "business_type_other")
    private String businessTypeOther;

    /** Free-text fallback for leadSourceId - see industryOther's javadoc above. */
    @Column(name = "lead_source_other")
    private String leadSourceOther;

    /** Free-text fallback for designationId - see industryOther's javadoc above. */
    @Column(name = "designation_other")
    private String designationOther;

    /** Free-text fallback for stateId - see industryOther's javadoc above. */
    @Column(name = "state_other")
    private String stateOther;

    /** Free-text fallback for cityId - see industryOther's javadoc above. */
    @Column(name = "city_other")
    private String cityOther;

    /** Free-text fallback for interestLevelId - see industryOther's javadoc above. */
    @Column(name = "interest_level_other")
    private String interestLevelOther;

    /**
     * Free-text supplement (not a fallback/either-or pairing like the other *Other fields
     * above) alongside productIds - an operator may select known PRODUCT rows AND jot down
     * additional products not yet in master data, so there is no "both set" conflict check
     * for this one.
     */
    @Column(name = "products_other")
    private String productsOther;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private LeadStatus status = LeadStatus.NEW;

    /** The employee this lead is visible to under the EMPLOYEE-role visibility rule. */
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    /**
     * References master_data rows of type PRODUCT - same simple id-set pattern as
     * Employee.assignedProductIds.
     */
    @ElementCollection
    @CollectionTable(name = "lead_products", joinColumns = @JoinColumn(name = "lead_id"))
    @Column(name = "product_id")
    @Builder.Default
    private Set<UUID> productIds = new HashSet<>();

    /** References master_data rows of type INTEREST_REASON - why this lead has its interest
     * level. Every reason's parent must be the interestLevelId (validated in the service). */
    @ElementCollection
    @CollectionTable(name = "lead_interest_reasons", joinColumns = @JoinColumn(name = "lead_id"))
    @Column(name = "reason_id")
    @Builder.Default
    private Set<UUID> interestReasonIds = new HashSet<>();
}
