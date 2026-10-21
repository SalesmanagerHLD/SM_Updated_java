package com.salesmanager.crm.purchaseorder;

import com.salesmanager.crm.entitlement.FeatureEntitlement;
import com.salesmanager.crm.entitlement.RequireEntitlement;
import com.salesmanager.crm.leadattachment.dto.LeadAttachmentResponse;
import com.salesmanager.crm.purchaseorder.dto.InvoiceDraftResponse;
import com.salesmanager.crm.purchaseorder.dto.PurchaseOrderCancelRequest;
import com.salesmanager.crm.purchaseorder.dto.PurchaseOrderRequest;
import com.salesmanager.crm.purchaseorder.dto.PurchaseOrderResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
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
 * No ADMIN-only restriction (same as invoices): visibility is owner-scoped, widened by
 * TEAM_VISIBILITY. There is deliberately no DELETE - a PO is cancelled. Downloading the PO scan
 * reuses {@code GET /attachments/{id}/download}. Every endpoint requires INVOICING.
 */
@RestController
@RequestMapping("/purchase-orders")
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;

    public PurchaseOrderController(PurchaseOrderService purchaseOrderService) {
        this.purchaseOrderService = purchaseOrderService;
    }

    @GetMapping
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public Page<PurchaseOrderResponse> list(@RequestParam(required = false) UUID leadId,
                                             @RequestParam(required = false) PurchaseOrderStatus status,
                                             @RequestParam(required = false) UUID customerId,
                                             @RequestParam(required = false) UUID ownerId,
                                             @RequestParam(required = false) LocalDate dateFrom,
                                             @RequestParam(required = false) LocalDate dateTo,
                                             @RequestParam(required = false) String search,
                                             Pageable pageable) {
        Page<PurchaseOrder> page = purchaseOrderService.list(
                new PurchaseOrderFilter(leadId, status, customerId, ownerId, dateFrom, dateTo, search), pageable);
        var linesByPo = purchaseOrderService.getLinesByPoIds(page.getContent().stream().map(PurchaseOrder::getId).toList());
        return page.map(po -> purchaseOrderService.toResponse(po, linesByPo.getOrDefault(po.getId(), List.of()), false));
    }

    @GetMapping("/{id}")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public PurchaseOrderResponse getById(@PathVariable UUID id) {
        PurchaseOrder po = purchaseOrderService.getById(id);
        return purchaseOrderService.toResponse(po, purchaseOrderService.getLines(id), true);
    }

    /** "Copy from quotation": a pre-filled (unsaved) PO body. */
    @GetMapping("/prefill")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public PurchaseOrderRequest prefill(@RequestParam UUID quotationId) {
        return purchaseOrderService.prefillFromQuotation(quotationId);
    }

    @PostMapping
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseOrderResponse create(@Valid @RequestBody PurchaseOrderRequest request) {
        PurchaseOrder po = purchaseOrderService.create(request);
        return purchaseOrderService.toResponse(po, purchaseOrderService.getLines(po.getId()), true);
    }

    @PutMapping("/{id}")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public PurchaseOrderResponse update(@PathVariable UUID id, @Valid @RequestBody PurchaseOrderRequest request) {
        PurchaseOrder po = purchaseOrderService.update(id, request);
        return purchaseOrderService.toResponse(po, purchaseOrderService.getLines(id), true);
    }

    @PostMapping("/{id}/accept")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public PurchaseOrderResponse accept(@PathVariable UUID id) {
        PurchaseOrder po = purchaseOrderService.accept(id);
        return purchaseOrderService.toResponse(po, purchaseOrderService.getLines(id), true);
    }

    @PostMapping("/{id}/cancel")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public PurchaseOrderResponse cancel(@PathVariable UUID id, @Valid @RequestBody PurchaseOrderCancelRequest request) {
        PurchaseOrder po = purchaseOrderService.cancel(id, request.reason());
        return purchaseOrderService.toResponse(po, purchaseOrderService.getLines(id), true);
    }

    /** Returns a pre-filled invoice body for the remaining quantities. Saves nothing: submit it to POST /invoices. */
    @PostMapping("/{id}/invoice")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public InvoiceDraftResponse invoiceDraft(@PathVariable UUID id) {
        return purchaseOrderService.buildInvoiceDraft(id);
    }

    @PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    @ResponseStatus(HttpStatus.CREATED)
    public LeadAttachmentResponse uploadAttachment(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return LeadAttachmentResponse.from(purchaseOrderService.uploadAttachment(id, file));
    }

    @GetMapping("/{id}/attachments")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public List<LeadAttachmentResponse> listAttachments(@PathVariable UUID id) {
        return purchaseOrderService.listAttachments(id).stream().map(LeadAttachmentResponse::from).toList();
    }
}
