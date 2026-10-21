package com.salesmanager.crm.purchaseorder;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseOrderRepository
        extends JpaRepository<PurchaseOrder, UUID>, JpaSpecificationExecutor<PurchaseOrder> {

    /** Serializes concurrent invoicing / cancelling of the same PO so quantities can't be double-billed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PurchaseOrder p where p.id = :id")
    Optional<PurchaseOrder> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByLeadIdAndPoNumber(UUID leadId, String poNumber);

    boolean existsByLeadIdAndPoNumberAndIdNot(UUID leadId, String poNumber, UUID id);
}
