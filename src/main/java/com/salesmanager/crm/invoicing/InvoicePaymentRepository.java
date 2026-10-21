package com.salesmanager.crm.invoicing;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvoicePaymentRepository extends JpaRepository<InvoicePayment, UUID> {

    List<InvoicePayment> findByInvoiceIdOrderByPaymentDateAscCreatedAtAsc(UUID invoiceId);

    @Query("select coalesce(sum(p.amount), 0) from InvoicePayment p where p.invoiceId = :invoiceId")
    BigDecimal sumAmountByInvoiceId(@Param("invoiceId") UUID invoiceId);

    /** Batch form for list pages - avoids one query per invoice. */
    @Query("select p.invoiceId, sum(p.amount) from InvoicePayment p where p.invoiceId in :ids group by p.invoiceId")
    List<Object[]> sumAmountByInvoiceIds(@Param("ids") Collection<UUID> ids);
}
