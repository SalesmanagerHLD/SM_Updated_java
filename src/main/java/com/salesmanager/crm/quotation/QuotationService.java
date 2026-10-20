package com.salesmanager.crm.quotation;

import com.salesmanager.crm.common.NotFoundException;
import com.salesmanager.crm.customer.Customer;
import com.salesmanager.crm.customer.CustomerService;
import com.salesmanager.crm.employee.EmployeeHierarchyService;
import com.salesmanager.crm.employee.Role;
import com.salesmanager.crm.inventory.Product;
import com.salesmanager.crm.inventory.ProductService;
import com.salesmanager.crm.invoicing.BillingProfileService;
import com.salesmanager.crm.invoicing.GstCalculator;
import com.salesmanager.crm.invoicing.Invoice;
import com.salesmanager.crm.invoicing.InvoiceService;
import com.salesmanager.crm.lead.LeadService;
import com.salesmanager.crm.masterdata.MasterDataService;
import com.salesmanager.crm.masterdata.MasterType;
import com.salesmanager.crm.quotation.dto.QuotationCreateRequest;
import com.salesmanager.crm.quotation.dto.QuotationLineItemRequest;
import com.salesmanager.crm.quotation.dto.QuotationStatusUpdateRequest;
import com.salesmanager.crm.security.CurrentUser;
import com.salesmanager.crm.security.UserPrincipal;
import com.salesmanager.crm.tenant.OrganizationRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unlike invoicing.Invoice (create-only), a Quotation is edit-in-place while DRAFT/SENT - a rep
 * revising a quotation before approval shouldn't need to void and recreate it. Status lifecycle:
 * DRAFT/SENT (set by create/update) -&gt; APPROVED/REJECTED (via {@link #updateStatus}, SENT only)
 * -&gt; CONVERTED (via {@link #convertToInvoice}, SENT or APPROVED only) - CONVERTED is terminal,
 * enforced by every mutating method below rejecting a non-DRAFT/SENT quotation.
 *
 * <p>Quotation creation/edit never touches Product stock - only {@link #convertToInvoice} does
 * (delegated to invoicing.InvoiceService#createFromQuotation, which reuses that class's existing
 * lock-ordering/two-phase validate-then-write discipline), consistent with "invoicing is the
 * real stock-out event," not quoting.
 */
@Service
public class QuotationService {

    private final QuotationRepository quotationRepository;
    private final QuotationLineItemRepository quotationLineItemRepository;
    private final QuotationNumberService quotationNumberService;
    private final CustomerService customerService;
    private final ProductService productService;
    private final MasterDataService masterDataService;
    private final EmployeeHierarchyService employeeHierarchyService;
    private final InvoiceService invoiceService;
    private final QuotationAttachmentRepository quotationAttachmentRepository;
    private final BillingProfileService billingProfileService;
    private final QuotationPdfService quotationPdfService;
    private final CurrentUser currentUser;
    private final OrganizationRepository organizationRepository;
    private final LeadService leadService;

    public QuotationService(QuotationRepository quotationRepository,
                             QuotationLineItemRepository quotationLineItemRepository,
                             QuotationNumberService quotationNumberService,
                             CustomerService customerService,
                             ProductService productService,
                             MasterDataService masterDataService,
                             EmployeeHierarchyService employeeHierarchyService,
                             InvoiceService invoiceService,
                             QuotationAttachmentRepository quotationAttachmentRepository,
                             BillingProfileService billingProfileService,
                             QuotationPdfService quotationPdfService,
                             CurrentUser currentUser,
                             OrganizationRepository organizationRepository,
                             LeadService leadService) {
        this.organizationRepository = organizationRepository;
        this.leadService = leadService;
        this.quotationRepository = quotationRepository;
        this.quotationLineItemRepository = quotationLineItemRepository;
        this.quotationNumberService = quotationNumberService;
        this.customerService = customerService;
        this.productService = productService;
        this.masterDataService = masterDataService;
        this.employeeHierarchyService = employeeHierarchyService;
        this.invoiceService = invoiceService;
        this.quotationAttachmentRepository = quotationAttachmentRepository;
        this.billingProfileService = billingProfileService;
        this.quotationPdfService = quotationPdfService;
        this.currentUser = currentUser;
    }

    @Transactional(noRollbackFor = {InvalidQuotationLineItemException.class, InvalidQuotationStateException.class,
            NotFoundException.class})
    public Quotation create(QuotationCreateRequest request) {
        if (request.status() != QuotationStatus.DRAFT && request.status() != QuotationStatus.SENT) {
            throw new InvalidQuotationStateException("A new quotation must be created as DRAFT or SENT");
        }
        UserPrincipal principal = currentUser.get();
        Customer customer = customerService.getById(request.customerId());
        validateReferences(request);

        List<ResolvedLine> resolvedLines = resolveLines(request.lineItems(), isInterState(request.stateId()));
        Totals totals = sumTotals(resolvedLines);

        String quotationNumber = quotationNumberService.allocateNext(
                principal.getOrganizationId(), request.quotationDate().getYear());

        Quotation quotation = Quotation.builder()
                .quotationNumber(quotationNumber)
                .leadId(request.leadId())
                .customerId(customer.getId())
                .customerName(customer.getName())
                .customerContactPerson(request.customerContactPerson())
                .customerDesignation(request.customerDesignation())
                .customerPhone(request.customerPhone())
                .customerEmail(request.customerEmail())
                .customerBillingAddress(request.customerBillingAddress())
                .customerGstin(request.customerGstin())
                .industryId(request.industryId())
                .industryOther(request.industryOther())
                .cityId(request.cityId())
                .cityOther(request.cityOther())
                .stateId(request.stateId())
                .stateOther(request.stateOther())
                .interestLevelId(request.interestLevelId())
                .interestLevelOther(request.interestLevelOther())
                .businessTypeId(request.businessTypeId())
                .businessTypeOther(request.businessTypeOther())
                .ownerId(principal.getEmployeeId())
                .typeOfVisit(request.typeOfVisit())
                .quotationDate(request.quotationDate())
                .validTillDate(request.validTillDate())
                .referenceEnquiryNo(request.referenceEnquiryNo())
                .expectedCloseDate(request.expectedCloseDate())
                .quotationNotes(request.quotationNotes())
                .termsAndConditions(request.termsAndConditions())
                .internalNote(request.internalNote())
                .followUpDate(request.followUpDate())
                .followUpTime(request.followUpTime())
                .followUpByEmployeeId(request.followUpByEmployeeId())
                .followUpNote(request.followUpNote())
                .subtotal(totals.subtotal)
                .discountTotal(totals.discountTotal)
                .taxableAmount(totals.taxableAmount)
                .cgstTotal(totals.cgstTotal)
                .sgstTotal(totals.sgstTotal)
                .igstTotal(totals.igstTotal)
                .grandTotal(totals.grandTotal)
                .status(request.status())
                .createdBy(principal.getEmployeeId())
                .build();
        Quotation saved = quotationRepository.saveAndFlush(quotation);
        saveLineItems(saved.getId(), resolvedLines);
        return saved;
    }

    @Transactional(noRollbackFor = {InvalidQuotationLineItemException.class, InvalidQuotationStateException.class,
            NotFoundException.class})
    public Quotation update(UUID id, QuotationCreateRequest request) {
        if (request.status() != QuotationStatus.DRAFT && request.status() != QuotationStatus.SENT) {
            throw new InvalidQuotationStateException("A quotation can only be saved as DRAFT or SENT");
        }
        Quotation quotation = loadForCurrentUser(id, false);
        if (quotation.getStatus() != QuotationStatus.DRAFT && quotation.getStatus() != QuotationStatus.SENT) {
            throw new InvalidQuotationStateException(
                    "Only a DRAFT or SENT quotation can be edited - current status is " + quotation.getStatus());
        }
        Customer customer = customerService.getById(request.customerId());
        validateReferences(request);

        List<ResolvedLine> resolvedLines = resolveLines(request.lineItems(), isInterState(request.stateId()));
        Totals totals = sumTotals(resolvedLines);

        quotation.setLeadId(request.leadId());
        quotation.setCustomerId(customer.getId());
        quotation.setCustomerName(customer.getName());
        quotation.setCustomerContactPerson(request.customerContactPerson());
        quotation.setCustomerDesignation(request.customerDesignation());
        quotation.setCustomerPhone(request.customerPhone());
        quotation.setCustomerEmail(request.customerEmail());
        quotation.setCustomerBillingAddress(request.customerBillingAddress());
        quotation.setCustomerGstin(request.customerGstin());
        quotation.setIndustryId(request.industryId());
        quotation.setIndustryOther(request.industryOther());
        quotation.setCityId(request.cityId());
        quotation.setCityOther(request.cityOther());
        quotation.setStateId(request.stateId());
        quotation.setStateOther(request.stateOther());
        quotation.setInterestLevelId(request.interestLevelId());
        quotation.setInterestLevelOther(request.interestLevelOther());
        quotation.setBusinessTypeId(request.businessTypeId());
        quotation.setBusinessTypeOther(request.businessTypeOther());
        quotation.setTypeOfVisit(request.typeOfVisit());
        quotation.setQuotationDate(request.quotationDate());
        quotation.setValidTillDate(request.validTillDate());
        quotation.setReferenceEnquiryNo(request.referenceEnquiryNo());
        quotation.setExpectedCloseDate(request.expectedCloseDate());
        quotation.setQuotationNotes(request.quotationNotes());
        quotation.setTermsAndConditions(request.termsAndConditions());
        quotation.setInternalNote(request.internalNote());
        quotation.setFollowUpDate(request.followUpDate());
        quotation.setFollowUpTime(request.followUpTime());
        quotation.setFollowUpByEmployeeId(request.followUpByEmployeeId());
        quotation.setFollowUpNote(request.followUpNote());
        quotation.setSubtotal(totals.subtotal);
        quotation.setDiscountTotal(totals.discountTotal);
        quotation.setTaxableAmount(totals.taxableAmount);
        quotation.setCgstTotal(totals.cgstTotal);
        quotation.setSgstTotal(totals.sgstTotal);
        quotation.setIgstTotal(totals.igstTotal);
        quotation.setGrandTotal(totals.grandTotal);
        quotation.setStatus(request.status());
        Quotation saved = quotationRepository.saveAndFlush(quotation);

        quotationLineItemRepository.deleteByQuotationId(id);
        saveLineItems(id, resolvedLines);
        return saved;
    }

    private void saveLineItems(UUID quotationId, List<ResolvedLine> resolvedLines) {
        int sortOrder = 0;
        for (ResolvedLine line : resolvedLines) {
            QuotationLineItem lineItem = QuotationLineItem.builder()
                    .quotationId(quotationId)
                    .productId(line.productId)
                    .hsnSac(line.hsnSac)
                    .description(line.description)
                    .quantity(line.quantity)
                    .unit(line.unit)
                    .unitPrice(line.unitPrice)
                    .discountPercent(line.discountPercent)
                    .taxRatePercent(line.taxRatePercent)
                    .lineSubtotal(line.lineSubtotal)
                    .lineDiscountAmount(line.lineDiscountAmount)
                    .lineTaxableAmount(line.lineTaxableAmount)
                    .lineCgstAmount(line.lineCgstAmount)
                    .lineSgstAmount(line.lineSgstAmount)
                    .lineIgstAmount(line.lineIgstAmount)
                    .lineTotal(line.lineTotal)
                    .sortOrder(sortOrder++)
                    .build();
            quotationLineItemRepository.saveAndFlush(lineItem);
        }
    }

    private void validateReferences(QuotationCreateRequest request) {
        masterDataService.validateCreatableField(request.industryId(), request.industryOther(),
                MasterType.INDUSTRY, "industryId", "industryOther");
        masterDataService.validateCreatableField(request.stateId(), request.stateOther(),
                MasterType.STATE, "stateId", "stateOther");
        masterDataService.validateCreatableField(request.cityId(), request.cityOther(),
                MasterType.CITY, "cityId", "cityOther", request.stateId());
        masterDataService.validateCreatableField(request.interestLevelId(), request.interestLevelOther(),
                MasterType.INTEREST_LEVEL, "interestLevelId", "interestLevelOther");
        masterDataService.validateCreatableField(request.businessTypeId(), request.businessTypeOther(),
                MasterType.BUSINESS_TYPE, "businessTypeId", "businessTypeOther");
    }

    /** Structural validation + snapshot resolution in one pass - no stock lock (Quotation never
     * deducts stock, see this class's javadoc), so a simple read-only Product lookup suffices. */
    /** IGST applies when the buyer's state is known and differs from the seller's billing-profile
     * state; with either unknown it stays CGST+SGST (a quotation isn't the legal tax document). */
    private boolean isInterState(java.util.UUID supplyStateId) {
        java.util.UUID sellerStateId = organizationRepository.findById(currentUser.get().getOrganizationId())
                .map(o -> o.getBillingStateId()).orElse(null);
        return GstCalculator.isInterState(sellerStateId, supplyStateId);
    }

    private List<ResolvedLine> resolveLines(List<QuotationLineItemRequest> requests, boolean interState) {
        List<ResolvedLine> resolved = new ArrayList<>();
        for (QuotationLineItemRequest lineRequest : requests) {
            boolean hasProduct = lineRequest.productId() != null;
            boolean hasAdHocDescription = lineRequest.description() != null && !lineRequest.description().isBlank();
            if (hasProduct == hasAdHocDescription) {
                throw new InvalidQuotationLineItemException("lineItems",
                        "Each line item must set exactly one of productId or description");
            }

            BigDecimal quantity = lineRequest.quantity();
            BigDecimal discountPercent = lineRequest.discountPercent() != null
                    ? lineRequest.discountPercent() : BigDecimal.ZERO;

            String hsnSac;
            String description;
            String unitOfMeasure;
            BigDecimal unitPrice;
            BigDecimal taxRatePercent;
            UUID productId = null;

            if (hasProduct) {
                Product product = productService.getById(lineRequest.productId());
                if (!product.isActive()) {
                    throw new NotFoundException("Product not found: " + lineRequest.productId());
                }
                productId = product.getId();
                hsnSac = product.getHsnSacCode();
                description = product.getName();
                unitOfMeasure = product.getUnitOfMeasure();
                unitPrice = product.getUnitPrice();
                taxRatePercent = product.getTaxRatePercent();
            } else {
                hsnSac = lineRequest.hsnSac();
                description = lineRequest.description();
                unitOfMeasure = lineRequest.unit();
                unitPrice = lineRequest.unitPrice() != null ? lineRequest.unitPrice() : BigDecimal.ZERO;
                taxRatePercent = lineRequest.taxRatePercent() != null ? lineRequest.taxRatePercent() : BigDecimal.ZERO;
            }

            BigDecimal lineSubtotal = round(unitPrice.multiply(quantity));
            BigDecimal lineDiscountAmount = round(lineSubtotal.multiply(discountPercent)
                    .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
            BigDecimal lineTaxableAmount = round(lineSubtotal.subtract(lineDiscountAmount));
            GstCalculator.Split tax = GstCalculator.split(lineTaxableAmount, taxRatePercent, interState);
            BigDecimal lineCgstAmount = tax.cgst();
            BigDecimal lineSgstAmount = tax.sgst();
            BigDecimal lineIgstAmount = tax.igst();
            BigDecimal lineTotal = round(lineTaxableAmount.add(tax.total()));

            resolved.add(new ResolvedLine(productId, hsnSac, description, quantity, unitOfMeasure, unitPrice,
                    discountPercent, taxRatePercent, lineSubtotal, lineDiscountAmount, lineTaxableAmount,
                    lineCgstAmount, lineSgstAmount, lineIgstAmount, lineTotal));
        }
        return resolved;
    }

    private Totals sumTotals(List<ResolvedLine> lines) {
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;
        BigDecimal taxableAmount = BigDecimal.ZERO;
        BigDecimal cgstTotal = BigDecimal.ZERO;
        BigDecimal sgstTotal = BigDecimal.ZERO;
        BigDecimal igstTotal = BigDecimal.ZERO;
        BigDecimal grandTotal = BigDecimal.ZERO;
        for (ResolvedLine line : lines) {
            subtotal = subtotal.add(line.lineSubtotal);
            discountTotal = discountTotal.add(line.lineDiscountAmount);
            taxableAmount = taxableAmount.add(line.lineTaxableAmount);
            cgstTotal = cgstTotal.add(line.lineCgstAmount);
            sgstTotal = sgstTotal.add(line.lineSgstAmount);
            igstTotal = igstTotal.add(line.lineIgstAmount);
            grandTotal = grandTotal.add(line.lineTotal);
        }
        return new Totals(round(subtotal), round(discountTotal), round(taxableAmount), round(cgstTotal),
                round(sgstTotal), round(igstTotal), round(grandTotal));
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private record ResolvedLine(UUID productId, String hsnSac, String description, BigDecimal quantity,
                                 String unit, BigDecimal unitPrice, BigDecimal discountPercent,
                                 BigDecimal taxRatePercent, BigDecimal lineSubtotal, BigDecimal lineDiscountAmount,
                                 BigDecimal lineTaxableAmount, BigDecimal lineCgstAmount, BigDecimal lineSgstAmount,
                                 BigDecimal lineIgstAmount, BigDecimal lineTotal) {
    }

    private record Totals(BigDecimal subtotal, BigDecimal discountTotal, BigDecimal taxableAmount,
                           BigDecimal cgstTotal, BigDecimal sgstTotal, BigDecimal igstTotal,
                           BigDecimal grandTotal) {
    }

    @Transactional(readOnly = true)
    public Page<Quotation> list(QuotationFilter filter, Pageable pageable) {
        UserPrincipal principal = currentUser.get();
        Specification<Quotation> spec = Specification
                .where(QuotationSpecifications.hasStatus(filter.status()))
                .and(QuotationSpecifications.hasCustomer(filter.customerId()))
                .and(QuotationSpecifications.hasCity(filter.cityId()))
                .and(QuotationSpecifications.hasState(filter.stateId()))
                .and(QuotationSpecifications.quotationDateBetween(filter.dateFrom(), filter.dateTo()))
                .and(QuotationSpecifications.matchesSearch(filter.search()));

        if (principal.getRole() == Role.EMPLOYEE) {
            Set<UUID> subordinateIds = employeeHierarchyService
                    .getTeamVisibilityScope(principal.getOrganizationId(), principal.getEmployeeId());
            if (subordinateIds.isEmpty()) {
                spec = spec.and(QuotationSpecifications.hasOwner(principal.getEmployeeId()));
            } else {
                Set<UUID> teamScope = new HashSet<>(subordinateIds);
                teamScope.add(principal.getEmployeeId());
                if (filter.ownerId() != null && teamScope.contains(filter.ownerId())) {
                    spec = spec.and(QuotationSpecifications.hasOwner(filter.ownerId()));
                } else {
                    spec = spec.and(QuotationSpecifications.hasOwnerIn(teamScope));
                }
            }
        } else {
            spec = spec.and(QuotationSpecifications.hasOwner(filter.ownerId()));
        }

        return quotationRepository.findAll(spec, pageable);
    }

    @Transactional(readOnly = true, noRollbackFor = NotFoundException.class)
    public Quotation getById(UUID id) {
        return loadForCurrentUser(id, true);
    }

    @Transactional(readOnly = true)
    public List<QuotationLineItem> getLineItems(UUID quotationId) {
        return quotationLineItemRepository.findByQuotationIdOrderBySortOrderAsc(quotationId);
    }

    /** Same visibility scoping as {@link #getById} - a colleague's quotation can't be downloaded
     * any more than it can be viewed. */
    @Transactional(readOnly = true, noRollbackFor = NotFoundException.class)
    public byte[] renderQuotationPdf(UUID id) {
        Quotation quotation = loadForCurrentUser(id, true);
        List<QuotationLineItem> lineItems = getLineItems(id);
        List<QuotationAttachment> attachments = quotationAttachmentRepository.findByQuotationIdOrderByCreatedAtDesc(id);
        String logoDataUri = billingProfileService.getLogoDataUri().orElse(null);
        return quotationPdfService.renderPdf(quotation, lineItems, attachments,
                billingProfileService.getBillingProfile(), logoDataUri);
    }

    /** SENT -> APPROVED/REJECTED only - any other transition (including from DRAFT, or
     * targeting DRAFT/SENT/CONVERTED) is rejected. TEAM_VISIBILITY is read-only, same rule as
     * Invoice#updateStatus - a manager can see but not decide a subordinate's quotation. */
    @Transactional(noRollbackFor = {NotFoundException.class, InvalidQuotationStateException.class})
    public Quotation updateStatus(UUID id, QuotationStatusUpdateRequest request) {
        Quotation quotation = loadForCurrentUser(id, false);
        if (quotation.getStatus() != QuotationStatus.SENT) {
            throw new InvalidQuotationStateException(
                    "Only a SENT quotation can be approved or rejected - current status is " + quotation.getStatus());
        }
        if (request.status() != QuotationStatus.APPROVED && request.status() != QuotationStatus.REJECTED) {
            throw new InvalidQuotationStateException("status must be APPROVED or REJECTED");
        }
        quotation.setStatus(request.status());
        return quotationRepository.saveAndFlush(quotation);
    }

    /** SENT or APPROVED only, never twice - sets status=CONVERTED and convertedInvoiceId in the
     * SAME transaction as invoicing.InvoiceService#createFromQuotation's writes (both share the
     * request-wide transaction TenantFilter already opens), so a failure partway through can
     * never leave a Quotation marked CONVERTED without a real Invoice behind it. */
    // The invoice-side exceptions are all thrown by InvoiceService's read-only validation phase,
    // BEFORE anything is written - so letting the shared request transaction commit is safe, and
    // without them here the 4xx response was cut off by an UnexpectedRollbackException at commit.
    @Transactional(noRollbackFor = {NotFoundException.class, InvalidQuotationStateException.class,
            com.salesmanager.crm.inventory.InsufficientStockException.class,
            com.salesmanager.crm.invoicing.InvalidInvoiceLineItemException.class,
            com.salesmanager.crm.invoicing.BillingProfileIncompleteException.class})
    public Invoice convertToInvoice(UUID id) {
        // Row-lock first: two concurrent clicks serialize here, and the second one then sees
        // CONVERTED and is rejected - instead of both creating an invoice and deducting stock.
        Quotation quotation = quotationRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Quotation not found: " + id));
        checkVisibility(quotation, id, false);
        if (quotation.getStatus() != QuotationStatus.SENT && quotation.getStatus() != QuotationStatus.APPROVED) {
            throw new InvalidQuotationStateException(
                    "Only a SENT or APPROVED quotation can be converted to an invoice - current status is "
                            + quotation.getStatus());
        }
        List<QuotationLineItem> lineItems = getLineItems(id);
        Invoice invoice = invoiceService.createFromQuotation(quotation, lineItems);
        quotation.setStatus(QuotationStatus.CONVERTED);
        quotation.setConvertedInvoiceId(invoice.getId());
        quotationRepository.saveAndFlush(quotation);
        // Same transaction as the invoice: a won deal marks its lead CLOSED_WON, but only where
        // the lead's own status rules already allow it (see LeadService#markClosedWonIfAllowed).
        // An invoice created directly with a leadId (no quotation) deliberately does NOT do this.
        if (quotation.getLeadId() != null) {
            leadService.markClosedWonIfAllowed(quotation.getLeadId());
        }
        return invoice;
    }

    private Quotation loadForCurrentUser(UUID id, boolean allowTeamVisibility) {
        Quotation quotation = quotationRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Quotation not found: " + id));
        return checkVisibility(quotation, id, allowTeamVisibility);
    }

    private Quotation checkVisibility(Quotation quotation, UUID id, boolean allowTeamVisibility) {
        UserPrincipal principal = currentUser.get();
        if (principal.getRole() == Role.EMPLOYEE && !quotation.getOwnerId().equals(principal.getEmployeeId())) {
            boolean withinTeamScope = allowTeamVisibility && employeeHierarchyService
                    .getTeamVisibilityScope(principal.getOrganizationId(), principal.getEmployeeId())
                    .contains(quotation.getOwnerId());
            if (!withinTeamScope) {
                throw new NotFoundException("Quotation not found: " + id);
            }
        }
        return quotation;
    }
}
