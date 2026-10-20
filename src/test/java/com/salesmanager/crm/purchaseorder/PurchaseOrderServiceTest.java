package com.salesmanager.crm.purchaseorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.salesmanager.crm.employee.Role;
import com.salesmanager.crm.invoicing.Invoice;
import com.salesmanager.crm.lead.Lead;
import com.salesmanager.crm.lead.LeadRepository;
import com.salesmanager.crm.lead.LeadService;
import com.salesmanager.crm.purchaseorder.dto.PurchaseOrderLineRequest;
import com.salesmanager.crm.purchaseorder.dto.PurchaseOrderRequest;
import com.salesmanager.crm.security.CurrentUser;
import com.salesmanager.crm.security.UserPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The PO rules that don't need a database: remaining-quantity checks, the admin override,
 * the status machine, quantity restore on cancel and the unique PO number. */
@ExtendWith(MockitoExtension.class)
class PurchaseOrderServiceTest {

    @Mock private PurchaseOrderRepository poRepository;
    @Mock private PurchaseOrderLineRepository lineRepository;
    @Mock private com.salesmanager.crm.invoicing.InvoiceRepository invoiceRepository;
    @Mock private LeadService leadService;
    @Mock private LeadRepository leadRepository;
    @Mock private com.salesmanager.crm.customer.CustomerService customerService;
    @Mock private com.salesmanager.crm.quotation.QuotationRepository quotationRepository;
    @Mock private com.salesmanager.crm.quotation.QuotationLineItemRepository quotationLineItemRepository;
    @Mock private com.salesmanager.crm.tenant.OrganizationRepository organizationRepository;
    @Mock private com.salesmanager.crm.activity.ActivityLogService activityLogService;
    @Mock private com.salesmanager.crm.leadattachment.LeadAttachmentService leadAttachmentService;
    @Mock private com.salesmanager.crm.leadattachment.LeadAttachmentRepository leadAttachmentRepository;
    @Mock private com.salesmanager.crm.employee.EmployeeHierarchyService employeeHierarchyService;
    @Mock private CurrentUser currentUser;
    @Mock private java.time.Clock billingClock;
    @InjectMocks private PurchaseOrderService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();

    private void actAs(Role role) {
        lenient().when(currentUser.get()).thenReturn(new UserPrincipal(employeeId, orgId, role, "x@test"));
    }

    private static PurchaseOrderLine line(String ordered, String billed) {
        PurchaseOrderLine line = PurchaseOrderLine.builder()
                .poId(UUID.randomUUID()).description("Item").quantityOrdered(new BigDecimal(ordered))
                .quantityBilled(new BigDecimal(billed)).rate(BigDecimal.TEN).taxRatePercent(BigDecimal.TEN)
                .lineTaxableAmount(BigDecimal.TEN).lineTotal(BigDecimal.TEN).build();
        line.setId(UUID.randomUUID());
        return line;
    }

    private static Map<UUID, PurchaseOrderLine> byId(PurchaseOrderLine... lines) {
        Map<UUID, PurchaseOrderLine> map = new LinkedHashMap<>();
        for (PurchaseOrderLine l : lines) {
            map.put(l.getId(), l);
        }
        return map;
    }

    // ---- billing more than remaining ----

    @Test
    void billingWithinRemaining_isAllowed() {
        actAs(Role.EMPLOYEE);
        PurchaseOrderLine l = line("10", "4");
        assertThat(service.checkWithinRemaining(Map.of(l.getId(), new BigDecimal("6")), byId(l), null)).isFalse();
    }

    @Test
    void billingMoreThanRemaining_isRejectedForANonAdmin_evenWithAReason() {
        actAs(Role.EMPLOYEE);
        PurchaseOrderLine l = line("10", "4");
        assertThatThrownBy(() -> service.checkWithinRemaining(Map.of(l.getId(), new BigDecimal("7")), byId(l), "pls"))
                .isInstanceOfSatisfying(OverBillingException.class, e -> {
                    assertThat(e.isOverridable()).isFalse();
                    assertThat(e.getViolations()).singleElement().satisfies(v ->
                            assertThat(v.remaining()).isEqualByComparingTo("6"));
                });
    }

    @Test
    void adminOverride_requiresAReason() {
        actAs(Role.ADMIN);
        PurchaseOrderLine l = line("10", "4");
        Map<UUID, BigDecimal> requested = Map.of(l.getId(), new BigDecimal("7"));
        for (String blank : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> service.checkWithinRemaining(requested, byId(l), blank))
                    .isInstanceOfSatisfying(OverBillingException.class, e -> assertThat(e.isOverridable()).isTrue());
        }
        assertThat(service.checkWithinRemaining(requested, byId(l), "Customer agreed extra units")).isTrue();
    }

    // ---- status machine ----

    @Test
    void status_movesAcceptedPartlyFullyAndBack() {
        PurchaseOrder po = PurchaseOrder.builder().status(PurchaseOrderStatus.ACCEPTED).build();
        PurchaseOrderLine a = line("10", "0");
        PurchaseOrderLine b = line("5", "0");

        assertThat(service.recomputeStatus(po, List.of(a, b))).isEqualTo(PurchaseOrderStatus.ACCEPTED);
        a.setQuantityBilled(new BigDecimal("10"));
        assertThat(service.recomputeStatus(po, List.of(a, b))).isEqualTo(PurchaseOrderStatus.PARTLY_BILLED);
        b.setQuantityBilled(new BigDecimal("5"));
        assertThat(service.recomputeStatus(po, List.of(a, b))).isEqualTo(PurchaseOrderStatus.FULLY_BILLED);
        b.setQuantityBilled(BigDecimal.ZERO);
        assertThat(service.recomputeStatus(po, List.of(a, b))).isEqualTo(PurchaseOrderStatus.PARTLY_BILLED);
        a.setQuantityBilled(BigDecimal.ZERO);
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.PARTLY_BILLED);
        assertThat(service.recomputeStatus(po, List.of(a, b))).isEqualTo(PurchaseOrderStatus.ACCEPTED);
    }

    @Test
    void onlyAcceptedAndPartlyBilledAreBillable() {
        assertThat(PurchaseOrderStatus.ACCEPTED.isBillable()).isTrue();
        assertThat(PurchaseOrderStatus.PARTLY_BILLED.isBillable()).isTrue();
        for (PurchaseOrderStatus s : List.of(PurchaseOrderStatus.RECEIVED, PurchaseOrderStatus.FULLY_BILLED,
                PurchaseOrderStatus.CANCELLED)) {
            assertThat(s.isBillable()).as(s.name()).isFalse();
        }
    }

    // ---- cancelling an invoice restores quantities ----

    @Test
    void restoreBilling_subtractsQuantitiesAndRecomputesStatus() {
        actAs(Role.EMPLOYEE);
        PurchaseOrder po = PurchaseOrder.builder().status(PurchaseOrderStatus.FULLY_BILLED).leadId(UUID.randomUUID())
                .poNumber("PO-1").build();
        po.setId(UUID.randomUUID());
        PurchaseOrderLine l1 = line("10", "10");
        PurchaseOrderLine l2 = line("5", "5");
        when(poRepository.findByIdForUpdate(po.getId())).thenReturn(Optional.of(po));
        when(lineRepository.findByPoIdForUpdate(po.getId())).thenReturn(List.of(l1, l2));
        when(leadRepository.findById(any())).thenReturn(Optional.empty());
        Invoice invoice = Invoice.builder().invoiceNumber("INV/1").build();

        service.restoreBilling(po.getId(), Map.of(l1.getId(), new BigDecimal("4")), invoice);

        assertThat(l1.getQuantityBilled()).isEqualByComparingTo("6");
        assertThat(l2.getQuantityBilled()).isEqualByComparingTo("5");
        assertThat(l1.getQuantityRemaining()).isEqualByComparingTo("4");
        assertThat(po.getStatus()).isEqualTo(PurchaseOrderStatus.PARTLY_BILLED);
    }

    // ---- unique PO number per customer ----

    @Test
    void create_rejectsADuplicatePoNumberForTheSameLead() {
        actAs(Role.EMPLOYEE);
        Lead lead = mock(Lead.class);
        UUID leadId = UUID.randomUUID();
        when(lead.getId()).thenReturn(leadId);
        when(leadService.getById(leadId)).thenReturn(lead);
        when(poRepository.existsByLeadIdAndPoNumber(leadId, "PO-77")).thenReturn(true);

        PurchaseOrderRequest request = new PurchaseOrderRequest(leadId, null, null, "  PO-77 ",
                LocalDate.now(), null, null, "Acme", null, null, null, null, null,
                List.of(new PurchaseOrderLineRequest(null, null, "Item", null, null, BigDecimal.ONE,
                        BigDecimal.TEN, BigDecimal.TEN)));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(InvalidPurchaseOrderStateException.class)
                .hasMessageContaining("PO-77");
    }
}
