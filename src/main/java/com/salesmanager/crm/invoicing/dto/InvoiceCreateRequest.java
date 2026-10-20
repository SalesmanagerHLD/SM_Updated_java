package com.salesmanager.crm.invoicing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * customerName (and the rest of the customer_* fields) are always taken from this request as
 * plain values, even when leadId is also provided - the frontend pre-fills them from the
 * picked Lead's CURRENT data at pick-time, but the server never re-derives/re-joins them from
 * Lead itself. leadId is stored purely as an optional reference.
 */
public record InvoiceCreateRequest(
        UUID leadId,

        /** Optional Customer master link; also supplies the default place-of-supply state. */
        UUID customerId,

        /** master_data STATE row of the place of supply. Defaults to the customer's state, then to
         * the seller's own state. Decides CGST+SGST vs IGST - the tax type is never a user input. */
        UUID placeOfSupplyStateId,

        @NotBlank(message = "customerName is required")
        @Size(max = 255, message = "customerName must be at most 255 characters")
        String customerName,

        @Size(max = 255, message = "customerContactPerson must be at most 255 characters")
        String customerContactPerson,

        @Size(max = 20, message = "customerPhone must be at most 20 characters")
        String customerPhone,

        @Size(max = 255, message = "customerEmail must be at most 255 characters")
        String customerEmail,

        @Size(max = 1000, message = "customerAddress must be at most 1000 characters")
        String customerAddress,

        @Size(max = 20, message = "customerGstin must be at most 20 characters")
        @Pattern(regexp = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$", message = "customerGstin must be a valid 15-character GSTIN")
        String customerGstin,

        @Size(max = 255, message = "shipToName must be at most 255 characters")
        String shipToName,

        @Size(max = 1000, message = "shipToAddress must be at most 1000 characters")
        String shipToAddress,

        @Size(max = 20, message = "shipToGstin must be at most 20 characters")
        @Pattern(regexp = "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$", message = "shipToGstin must be a valid 15-character GSTIN")
        String shipToGstin,

        @NotNull(message = "invoiceDate is required")
        LocalDate invoiceDate,

        LocalDate dueDate,

        @Size(max = 255, message = "placeOfSupply must be at most 255 characters")
        String placeOfSupply,

        boolean reverseCharge,

        @NotEmpty(message = "lineItems must contain at least one item")
        @Valid
        List<InvoiceLineItemRequest> lineItems,

        @Size(max = 2000, message = "notes must be at most 2000 characters")
        String notes) {
}
