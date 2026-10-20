package com.salesmanager.crm.invoicing;

import com.salesmanager.crm.entitlement.FeatureEntitlement;
import com.salesmanager.crm.entitlement.RequireEntitlement;
import com.salesmanager.crm.invoicing.dto.BillingProfileResponse;
import com.salesmanager.crm.invoicing.dto.BillingProfileUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * GET open to any entitled authenticated user (any employee generating an invoice PDF needs
 * the seller header populated), PUT/logo-upload/logo-delete ADMIN-only - same "generic reads,
 * ADMIN-only mutations" split as theme.ThemeController. Gated by INVOICING since
 * this only matters when Invoicing is in use.
 */
@RestController
public class BillingProfileController {

    private final BillingProfileService billingProfileService;

    public BillingProfileController(BillingProfileService billingProfileService) {
        this.billingProfileService = billingProfileService;
    }

    @GetMapping("/organizations/me/billing-profile")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public BillingProfileResponse getBillingProfile() {
        return billingProfileService.getBillingProfile();
    }

    @PutMapping("/organizations/me/billing-profile")
    @PreAuthorize("hasRole('ADMIN')")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public BillingProfileResponse updateBillingProfile(@Valid @RequestBody BillingProfileUpdateRequest request) {
        return billingProfileService.updateBillingProfile(request);
    }

    /** Any entitled user can preview the currently-set logo (needed by the Settings page
     * preview, same visibility as the rest of the billing profile). 404 if none is set. */
    @GetMapping("/organizations/me/logo")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public ResponseEntity<byte[]> getLogo() {
        return billingProfileService.getLogoBytes()
                .map(logo -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, logo.contentType())
                        .body(logo.bytes()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping(value = "/organizations/me/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public BillingProfileResponse uploadLogo(@RequestPart("file") MultipartFile file) {
        return billingProfileService.uploadLogo(file);
    }

    @DeleteMapping("/organizations/me/logo")
    @PreAuthorize("hasRole('ADMIN')")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public BillingProfileResponse deleteLogo() {
        return billingProfileService.deleteLogo();
    }
}
