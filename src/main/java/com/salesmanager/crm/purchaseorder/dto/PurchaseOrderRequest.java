package com.salesmanager.crm.purchaseorder.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Used for create and update. On update {@code leadId} must match the PO's lead, and
 * {@code lineItems} may be omitted to change the header only (the only option once the PO has
 * been invoiced). Customer fields default from the Customer master when {@code customerId} is set.
 */
public record PurchaseOrderRequest(
        @NotNull(message = "leadId is required")
        UUID leadId,

        UUID quotationId,

        UUID customerId,

        @NotBlank(message = "poNumber is required")
        @Size(max = 100, message = "poNumber must be at most 100 characters")
        String poNumber,

        @NotNull(message = "poDate is required")
        LocalDate poDate,

        LocalDate deliveryDate,

        @Min(value = 0, message = "paymentTermsDays must not be negative")
        @Max(value = 365, message = "paymentTermsDays must be at most 365")
        Integer paymentTermsDays,

        @Size(max = 255, message = "customerName must be at most 255 characters")
        String customerName,

        @Size(max = 20, message = "customerGstin must be at most 20 characters")
        @Pattern(regexp = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$", message = "customerGstin must be a valid 15-character GSTIN")
        String customerGstin,

        @Size(max = 1000, message = "billingAddress must be at most 1000 characters")
        String billingAddress,

        @Size(max = 1000, message = "shippingAddress must be at most 1000 characters")
        String shippingAddress,

        /** Decides CGST+SGST vs IGST. Defaults to the customer's state, then the seller's own. */
        UUID placeOfSupplyStateId,

        @Size(max = 2000, message = "notes must be at most 2000 characters")
        String notes,

        @Valid
        List<PurchaseOrderLineRequest> lineItems) {
}
