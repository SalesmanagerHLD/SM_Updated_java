package com.salesmanager.crm.invoicing;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Deliberately relies on the Hibernate {@code tenantFilter} (and Postgres RLS as
 * defense-in-depth) for organization scoping, same as every other repository.
 */
public interface InvoiceLineItemRepository extends JpaRepository<InvoiceLineItem, UUID> {

    List<InvoiceLineItem> findByInvoiceIdOrderBySortOrderAsc(UUID invoiceId);

    /** Batch form for list pages (one query instead of one per invoice). */
    List<InvoiceLineItem> findByInvoiceIdInOrderBySortOrderAsc(Collection<UUID> invoiceIds);
}
