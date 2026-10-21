package com.salesmanager.crm.purchaseorder;

import com.salesmanager.crm.tenant.TenantAware;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;

/**
 * The order a customer sends after accepting a quotation. Customer fields are SNAPSHOTS taken at
 * save time (same discipline as invoicing.Invoice). Header totals are computed server-side with
 * the same GST split as invoices, from the lines.
 */
@Entity
@Table(name = "customer_po")
@Filter(name = "tenantFilter", condition = "organization_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class PurchaseOrder extends TenantAware {

    @Column(name = "lead_id", nullable = false)
    private UUID leadId;

    @Column(name = "quotation_id")
    private UUID quotationId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    /** As typed by the customer; trimmed, otherwise untouched. */
    @Column(name = "po_number", nullable = false, length = 100)
    private String poNumber;

    @Column(name = "po_date", nullable = false)
    private LocalDate poDate;

    @Column(name = "delivery_date")
    private LocalDate deliveryDate;

    @Column(name = "payment_terms_days")
    private Integer paymentTermsDays;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PurchaseOrderStatus status = PurchaseOrderStatus.RECEIVED;

    @Column(name = "customer_name", nullable = false, length = 255)
    private String customerName;

    @Column(name = "customer_gstin", length = 20)
    private String customerGstin;

    @Column(name = "billing_address", length = 1000)
    private String billingAddress;

    @Column(name = "shipping_address", length = 1000)
    private String shippingAddress;

    @Column(name = "place_of_supply_state_id")
    private UUID placeOfSupplyStateId;

    @Column(name = "taxable_total", nullable = false)
    private BigDecimal taxableTotal;

    @Column(name = "cgst_total", nullable = false)
    @Builder.Default
    private BigDecimal cgstTotal = BigDecimal.ZERO;

    @Column(name = "sgst_total", nullable = false)
    @Builder.Default
    private BigDecimal sgstTotal = BigDecimal.ZERO;

    @Column(name = "igst_total", nullable = false)
    @Builder.Default
    private BigDecimal igstTotal = BigDecimal.ZERO;

    @Column(name = "tax_total", nullable = false)
    private BigDecimal taxTotal;

    @Column(name = "grand_total", nullable = false)
    private BigDecimal grandTotal;

    @Column(length = 2000)
    private String notes;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;
}
