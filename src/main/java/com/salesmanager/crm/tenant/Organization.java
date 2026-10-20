package com.salesmanager.crm.tenant;

import com.salesmanager.crm.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The tenant itself. Deliberately does NOT extend {@link TenantAware} - an organization
 * has no organization_id, it IS the organization.
 */
@Entity
@Table(name = "organizations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Organization extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String subdomain;

    /**
     * Raw JSON stored as a String in a jsonb column. Deliberately not mapped to a rich
     * type in Phase 0 - not needed yet, and avoids pulling in a JSON <-> Java converter.
     * {@code @JdbcTypeCode(SqlTypes.JSON)} tells Hibernate 6 to bind this as jsonb rather
     * than plain varchar, which Postgres otherwise rejects with a type mismatch.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "theme_settings", columnDefinition = "jsonb")
    private String themeSettings;

    /**
     * The seller header shown on a generated invoice PDF (invoicing.InvoicePdfHtmlBuilder) -
     * all optional plain text, no format validation ("lightweight" v1, no GST-compliance
     * rigor). Added directly to Organization rather than a separate one-row-per-org profile
     * table, matching the same precedent as {@code themeSettings} above: singular,
     * one-per-org, admin-editable config lives directly here.
     */
    @Column(name = "billing_address", length = 500)
    private String billingAddress;

    @Column(name = "billing_gstin", length = 20)
    private String billingGstin;

    @Column(name = "billing_phone", length = 50)
    private String billingPhone;

    /** The seller's state (a master_data STATE row) - one side of the CGST+SGST vs IGST decision
     * (see invoicing.GstCalculator). Null until the admin sets it in the billing profile. */
    @Column(name = "billing_state_id")
    private java.util.UUID billingStateId;

    /**
     * Optional logo shown at the top of a generated invoice PDF - stored directly as bytea
     * (not S3; see V14 migration comment) since it's a small, rarely-changed, single-row-per-org
     * asset and this codebase has no file-upload infrastructure yet. logoContentType is always
     * set together with logoImage (image/png or image/jpeg only, validated in
     * BillingProfileService) so the GET /organizations/me/logo endpoint can set the right
     * Content-Type without re-sniffing the bytes.
     */
    @Column(name = "logo_image")
    private byte[] logoImage;

    @Column(name = "logo_content_type", length = 50)
    private String logoContentType;

    /** Optional free text shown near the seller header / at the bottom of the invoice PDF -
     * e.g. a tagline, bank details, or terms & conditions. Plain text, HTML-escaped by
     * InvoicePdfHtmlBuilder like every other user-supplied field. */
    @Column(name = "invoice_header_text", length = 1000)
    private String invoiceHeaderText;

    @Column(name = "invoice_footer_text", length = 1000)
    private String invoiceFooterText;

    /** Payment/bank details shown in the "Payment Details" section of a generated Invoice PDF
     * (invoicing.InvoicePdfHtmlBuilder) - added for the Quotations/Invoices plan's Invoice
     * repurposing work (section 17.3). Same "lightweight, no format validation" precedent as
     * the billing_* fields above. */
    @Column(name = "bank_name", length = 255)
    private String bankName;

    @Column(name = "bank_account_number", length = 50)
    private String bankAccountNumber;

    @Column(name = "bank_ifsc", length = 20)
    private String bankIfsc;

    @Column(name = "bank_branch", length = 255)
    private String bankBranch;

    @Column(name = "upi_id", length = 100)
    private String upiId;
}
