package com.salesmanager.crm.invoicing;

import com.salesmanager.crm.entitlement.FeatureEntitlement;
import com.salesmanager.crm.entitlement.RequireEntitlement;
import com.salesmanager.crm.invoicing.dto.InvoiceCancelRequest;
import com.salesmanager.crm.invoicing.dto.InvoiceCreateRequest;
import com.salesmanager.crm.invoicing.dto.InvoiceResponse;
import com.salesmanager.crm.invoicing.dto.InvoiceStatusUpdateRequest;
import com.salesmanager.crm.invoicing.dto.PaymentRequest;
import com.salesmanager.crm.invoicing.dto.PaymentResponse;
import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * No ADMIN-only restriction here - any authenticated employee closes and invoices their own
 * deals, same as Lead creation. Visibility (list/getById) is owner-scoped, expanded by
 * TEAM_VISIBILITY exactly like lead.LeadController. Every endpoint requires
 * INVOICING.
 */
@RestController
@RequestMapping("/invoices")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @GetMapping
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public Page<InvoiceResponse> list(@RequestParam(required = false) InvoiceStatus status,
                                       @RequestParam(required = false) UUID ownerId,
                                       Pageable pageable) {
        InvoiceFilter filter = new InvoiceFilter(status, ownerId);
        Page<Invoice> page = invoiceService.list(filter, pageable);
        // Batch-load lines and payment totals for the whole page (2 queries, not 2 per invoice).
        List<UUID> ids = page.getContent().stream().map(Invoice::getId).toList();
        Map<UUID, List<InvoiceLineItem>> lines = invoiceService.getLineItemsByInvoiceIds(ids);
        Map<UUID, java.math.BigDecimal> paid = invoiceService.getAmountPaidByInvoiceIds(ids);
        return page.map(invoice -> InvoiceResponse.from(invoice,
                lines.getOrDefault(invoice.getId(), List.of()), paid.get(invoice.getId())));
    }

    @GetMapping("/{id}")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public InvoiceResponse getById(@PathVariable UUID id) {
        Invoice invoice = invoiceService.getById(id);
        return toResponse(invoice);
    }

    @PostMapping
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceResponse create(@Valid @RequestBody InvoiceCreateRequest request) {
        Invoice invoice = invoiceService.create(request);
        return toResponse(invoice);
    }

    @PostMapping("/{id}/payments")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceResponse recordPayment(@PathVariable UUID id, @Valid @RequestBody PaymentRequest request) {
        return toResponse(invoiceService.recordPayment(id, request));
    }

    @GetMapping("/{id}/payments")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public List<PaymentResponse> listPayments(@PathVariable UUID id) {
        return invoiceService.getPayments(id).stream().map(PaymentResponse::from).toList();
    }

    @PostMapping("/{id}/cancel")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public InvoiceResponse cancel(@PathVariable UUID id, @Valid @RequestBody InvoiceCancelRequest request) {
        return toResponse(invoiceService.cancel(id, request.reason()));
    }

    private InvoiceResponse toResponse(Invoice invoice) {
        return InvoiceResponse.from(invoice, invoiceService.getLineItems(invoice.getId()),
                invoiceService.getAmountPaid(invoice.getId()));
    }

    @PatchMapping("/{id}/status")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public InvoiceResponse updateStatus(@PathVariable UUID id, @Valid @RequestBody InvoiceStatusUpdateRequest request) {
        Invoice invoice = invoiceService.updateStatus(id, request);
        return toResponse(invoice);
    }

    /** First binary-download endpoint in this codebase - plain ResponseEntity<byte[]>, nothing
     * exotic needed. */
    @GetMapping("/{id}/pdf")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public ResponseEntity<byte[]> downloadPdf(@PathVariable UUID id) {
        Invoice invoice = invoiceService.getById(id);
        byte[] pdfBytes = invoiceService.renderInvoicePdf(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + invoice.getInvoiceNumber() + ".pdf\"")
                .body(pdfBytes);
    }
}
