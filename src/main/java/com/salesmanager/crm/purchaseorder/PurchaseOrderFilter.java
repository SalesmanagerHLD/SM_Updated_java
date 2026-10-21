package com.salesmanager.crm.purchaseorder;

import java.time.LocalDate;
import java.util.UUID;

public record PurchaseOrderFilter(UUID leadId, PurchaseOrderStatus status, UUID customerId, UUID ownerId,
                                   LocalDate dateFrom, LocalDate dateTo, String search) {
}
