package com.salesmanager.crm.lead;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Builds dynamic WHERE clauses for GET /leads' optional status/ownerId/interestLevelId
 * filters, composed via Specification.where(...).and(...) in LeadService#list rather than a
 * combinatorial pile of derived query methods (findByStatus, findByOwnerId,
 * findByStatusAndOwnerId, ...). Each method returns null for a null filter value, which
 * Specification.and(...) treats as "no additional restriction" (a standard Spring Data
 * Specification idiom).
 */
final class LeadSpecifications {

    private LeadSpecifications() {
    }

    static Specification<Lead> hasStatus(LeadStatus status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    static Specification<Lead> hasOwner(UUID ownerId) {
        return (root, query, cb) -> ownerId == null ? null : cb.equal(root.get("ownerId"), ownerId);
    }

    /** Team-visibility scoping (FeatureEntitlement.TEAM_VISIBILITY) - owner in a manager's scope. */
    static Specification<Lead> hasOwnerIn(Set<UUID> ownerIds) {
        return (root, query, cb) -> ownerIds == null ? null : root.get("ownerId").in(ownerIds);
    }

    static Specification<Lead> hasInterestLevel(UUID interestLevelId) {
        return (root, query, cb) ->
                interestLevelId == null ? null : cb.equal(root.get("interestLevelId"), interestLevelId);
    }

    static Specification<Lead> hasState(UUID stateId) {
        return (root, query, cb) -> stateId == null ? null : cb.equal(root.get("stateId"), stateId);
    }

    static Specification<Lead> hasCity(UUID cityId) {
        return (root, query, cb) -> cityId == null ? null : cb.equal(root.get("cityId"), cityId);
    }

    /** productIds is a Lead-owned @ElementCollection (lead_products join table) - cb.isMember
     * is the standard JPA Criteria idiom for "this element-collection contains X", equivalent
     * to an EXISTS-against-the-join-table subquery. */
    static Specification<Lead> hasProduct(UUID productId) {
        return (root, query, cb) ->
                productId == null ? null : cb.isMember(productId, root.get("productIds"));
    }

    /** Leads that carry the given INTEREST_REASON (lead_interest_reasons element collection). */
    static Specification<Lead> hasInterestReason(UUID reasonId) {
        return (root, query, cb) ->
                reasonId == null ? null : cb.isMember(reasonId, root.get("interestReasonIds"));
    }

    /** Backs the Reports Dashboard's global filter bar (section 17.5) - not used by GET /leads
     * itself. */
    static Specification<Lead> hasBusinessType(UUID businessTypeId) {
        return (root, query, cb) ->
                businessTypeId == null ? null : cb.equal(root.get("businessTypeId"), businessTypeId);
    }

    static Specification<Lead> hasNextFollowupDate(LocalDate date) {
        return (root, query, cb) -> date == null ? null : cb.equal(root.get("nextFollowupDate"), date);
    }

    static Specification<Lead> hasExpectedCloseDate(LocalDate date) {
        return (root, query, cb) -> date == null ? null : cb.equal(root.get("expectedCloseDate"), date);
    }

    /** Filters on createdAt (an OffsetDateTime), given inclusive LocalDate bounds - dateTo is
     * treated as through-end-of-that-day (a strict "less than the start of the following day"
     * upper bound), not just midnight, so a lead created any time ON dateTo is included. Both
     * bounds are independently optional, same "either side may be null" convention as
     * VisitRepository's COALESCE-based date-range queries elsewhere in this codebase. */
    static Specification<Lead> createdBetween(LocalDate dateFrom, LocalDate dateTo) {
        return (root, query, cb) -> {
            Predicate fromPredicate = dateFrom == null ? null
                    : cb.greaterThanOrEqualTo(root.get("createdAt"), dateFrom.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime());
            Predicate toPredicate = dateTo == null ? null
                    : cb.lessThan(root.get("createdAt"), dateTo.plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime());
            if (fromPredicate == null) {
                return toPredicate;
            }
            if (toPredicate == null) {
                return fromPredicate;
            }
            return cb.and(fromPredicate, toPredicate);
        };
    }

    /** Case-insensitive substring match across companyName/contactPerson/contactNo/email - for
     * the "find an existing lead" picker (see LeadFilter#search's javadoc). email is nullable,
     * so its LIKE predicate is guarded with an IS NOT NULL check first (Postgres LOWER(NULL)
     * evaluates to NULL, which a LIKE comparison never matches, but being explicit here avoids
     * relying on that implicitly). */
    static Specification<Lead> matchesSearch(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        String likePattern = "%" + term.trim().toLowerCase() + "%";
        return (root, query, cb) -> {
            Predicate companyMatch = cb.like(cb.lower(root.get("companyName")), likePattern);
            Predicate contactMatch = cb.like(cb.lower(root.get("contactPerson")), likePattern);
            Predicate phoneMatch = cb.like(cb.lower(root.get("contactNo")), likePattern);
            Predicate emailMatch = cb.and(
                    cb.isNotNull(root.get("email")),
                    cb.like(cb.lower(root.get("email")), likePattern));
            return cb.or(companyMatch, contactMatch, phoneMatch, emailMatch);
        };
    }
}
