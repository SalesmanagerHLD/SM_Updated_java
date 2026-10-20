package com.salesmanager.crm.invoicing.dto;

import com.salesmanager.crm.invoicing.PaymentMode;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record PaymentRequest(
        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be greater than zero")
        BigDecimal amount,

        @NotNull(message = "mode is required")
        PaymentMode mode,

        /** Defaults to today (billing time zone) when omitted. */
        LocalDate paymentDate,

        @Size(max = 100, message = "reference must be at most 100 characters")
        String reference,

        @Size(max = 500, message = "notes must be at most 500 characters")
        String notes) {
}
