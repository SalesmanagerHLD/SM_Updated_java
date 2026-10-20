package com.salesmanager.crm.invoicing;

import com.salesmanager.crm.tenant.TenantAware;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Create-only in v1 - no edit/delete/void endpoint exists, so this entity is written once by
 * InvoiceService#create and never mutated again except {@link #getStatus()} (mark paid/unpaid).
 *
 * <p>{@code ownerId}/{@code createdBy} mirror lead.Lead's exact two-column shape: ownerId is
 * who this invoice is visible to under the EMPLOYEE-role/TEAM_VISIBILITY scoping rule,
 * createdBy is pure audit - always equal today (no reassignment endpoint exists), kept as two
 * columns for consistency with Lead's convention.
 *
 * <p>{@code leadId} is optional - an invoice can stand alone with no CRM Lead at all. When a
 * Lead IS picked to prefill, the customer_* fields below are SNAPSHOTTED at creation time, not
 * live-joined - editing the source Lead afterwards never changes an already-created invoice,
 * same "denormalize at write time" discipline as activity.ActivityLog.
 */
@Entity
@Table(name = "invoices")
@Filter(name = "tenantFilter", condition = "organization_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Invoice extends TenantAware {

    @Column(name = "invoice_number", nullable = false, length = 50)
    private String invoiceNumber;

    @Column(name = "lead_id")
    private UUID leadId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "customer_name", nullable = false, length = 255)
    private String customerName;

    @Column(name = "customer_contact_person", length = 255)
    private String customerContactPerson;

    @Column(name = "customer_phone", length = 20)
    private String customerPhone;

    @Column(name = "customer_email", length = 255)
    private String customerEmail;

    @Column(name = "customer_address", length = 1000)
    private String customerAddress;

    @Column(name = "customer_gstin", length = 20)
    private String customerGstin;

    /** Ship-To defaults to a copy of Bill-To on the frontend ("same as billing" checkbox) but is
     * independently editable/stored - null when never diverged from Bill-To. */
    @Column(name = "ship_to_name", length = 255)
    private String shipToName;

    @Column(name = "ship_to_address", length = 1000)
    private String shipToAddress;

    @Column(name = "ship_to_gstin", length = 20)
    private String shipToGstin;

    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "place_of_supply", length = 255)
    private String placeOfSupply;

    @Column(name = "reverse_charge", nullable = false)
    @Builder.Default
    private boolean reverseCharge = false;

    /** Set only when this invoice was created via QuotationController#convertToInvoice - null
     * for a direct-create invoice (still fully supported, see InvoiceService#create). */
    @Column(name = "quotation_id")
    private UUID quotationId;

    @Column(nullable = false)
    private BigDecimal subtotal;

    @Column(name = "tax_total", nullable = false)
    private BigDecimal taxTotal;

    @Column(name = "grand_total", nullable = false)
    private BigDecimal grandTotal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private InvoiceStatus status = InvoiceStatus.UNPAID;

    @Column(length = 2000)
    private String notes;

    /** Optional link to the Customer master - set when created from a Customer/Quotation. */
    @Column(name = "customer_id")
    private UUID customerId;

    /** master_data STATE row that decides CGST+SGST vs IGST together with {@link #sellerStateId}.
     * Null means "not known" and is treated as intra-state (place of supply = seller's state). */
    @Column(name = "place_of_supply_state_id")
    private UUID placeOfSupplyStateId;

    // Seller snapshot (Rule 46): copied from the Organization when the invoice is issued, so a
    // later billing-profile edit never rewrites an already-issued invoice or its PDF.
    @Column(name = "seller_name", length = 255)
    private String sellerName;

    @Column(name = "seller_gstin", length = 20)
    private String sellerGstin;

    @Column(name = "seller_address", length = 500)
    private String sellerAddress;

    @Column(name = "seller_phone", length = 50)
    private String sellerPhone;

    @Column(name = "seller_state_id")
    private UUID sellerStateId;

    @Column(name = "seller_bank_name", length = 255)
    private String sellerBankName;

    @Column(name = "seller_bank_account_number", length = 50)
    private String sellerBankAccountNumber;

    @Column(name = "seller_bank_ifsc", length = 20)
    private String sellerBankIfsc;

    @Column(name = "seller_bank_branch", length = 255)
    private String sellerBankBranch;

    @Column(name = "seller_upi_id", length = 100)
    private String sellerUpiId;

    @Column(name = "seller_header_text", length = 1000)
    private String sellerHeaderText;

    @Column(name = "seller_footer_text", length = 1000)
    private String sellerFooterText;

    @Column(name = "discount_total", nullable = false)
    @Builder.Default
    private BigDecimal discountTotal = BigDecimal.ZERO;

    @Column(name = "taxable_amount", nullable = false)
    @Builder.Default
    private BigDecimal taxableAmount = BigDecimal.ZERO;

    @Column(name = "cgst_total", nullable = false)
    @Builder.Default
    private BigDecimal cgstTotal = BigDecimal.ZERO;

    @Column(name = "sgst_total", nullable = false)
    @Builder.Default
    private BigDecimal sgstTotal = BigDecimal.ZERO;

    @Column(name = "igst_total", nullable = false)
    @Builder.Default
    private BigDecimal igstTotal = BigDecimal.ZERO;

    @Column(name = "cancelled_at")
    private java.time.OffsetDateTime cancelledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    /** JSON copy of the invoice design in force when this invoice was issued (null for invoices
     * issued before custom designs - those render with the default design). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "design_settings", columnDefinition = "jsonb")
    private String designSettings;

    /** Copies the seller's current billing profile onto this invoice (call once, at issue). */
    public void snapshotSellerFrom(com.salesmanager.crm.tenant.Organization o) {
        this.sellerName = o.getName();
        this.sellerGstin = o.getBillingGstin();
        this.sellerAddress = o.getBillingAddress();
        this.sellerPhone = o.getBillingPhone();
        this.sellerStateId = o.getBillingStateId();
        this.sellerBankName = o.getBankName();
        this.sellerBankAccountNumber = o.getBankAccountNumber();
        this.sellerBankIfsc = o.getBankIfsc();
        this.sellerBankBranch = o.getBankBranch();
        this.sellerUpiId = o.getUpiId();
        this.sellerHeaderText = o.getInvoiceHeaderText();
        this.sellerFooterText = o.getInvoiceFooterText();
    }
}
