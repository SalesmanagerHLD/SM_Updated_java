package com.salesmanager.crm.invoicing.design;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped by the Hibernate tenantFilter (and RLS), like every other repository. */
public interface InvoiceDesignRepository extends JpaRepository<InvoiceDesign, UUID> {

    Optional<InvoiceDesign> findByDocumentType(DocumentType documentType);
}
