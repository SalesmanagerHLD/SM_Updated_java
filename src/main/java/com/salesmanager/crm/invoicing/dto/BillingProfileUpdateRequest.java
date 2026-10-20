package com.salesmanager.crm.invoicing.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** All fields optional/nullable - GSTIN is format-checked, everything else is free text. businessName is not editable here - it's Organization#name, set at registration.
 * The logo itself is uploaded separately via PUT /organizations/me/logo (multipart), not here. */
public record BillingProfileUpdateRequest(
        @Size(max = 500, message = "billingAddress must be at most 500 characters")
        String billingAddress,

        @Size(max = 20, message = "billingGstin must be at most 20 characters")
        @Pattern(regexp = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$", message = "billingGstin must be a valid 15-character GSTIN")
        String billingGstin,

        /** master_data STATE row - the seller's state, needed to decide CGST+SGST vs IGST. */
        UUID billingStateId,

        @Size(max = 50, message = "billingPhone must be at most 50 characters")
        String billingPhone,

        @Size(max = 1000, message = "invoiceHeaderText must be at most 1000 characters")
        String invoiceHeaderText,

        @Size(max = 1000, message = "invoiceFooterText must be at most 1000 characters")
        String invoiceFooterText,

        @Size(max = 255, message = "bankName must be at most 255 characters")
        String bankName,

        @Size(max = 50, message = "bankAccountNumber must be at most 50 characters")
        String bankAccountNumber,

        @Size(max = 20, message = "bankIfsc must be at most 20 characters")
        String bankIfsc,

        @Size(max = 255, message = "bankBranch must be at most 255 characters")
        String bankBranch,

        @Size(max = 100, message = "upiId must be at most 100 characters")
        String upiId) {
}
