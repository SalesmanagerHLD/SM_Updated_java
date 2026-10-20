package com.salesmanager.crm.quotation;

import com.salesmanager.crm.tenant.TenantAware;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;

/**
 * Exactly one of {@code productId} (catalog line) or a free-text {@code description} +
 * manually-entered price/tax (ad-hoc line) - same mutual-exclusivity rule as
 * invoicing.InvoiceLineItem, validated in QuotationService. {@code hsnSac}/description/unitPrice/
 * taxRatePercent are always SNAPSHOTTED at quotation-creation (or edit) time.
 *
 * <p>{@code lineCgstAmount}/{@code lineSgstAmount} are {@code taxRatePercent}'s line tax split in
 * half (intra-state assumption - no IGST modeled in v1), computed and stored at write time so a
 * historical quotation's PDF never drifts if this splitting rule is ever revisited.
 */
@Entity
@Table(name = "quotation_line_items")
@Filter(name = "tenantFilter", condition = "organization_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class QuotationLineItem extends TenantAware {

    @Column(name = "quotation_id", nullable = false)
    private UUID quotationId;

    /** Null for an ad-hoc (not-in-catalog) line. */
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "hsn_sac", length = 20)
    private String hsnSac;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(length = 50)
    private String unit;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "discount_percent", nullable = false)
    private BigDecimal discountPercent;

    @Column(name = "tax_rate_percent", nullable = false)
    private BigDecimal taxRatePercent;

    @Column(name = "line_subtotal", nullable = false)
    private BigDecimal lineSubtotal;

    @Column(name = "line_discount_amount", nullable = false)
    private BigDecimal lineDiscountAmount;

    @Column(name = "line_taxable_amount", nullable = false)
    private BigDecimal lineTaxableAmount;

    @Column(name = "line_cgst_amount", nullable = false)
    private BigDecimal lineCgstAmount;

    @Column(name = "line_sgst_amount", nullable = false)
    private BigDecimal lineSgstAmount;

    @Column(name = "line_total", nullable = false)
    private BigDecimal lineTotal;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "line_igst_amount", nullable = false)
    @Builder.Default
    private BigDecimal lineIgstAmount = BigDecimal.ZERO;
}
