package com.salesmanager.crm.quotation;

import com.salesmanager.crm.entitlement.FeatureEntitlement;
import com.salesmanager.crm.entitlement.RequireEntitlement;
import com.salesmanager.crm.invoicing.Invoice;
import com.salesmanager.crm.invoicing.dto.InvoiceResponse;
import com.salesmanager.crm.invoicing.InvoiceService;
import com.salesmanager.crm.quotation.dto.QuotationAttachmentResponse;
import com.salesmanager.crm.quotation.dto.QuotationCreateRequest;
import com.salesmanager.crm.quotation.dto.QuotationResponse;
import com.salesmanager.crm.quotation.dto.QuotationStatusUpdateRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * No ADMIN-only restriction - any authenticated employee drafts/sends their own quotations, same
 * as Lead/Invoice creation. Visibility is owner-scoped, expanded by TEAM_VISIBILITY exactly like
 * invoicing.InvoiceController. Every endpoint requires INVOICING.
 */
@RestController
@RequestMapping("/quotations")
public class QuotationController {

    private final QuotationService quotationService;
    private final QuotationAttachmentService quotationAttachmentService;
    private final InvoiceService invoiceService;

    public QuotationController(QuotationService quotationService,
                                QuotationAttachmentService quotationAttachmentService,
                                InvoiceService invoiceService) {
        this.quotationService = quotationService;
        this.quotationAttachmentService = quotationAttachmentService;
        this.invoiceService = invoiceService;
    }

    @GetMapping
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public Page<QuotationResponse> list(@RequestParam(required = false) QuotationStatus status,
                                         @RequestParam(required = false) UUID ownerId,
                                         @RequestParam(required = false) UUID customerId,
                                         @RequestParam(required = false) UUID cityId,
                                         @RequestParam(required = false) UUID stateId,
                                         @RequestParam(required = false) LocalDate dateFrom,
                                         @RequestParam(required = false) LocalDate dateTo,
                                         @RequestParam(required = false) String search,
                                         Pageable pageable) {
        QuotationFilter filter = new QuotationFilter(status, ownerId, customerId, cityId, stateId,
                dateFrom, dateTo, search);
        return quotationService.list(filter, pageable)
                .map(q -> QuotationResponse.from(q, quotationService.getLineItems(q.getId())));
    }

    @GetMapping("/{id}")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public QuotationResponse getById(@PathVariable UUID id) {
        Quotation quotation = quotationService.getById(id);
        return QuotationResponse.from(quotation, quotationService.getLineItems(id));
    }

    @PostMapping
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    @ResponseStatus(HttpStatus.CREATED)
    public QuotationResponse create(@Valid @RequestBody QuotationCreateRequest request) {
        Quotation quotation = quotationService.create(request);
        return QuotationResponse.from(quotation, quotationService.getLineItems(quotation.getId()));
    }

    @PutMapping("/{id}")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public QuotationResponse update(@PathVariable UUID id, @Valid @RequestBody QuotationCreateRequest request) {
        Quotation quotation = quotationService.update(id, request);
        return QuotationResponse.from(quotation, quotationService.getLineItems(id));
    }

    @PatchMapping("/{id}/status")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public QuotationResponse updateStatus(@PathVariable UUID id,
                                           @Valid @RequestBody QuotationStatusUpdateRequest request) {
        Quotation quotation = quotationService.updateStatus(id, request);
        return QuotationResponse.from(quotation, quotationService.getLineItems(id));
    }

    @PostMapping("/{id}/convert-to-invoice")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public InvoiceResponse convertToInvoice(@PathVariable UUID id) {
        Invoice invoice = quotationService.convertToInvoice(id);
        return InvoiceResponse.from(invoice, invoiceService.getLineItems(invoice.getId()),
                invoiceService.getAmountPaid(invoice.getId()));
    }

    @GetMapping("/{id}/pdf")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public ResponseEntity<byte[]> downloadPdf(@PathVariable UUID id) {
        Quotation quotation = quotationService.getById(id);
        byte[] pdfBytes = quotationService.renderQuotationPdf(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + quotation.getQuotationNumber() + ".pdf\"")
                .body(pdfBytes);
    }

    @PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    @ResponseStatus(HttpStatus.CREATED)
    public QuotationAttachmentResponse uploadAttachment(@PathVariable UUID id,
                                                          @RequestPart("file") MultipartFile file) {
        return QuotationAttachmentResponse.from(quotationAttachmentService.upload(id, file));
    }

    @GetMapping("/{id}/attachments")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public List<QuotationAttachmentResponse> listAttachments(@PathVariable UUID id) {
        return quotationAttachmentService.list(id).stream().map(QuotationAttachmentResponse::from).toList();
    }

    @GetMapping("/attachments/{attachmentId}/download")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public ResponseEntity<byte[]> downloadAttachment(@PathVariable UUID attachmentId) {
        QuotationAttachmentService.LoadedAttachment loaded = quotationAttachmentService.download(attachmentId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + sanitizeHeaderValue(loaded.fileName()) + "\"")
                .contentType(resolveMediaType(loaded.contentType()))
                .body(loaded.content());
    }

    @DeleteMapping("/attachments/{attachmentId}")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAttachment(@PathVariable UUID attachmentId) {
        quotationAttachmentService.delete(attachmentId);
    }

    private static MediaType resolveMediaType(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /** Strips characters that would break the Content-Disposition header's quoted-string syntax
     * or otherwise be unsafe in a header value - see leadattachment.LeadAttachmentController's
     * identical helper. */
    private static String sanitizeHeaderValue(String fileName) {
        return fileName.replaceAll("[\"\\r\\n]", "_");
    }
}
