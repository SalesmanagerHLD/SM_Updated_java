package com.salesmanager.crm.invoicing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Deliberately relies on the Hibernate {@code tenantFilter} (and Postgres RLS as
 * defense-in-depth) for organization scoping, same as every other repository - no manual
 * "WHERE organizationId = ..." here. JpaSpecificationExecutor backs InvoiceService#list's
 * dynamic owner/status filter combinations (see InvoiceSpecifications), same shape as
 * LeadRepository.
 */
public interface InvoiceRepository extends JpaRepository<Invoice, UUID>, JpaSpecificationExecutor<Invoice> {

    boolean existsByInvoiceNumber(String invoiceNumber);

    java.util.List<Invoice> findByPoIdOrderByCreatedAtDesc(UUID poId);

    boolean existsByPoId(UUID poId);

    boolean existsByPoIdAndStatusNot(UUID poId, InvoiceStatus status);

    /** Row-locks the invoice for the rest of the request-wide transaction - used by payment
     * recording and cancellation so two concurrent requests can't overpay or double-cancel. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Invoice i WHERE i.id = :id")
    Optional<Invoice> findByIdForUpdate(@Param("id") UUID id);

    /** Backs ReportingService#revenue (Dashboard's "Revenue (YTD)" stat) - COALESCE guards the
     * no-invoices-yet case, since SUM() over zero rows is SQL NULL, not zero. */
    @Query("SELECT COALESCE(SUM(i.grandTotal), 0) FROM Invoice i "
            + "WHERE i.invoiceDate >= :dateFrom AND i.invoiceDate <= :dateTo "
            + "AND i.status <> com.salesmanager.crm.invoicing.InvoiceStatus.CANCELLED")
    BigDecimal sumGrandTotalBetween(@Param("dateFrom") LocalDate dateFrom, @Param("dateTo") LocalDate dateTo);

    /** Team-visibility counterpart to {@link #sumGrandTotalBetween(LocalDate, LocalDate)}. */
    @Query("SELECT COALESCE(SUM(i.grandTotal), 0) FROM Invoice i "
            + "WHERE i.ownerId IN :ownerIds AND i.invoiceDate >= :dateFrom AND i.invoiceDate <= :dateTo "
            + "AND i.status <> com.salesmanager.crm.invoicing.InvoiceStatus.CANCELLED")
    BigDecimal sumGrandTotalForOwnersBetween(@Param("ownerIds") Set<UUID> ownerIds,
            @Param("dateFrom") LocalDate dateFrom, @Param("dateTo") LocalDate dateTo);

    // --- Everything below backs ReportingService#quotationInvoiceSummary's stat cards. ---

    long countByStatusInAndInvoiceDateBetween(Collection<InvoiceStatus> statuses, LocalDate dateFrom, LocalDate dateTo);

    long countByOwnerIdInAndStatusInAndInvoiceDateBetween(Set<UUID> ownerIds, Collection<InvoiceStatus> statuses,
                                                          LocalDate dateFrom, LocalDate dateTo);

    @Query("SELECT COALESCE(SUM(i.grandTotal - COALESCE((SELECT SUM(p.amount) FROM InvoicePayment p WHERE p.invoiceId = i.id), 0)), 0) FROM Invoice i "
            + "WHERE i.status IN :statuses AND i.invoiceDate >= :dateFrom AND i.invoiceDate <= :dateTo")
    BigDecimal sumBalanceDueByStatusBetween(@Param("statuses") Collection<InvoiceStatus> statuses,
            @Param("dateFrom") LocalDate dateFrom, @Param("dateTo") LocalDate dateTo);

    @Query("SELECT COALESCE(SUM(i.grandTotal - COALESCE((SELECT SUM(p.amount) FROM InvoicePayment p WHERE p.invoiceId = i.id), 0)), 0) FROM Invoice i "
            + "WHERE i.ownerId IN :ownerIds AND i.status IN :statuses "
            + "AND i.invoiceDate >= :dateFrom AND i.invoiceDate <= :dateTo")
    BigDecimal sumBalanceDueByStatusForOwnersBetween(@Param("ownerIds") Set<UUID> ownerIds,
            @Param("statuses") Collection<InvoiceStatus> statuses, @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo);

    /** "Pending Payments" = unpaid AND already past its due date (overdue), all-time - distinct
     * from "Pending Invoices" above (unpaid, created within the selected period regardless of
     * due date). */
    long countByStatusInAndDueDateLessThan(Collection<InvoiceStatus> statuses, LocalDate date);

    long countByOwnerIdInAndStatusInAndDueDateLessThan(Set<UUID> ownerIds, Collection<InvoiceStatus> statuses, LocalDate date);

    @Query("SELECT COALESCE(SUM(i.grandTotal - COALESCE((SELECT SUM(p.amount) FROM InvoicePayment p WHERE p.invoiceId = i.id), 0)), 0) FROM Invoice i WHERE i.status IN :statuses AND i.dueDate < :date")
    BigDecimal sumBalanceDueByStatusAndDueDateBefore(@Param("statuses") Collection<InvoiceStatus> statuses,
            @Param("date") LocalDate date);

    @Query("SELECT COALESCE(SUM(i.grandTotal - COALESCE((SELECT SUM(p.amount) FROM InvoicePayment p WHERE p.invoiceId = i.id), 0)), 0) FROM Invoice i "
            + "WHERE i.ownerId IN :ownerIds AND i.status IN :statuses AND i.dueDate < :date")
    BigDecimal sumBalanceDueByStatusAndDueDateBeforeForOwners(@Param("ownerIds") Set<UUID> ownerIds,
            @Param("statuses") Collection<InvoiceStatus> statuses, @Param("date") LocalDate date);

    /** "Outstanding" = every unpaid invoice ever, not scoped to a period - total receivable. */
    @Query("SELECT COALESCE(SUM(i.grandTotal - COALESCE((SELECT SUM(p.amount) FROM InvoicePayment p WHERE p.invoiceId = i.id), 0)), 0) FROM Invoice i WHERE i.status IN :statuses")
    BigDecimal sumBalanceDueByStatus(@Param("statuses") Collection<InvoiceStatus> statuses);

    @Query("SELECT COALESCE(SUM(i.grandTotal - COALESCE((SELECT SUM(p.amount) FROM InvoicePayment p WHERE p.invoiceId = i.id), 0)), 0) FROM Invoice i WHERE i.ownerId IN :ownerIds AND i.status IN :statuses")
    BigDecimal sumBalanceDueByStatusForOwners(@Param("ownerIds") Set<UUID> ownerIds,
            @Param("statuses") Collection<InvoiceStatus> statuses);
}
