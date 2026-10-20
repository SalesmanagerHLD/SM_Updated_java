package com.salesmanager.crm.invoicing;

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
 * Exactly one of {@code productId} (a real catalog line) or a free-text {@code description} +
 * manually-entered price/tax (an ad-hoc line) - see InvoiceService's line-item validation.
 * {@code description}/{@code unitPrice}/{@code taxRatePercent} are always SNAPSHOTTED here at
 * invoice-creation time (the product's name/price as of that moment, or the typed ad-hoc
 * values) - never re-read from the live Product afterwards, so an invoice never silently
 * changes if the Product's price changes later.
 */
@Entity
@Table(name = "invoice_line_items")
@Filter(name = "tenantFilter", condition = "organization_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class InvoiceLineItem extends TenantAware {

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    /** Null for an ad-hoc (not-in-catalog) line. */
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "hsn_sac", length = 20)
    private String hsnSac;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "discount_percent", nullable = false)
    @Builder.Default
    private BigDecimal discountPercent = BigDecimal.ZERO;

    @Column(name = "tax_rate_percent", nullable = false)
    private BigDecimal taxRatePercent;

    @Column(name = "line_subtotal", nullable = false)
    private BigDecimal lineSubtotal;

    @Column(name = "line_discount_amount", nullable = false)
    @Builder.Default
    private BigDecimal lineDiscountAmount = BigDecimal.ZERO;

    @Column(name = "line_tax_amount", nullable = false)
    private BigDecimal lineTaxAmount;

    @Column(name = "line_cgst_amount", nullable = false)
    @Builder.Default
    private BigDecimal lineCgstAmount = BigDecimal.ZERO;

    @Column(name = "line_sgst_amount", nullable = false)
    @Builder.Default
    private BigDecimal lineSgstAmount = BigDecimal.ZERO;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(length = 20)
    private String unit;

    @Column(name = "line_igst_amount", nullable = false)
    @Builder.Default
    private BigDecimal lineIgstAmount = BigDecimal.ZERO;
}
