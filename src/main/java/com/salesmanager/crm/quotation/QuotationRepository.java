package com.salesmanager.crm.quotation;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface QuotationRepository extends JpaRepository<Quotation, UUID>, JpaSpecificationExecutor<Quotation> {

    /** Row-locks the quotation for the rest of the (request-wide) transaction so two concurrent
     * conversions serialize: the second sees CONVERTED and is rejected instead of creating a
     * second invoice and deducting stock twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from Quotation q where q.id = :id")
    Optional<Quotation> findByIdForUpdate(@Param("id") UUID id);

    long countByStatus(QuotationStatus status);

    /** Backs ReportingService#quotationInvoiceSummary's "Total Quotations"/"Approved
     * Quotations"/"Converted to Invoice" stat cards. */
    long countByQuotationDateBetween(LocalDate dateFrom, LocalDate dateTo);

    long countByOwnerIdInAndQuotationDateBetween(Set<UUID> ownerIds, LocalDate dateFrom, LocalDate dateTo);

    long countByStatusAndQuotationDateBetween(QuotationStatus status, LocalDate dateFrom, LocalDate dateTo);

    long countByOwnerIdInAndStatusAndQuotationDateBetween(Set<UUID> ownerIds, QuotationStatus status,
                                                           LocalDate dateFrom, LocalDate dateTo);
}
