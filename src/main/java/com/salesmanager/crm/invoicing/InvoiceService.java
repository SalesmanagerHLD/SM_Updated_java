package com.salesmanager.crm.invoicing;

import com.salesmanager.crm.common.NotFoundException;
import com.salesmanager.crm.invoicing.design.InvoiceDesignSettings;
import com.salesmanager.crm.customer.Customer;
import com.salesmanager.crm.customer.CustomerService;
import com.salesmanager.crm.employee.EmployeeHierarchyService;
import com.salesmanager.crm.employee.Role;
import com.salesmanager.crm.inventory.InsufficientStockException;
import com.salesmanager.crm.inventory.Product;
import com.salesmanager.crm.inventory.ProductService;
import com.salesmanager.crm.inventory.StockMovementReason;
import com.salesmanager.crm.invoicing.dto.InvoiceCreateRequest;
import com.salesmanager.crm.invoicing.dto.InvoiceLineItemRequest;
import com.salesmanager.crm.invoicing.dto.InvoiceStatusUpdateRequest;
import com.salesmanager.crm.invoicing.dto.PaymentRequest;
import com.salesmanager.crm.quotation.Quotation;
import com.salesmanager.crm.quotation.QuotationLineItem;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An issued invoice is never edited or deleted. The only things that change afterwards are
 * payments recorded against it (insert-only ledger) and cancellation, which reverses the stock
 * deduction - see {@link #cancel}.
 *
 * <p><b>Why {@link #create} validates ALL catalog lines BEFORE writing anything (critical)</b>:
 * TenantFilter wraps the WHOLE HTTP request in one shared transaction, and in this codebase's
 * established convention, a method's {@code noRollbackFor} doesn't just avoid an ugly HTTP
 * response - it determines whether that ENTIRE shared transaction commits (persisting
 * everything written in the request so far) or rolls back, since {@code noRollbackFor}
 * prevents the transaction from being marked rollback-only at all. That means excluding
 * InsufficientStockException/NotFoundException from rollback AFTER the Invoice/InvoiceLineItem
 * rows were already written would let a "failed" (409/404) invoice creation silently COMMIT a
 * ghost invoice anyway. The fix: do all locking/validation
 * (inventory.ProductService#lockAndCheckStock - read-only, throws nothing that could ever leave
 * bad data behind) for every catalog line FIRST, and only once every line has been confirmed
 * valid AND in-stock, write the Invoice/InvoiceLineItem rows and then actually decrement stock
 * (inventory.ProductService#applyStockChange, which by this point cannot legitimately fail -
 * the locks acquired during validation are held continuously through to this point, so nothing
 * else could have changed the checked quantities in between).
 *
 * <p><b>Lock-ordering discipline (critical)</b>: a single invoice can reference several catalog
 * products, each pessimistically locked for the request's full duration. Two concurrent
 * invoices referencing the same two products in opposite order is a textbook deadlock -
 * {@link #create} always locks/decrements DISTINCT products in ascending-id order (never the
 * order line items happen to appear in the request) to guarantee every transaction acquires
 * multi-product locks in the same relative order.
 *
 * <p><b>Fractional quantities</b>: InvoiceLineItem.quantity is a BigDecimal (ad-hoc lines may
 * be fractional, e.g. "1.5 hours consulting"), but Product.stockQuantity is a plain int - a
 * catalog line's quantity must therefore be a whole number (validated below); only an ad-hoc
 * line (no stock to touch) may be fractional.
 */
@Service
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceLineItemRepository invoiceLineItemRepository;
    private final InvoiceNumberService invoiceNumberService;
    private final ProductService productService;
    private final EmployeeHierarchyService employeeHierarchyService;
    private final CurrentUser currentUser;
    private final BillingProfileService billingProfileService;
    private final InvoicePdfService invoicePdfService;
    private final InvoicePaymentRepository paymentRepository;
    private final OrganizationRepository organizationRepository;
    private final CustomerService customerService;
    private final Clock billingClock;
    private final InvoiceDesignService invoiceDesignService;
    private final InvoiceLogoSnapshotRepository logoSnapshotRepository;

    public InvoiceService(InvoiceRepository invoiceRepository,
                           InvoiceLineItemRepository invoiceLineItemRepository,
                           InvoiceNumberService invoiceNumberService,
                           ProductService productService,
                           EmployeeHierarchyService employeeHierarchyService,
                           CurrentUser currentUser,
                           BillingProfileService billingProfileService,
                           InvoicePdfService invoicePdfService,
                           InvoicePaymentRepository paymentRepository,
                           OrganizationRepository organizationRepository,
                           CustomerService customerService,
                           Clock billingClock,
                           InvoiceDesignService invoiceDesignService,
                           InvoiceLogoSnapshotRepository logoSnapshotRepository) {
        this.invoiceRepository = invoiceRepository;
        this.invoiceLineItemRepository = invoiceLineItemRepository;
        this.invoiceNumberService = invoiceNumberService;
        this.productService = productService;
        this.employeeHierarchyService = employeeHierarchyService;
        this.currentUser = currentUser;
        this.billingProfileService = billingProfileService;
        this.invoicePdfService = invoicePdfService;
        this.paymentRepository = paymentRepository;
        this.organizationRepository = organizationRepository;
        this.customerService = customerService;
        this.billingClock = billingClock;
        this.invoiceDesignService = invoiceDesignService;
        this.logoSnapshotRepository = logoSnapshotRepository;
    }

    // noRollbackFor must cover every exception Phase 1 can throw, not just whichever inner
    // method actually throws it - EVERY @Transactional method the exception propagates THROUGH
    // gets its own chance (via its own rollback rules) to mark the shared transaction
    // rollback-only, and create() is the last such boundary before the controller. Safe here
    // because all of them are only ever thrown before Phase 2 writes anything - see this class's
    // javadoc.
    @Transactional(noRollbackFor = {InvalidInvoiceLineItemException.class, NotFoundException.class,
            InsufficientStockException.class, BillingProfileIncompleteException.class})
    public Invoice create(InvoiceCreateRequest request) {
        UserPrincipal principal = currentUser.get();
        UUID actingEmployeeId = principal.getEmployeeId();

        // Phase 1 (read-only): structural validation first (no DB access), then who-supplies-
        // where (tax type), then aggregate requested quantity per distinct catalog product and
        // lock + confirm each is active and has enough stock IN ASCENDING-ID ORDER.
        List<RawLine> rawLines = validateLineItemShapes(request.lineItems());
        Organization seller = loadOrganization(principal.getOrganizationId());
        Customer customer = request.customerId() != null ? customerService.getById(request.customerId()) : null;
        UUID supplyStateId = request.placeOfSupplyStateId() != null ? request.placeOfSupplyStateId()
                : (customer != null ? customer.getStateId() : null);
        boolean interState = determineInterState(seller, supplyStateId);

        Map<UUID, BigDecimal> quantityByProductId = aggregateQuantityByProduct(rawLines);
        Map<UUID, Product> lockedProducts = lockProducts(quantityByProductId);
        List<ResolvedLine> resolvedLines = resolveLines(rawLines, lockedProducts, interState);

        // Phase 2 (writes): everything from here on should not legitimately fail.
        Totals totals = sumTotals(resolvedLines);
        String invoiceNumber = invoiceNumberService.allocateNext(principal.getOrganizationId(), request.invoiceDate());

        Invoice invoice = Invoice.builder()
                .invoiceNumber(invoiceNumber)
                .leadId(request.leadId())
                .customerId(request.customerId())
                .ownerId(actingEmployeeId)
                .createdBy(actingEmployeeId)
                .customerName(request.customerName())
                .customerContactPerson(request.customerContactPerson())
                .customerPhone(request.customerPhone())
                .customerEmail(request.customerEmail())
                .customerAddress(request.customerAddress())
                .customerGstin(request.customerGstin())
                .shipToName(request.shipToName())
                .shipToAddress(request.shipToAddress())
                .shipToGstin(request.shipToGstin())
                .invoiceDate(request.invoiceDate())
                .dueDate(request.dueDate())
                .placeOfSupply(request.placeOfSupply())
                .placeOfSupplyStateId(supplyStateId != null ? supplyStateId : seller.getBillingStateId())
                .reverseCharge(request.reverseCharge())
                .status(InvoiceStatus.UNPAID)
                .notes(request.notes())
                .build();
        applyTotals(invoice, totals);
        invoice.snapshotSellerFrom(seller);
        return persistInvoice(invoice, resolvedLines, quantityByProductId, actingEmployeeId);
    }

    /**
     * Builds a real Invoice from an already-APPROVED/SENT Quotation - called only by
     * quotation.QuotationService#convertToInvoice (which has already row-locked the quotation),
     * sharing this class's lock-ordering/two-phase validate-then-write discipline since converting
     * is the genuine stock-out event a Quotation itself never triggers.
     *
     * <p>Price, discount and tax rate are COPIED from the approved quotation's lines - never
     * re-read from the live Product - so the customer is invoiced exactly what they approved.
     * Products are locked only for the stock check/deduction. The invoice date is "today" in the
     * billing time zone (not the JVM's UTC), and the number comes from that date's financial year.
     */
    @Transactional(noRollbackFor = {InvalidInvoiceLineItemException.class, NotFoundException.class,
            InsufficientStockException.class, BillingProfileIncompleteException.class})
    public Invoice createFromQuotation(Quotation quotation, List<QuotationLineItem> quotationLineItems) {
        UUID actingEmployeeId = currentUser.get().getEmployeeId();

        List<RawLine> rawLines = new ArrayList<>();
        for (QuotationLineItem item : quotationLineItems) {
            rawLines.add(new RawLine(item.getProductId(), item.getHsnSac(), item.getDescription(), item.getUnit(),
                    item.getQuantity(), item.getUnitPrice(), item.getDiscountPercent(), item.getTaxRatePercent(),
                    true));
        }
        Organization seller = loadOrganization(quotation.getOrganizationId());
        UUID supplyStateId = quotation.getStateId();
        boolean interState = determineInterState(seller, supplyStateId);

        Map<UUID, BigDecimal> quantityByProductId = aggregateQuantityByProduct(rawLines);
        Map<UUID, Product> lockedProducts = lockProducts(quantityByProductId);
        List<ResolvedLine> resolvedLines = resolveLines(rawLines, lockedProducts, interState);
        Totals totals = sumTotals(resolvedLines);

        LocalDate invoiceDate = LocalDate.now(billingClock);
        String invoiceNumber = invoiceNumberService.allocateNext(quotation.getOrganizationId(), invoiceDate);

        Invoice invoice = Invoice.builder()
                .invoiceNumber(invoiceNumber)
                .leadId(quotation.getLeadId())
                .customerId(quotation.getCustomerId())
                .ownerId(quotation.getOwnerId())
                .createdBy(actingEmployeeId)
                .customerName(quotation.getCustomerName())
                .customerContactPerson(quotation.getCustomerContactPerson())
                .customerPhone(quotation.getCustomerPhone())
                .customerEmail(quotation.getCustomerEmail())
                .customerAddress(quotation.getCustomerBillingAddress())
                .customerGstin(quotation.getCustomerGstin())
                .shipToName(quotation.getCustomerName())
                .shipToAddress(quotation.getCustomerBillingAddress())
                .shipToGstin(quotation.getCustomerGstin())
                .invoiceDate(invoiceDate)
                .dueDate(invoiceDate.plusDays(15))
                .placeOfSupplyStateId(supplyStateId != null ? supplyStateId : seller.getBillingStateId())
                .quotationId(quotation.getId())
                .status(InvoiceStatus.UNPAID)
                .notes(quotation.getQuotationNotes())
                .build();
        applyTotals(invoice, totals);
        invoice.snapshotSellerFrom(seller);
        return persistInvoice(invoice, resolvedLines, quantityByProductId, actingEmployeeId);
    }

    private Invoice persistInvoice(Invoice invoice, List<ResolvedLine> resolvedLines,
                                    Map<UUID, BigDecimal> quantityByProductId, UUID actingEmployeeId) {
        // The invoice keeps the design and logo in force right now, so editing either later
        // never changes this invoice's PDF.
        invoice.setDesignSettings(invoiceDesignService.currentInvoiceSettingsJson());
        Invoice savedInvoice = invoiceRepository.saveAndFlush(invoice);
        saveLineItems(savedInvoice.getId(), resolvedLines);
        billingProfileService.getLogoBytes().ifPresent(logo ->
                logoSnapshotRepository.save(InvoiceLogoSnapshot.builder()
                        .invoiceId(savedInvoice.getId())
                        .image(logo.bytes())
                        .contentType(logo.contentType())
                        .build()));

        // Same ascending-id order as the Phase 1 validation loop - the lock-ordering discipline
        // applies here too, even though these calls should no longer be able to fail.
        for (Map.Entry<UUID, BigDecimal> entry : quantityByProductId.entrySet()) {
            int quantity = entry.getValue().intValueExact();
            productService.applyStockChange(entry.getKey(), -quantity, StockMovementReason.INVOICE,
                    savedInvoice.getId(), "Invoice " + savedInvoice.getInvoiceNumber(), actingEmployeeId);
        }
        return savedInvoice;
    }

    private Map<UUID, Product> lockProducts(Map<UUID, BigDecimal> quantityByProductId) {
        Map<UUID, Product> lockedProducts = new HashMap<>();
        for (Map.Entry<UUID, BigDecimal> entry : quantityByProductId.entrySet()) {
            lockedProducts.put(entry.getKey(),
                    productService.lockAndCheckStock(entry.getKey(), entry.getValue().intValueExact()));
        }
        return lockedProducts;
    }

    private Organization loadOrganization(UUID organizationId) {
        return organizationRepository.findById(organizationId)
                .orElseThrow(() -> new NotFoundException("Organization not found: " + organizationId));
    }

    /** Tax type = seller state vs place-of-supply state. When the buyer's state is unknown the
     * place of supply defaults to the seller's own state (intra-state). When it IS known but the
     * seller's isn't, the tax type can't be determined - reject rather than guess. */
    private boolean determineInterState(Organization seller, UUID supplyStateId) {
        if (supplyStateId == null) {
            return false;
        }
        if (seller.getBillingStateId() == null) {
            throw new BillingProfileIncompleteException(
                    "Set your state in the billing profile before invoicing a customer with a known state - "
                            + "it decides whether CGST+SGST or IGST applies");
        }
        return GstCalculator.isInterState(seller.getBillingStateId(), supplyStateId);
    }

    private void applyTotals(Invoice invoice, Totals totals) {
        invoice.setSubtotal(totals.subtotal);
        invoice.setDiscountTotal(totals.discountTotal);
        invoice.setTaxableAmount(totals.taxableAmount);
        invoice.setCgstTotal(totals.cgstTotal);
        invoice.setSgstTotal(totals.sgstTotal);
        invoice.setIgstTotal(totals.igstTotal);
        invoice.setTaxTotal(totals.taxTotal);
        invoice.setGrandTotal(totals.grandTotal);
    }

    private void saveLineItems(UUID invoiceId, List<ResolvedLine> resolvedLines) {
        int sortOrder = 0;
        for (ResolvedLine line : resolvedLines) {
            InvoiceLineItem lineItem = InvoiceLineItem.builder()
                    .invoiceId(invoiceId)
                    .productId(line.productId)
                    .hsnSac(line.hsnSac)
                    .description(line.description)
                    .quantity(line.quantity)
                    .unitPrice(line.unitPrice)
                    .discountPercent(line.discountPercent)
                    .taxRatePercent(line.taxRatePercent)
                    .lineSubtotal(line.lineSubtotal)
                    .lineDiscountAmount(line.lineDiscountAmount)
                    .unit(line.unit)
                    .lineTaxAmount(line.lineCgstAmount.add(line.lineSgstAmount).add(line.lineIgstAmount))
                    .lineCgstAmount(line.lineCgstAmount)
                    .lineSgstAmount(line.lineSgstAmount)
                    .lineIgstAmount(line.lineIgstAmount)
                    .sortOrder(sortOrder++)
                    .build();
            invoiceLineItemRepository.saveAndFlush(lineItem);
        }
    }

    private Totals sumTotals(List<ResolvedLine> lines) {
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        BigDecimal cgst = BigDecimal.ZERO;
        BigDecimal sgst = BigDecimal.ZERO;
        BigDecimal igst = BigDecimal.ZERO;
        for (ResolvedLine line : lines) {
            subtotal = subtotal.add(line.lineSubtotal);
            discount = discount.add(line.lineDiscountAmount);
            cgst = cgst.add(line.lineCgstAmount);
            sgst = sgst.add(line.lineSgstAmount);
            igst = igst.add(line.lineIgstAmount);
        }
        subtotal = round(subtotal);
        discount = round(discount);
        BigDecimal taxable = round(subtotal.subtract(discount));
        cgst = round(cgst);
        sgst = round(sgst);
        igst = round(igst);
        BigDecimal taxTotal = round(cgst.add(sgst).add(igst));
        return new Totals(subtotal, discount, taxable, cgst, sgst, igst, taxTotal, round(taxable.add(taxTotal)));
    }

    private record Totals(BigDecimal subtotal, BigDecimal discountTotal, BigDecimal taxableAmount,
                           BigDecimal cgstTotal, BigDecimal sgstTotal, BigDecimal igstTotal,
                           BigDecimal taxTotal, BigDecimal grandTotal) {
    }

    /** Sums requested quantity per distinct product (referenced by more than one line means one
     * combined deduction) into a map ordered by ascending product id - see this class's javadoc
     * for why the order is not negotiable. */
    private Map<UUID, BigDecimal> aggregateQuantityByProduct(List<RawLine> rawLines) {
        Map<UUID, BigDecimal> quantityByProductId = new TreeMap<>(Comparator.naturalOrder());
        for (RawLine line : rawLines) {
            if (line.productId != null) {
                quantityByProductId.merge(line.productId, line.quantity, BigDecimal::add);
            }
        }
        return quantityByProductId;
    }

    /** A line item after structural validation only. A catalog line from a direct-create request
     * carries just its productId + quantity (price/name come from the locked Product);
     * {@code fromSnapshot} lines (converted quotations) carry their own already-approved
     * price/tax/description and use productId only for the stock check. */
    private record RawLine(UUID productId, String hsnSac, String description, String unit, BigDecimal quantity,
                            BigDecimal unitPrice, BigDecimal discountPercent, BigDecimal taxRatePercent,
                            boolean fromSnapshot) {
    }

    /** One resolved, snapshot-ready line. Tax is split by {@link GstCalculator}: CGST+SGST for an
     * intra-state supply, IGST for inter-state. */
    private record ResolvedLine(UUID productId, String hsnSac, String description, String unit,
                                 BigDecimal quantity, BigDecimal unitPrice, BigDecimal discountPercent,
                                 BigDecimal taxRatePercent, BigDecimal lineSubtotal, BigDecimal lineDiscountAmount,
                                 BigDecimal lineCgstAmount, BigDecimal lineSgstAmount, BigDecimal lineIgstAmount) {
    }

    /** Mutual exclusivity (exactly one of productId/description) and whole-number-quantity (for
     * a catalog line) validation - pure in-memory, no DB access, so it can never leave anything
     * to roll back regardless of how many line items are checked before one fails. */
    private List<RawLine> validateLineItemShapes(List<InvoiceLineItemRequest> requests) {
        List<RawLine> rawLines = new ArrayList<>();
        for (InvoiceLineItemRequest lineRequest : requests) {
            boolean hasProduct = lineRequest.productId() != null;
            boolean hasAdHocDescription = lineRequest.description() != null && !lineRequest.description().isBlank();
            if (hasProduct == hasAdHocDescription) {
                throw new InvalidInvoiceLineItemException("lineItems",
                        "Each line item must set exactly one of productId or description");
            }
            if (hasProduct && lineRequest.quantity().stripTrailingZeros().scale() > 0) {
                throw new InvalidInvoiceLineItemException("quantity",
                        "quantity must be a whole number for a catalog product line");
            }
            rawLines.add(new RawLine(lineRequest.productId(), lineRequest.hsnSac(), lineRequest.description(),
                    lineRequest.unit(), lineRequest.quantity(), lineRequest.unitPrice(),
                    lineRequest.discountPercent(), lineRequest.taxRatePercent(), false));
        }
        return rawLines;
    }

    /** Turns each structurally-valid raw line into a fully snapshot-ready ResolvedLine. */
    private List<ResolvedLine> resolveLines(List<RawLine> rawLines, Map<UUID, Product> lockedProducts,
                                             boolean interState) {
        List<ResolvedLine> resolved = new ArrayList<>();
        for (RawLine line : rawLines) {
            String hsnSac;
            String description;
            String unit;
            BigDecimal unitPrice;
            BigDecimal taxRatePercent;
            UUID productId = line.productId;
            if (line.productId != null && !line.fromSnapshot) {
                Product product = lockedProducts.get(line.productId);
                hsnSac = product.getHsnSacCode();
                description = product.getName();
                unit = product.getUnitOfMeasure();
                unitPrice = product.getUnitPrice();
                taxRatePercent = product.getTaxRatePercent();
            } else {
                hsnSac = line.hsnSac;
                description = line.description;
                unit = line.unit;
                unitPrice = line.unitPrice != null ? line.unitPrice : BigDecimal.ZERO;
                taxRatePercent = line.taxRatePercent != null ? line.taxRatePercent : BigDecimal.ZERO;
            }
            BigDecimal discountPercent = line.discountPercent != null ? line.discountPercent : BigDecimal.ZERO;

            BigDecimal lineSubtotal = round(unitPrice.multiply(line.quantity));
            BigDecimal lineDiscountAmount = round(lineSubtotal.multiply(discountPercent)
                    .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
            BigDecimal lineTaxableAmount = lineSubtotal.subtract(lineDiscountAmount);
            GstCalculator.Split tax = GstCalculator.split(lineTaxableAmount, taxRatePercent, interState);

            resolved.add(new ResolvedLine(productId, hsnSac, description, unit, line.quantity, unitPrice,
                    discountPercent, taxRatePercent, lineSubtotal, lineDiscountAmount,
                    tax.cgst(), tax.sgst(), tax.igst()));
        }
        return resolved;
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    public Page<Invoice> list(InvoiceFilter filter, Pageable pageable) {
        UserPrincipal principal = currentUser.get();
        Specification<Invoice> spec = Specification.where(InvoiceSpecifications.hasStatus(filter.status()));

        if (principal.getRole() == Role.EMPLOYEE) {
            Set<UUID> subordinateIds = employeeHierarchyService
                    .getTeamVisibilityScope(principal.getOrganizationId(), principal.getEmployeeId());
            if (subordinateIds.isEmpty()) {
                spec = spec.and(InvoiceSpecifications.hasOwner(principal.getEmployeeId()));
            } else {
                Set<UUID> teamScope = new HashSet<>(subordinateIds);
                teamScope.add(principal.getEmployeeId());
                if (filter.ownerId() != null && teamScope.contains(filter.ownerId())) {
                    spec = spec.and(InvoiceSpecifications.hasOwner(filter.ownerId()));
                } else {
                    spec = spec.and(InvoiceSpecifications.hasOwnerIn(teamScope));
                }
            }
        } else {
            spec = spec.and(InvoiceSpecifications.hasOwner(filter.ownerId()));
        }

        return invoiceRepository.findAll(spec, pageable);
    }

    /** ADMIN can fetch any invoice in their org; EMPLOYEE gets a NotFoundException (never a
     * 403) for a colleague's invoice unless TEAM_VISIBILITY is entitled and its owner is in
     * their subordinate chain - same information-hiding principle as lead.LeadService#getById. */
    @Transactional(readOnly = true, noRollbackFor = NotFoundException.class)
    public Invoice getById(UUID id) {
        return loadForCurrentUser(id, true);
    }

    @Transactional(readOnly = true)
    public List<InvoiceLineItem> getLineItems(UUID invoiceId) {
        return invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc(invoiceId);
    }

    /** Batch form for list pages - one query for every invoice on the page. */
    @Transactional(readOnly = true)
    public Map<UUID, List<InvoiceLineItem>> getLineItemsByInvoiceIds(Collection<UUID> invoiceIds) {
        Map<UUID, List<InvoiceLineItem>> byInvoice = new HashMap<>();
        if (invoiceIds.isEmpty()) {
            return byInvoice;
        }
        for (InvoiceLineItem item : invoiceLineItemRepository.findByInvoiceIdInOrderBySortOrderAsc(invoiceIds)) {
            byInvoice.computeIfAbsent(item.getInvoiceId(), k -> new ArrayList<>()).add(item);
        }
        return byInvoice;
    }

    @Transactional(readOnly = true)
    public BigDecimal getAmountPaid(UUID invoiceId) {
        return paymentRepository.sumAmountByInvoiceId(invoiceId);
    }

    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> getAmountPaidByInvoiceIds(Collection<UUID> invoiceIds) {
        Map<UUID, BigDecimal> paid = new HashMap<>();
        if (invoiceIds.isEmpty()) {
            return paid;
        }
        for (Object[] row : paymentRepository.sumAmountByInvoiceIds(invoiceIds)) {
            paid.put((UUID) row[0], (BigDecimal) row[1]);
        }
        return paid;
    }

    /** Same visibility scoping as {@link #getById}. */
    @Transactional(readOnly = true, noRollbackFor = NotFoundException.class)
    public List<InvoicePayment> getPayments(UUID invoiceId) {
        loadForCurrentUser(invoiceId, true);
        return paymentRepository.findByInvoiceIdOrderByPaymentDateAscCreatedAtAsc(invoiceId);
    }

    /** Same visibility scoping as {@link #getById} (via loadForCurrentUser) - a colleague's
     * invoice can't be downloaded any more than it can be viewed. */
    @Transactional(readOnly = true, noRollbackFor = NotFoundException.class)
    public byte[] renderInvoicePdf(UUID id) {
        Invoice invoice = loadForCurrentUser(id, true);
        List<InvoiceLineItem> lineItems = getLineItems(id);
        // Snapshot logo first; invoices issued before snapshots existed fall back to the live logo.
        String logoDataUri = logoSnapshotRepository.findByInvoiceId(id)
                .map(snap -> toDataUri(snap.getContentType(), snap.getImage()))
                .or(() -> billingProfileService.getLogoDataUri())
                .orElse(null);
        InvoiceDesignSettings design = invoiceDesignService.parseOrDefault(invoice.getDesignSettings());
        return invoicePdfService.renderPdf(invoice, lineItems, paymentRepository.sumAmountByInvoiceId(id),
                logoDataUri, design);
    }

    /**
     * Renders UNSAVED design settings to a PDF for the Invoice design page's live preview -
     * against a real invoice when invoiceId is given (same visibility rules as downloading it),
     * otherwise a made-up sample. Stores nothing.
     */
    @Transactional(readOnly = true)
    public byte[] renderDesignPreviewPdf(InvoiceDesignSettings design, UUID invoiceId) {
        String logoDataUri = billingProfileService.getLogoDataUri().orElse(null);
        if (invoiceId != null) {
            Invoice invoice = loadForCurrentUser(invoiceId, true);
            return invoicePdfService.renderPdf(invoice, getLineItems(invoiceId),
                    paymentRepository.sumAmountByInvoiceId(invoiceId), logoDataUri, design);
        }
        Invoice sample = InvoiceDesignSample.invoice(billingProfileService.getBillingProfile());
        return invoicePdfService.renderPdf(sample, InvoiceDesignSample.lines(), BigDecimal.ZERO, logoDataUri, design);
    }

    private static String toDataUri(String contentType, byte[] bytes) {
        return "data:" + contentType + ";base64," + java.util.Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Records one payment against an invoice (one row per payment mode) and re-derives the
     * invoice status from the ledger: nothing paid -> UNPAID, some -> PARTIALLY_PAID, all -> PAID.
     * The invoice row is locked first so two concurrent payments can't together overpay it.
     * TEAM_VISIBILITY is a READ-only grant here too, same rule as Lead.
     */
    @Transactional(noRollbackFor = {NotFoundException.class, InvalidInvoiceStateException.class})
    public Invoice recordPayment(UUID id, PaymentRequest request) {
        Invoice invoice = loadForCurrentUserForUpdate(id);
        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            throw new InvalidInvoiceStateException("A cancelled invoice cannot receive payments");
        }
        BigDecimal alreadyPaid = paymentRepository.sumAmountByInvoiceId(id);
        BigDecimal balance = invoice.getGrandTotal().subtract(alreadyPaid);
        BigDecimal amount = round(request.amount());
        if (amount.compareTo(balance) > 0) {
            throw new InvalidInvoiceStateException(
                    "Payment of " + amount + " exceeds the balance due of " + balance);
        }
        paymentRepository.saveAndFlush(InvoicePayment.builder()
                .invoiceId(id)
                .amount(amount)
                .mode(request.mode())
                .paymentDate(request.paymentDate() != null ? request.paymentDate() : LocalDate.now(billingClock))
                .reference(request.reference())
                .notes(request.notes())
                .recordedBy(currentUser.get().getEmployeeId())
                .build());
        return refreshStatus(invoice, alreadyPaid.add(amount));
    }

    /**
     * Cancels an invoice (never deletes it): status -> CANCELLED with a reason, and every catalog
     * line's stock is returned (ascending product-id order, same lock discipline as creation).
     * An invoice that already has payments can't be cancelled here - the money would be
     * stranded in the ledger; that needs the credit-note/refund flow.
     */
    @Transactional(noRollbackFor = {NotFoundException.class, InvalidInvoiceStateException.class})
    public Invoice cancel(UUID id, String reason) {
        Invoice invoice = loadForCurrentUserForUpdate(id);
        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            throw new InvalidInvoiceStateException("Invoice is already cancelled");
        }
        if (paymentRepository.sumAmountByInvoiceId(id).signum() > 0) {
            throw new InvalidInvoiceStateException(
                    "An invoice with recorded payments cannot be cancelled - refund/credit it instead");
        }
        UUID actingEmployeeId = currentUser.get().getEmployeeId();

        Map<UUID, BigDecimal> quantityByProductId = new TreeMap<>(Comparator.naturalOrder());
        for (InvoiceLineItem line : invoiceLineItemRepository.findByInvoiceIdOrderBySortOrderAsc(id)) {
            if (line.getProductId() != null) {
                quantityByProductId.merge(line.getProductId(), line.getQuantity(), BigDecimal::add);
            }
        }
        for (Map.Entry<UUID, BigDecimal> entry : quantityByProductId.entrySet()) {
            productService.applyStockChange(entry.getKey(), entry.getValue().intValueExact(),
                    StockMovementReason.INVOICE_CANCELLATION, id,
                    "Cancelled invoice " + invoice.getInvoiceNumber(), actingEmployeeId);
        }

        invoice.setStatus(InvoiceStatus.CANCELLED);
        invoice.setCancelledAt(OffsetDateTime.now(billingClock));
        invoice.setCancelReason(reason);
        invoice.setCancelledBy(actingEmployeeId);
        return invoiceRepository.saveAndFlush(invoice);
    }

    /** Legacy endpoint kept for API compatibility: PAID now means "settle the remaining balance
     * with a payment". Moving back to UNPAID (or setting the other statuses directly) is no
     * longer possible - the status is derived from payments, and corrections go through cancel. */
    @Transactional(noRollbackFor = {NotFoundException.class, InvalidInvoiceStateException.class})
    public Invoice updateStatus(UUID id, InvoiceStatusUpdateRequest request) {
        if (request.status() != InvoiceStatus.PAID) {
            throw new InvalidInvoiceStateException(
                    "Status can only be set to PAID (which records the remaining balance as a payment). "
                            + "Use the payments and cancel endpoints for anything else");
        }
        Invoice invoice = loadForCurrentUserForUpdate(id);
        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            throw new InvalidInvoiceStateException("A cancelled invoice cannot be marked paid");
        }
        BigDecimal balance = invoice.getGrandTotal().subtract(paymentRepository.sumAmountByInvoiceId(id));
        if (balance.signum() > 0) {
            return recordPayment(id, new PaymentRequest(balance, PaymentMode.OTHER, null, null, "Marked as paid"));
        }
        return refreshStatus(invoice, invoice.getGrandTotal());
    }

    private Invoice refreshStatus(Invoice invoice, BigDecimal totalPaid) {
        InvoiceStatus status;
        if (totalPaid.compareTo(invoice.getGrandTotal()) >= 0 && totalPaid.signum() > 0) {
            status = InvoiceStatus.PAID;
        } else if (totalPaid.signum() > 0) {
            status = InvoiceStatus.PARTIALLY_PAID;
        } else {
            status = InvoiceStatus.UNPAID;
        }
        invoice.setStatus(status);
        return invoiceRepository.saveAndFlush(invoice);
    }

    private Invoice loadForCurrentUserForUpdate(UUID id) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Invoice not found: " + id));
        return checkVisibility(invoice, id, false);
    }

    private Invoice loadForCurrentUser(UUID id, boolean allowTeamVisibility) {
        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Invoice not found: " + id));
        return checkVisibility(invoice, id, allowTeamVisibility);
    }

    private Invoice checkVisibility(Invoice invoice, UUID id, boolean allowTeamVisibility) {
        UserPrincipal principal = currentUser.get();
        if (principal.getRole() == Role.EMPLOYEE && !invoice.getOwnerId().equals(principal.getEmployeeId())) {
            boolean withinTeamScope = allowTeamVisibility && employeeHierarchyService
                    .getTeamVisibilityScope(principal.getOrganizationId(), principal.getEmployeeId())
                    .contains(invoice.getOwnerId());
            if (!withinTeamScope) {
                throw new NotFoundException("Invoice not found: " + id);
            }
        }
        return invoice;
    }
}
