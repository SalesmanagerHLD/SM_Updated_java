package com.salesmanager.crm.invoicing;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceLogoSnapshotRepository extends JpaRepository<InvoiceLogoSnapshot, UUID> {

    Optional<InvoiceLogoSnapshot> findByInvoiceId(UUID invoiceId);
}
