package com.salesmanager.crm.purchaseorder;

import com.salesmanager.crm.activity.ActivityLogService;
import com.salesmanager.crm.activity.ActivityType;
import com.salesmanager.crm.common.NotFoundException;
import com.salesmanager.crm.customer.Customer;
import com.salesmanager.crm.customer.CustomerService;
import com.salesmanager.crm.employee.EmployeeHierarchyService;
import com.salesmanager.crm.employee.Role;
import com.salesmanager.crm.invoicing.BillingProfileIncompleteException;
import com.salesmanager.crm.invoicing.GstCalculator;
import com.salesmanager.crm.invoicing.Invoice;
import com.salesmanager.crm.invoicing.InvoiceRepository;
import com.salesmanager.crm.invoicing.InvoiceStatus;
import com.salesmanager.crm.lead.Lead;
import com.salesmanager.crm.lead.LeadRepository;
import com.salesmanager.crm.lead.LeadService;
import com.salesmanager.crm.leadattachment.LeadAttachment;
import com.salesmanager.crm.leadattachment.LeadAttachmentRepository;
import com.salesmanager.crm.leadattachment.LeadAttachmentService;
import com.salesmanager.crm.purchaseorder.dto.InvoiceDraftResponse;
import com.salesmanager.crm.purchaseorder.dto.PurchaseOrderLineRequest;
import com.salesmanager.crm.purchaseorder.dto.PurchaseOrderLineResponse;
import com.salesmanager.crm.purchaseorder.dto.PurchaseOrderRequest;
import com.salesmanager.crm.purchaseorder.dto.PurchaseOrderResponse;
import com.salesmanager.crm.quotation.Quotation;
import com.salesmanager.crm.quotation.QuotationLineItem;
import com.salesmanager.crm.quotation.QuotationLineItemRepository;
import com.salesmanager.crm.quotation.QuotationRepository;
import com.salesmanager.crm.security.CurrentUser;
import com.salesmanager.crm.security.UserPrincipal;
import com.salesmanager.crm.tenant.Organization;
import com.salesmanager.crm.tenant.OrganizationRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Customer purchase orders. A PO is optional - invoices can still be created without one - and
 * one invoice belongs to at most one PO.
 *
 * <p><b>Validate-then-write (critical)</b>: TenantFilter wraps the whole request in one shared
 * transaction, so every {@code noRollbackFor} below is only safe because the exceptions it lists
 * are thrown strictly BEFORE this class writes anything (see invoicing.InvoiceService's javadoc
 * for the full reasoning). The billing hooks used by InvoiceService follow the same rule:
 * {@link #lockForBilling} and {@link #checkWithinRemaining} are read-only; only
 * {@link #applyBilling} / {@link #restoreBilling} write, after everything has been validated.
 *
 * <p><b>Lock order</b>: PO row first, then its lines. The invoice row (when cancelling) is locked
 * before the PO by InvoiceService, so the order is always invoice -> PO -> lines.
 */
@Service
public class PurchaseOrderService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final PurchaseOrderRepository poRepository;
    private final PurchaseOrderLineRepository lineRepository;
    private final InvoiceRepository invoiceRepository;
    private final LeadService leadService;
    private final LeadRepository leadRepository;
    private final CustomerService customerService;
    private final QuotationRepository quotationRepository;
    private final QuotationLineItemRepository quotationLineItemRepository;
    private final OrganizationRepository organizationRepository;
    private final ActivityLogService activityLogService;
    private final LeadAttachmentService leadAttachmentService;
    private final LeadAttachmentRepository leadAttachmentRepository;
    private final EmployeeHierarchyService employeeHierarchyService;
    private final CurrentUser currentUser;
    private final Clock billingClock;

    public PurchaseOrderService(PurchaseOrderRepository poRepository,
                                 PurchaseOrderLineRepository lineRepository,
                                 InvoiceRepository invoiceRepository,
                                 LeadService leadService,
                                 LeadRepository leadRepository,
                                 CustomerService customerService,
                                 QuotationRepository quotationRepository,
                                 QuotationLineItemRepository quotationLineItemRepository,
                                 OrganizationRepository organizationRepository,
                                 ActivityLogService activityLogService,
                                 LeadAttachmentService leadAttachmentService,
                                 LeadAttachmentRepository leadAttachmentRepository,
                                 EmployeeHierarchyService employeeHierarchyService,
                                 CurrentUser currentUser,
                                 Clock billingClock) {
        this.poRepository = poRepository;
        this.lineRepository = lineRepository;
        this.invoiceRepository = invoiceRepository;
        this.leadService = leadService;
        this.leadRepository = leadRepository;
        this.customerService = customerService;
        this.quotationRepository = quotationRepository;
        this.quotationLineItemRepository = quotationLineItemRepository;
        this.organizationRepository = organizationRepository;
        this.activityLogService = activityLogService;
        this.leadAttachmentService = leadAttachmentService;
        this.leadAttachmentRepository = leadAttachmentRepository;
        this.employeeHierarchyService = employeeHierarchyService;
        this.currentUser = currentUser;
        this.billingClock = billingClock;
    }

    // ------------------------------------------------------------------ create / update

    @Transactional(noRollbackFor = {NotFoundException.class, InvalidPurchaseOrderStateException.class,
            PurchaseOrderLineException.class, BillingProfileIncompleteException.class})
    public PurchaseOrder create(PurchaseOrderRequest request) {
        UserPrincipal principal = currentUser.get();
        Lead lead = leadService.getById(request.leadId());
        String poNumber = normalizePoNumber(request.poNumber());
        if (poRepository.existsByLeadIdAndPoNumber(lead.getId(), poNumber)) {
            throw new InvalidPurchaseOrderStateException(
                    "Purchase order " + poNumber + " already exists for this customer");
        }
        Customer customer = request.customerId() != null ? customerService.getById(request.customerId()) : null;
        Quotation quotation = resolveQuotation(request.quotationId(), lead.getId());
        CustomerSnapshot snapshot = snapshotCustomer(request, customer, lead);
        Organization seller = loadOrganization(principal.getOrganizationId());
        UUID supplyStateId = request.placeOfSupplyStateId() != null ? request.placeOfSupplyStateId()
                : (customer != null ? customer.getStateId() : null);
        List<ComputedLine> lines = computeLines(requireLines(request.lineItems()),
                determineInterState(seller, supplyStateId), quotation);

        // Phase 2: writes.
        Totals totals = sum(lines);
        PurchaseOrder po = PurchaseOrder.builder()
                .leadId(lead.getId())
                .quotationId(quotation != null ? quotation.getId() : null)
                .customerId(request.customerId())
                .ownerId(principal.getEmployeeId())
                .createdBy(principal.getEmployeeId())
                .poNumber(poNumber)
                .poDate(request.poDate())
                .deliveryDate(request.deliveryDate())
                .paymentTermsDays(request.paymentTermsDays())
                .status(PurchaseOrderStatus.RECEIVED)
                .customerName(snapshot.name())
                .customerGstin(snapshot.gstin())
                .billingAddress(snapshot.billingAddress())
                .shippingAddress(snapshot.shippingAddress())
                .placeOfSupplyStateId(supplyStateId != null ? supplyStateId : seller.getBillingStateId())
                .notes(request.notes())
                .build();
        applyTotals(po, totals);
        PurchaseOrder saved = poRepository.saveAndFlush(po);
        saveLines(saved.getId(), lines);
        recordActivity(saved, lead, ActivityType.PO_RECEIVED,
                "Purchase order " + poNumber + " received (" + lines.size() + " line"
                        + (lines.size() == 1 ? "" : "s") + ", total " + saved.getGrandTotal() + ")");
        return saved;
    }

    @Transactional(noRollbackFor = {NotFoundException.class, InvalidPurchaseOrderStateException.class,
            PurchaseOrderLineException.class, BillingProfileIncompleteException.class})
    public PurchaseOrder update(UUID id, PurchaseOrderRequest request) {
        UserPrincipal principal = currentUser.get();
        PurchaseOrder po = loadForUpdate(id, false);
        if (po.getStatus() != PurchaseOrderStatus.RECEIVED && po.getStatus() != PurchaseOrderStatus.ACCEPTED) {
            throw new InvalidPurchaseOrderStateException(
                    "A purchase order can only be edited while it is Received or Accepted (this one is "
                            + po.getStatus() + ")");
        }
        if (!po.getLeadId().equals(request.leadId())) {
            throw new PurchaseOrderLineException("leadId", "A purchase order cannot be moved to another lead");
        }
        String poNumber = normalizePoNumber(request.poNumber());
        if (poRepository.existsByLeadIdAndPoNumberAndIdNot(po.getLeadId(), poNumber, id)) {
            throw new InvalidPurchaseOrderStateException(
                    "Purchase order " + poNumber + " already exists for this customer");
        }
        boolean replaceLines = request.lineItems() != null;
        if (replaceLines && invoiceRepository.existsByPoId(id)) {
            throw new InvalidPurchaseOrderStateException(
                    "Lines cannot be changed once an invoice has been raised against this purchase order "
                            + "- edit the header only, or cancel the invoice(s) and the PO");
        }
        Lead lead = leadRepository.findById(po.getLeadId())
                .orElseThrow(() -> new NotFoundException("Lead not found: " + po.getLeadId()));

        Customer customer = request.customerId() != null ? customerService.getById(request.customerId()) : null;
        CustomerSnapshot snapshot = snapshotCustomer(request, customer, lead);
        List<ComputedLine> lines = null;
        Quotation quotation = null;
        UUID supplyStateId = po.getPlaceOfSupplyStateId();
        if (replaceLines) {
            quotation = resolveQuotation(request.quotationId(), po.getLeadId());
            Organization seller = loadOrganization(principal.getOrganizationId());
            supplyStateId = request.placeOfSupplyStateId() != null ? request.placeOfSupplyStateId()
                    : (customer != null ? customer.getStateId() : po.getPlaceOfSupplyStateId());
            lines = computeLines(requireLines(request.lineItems()), determineInterState(seller, supplyStateId),
                    quotation);
        }

        // Phase 2: writes.
        po.setPoNumber(poNumber);
        po.setPoDate(request.poDate());
        po.setDeliveryDate(request.deliveryDate());
        po.setPaymentTermsDays(request.paymentTermsDays());
        po.setCustomerId(request.customerId());
        po.setCustomerName(snapshot.name());
        po.setCustomerGstin(snapshot.gstin());
        po.setBillingAddress(snapshot.billingAddress());
        po.setShippingAddress(snapshot.shippingAddress());
        po.setNotes(request.notes());
        if (replaceLines) {
            po.setQuotationId(quotation != null ? quotation.getId() : null);
            po.setPlaceOfSupplyStateId(supplyStateId);
            lineRepository.deleteAll(lineRepository.findByPoIdOrderBySortOrderAsc(id));
            lineRepository.flush();
            applyTotals(po, sum(lines));
            poRepository.saveAndFlush(po);
            saveLines(id, lines);
        } else {
            poRepository.saveAndFlush(po);
        }
        return po;
    }

    @Transactional(noRollbackFor = {NotFoundException.class, InvalidPurchaseOrderStateException.class})
    public PurchaseOrder accept(UUID id) {
        PurchaseOrder po = loadForUpdate(id, false);
        if (po.getStatus() != PurchaseOrderStatus.RECEIVED) {
            throw new InvalidPurchaseOrderStateException(
                    "Only a Received purchase order can be accepted (this one is " + po.getStatus() + ")");
        }
        po.setStatus(PurchaseOrderStatus.ACCEPTED);
        PurchaseOrder saved = poRepository.saveAndFlush(po);
        recordActivity(saved, null, ActivityType.PO_ACCEPTED, "Purchase order " + saved.getPoNumber() + " accepted");
        return saved;
    }

    @Transactional(noRollbackFor = {NotFoundException.class, InvalidPurchaseOrderStateException.class})
    public PurchaseOrder cancel(UUID id, String reason) {
        PurchaseOrder po = loadForUpdate(id, false);
        if (po.getStatus() == PurchaseOrderStatus.CANCELLED) {
            throw new InvalidPurchaseOrderStateException("Purchase order is already cancelled");
        }
        if (invoiceRepository.existsByPoIdAndStatusNot(id, InvoiceStatus.CANCELLED)) {
            throw new InvalidPurchaseOrderStateException(
                    "A purchase order with an active invoice cannot be cancelled - cancel its invoice(s) first");
        }
        po.setStatus(PurchaseOrderStatus.CANCELLED);
        po.setCancelReason(reason);
        po.setCancelledAt(OffsetDateTime.now(billingClock));
        po.setCancelledBy(currentUser.get().getEmployeeId());
        PurchaseOrder saved = poRepository.saveAndFlush(po);
        recordActivity(saved, null, ActivityType.PO_CANCELLED,
                "Purchase order " + saved.getPoNumber() + " cancelled: " + reason);
        return saved;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public Page<PurchaseOrder> list(PurchaseOrderFilter filter, Pageable pageable) {
        UserPrincipal principal = currentUser.get();
        Specification<PurchaseOrder> spec = Specification
                .where(PurchaseOrderSpecifications.hasLead(filter.leadId()))
                .and(PurchaseOrderSpecifications.hasStatus(filter.status()))
                .and(PurchaseOrderSpecifications.hasCustomer(filter.customerId()))
                .and(PurchaseOrderSpecifications.poDateBetween(filter.dateFrom(), filter.dateTo()))
                .and(PurchaseOrderSpecifications.matchesSearch(filter.search()));

        // Same visibility as invoices: an EMPLOYEE sees their own, plus their team's when
        // TEAM_VISIBILITY gives them subordinates; an ADMIN sees the whole organization.
        if (principal.getRole() == Role.EMPLOYEE) {
            Set<UUID> subordinateIds = employeeHierarchyService
                    .getTeamVisibilityScope(principal.getOrganizationId(), principal.getEmployeeId());
            if (subordinateIds.isEmpty()) {
                spec = spec.and(PurchaseOrderSpecifications.hasOwner(principal.getEmployeeId()));
            } else {
                Set<UUID> teamScope = new HashSet<>(subordinateIds);
                teamScope.add(principal.getEmployeeId());
                if (filter.ownerId() != null && teamScope.contains(filter.ownerId())) {
                    spec = spec.and(PurchaseOrderSpecifications.hasOwner(filter.ownerId()));
                } else {
                    spec = spec.and(PurchaseOrderSpecifications.hasOwnerIn(teamScope));
                }
            }
        } else {
            spec = spec.and(PurchaseOrderSpecifications.hasOwner(filter.ownerId()));
        }
        return poRepository.findAll(spec, pageable);
    }

    @Transactional(readOnly = true, noRollbackFor = NotFoundException.class)
    public PurchaseOrder getById(UUID id) {
        PurchaseOrder po = poRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Purchase order not found: " + id));
        return checkVisibility(po, id, true);
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrderLine> getLines(UUID poId) {
        return lineRepository.findByPoIdOrderBySortOrderAsc(poId);
    }

    @Transactional(readOnly = true)
    public Map<UUID, List<PurchaseOrderLine>> getLinesByPoIds(Collection<UUID> poIds) {
        if (poIds.isEmpty()) {
            return Map.of();
        }
        return lineRepository.findByPoIdIn(poIds).stream()
                .sorted(java.util.Comparator.comparingInt(PurchaseOrderLine::getSortOrder))
                .collect(Collectors.groupingBy(PurchaseOrderLine::getPoId));
    }

    @Transactional(readOnly = true)
    public List<Invoice> getInvoices(UUID poId) {
        return invoiceRepository.findByPoIdOrderByCreatedAtDesc(poId);
    }

    /** Builds the response with each line's quotation-rate flag; one extra query for all lines. */
    @Transactional(readOnly = true)
    public PurchaseOrderResponse toResponse(PurchaseOrder po, List<PurchaseOrderLine> lines, boolean withInvoices) {
        Set<UUID> quotationLineIds = lines.stream().map(PurchaseOrderLine::getQuotationLineId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, BigDecimal> quotationRates = new HashMap<>();
        if (!quotationLineIds.isEmpty()) {
            quotationLineItemRepository.findAllById(quotationLineIds)
                    .forEach(q -> quotationRates.put(q.getId(), netRate(q)));
        }
        List<PurchaseOrderLineResponse> lineResponses = lines.stream()
                .map(l -> PurchaseOrderLineResponse.from(l,
                        l.getQuotationLineId() == null ? null : quotationRates.get(l.getQuotationLineId())))
                .toList();
        return PurchaseOrderResponse.from(po, lineResponses, withInvoices ? getInvoices(po.getId()) : List.of());
    }

    /** Pre-fills a PO from a quotation (the "copy from quotation" button). Nothing is saved. */
    @Transactional(readOnly = true, noRollbackFor = NotFoundException.class)
    public PurchaseOrderRequest prefillFromQuotation(UUID quotationId) {
        Quotation quotation = quotationRepository.findById(quotationId)
                .orElseThrow(() -> new NotFoundException("Quotation not found: " + quotationId));
        if (quotation.getLeadId() == null) {
            throw new PurchaseOrderLineException("quotationId",
                    "This quotation is not linked to a lead, so a purchase order cannot be raised from it");
        }
        leadService.getById(quotation.getLeadId());
        List<PurchaseOrderLineRequest> lines = quotationLineItemRepository
                .findByQuotationIdOrderBySortOrderAsc(quotationId).stream()
                .map(q -> new PurchaseOrderLineRequest(q.getProductId(), q.getId(), q.getDescription(),
                        q.getHsnSac(), q.getUnit(), q.getQuantity(), netRate(q), q.getTaxRatePercent()))
                .toList();
        return new PurchaseOrderRequest(quotation.getLeadId(), quotationId, quotation.getCustomerId(), "",
                LocalDate.now(billingClock), null, null, quotation.getCustomerName(), quotation.getCustomerGstin(),
                quotation.getCustomerBillingAddress(), quotation.getCustomerBillingAddress(),
                quotation.getStateId(), null, lines);
    }

    /**
     * A pre-filled invoice for the PO's remaining quantities. Nothing is saved - invoices have no
     * draft state in this system, so the user reviews this and submits it to POST /invoices, which
     * is where billed quantities are actually updated.
     */
    @Transactional(readOnly = true, noRollbackFor = {NotFoundException.class,
            InvalidPurchaseOrderStateException.class})
    public InvoiceDraftResponse buildInvoiceDraft(UUID id) {
        PurchaseOrder po = poRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Purchase order not found: " + id));
        checkVisibility(po, id, false);
        requireBillable(po);
        List<InvoiceDraftResponse.Line> lines = new ArrayList<>();
        for (PurchaseOrderLine line : lineRepository.findByPoIdOrderBySortOrderAsc(id)) {
            if (line.getQuantityRemaining().signum() > 0) {
                lines.add(new InvoiceDraftResponse.Line(line.getId(), line.getProductId(), line.getDescription(),
                        line.getHsnSac(), line.getUnit(), line.getQuantityRemaining(), line.getRate(),
                        line.getTaxRatePercent()));
            }
        }
        if (lines.isEmpty()) {
            throw new InvalidPurchaseOrderStateException("Nothing left to bill on this purchase order");
        }
        LocalDate today = LocalDate.now(billingClock);
        LocalDate dueDate = po.getPaymentTermsDays() != null ? today.plusDays(po.getPaymentTermsDays()) : null;
        return new InvoiceDraftResponse(po.getLeadId(), po.getCustomerId(), po.getPlaceOfSupplyStateId(),
                po.getCustomerName(), po.getBillingAddress(), po.getCustomerGstin(), po.getCustomerName(),
                po.getShippingAddress() != null ? po.getShippingAddress() : po.getBillingAddress(),
                today, dueDate, po.getId(), po.getPoNumber(), po.getPoDate(), po.getNotes(), lines);
    }

    // ------------------------------------------------------------------ attachments

    @Transactional(noRollbackFor = {NotFoundException.class,
            com.salesmanager.crm.leadattachment.InvalidAttachmentException.class})
    public LeadAttachment uploadAttachment(UUID poId, MultipartFile file) {
        PurchaseOrder po = poRepository.findById(poId)
                .orElseThrow(() -> new NotFoundException("Purchase order not found: " + poId));
        checkVisibility(po, poId, false);
        return leadAttachmentService.upload(po.getLeadId(), poId, file);
    }

    @Transactional(readOnly = true)
    public List<LeadAttachment> listAttachments(UUID poId) {
        getById(poId);
        return leadAttachmentRepository.findByPoIdOrderByCreatedAtDesc(poId);
    }

    // ------------------------------------------------------------------ hooks for InvoiceService

    /** Locks a PO for invoicing and checks it may be billed. Read-only apart from the row lock. */
    @Transactional(noRollbackFor = {NotFoundException.class, InvalidPurchaseOrderStateException.class})
    public PurchaseOrder lockForBilling(UUID poId, UUID leadId) {
        PurchaseOrder po = loadForUpdate(poId, false);
        requireBillable(po);
        if (leadId != null && !leadId.equals(po.getLeadId())) {
            throw new InvalidPurchaseOrderStateException("This invoice's lead does not match the purchase order's lead");
        }
        return po;
    }

    /** The PO's lines, row-locked, keyed by id. Call after {@link #lockForBilling}. */
    @Transactional
    public Map<UUID, PurchaseOrderLine> lockLines(UUID poId) {
        Map<UUID, PurchaseOrderLine> byId = new LinkedHashMap<>();
        for (PurchaseOrderLine line : lineRepository.findByPoIdForUpdate(poId)) {
            byId.put(line.getId(), line);
        }
        return byId;
    }

    /**
     * Over-billing check - writes nothing. Returns true when billing goes past a line's remaining
     * quantity and an ADMIN supplied a reason; throws {@link OverBillingException} when it does
     * and they didn't (or the caller isn't an admin). Returns false when everything fits.
     */
    public boolean checkWithinRemaining(Map<UUID, BigDecimal> requestedByLine,
                                         Map<UUID, PurchaseOrderLine> lines, String overrideReason) {
        List<OverBillingException.Violation> violations = new ArrayList<>();
        for (Map.Entry<UUID, BigDecimal> entry : requestedByLine.entrySet()) {
            PurchaseOrderLine line = lines.get(entry.getKey());
            BigDecimal remaining = line.getQuantityRemaining().max(BigDecimal.ZERO);
            if (entry.getValue().compareTo(remaining) > 0) {
                violations.add(new OverBillingException.Violation(line.getId(), line.getDescription(),
                        entry.getValue(), remaining));
            }
        }
        if (violations.isEmpty()) {
            return false;
        }
        String detail = violations.stream()
                .map(v -> v.description() + ": billing " + v.requested().stripTrailingZeros().toPlainString()
                        + " but only " + v.remaining().stripTrailingZeros().toPlainString() + " remaining")
                .collect(Collectors.joining("; "));
        if (currentUser.get().getRole() != Role.ADMIN) {
            throw new OverBillingException("Cannot bill more than the remaining purchase order quantity ("
                    + detail + "). Only an admin can override this.", violations, false);
        }
        if (overrideReason == null || overrideReason.isBlank()) {
            throw new OverBillingException("Billing exceeds the remaining purchase order quantity (" + detail
                    + "). Provide an override reason to continue.", violations, true);
        }
        return true;
    }

    /** Adds the invoiced quantities to the PO lines and moves the PO status. Call after the
     * invoice has been saved and every check has passed. */
    @Transactional
    public void applyBilling(PurchaseOrder po, Map<UUID, PurchaseOrderLine> lines,
                              Map<UUID, BigDecimal> billedByLine, Invoice invoice, String overrideReason) {
        for (Map.Entry<UUID, BigDecimal> entry : billedByLine.entrySet()) {
            PurchaseOrderLine line = lines.get(entry.getKey());
            line.setQuantityBilled(line.getQuantityBilled().add(entry.getValue()));
        }
        lineRepository.saveAllAndFlush(billedByLine.keySet().stream().map(lines::get).toList());
        PurchaseOrderStatus status = recomputeStatus(po, lines.values());
        String text = "Purchase order " + po.getPoNumber() + " billed on invoice " + invoice.getInvoiceNumber()
                + " (" + statusLabel(status) + ")";
        if (overrideReason != null) {
            text += " - admin override of the remaining quantity: " + overrideReason;
        }
        recordActivity(po, null, ActivityType.PO_BILLED, text);
    }

    /** Subtracts a cancelled invoice's quantities from its PO and moves the PO status back. */
    @Transactional(noRollbackFor = NotFoundException.class)
    public void restoreBilling(UUID poId, Map<UUID, BigDecimal> billedByLine, Invoice invoice) {
        PurchaseOrder po = poRepository.findByIdForUpdate(poId)
                .orElseThrow(() -> new NotFoundException("Purchase order not found: " + poId));
        Map<UUID, PurchaseOrderLine> lines = lockLines(poId);
        for (Map.Entry<UUID, BigDecimal> entry : billedByLine.entrySet()) {
            PurchaseOrderLine line = lines.get(entry.getKey());
            if (line != null) {
                line.setQuantityBilled(line.getQuantityBilled().subtract(entry.getValue()).max(BigDecimal.ZERO));
            }
        }
        lineRepository.saveAllAndFlush(lines.values());
        PurchaseOrderStatus status = recomputeStatus(po, lines.values());
        recordActivity(po, null, ActivityType.PO_BILLED, "Invoice " + invoice.getInvoiceNumber()
                + " cancelled - quantities returned to purchase order " + po.getPoNumber()
                + " (" + statusLabel(status) + ")");
    }

    // ------------------------------------------------------------------ internals

    /** ACCEPTED when nothing is billed, FULLY_BILLED when every line is, PARTLY_BILLED otherwise. */
    PurchaseOrderStatus recomputeStatus(PurchaseOrder po, Collection<PurchaseOrderLine> lines) {
        boolean anyBilled = lines.stream().anyMatch(l -> l.getQuantityBilled().signum() > 0);
        boolean allFull = !lines.isEmpty()
                && lines.stream().allMatch(l -> l.getQuantityBilled().compareTo(l.getQuantityOrdered()) >= 0);
        PurchaseOrderStatus status = allFull ? PurchaseOrderStatus.FULLY_BILLED
                : anyBilled ? PurchaseOrderStatus.PARTLY_BILLED : PurchaseOrderStatus.ACCEPTED;
        po.setStatus(status);
        poRepository.saveAndFlush(po);
        return status;
    }

    private static String statusLabel(PurchaseOrderStatus status) {
        return switch (status) {
            case PARTLY_BILLED -> "partly billed";
            case FULLY_BILLED -> "fully billed";
            case ACCEPTED -> "accepted, nothing billed";
            default -> status.name().toLowerCase();
        };
    }

    private void requireBillable(PurchaseOrder po) {
        if (!po.getStatus().isBillable()) {
            throw new InvalidPurchaseOrderStateException(
                    po.getStatus() == PurchaseOrderStatus.RECEIVED
                            ? "Accept the purchase order before invoicing it"
                            : "Purchase order " + po.getPoNumber() + " is " + po.getStatus()
                                    + " and cannot be invoiced");
        }
    }

    private PurchaseOrder loadForUpdate(UUID id, boolean allowTeamVisibility) {
        PurchaseOrder po = poRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Purchase order not found: " + id));
        return checkVisibility(po, id, allowTeamVisibility);
    }

    private PurchaseOrder checkVisibility(PurchaseOrder po, UUID id, boolean allowTeamVisibility) {
        UserPrincipal principal = currentUser.get();
        if (principal.getRole() == Role.EMPLOYEE && !po.getOwnerId().equals(principal.getEmployeeId())) {
            boolean withinTeamScope = allowTeamVisibility && employeeHierarchyService
                    .getTeamVisibilityScope(principal.getOrganizationId(), principal.getEmployeeId())
                    .contains(po.getOwnerId());
            if (!withinTeamScope) {
                throw new NotFoundException("Purchase order not found: " + id);
            }
        }
        return po;
    }

    private void recordActivity(PurchaseOrder po, Lead leadOrNull, ActivityType type, String description) {
        Lead lead = leadOrNull != null ? leadOrNull : leadRepository.findById(po.getLeadId()).orElse(null);
        if (lead == null) {
            return;
        }
        activityLogService.record(lead.getId(), lead.getOwnerId(), lead.getCompanyName(), type,
                currentUser.get().getEmployeeId(), description);
    }

    private static String normalizePoNumber(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new PurchaseOrderLineException("poNumber", "poNumber is required");
        }
        return trimmed;
    }

    private Organization loadOrganization(UUID organizationId) {
        return organizationRepository.findById(organizationId)
                .orElseThrow(() -> new NotFoundException("Organization not found: " + organizationId));
    }

    /** Same rule as InvoiceService: unknown buyer state means intra-state; a known buyer state
     * with an unknown seller state can't be decided, so reject rather than guess. */
    private boolean determineInterState(Organization seller, UUID supplyStateId) {
        if (supplyStateId == null) {
            return false;
        }
        if (seller.getBillingStateId() == null) {
            throw new BillingProfileIncompleteException(
                    "Set your state in the billing profile before recording a purchase order for a customer "
                            + "with a known state - it decides whether CGST+SGST or IGST applies");
        }
        return GstCalculator.isInterState(seller.getBillingStateId(), supplyStateId);
    }

    private Quotation resolveQuotation(UUID quotationId, UUID leadId) {
        if (quotationId == null) {
            return null;
        }
        Quotation quotation = quotationRepository.findById(quotationId)
                .orElseThrow(() -> new NotFoundException("Quotation not found: " + quotationId));
        if (quotation.getLeadId() == null || !quotation.getLeadId().equals(leadId)) {
            throw new PurchaseOrderLineException("quotationId", "That quotation belongs to a different lead");
        }
        return quotation;
    }

    private CustomerSnapshot snapshotCustomer(PurchaseOrderRequest request, Customer customer, Lead lead) {
        String name = firstNonBlank(request.customerName(), customer != null ? customer.getName() : null,
                lead.getCompanyName());
        String gstin = firstNonBlank(request.customerGstin(), customer != null ? customer.getGstin() : null, null);
        String billing = firstNonBlank(request.billingAddress(), customer != null ? customer.getAddress() : null,
                lead.getAddress());
        String shipping = firstNonBlank(request.shippingAddress(), null, billing);
        return new CustomerSnapshot(name, gstin, billing, shipping);
    }

    private static String firstNonBlank(String a, String b, String c) {
        for (String value : new String[] {a, b, c}) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static List<PurchaseOrderLineRequest> requireLines(List<PurchaseOrderLineRequest> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new PurchaseOrderLineException("lineItems", "lineItems must contain at least one item");
        }
        return lines;
    }

    /** Pure in-memory/read-only: builds every line's amounts (and checks quotation links) before anything is written. */
    private List<ComputedLine> computeLines(List<PurchaseOrderLineRequest> requests, boolean interState,
                                             Quotation quotation) {
        Set<UUID> quotationLineIds = new HashSet<>();
        if (quotation != null) {
            quotationLineItemRepository.findByQuotationIdOrderBySortOrderAsc(quotation.getId())
                    .forEach(q -> quotationLineIds.add(q.getId()));
        }
        List<ComputedLine> out = new ArrayList<>();
        int sort = 0;
        for (PurchaseOrderLineRequest req : requests) {
            if (req.quotationLineId() != null && !quotationLineIds.contains(req.quotationLineId())) {
                throw new PurchaseOrderLineException("lineItems",
                        "A line refers to a quotation line that is not on the linked quotation");
            }
            if (req.productId() != null && req.quantity().stripTrailingZeros().scale() > 0) {
                throw new PurchaseOrderLineException("quantity",
                        "quantity must be a whole number for a catalog product line");
            }
            BigDecimal taxable = round(req.rate().multiply(req.quantity()));
            GstCalculator.Split tax = GstCalculator.split(taxable, req.taxRatePercent(), interState);
            BigDecimal total = round(taxable.add(tax.cgst()).add(tax.sgst()).add(tax.igst()));
            out.add(new ComputedLine(req, taxable, tax, total, sort++));
        }
        return out;
    }

    private void saveLines(UUID poId, List<ComputedLine> lines) {
        for (ComputedLine c : lines) {
            PurchaseOrderLineRequest r = c.request();
            lineRepository.saveAndFlush(PurchaseOrderLine.builder()
                    .poId(poId)
                    .productId(r.productId())
                    .quotationLineId(r.quotationLineId())
                    .description(r.description().trim())
                    .hsnSac(r.hsnSac())
                    .unit(r.unit())
                    .quantityOrdered(r.quantity())
                    .rate(round(r.rate()))
                    .taxRatePercent(r.taxRatePercent())
                    .lineTaxableAmount(c.taxable())
                    .lineCgstAmount(c.tax().cgst())
                    .lineSgstAmount(c.tax().sgst())
                    .lineIgstAmount(c.tax().igst())
                    .lineTotal(c.total())
                    .sortOrder(c.sortOrder())
                    .build());
        }
    }

    private Totals sum(List<ComputedLine> lines) {
        BigDecimal taxable = BigDecimal.ZERO;
        BigDecimal cgst = BigDecimal.ZERO;
        BigDecimal sgst = BigDecimal.ZERO;
        BigDecimal igst = BigDecimal.ZERO;
        for (ComputedLine c : lines) {
            taxable = taxable.add(c.taxable());
            cgst = cgst.add(c.tax().cgst());
            sgst = sgst.add(c.tax().sgst());
            igst = igst.add(c.tax().igst());
        }
        taxable = round(taxable);
        cgst = round(cgst);
        sgst = round(sgst);
        igst = round(igst);
        BigDecimal taxTotal = round(cgst.add(sgst).add(igst));
        return new Totals(taxable, cgst, sgst, igst, taxTotal, round(taxable.add(taxTotal)));
    }

    private void applyTotals(PurchaseOrder po, Totals t) {
        po.setTaxableTotal(t.taxable());
        po.setCgstTotal(t.cgst());
        po.setSgstTotal(t.sgst());
        po.setIgstTotal(t.igst());
        po.setTaxTotal(t.tax());
        po.setGrandTotal(t.grand());
    }

    /** A quotation line's price after its discount - what the customer was actually quoted per unit. */
    private static BigDecimal netRate(QuotationLineItem q) {
        BigDecimal discount = q.getDiscountPercent() != null ? q.getDiscountPercent() : BigDecimal.ZERO;
        return round(q.getUnitPrice().multiply(HUNDRED.subtract(discount)).divide(HUNDRED, 10, RoundingMode.HALF_UP));
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private record CustomerSnapshot(String name, String gstin, String billingAddress, String shippingAddress) {
    }

    private record ComputedLine(PurchaseOrderLineRequest request, BigDecimal taxable, GstCalculator.Split tax,
                                 BigDecimal total, int sortOrder) {
    }

    private record Totals(BigDecimal taxable, BigDecimal cgst, BigDecimal sgst, BigDecimal igst,
                           BigDecimal tax, BigDecimal grand) {
    }
}
