package com.salesmanager.crm.quotation;

import com.salesmanager.crm.tenant.TenantAware;
import com.salesmanager.crm.visit.VisitType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;

/**
 * DRAFT/SENT quotations are edit-in-place (unlike Invoice, which is create-only) - a rep
 * revising a quotation before it's approved should not need to void and recreate it. Once
 * APPROVED/REJECTED/CONVERTED, QuotationService blocks further edits (see its own javadoc).
 *
 * <p>{@code customerId} is a real FK, but every {@code customer*} field below is SNAPSHOTTED
 * from that Customer at creation time (same "keeps its own copy from this point on" discipline
 * Invoice already applies to its optional Lead link) - editing the Customer master afterward
 * never retroactively changes an already-created quotation.
 *
 * <p>{@code convertedInvoiceId} is set exactly once, by
 * {@code QuotationService#convertToInvoice}, and is what makes CONVERTED a genuinely terminal
 * state - see that method's javadoc for the full transition rules.
 */
@Entity
@Table(name = "quotations")
@Filter(name = "tenantFilter", condition = "organization_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Quotation extends TenantAware {

    @Column(name = "quotation_number", nullable = false, length = 50)
    private String quotationNumber;

    @Column(name = "lead_id")
    private UUID leadId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "customer_name", nullable = false, length = 255)
    private String customerName;

    @Column(name = "customer_contact_person", length = 255)
    private String customerContactPerson;

    @Column(name = "customer_designation", length = 255)
    private String customerDesignation;

    @Column(name = "customer_phone", length = 20)
    private String customerPhone;

    @Column(name = "customer_email", length = 255)
    private String customerEmail;

    @Column(name = "customer_billing_address", length = 1000)
    private String customerBillingAddress;

    @Column(name = "customer_gstin", length = 20)
    private String customerGstin;

    @Column(name = "industry_id")
    private UUID industryId;

    @Column(name = "industry_other", length = 255)
    private String industryOther;

    @Column(name = "city_id")
    private UUID cityId;

    @Column(name = "city_other", length = 255)
    private String cityOther;

    @Column(name = "state_id")
    private UUID stateId;

    @Column(name = "state_other", length = 255)
    private String stateOther;

    @Column(name = "interest_level_id")
    private UUID interestLevelId;

    @Column(name = "interest_level_other", length = 255)
    private String interestLevelOther;

    @Column(name = "business_type_id")
    private UUID businessTypeId;

    @Column(name = "business_type_other", length = 255)
    private String businessTypeOther;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type_of_visit", length = 20)
    private VisitType typeOfVisit;

    @Column(name = "quotation_date", nullable = false)
    private LocalDate quotationDate;

    @Column(name = "valid_till_date")
    private LocalDate validTillDate;

    @Column(name = "reference_enquiry_no", length = 100)
    private String referenceEnquiryNo;

    @Column(name = "expected_close_date")
    private LocalDate expectedCloseDate;

    @Column(name = "quotation_notes", columnDefinition = "text")
    private String quotationNotes;

    @Column(name = "terms_and_conditions", columnDefinition = "text")
    private String termsAndConditions;

    /** Never rendered into the PDF - visible to the org's own team only. */
    @Column(name = "internal_note", columnDefinition = "text")
    private String internalNote;

    @Column(name = "follow_up_date")
    private LocalDate followUpDate;

    @Column(name = "follow_up_time")
    private LocalTime followUpTime;

    @Column(name = "follow_up_by_employee_id")
    private UUID followUpByEmployeeId;

    @Column(name = "follow_up_note", length = 1000)
    private String followUpNote;

    @Column(nullable = false)
    private BigDecimal subtotal;

    @Column(name = "discount_total", nullable = false)
    @Builder.Default
    private BigDecimal discountTotal = BigDecimal.ZERO;

    @Column(name = "taxable_amount", nullable = false)
    private BigDecimal taxableAmount;

    @Column(name = "cgst_total", nullable = false)
    @Builder.Default
    private BigDecimal cgstTotal = BigDecimal.ZERO;

    @Column(name = "sgst_total", nullable = false)
    @Builder.Default
    private BigDecimal sgstTotal = BigDecimal.ZERO;

    @Column(name = "grand_total", nullable = false)
    private BigDecimal grandTotal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private QuotationStatus status = QuotationStatus.DRAFT;

    @Column(name = "converted_invoice_id")
    private UUID convertedInvoiceId;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "igst_total", nullable = false)
    @Builder.Default
    private BigDecimal igstTotal = BigDecimal.ZERO;
}
