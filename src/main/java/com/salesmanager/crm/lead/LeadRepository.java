package com.salesmanager.crm.lead;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Deliberately relies on the Hibernate {@code tenantFilter} (and Postgres RLS as
 * defense-in-depth) for organization scoping, same as EmployeeRepository/MasterDataRepository -
 * no manual "WHERE organizationId = ..." here. JpaSpecificationExecutor backs LeadService#list's
 * dynamic status/owner/interestLevel filter combinations (see LeadSpecifications) instead of a
 * combinatorial pile of derived query methods.
 */
public interface LeadRepository extends JpaRepository<Lead, UUID>, JpaSpecificationExecutor<Lead> {

    /**
     * Backs the duplicate-lead check (LeadService#checkDuplicates). A null argument is
     * translated by Spring Data's Criteria-based query derivation into an "IS NULL" predicate
     * for that side of the OR, so callers may pass either contactNo or companyName alone.
     */
    List<Lead> findByContactNoOrCompanyNameIgnoreCase(String contactNo, String companyName);

    /**
     * Backs VisitService's ownership-via-parent-lead visibility rule (an EMPLOYEE sees
     * Visits only for leads they own) and its personal "today's follow-ups" agenda - both
     * need the set of lead ids a given employee owns.
     */
    List<Lead> findByOwnerId(UUID ownerId);

    /**
     * Team-visibility (FeatureEntitlement.TEAM_VISIBILITY) counterpart to {@link #findByOwnerId} -
     * backs VisitService#list and ReportingService#visitsCompletedVsMissed's scoped variant when
     * a manager's visible owners are themself + every subordinate, not just a single id.
     */
    List<Lead> findByOwnerIdIn(Set<UUID> ownerIds);

    /**
     * Backs ReportingService#pipelineSummary/#conversionRate - a single GROUP BY query
     * (rather than one count() call per LeadStatus) so the whole byStatus breakdown is one
     * round trip. Statuses with zero leads simply produce no row here; ReportingService fills
     * those in as explicit zero-count buckets. This is JPQL (not native SQL) specifically so
     * the Hibernate {@code tenantFilter} - which only rewrites HQL/Criteria queries, not native
     * SQL - is guaranteed to apply, same org-scoping as every other query in this repository.
     */
    @Query("SELECT l.status AS status, COUNT(l) AS count FROM Lead l GROUP BY l.status")
    List<LeadStatusCount> countGroupedByStatus();

    /**
     * Backs the {@code byOwner} breakdown in ReportingService#pipelineSummary - one row per
     * distinct ownerId with at least one lead in the org, giving both their total lead count
     * and how many of those are CLOSED_WON. Also JPQL, not native SQL, for the same
     * tenantFilter-applicability reason as {@link #countGroupedByStatus()}.
     */
    @Query("SELECT l.ownerId AS ownerId, COUNT(l) AS leadCount, "
            + "SUM(CASE WHEN l.status = com.salesmanager.crm.lead.LeadStatus.CLOSED_WON THEN 1L ELSE 0L END) AS closedWonCount "
            + "FROM Lead l GROUP BY l.ownerId")
    List<LeadOwnerCount> countGroupedByOwner();

    /**
     * Team-visibility (FeatureEntitlement.TEAM_VISIBILITY) counterpart to
     * {@link #countGroupedByStatus()} - restricts the same status breakdown to a manager's
     * scope (themself + every subordinate) instead of the whole org, for ReportingService's
     * manager-scoped pipeline-summary/conversion-rate variants.
     */
    @Query("SELECT l.status AS status, COUNT(l) AS count FROM Lead l "
            + "WHERE l.ownerId IN :ownerIds GROUP BY l.status")
    List<LeadStatusCount> countGroupedByStatusForOwners(@Param("ownerIds") Set<UUID> ownerIds);

    /**
     * Backs ReportingService#teamProgress (Team Progress view) - per-team-member lead status
     * breakdown, i.e. {@link #countGroupedByStatusForOwners(Set)} but keyed by ownerId too
     * instead of collapsing every owner into one combined total.
     */
    @Query("SELECT l.ownerId AS ownerId, l.status AS status, COUNT(l) AS count FROM Lead l "
            + "WHERE l.ownerId IN :ownerIds GROUP BY l.ownerId, l.status")
    List<LeadOwnerStatusCount> countGroupedByOwnerAndStatusForOwners(@Param("ownerIds") Set<UUID> ownerIds);

    /**
     * Backs ReportingService#leadsBySource (Dashboard's "Leads by Source" chart) - same
     * GROUP BY-in-one-round-trip shape as {@link #countGroupedByStatus()}. leadSourceId is
     * nullable, and Lead's own javadoc already establishes it's a raw id into master_data, not
     * a JPA relation - so this returns raw ids/counts only; ReportingService resolves ids to
     * labels (and buckets the null-id row as "Other") since a label lookup needs
     * MasterDataRepository, not this one.
     */
    @Query("SELECT l.leadSourceId AS leadSourceId, COUNT(l) AS count FROM Lead l GROUP BY l.leadSourceId")
    List<LeadSourceCount> countGroupedByLeadSource();

    /** Team-visibility counterpart to {@link #countGroupedByLeadSource()}, same shape as
     * {@link #countGroupedByStatusForOwners(Set)}. */
    @Query("SELECT l.leadSourceId AS leadSourceId, COUNT(l) AS count FROM Lead l "
            + "WHERE l.ownerId IN :ownerIds GROUP BY l.leadSourceId")
    List<LeadSourceCount> countGroupedByLeadSourceForOwners(@Param("ownerIds") Set<UUID> ownerIds);

    /**
     * Backs ReportingService#interestLevelStatusMatrix (Reports' "Interest Level x Status"
     * matrix, e.g. "how many Hot leads are stuck in Contacted") - one GROUP BY across both axes
     * in a single round trip, same shape as {@link #countGroupedByStatus()}. interestLevelId is
     * nullable; ReportingService resolves ids to labels and buckets null into "Not Set".
     */
    @Query("SELECT l.interestLevelId AS interestLevelId, l.status AS status, COUNT(l) AS count FROM Lead l "
            + "GROUP BY l.interestLevelId, l.status")
    List<LeadInterestStatusCount> countGroupedByInterestLevelAndStatus();

    /** Team-visibility counterpart to {@link #countGroupedByInterestLevelAndStatus()}, same
     * shape as {@link #countGroupedByStatusForOwners(Set)}. */
    @Query("SELECT l.interestLevelId AS interestLevelId, l.status AS status, COUNT(l) AS count FROM Lead l "
            + "WHERE l.ownerId IN :ownerIds GROUP BY l.interestLevelId, l.status")
    List<LeadInterestStatusCount> countGroupedByInterestLevelAndStatusForOwners(@Param("ownerIds") Set<UUID> ownerIds);

    /** Leads per (interest level, reason), created within [from, to). */
    @Query("SELECT l.interestLevelId AS interestLevelId, r AS reasonId, COUNT(l) AS count FROM Lead l "
            + "JOIN l.interestReasonIds r WHERE l.createdAt >= :from AND l.createdAt < :to "
            + "GROUP BY l.interestLevelId, r")
    List<LeadInterestReasonCount> countReasons(@Param("from") java.time.OffsetDateTime from,
                                               @Param("to") java.time.OffsetDateTime to);

    /** Team-visibility counterpart to {@link #countReasons}. */
    @Query("SELECT l.interestLevelId AS interestLevelId, r AS reasonId, COUNT(l) AS count FROM Lead l "
            + "JOIN l.interestReasonIds r WHERE l.ownerId IN :ownerIds "
            + "AND l.createdAt >= :from AND l.createdAt < :to GROUP BY l.interestLevelId, r")
    List<LeadInterestReasonCount> countReasonsForOwners(@Param("ownerIds") Set<UUID> ownerIds,
                                                        @Param("from") java.time.OffsetDateTime from,
                                                        @Param("to") java.time.OffsetDateTime to);

    /** Distinct leads that have at least one interest reason, within [from, to). */
    @Query("SELECT COUNT(DISTINCT l.id) FROM Lead l JOIN l.interestReasonIds r "
            + "WHERE l.createdAt >= :from AND l.createdAt < :to")
    long countLeadsWithReasons(@Param("from") java.time.OffsetDateTime from,
                               @Param("to") java.time.OffsetDateTime to);

    @Query("SELECT COUNT(DISTINCT l.id) FROM Lead l JOIN l.interestReasonIds r "
            + "WHERE l.ownerId IN :ownerIds AND l.createdAt >= :from AND l.createdAt < :to")
    long countLeadsWithReasonsForOwners(@Param("ownerIds") Set<UUID> ownerIds,
                                        @Param("from") java.time.OffsetDateTime from,
                                        @Param("to") java.time.OffsetDateTime to);
}
