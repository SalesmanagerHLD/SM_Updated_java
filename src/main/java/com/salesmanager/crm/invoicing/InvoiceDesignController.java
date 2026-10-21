package com.salesmanager.crm.invoicing;

import com.salesmanager.crm.entitlement.FeatureEntitlement;
import com.salesmanager.crm.entitlement.RequireEntitlement;
import com.salesmanager.crm.invoicing.InvoiceDesignService.DesignView;
import com.salesmanager.crm.invoicing.design.DocumentType;
import com.salesmanager.crm.invoicing.design.InvoiceDesignSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET and preview are open to any entitled user (every user downloads invoices); saving and
 * resetting the design are ADMIN-only - same split as {@link BillingProfileController}. Preview
 * renders unsaved settings to a real PDF and stores nothing.
 */
@RestController
public class InvoiceDesignController {

    private final InvoiceDesignService designService;
    private final InvoiceService invoiceService;

    public InvoiceDesignController(InvoiceDesignService designService, InvoiceService invoiceService) {
        this.designService = designService;
        this.invoiceService = invoiceService;
    }

    public record DesignResponse(InvoiceDesignSettings settings, int version, boolean isDefault) {
        static DesignResponse from(DesignView view) {
            return new DesignResponse(view.settings(), view.version(), view.isDefault());
        }
    }

    public record PreviewRequest(@NotNull @Valid InvoiceDesignSettings settings, UUID invoiceId) {
    }

    @GetMapping("/organizations/me/invoice-design")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public DesignResponse get(@RequestParam(defaultValue = "INVOICE") DocumentType documentType) {
        return DesignResponse.from(designService.get(documentType));
    }

    @PutMapping("/organizations/me/invoice-design")
    @PreAuthorize("hasRole('ADMIN')")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public DesignResponse save(@RequestParam(defaultValue = "INVOICE") DocumentType documentType,
                               @Valid @RequestBody InvoiceDesignSettings settings) {
        return DesignResponse.from(designService.save(documentType, settings));
    }

    @DeleteMapping("/organizations/me/invoice-design")
    @PreAuthorize("hasRole('ADMIN')")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public DesignResponse reset(@RequestParam(defaultValue = "INVOICE") DocumentType documentType) {
        return DesignResponse.from(designService.reset(documentType));
    }

    @PostMapping("/organizations/me/invoice-design/preview")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public ResponseEntity<byte[]> preview(@Valid @RequestBody PreviewRequest request) {
        byte[] pdf = invoiceService.renderDesignPreviewPdf(request.settings(), request.invoiceId());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"invoice-design-preview.pdf\"")
                .body(pdf);
    }
}
