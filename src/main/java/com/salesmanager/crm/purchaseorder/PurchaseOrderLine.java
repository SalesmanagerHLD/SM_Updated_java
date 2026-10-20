package com.salesmanager.crm.purchaseorder;

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

/** Remaining quantity is always {@code quantityOrdered - quantityBilled}, never stored. */
@Entity
@Table(name = "customer_po_line")
@Filter(name = "tenantFilter", condition = "organization_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class PurchaseOrderLine extends TenantAware {

    @Column(name = "po_id", nullable = false)
    private UUID poId;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "quotation_line_id")
    private UUID quotationLineId;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(name = "hsn_sac", length = 20)
    private String hsnSac;

    @Column(length = 50)
    private String unit;

    @Column(name = "quantity_ordered", nullable = false)
    private BigDecimal quantityOrdered;

    @Column(name = "quantity_billed", nullable = false)
    @Builder.Default
    private BigDecimal quantityBilled = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal rate;

    @Column(name = "tax_rate_percent", nullable = false)
    private BigDecimal taxRatePercent;

    @Column(name = "line_taxable_amount", nullable = false)
    private BigDecimal lineTaxableAmount;

    @Column(name = "line_cgst_amount", nullable = false)
    @Builder.Default
    private BigDecimal lineCgstAmount = BigDecimal.ZERO;

    @Column(name = "line_sgst_amount", nullable = false)
    @Builder.Default
    private BigDecimal lineSgstAmount = BigDecimal.ZERO;

    @Column(name = "line_igst_amount", nullable = false)
    @Builder.Default
    private BigDecimal lineIgstAmount = BigDecimal.ZERO;

    @Column(name = "line_total", nullable = false)
    private BigDecimal lineTotal;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public BigDecimal getQuantityRemaining() {
        return quantityOrdered.subtract(quantityBilled);
    }
}
