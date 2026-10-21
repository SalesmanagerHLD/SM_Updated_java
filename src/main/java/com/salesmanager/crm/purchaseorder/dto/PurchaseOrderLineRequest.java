package com.salesmanager.crm.purchaseorder.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record PurchaseOrderLineRequest(
        /** Optional catalog link; invoicing a linked line deducts that product's stock. */
        UUID productId,

        /** Optional: the quotation line this came from, for the price-difference flag. */
        UUID quotationLineId,

        @NotBlank(message = "description is required")
        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        @Size(max = 20, message = "hsnSac must be at most 20 characters")
        String hsnSac,

        @Size(max = 50, message = "unit must be at most 50 characters")
        String unit,

        @NotNull(message = "quantity is required")
        @DecimalMin(value = "0.01", message = "quantity must be greater than zero")
        BigDecimal quantity,

        @NotNull(message = "rate is required")
        @DecimalMin(value = "0.0", message = "rate must not be negative")
        BigDecimal rate,

        @NotNull(message = "taxRatePercent is required")
        @DecimalMin(value = "0.0", message = "taxRatePercent must not be negative")
        BigDecimal taxRatePercent) {
}
