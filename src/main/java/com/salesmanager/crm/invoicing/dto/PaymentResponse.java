package com.salesmanager.crm.invoicing.dto;

import com.salesmanager.crm.invoicing.InvoicePayment;
import com.salesmanager.crm.invoicing.PaymentMode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID invoiceId,
        BigDecimal amount,
        PaymentMode mode,
        LocalDate paymentDate,
        String reference,
        String notes,
        UUID recordedBy,
        OffsetDateTime createdAt) {

    public static PaymentResponse from(InvoicePayment p) {
        return new PaymentResponse(p.getId(), p.getInvoiceId(), p.getAmount(), p.getMode(), p.getPaymentDate(),
                p.getReference(), p.getNotes(), p.getRecordedBy(), p.getCreatedAt());
    }
}
