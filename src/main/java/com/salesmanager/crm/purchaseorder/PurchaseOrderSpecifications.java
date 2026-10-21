package com.salesmanager.crm.purchaseorder;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

final class PurchaseOrderSpecifications {

    private PurchaseOrderSpecifications() {
    }

    static Specification<PurchaseOrder> hasLead(UUID leadId) {
        return (root, query, cb) -> leadId == null ? null : cb.equal(root.get("leadId"), leadId);
    }

    static Specification<PurchaseOrder> hasStatus(PurchaseOrderStatus status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    static Specification<PurchaseOrder> hasCustomer(UUID customerId) {
        return (root, query, cb) -> customerId == null ? null : cb.equal(root.get("customerId"), customerId);
    }

    static Specification<PurchaseOrder> hasOwner(UUID ownerId) {
        return (root, query, cb) -> ownerId == null ? null : cb.equal(root.get("ownerId"), ownerId);
    }

    static Specification<PurchaseOrder> hasOwnerIn(Set<UUID> ownerIds) {
        return (root, query, cb) -> ownerIds == null ? null : root.get("ownerId").in(ownerIds);
    }

    static Specification<PurchaseOrder> poDateBetween(LocalDate from, LocalDate to) {
        return (root, query, cb) -> {
            Predicate fromP = from == null ? null : cb.greaterThanOrEqualTo(root.get("poDate"), from);
            Predicate toP = to == null ? null : cb.lessThanOrEqualTo(root.get("poDate"), to);
            if (fromP == null) return toP;
            if (toP == null) return fromP;
            return cb.and(fromP, toP);
        };
    }

    static Specification<PurchaseOrder> matchesSearch(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        String like = "%" + term.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("customerName")), like),
                cb.like(cb.lower(root.get("poNumber")), like));
    }
}
