package com.salesmanager.crm.invoicing;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns invoice_number_counters' lock/increment logic in isolation, for testability. Keyed by
 * (organizationId, year) - see V12__invoicing.sql's comment for why this is a plain
 * SELECT...FOR UPDATE row lock inside the caller's existing transaction (TenantFilter already
 * wraps the whole request in one), NOT scheduler.AdvisoryLockRunner - that guards whole
 * scheduled-job executions across instances, not a per-row business-counter increment.
 *
 * <p>Must be called from within the SAME transaction as the rest of InvoiceService#create, so
 * the row lock is held for the invoice's entire creation (preventing two concurrent invoices
 * in the same org+year from ever allocating the same number), and released automatically at
 * that transaction's commit/rollback.
 */
@Service
public class InvoiceNumberService {

    private final EntityManager entityManager;

    public InvoiceNumberService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional
    public String allocateNext(UUID organizationId, LocalDate invoiceDate) {
        // The counter's "year" column holds the FINANCIAL year's start year (Apr-Mar), so the
        // series restarts on 1 April: INV/26-27/0001. Max 14 chars, under the GST 16-char limit.
        int year = FinancialYear.startYear(invoiceDate);
        entityManager.createNativeQuery(
                        "INSERT INTO invoice_number_counters (organization_id, year, next_number) "
                                + "VALUES (:orgId, :year, 1) ON CONFLICT (organization_id, year) DO NOTHING")
                .setParameter("orgId", organizationId)
                .setParameter("year", year)
                .executeUpdate();

        Number nextNumber = (Number) entityManager.createNativeQuery(
                        "SELECT next_number FROM invoice_number_counters "
                                + "WHERE organization_id = :orgId AND year = :year FOR UPDATE")
                .setParameter("orgId", organizationId)
                .setParameter("year", year)
                .getSingleResult();

        entityManager.createNativeQuery(
                        "UPDATE invoice_number_counters SET next_number = next_number + 1 "
                                + "WHERE organization_id = :orgId AND year = :year")
                .setParameter("orgId", organizationId)
                .setParameter("year", year)
                .executeUpdate();

        return String.format("INV/%s/%04d", FinancialYear.label(year), nextNumber.intValue());
    }
}
