package com.salesmanager.crm.invoicing.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Exactly one of {@code productId} (a real catalog line - description/unitPrice/taxRatePercent
 * are ignored and derived from the Product instead) or {@code description}+{@code unitPrice}
 * (an ad-hoc line not in the catalog) must be set - enforced in InvoiceService, not here
 * (Bean Validation can't express "exactly one of" across fields cleanly).
 */
public record InvoiceLineItemRequest(
        UUID productId,

        @Size(max = 20, message = "hsnSac must be at most 20 characters")
        String hsnSac,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        @NotNull(message = "quantity is required")
        @DecimalMin(value = "0.01", message = "quantity must be greater than zero")
        BigDecimal quantity,

        @DecimalMin(value = "0.0", message = "unitPrice must not be negative")
        BigDecimal unitPrice,

        @DecimalMin(value = "0.0", message = "discountPercent must not be negative")
        @DecimalMax(value = "100.0", message = "discountPercent must not exceed 100")
        BigDecimal discountPercent,

        @DecimalMin(value = "0.0", message = "taxRatePercent must not be negative")
        BigDecimal taxRatePercent,

        @Size(max = 20, message = "unit must be at most 20 characters")
        String unit,

        /** On a PO invoice: the PO line being billed. Price, tax, HSN and description then come
         * from the PO line; only quantity is taken from this request. */
        UUID poLineId) {
}
