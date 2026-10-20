package com.salesmanager.crm.purchaseorder;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLine, UUID> {

    List<PurchaseOrderLine> findByPoIdOrderBySortOrderAsc(UUID poId);

    List<PurchaseOrderLine> findByPoIdIn(Collection<UUID> poIds);

    /** Lines are only locked after their PO row is, so the lock order is always PO then lines. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from PurchaseOrderLine l where l.poId = :poId order by l.sortOrder asc")
    List<PurchaseOrderLine> findByPoIdForUpdate(@Param("poId") UUID poId);
}
