package com.salesmanager.crm.reporting;

import com.salesmanager.crm.activity.ActivityLogRepository;
import com.salesmanager.crm.activity.OwnerLastActivity;
import com.salesmanager.crm.employee.Employee;
import com.salesmanager.crm.employee.EmployeeHierarchyService;
import com.salesmanager.crm.employee.EmployeeRepository;
import com.salesmanager.crm.employee.Role;
import com.salesmanager.crm.entitlement.EntitlementService;
import com.salesmanager.crm.entitlement.FeatureEntitlement;
import com.salesmanager.crm.invoicing.InvoiceRepository;
import com.salesmanager.crm.invoicing.InvoiceStatus;
import com.salesmanager.crm.lead.Lead;
import com.salesmanager.crm.lead.LeadDashboardFilter;
import com.salesmanager.crm.lead.LeadInterestStatusCount;
import com.salesmanager.crm.lead.LeadOwnerCount;
import com.salesmanager.crm.lead.LeadOwnerStatusCount;
import com.salesmanager.crm.lead.LeadRepository;
import com.salesmanager.crm.lead.LeadService;
import com.salesmanager.crm.lead.LeadSourceCount;
import com.salesmanager.crm.lead.LeadStatus;
import com.salesmanager.crm.lead.LeadStatusCount;
import com.salesmanager.crm.masterdata.MasterData;
import com.salesmanager.crm.masterdata.MasterDataRepository;
import com.salesmanager.crm.masterdata.MasterType;
import com.salesmanager.crm.quotation.QuotationRepository;
import com.salesmanager.crm.quotation.QuotationStatus;
import com.salesmanager.crm.reporting.dto.ConversionRateResponse;
import com.salesmanager.crm.lead.LeadInterestReasonCount;
import com.salesmanager.crm.reporting.dto.InterestLevelStatusMatrixResponse;
import com.salesmanager.crm.reporting.dto.InterestReasonReportResponse;
import com.salesmanager.crm.reporting.dto.InterestReasonRow;
import com.salesmanager.crm.reporting.dto.InterestLevelStatusRow;
import com.salesmanager.crm.reporting.dto.LeadDashboardResponse;
import com.salesmanager.crm.reporting.dto.LeadSourceBreakdown;
import com.salesmanager.crm.reporting.dto.LeadsBySourceResponse;
import com.salesmanager.crm.reporting.dto.OwnerBreakdown;
import com.salesmanager.crm.reporting.dto.PipelineSummaryResponse;
import com.salesmanager.crm.reporting.dto.QuotationInvoiceSummaryResponse;
import com.salesmanager.crm.reporting.dto.RevenueResponse;
import com.salesmanager.crm.reporting.dto.TeamMemberProgress;
import com.salesmanager.crm.reporting.dto.TeamProgressResponse;
import com.salesmanager.crm.reporting.dto.VisitsByTypeResponse;
import com.salesmanager.crm.reporting.dto.VisitsCompletedVsMissedResponse;
import com.salesmanager.crm.security.CurrentUser;
import com.salesmanager.crm.security.UserPrincipal;
import com.salesmanager.crm.visit.Visit;
import com.salesmanager.crm.visit.VisitRepository;
import com.salesmanager.crm.visit.VisitStatus;
import com.salesmanager.crm.visit.VisitStatusCount;
import com.salesmanager.crm.visit.VisitType;
import com.salesmanager.crm.visit.VisitTypeCount;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only aggregate queries for the Phase 5 reporting/dashboard endpoints. Every query here
 * goes through LeadRepository/VisitRepository/EmployeeRepository's normal EntityManager, so the
 * Hibernate {@code tenantFilter} that's already active for the current request (see
 * TenantSessionManager) scopes these aggregates to the current org exactly like every other
 * read in the codebase - no manual "WHERE organizationId = ..." here either.
 *
 * <p>ADMIN always gets the unrestricted, org-wide picture. An EMPLOYEE only reaches these
 * methods at all when TEAM_VISIBILITY is entitled AND they have at least one subordinate (see
 * {@link #resolveOwnerScope()}) - a plain individual contributor still gets a 403, same as
 * before this feature existed. That manager's aggregates are then scoped to themself + their
 * whole subordinate chain, never the full org.
 */
@Service
public class ReportingService {

    private final LeadRepository leadRepository;
    private final LeadService leadService;
    private final VisitRepository visitRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeHierarchyService employeeHierarchyService;
    private final MasterDataRepository masterDataRepository;
    private final InvoiceRepository invoiceRepository;
    private final QuotationRepository quotationRepository;
    private final ActivityLogRepository activityLogRepository;
    private final EntitlementService entitlementService;
    private final CurrentUser currentUser;

    public ReportingService(LeadRepository leadRepository, LeadService leadService,
                             VisitRepository visitRepository,
                             EmployeeRepository employeeRepository,
                             EmployeeHierarchyService employeeHierarchyService,
                             MasterDataRepository masterDataRepository,
                             InvoiceRepository invoiceRepository,
                             QuotationRepository quotationRepository,
                             ActivityLogRepository activityLogRepository,
                             EntitlementService entitlementService,
                             CurrentUser currentUser) {
        this.leadRepository = leadRepository;
        this.leadService = leadService;
        this.visitRepository = visitRepository;
        this.employeeRepository = employeeRepository;
        this.employeeHierarchyService = employeeHierarchyService;
        this.masterDataRepository = masterDataRepository;
        this.invoiceRepository = invoiceRepository;
        this.quotationRepository = quotationRepository;
        this.activityLogRepository = activityLogRepository;
        this.entitlementService = entitlementService;
        this.currentUser = currentUser;
    }

    /**
     * byStatus always contains every LeadStatus value (pre-seeded to zero below, then
     * overwritten for statuses that actually have leads) so the frontend gets a consistent set
     * of categories to render regardless of which ones happen to be empty in this org.
     */
    @Transactional(readOnly = true, noRollbackFor = AccessDeniedException.class)
    public PipelineSummaryResponse pipelineSummary() {
        Set<UUID> ownerScope = resolveOwnerScope();
        Map<LeadStatus, Long> byStatus = new EnumMap<>(LeadStatus.class);
        for (LeadStatus status : LeadStatus.values()) {
            byStatus.put(status, 0L);
        }
        long totalLeads = 0;
        List<LeadStatusCount> statusRows = ownerScope == null
                ? leadRepository.countGroupedByStatus()
                : leadRepository.countGroupedByStatusForOwners(ownerScope);
        for (LeadStatusCount row : statusRows) {
            byStatus.put(row.getStatus(), row.getCount());
            totalLeads += row.getCount();
        }

        List<LeadOwnerCount> ownerCounts = leadRepository.countGroupedByOwner().stream()
                .filter(row -> ownerScope == null || ownerScope.contains(row.getOwnerId()))
                .toList();
        Set<UUID> ownerIds = ownerCounts.stream().map(LeadOwnerCount::getOwnerId).collect(Collectors.toSet());
        Map<UUID, String> ownerNamesById = employeeRepository.findAllById(ownerIds).stream()
                .collect(Collectors.toMap(Employee::getId, Employee::getFullName));

        List<OwnerBreakdown> byOwner = ownerCounts.stream()
                .map(row -> new OwnerBreakdown(
                        row.getOwnerId(),
                        ownerNamesById.getOrDefault(row.getOwnerId(), "Unknown"),
                        row.getLeadCount(),
                        row.getClosedWonCount()))
                .toList();

        return new PipelineSummaryResponse(byStatus, totalLeads, byOwner);
    }

    /**
     * Reuses the same status-count aggregate as pipelineSummary() rather than a raw SQL
     * division, so the totalLeads==0 case and the 2-decimal rounding are both explicit,
     * ordinary Java rather than baked into a query.
     */
    @Transactional(readOnly = true, noRollbackFor = AccessDeniedException.class)
    public ConversionRateResponse conversionRate() {
        Set<UUID> ownerScope = resolveOwnerScope();
        long totalLeads = 0;
        long closedWonCount = 0;
        long lostCount = 0;
        List<LeadStatusCount> statusRows = ownerScope == null
                ? leadRepository.countGroupedByStatus()
                : leadRepository.countGroupedByStatusForOwners(ownerScope);
        for (LeadStatusCount row : statusRows) {
            totalLeads += row.getCount();
            if (row.getStatus() == LeadStatus.CLOSED_WON) {
                closedWonCount = row.getCount();
            } else if (row.getStatus() == LeadStatus.LOST) {
                lostCount = row.getCount();
            }
        }

        double conversionRatePercent = totalLeads == 0
                ? 0.0
                : roundToTwoDecimals(closedWonCount * 100.0 / totalLeads);

        return new ConversionRateResponse(totalLeads, closedWonCount, lostCount, conversionRatePercent);
    }

    /**
     * dateFrom/dateTo are both optional and independently nullable (VisitRepository's query
     * handles a null bound as "no restriction on that side"). completionRatePercent's
     * denominator is completed+missed only - PLANNED visits are excluded since they haven't
     * resolved to either outcome yet.
     */
    @Transactional(readOnly = true, noRollbackFor = AccessDeniedException.class)
    public VisitsCompletedVsMissedResponse visitsCompletedVsMissed(LocalDate dateFrom, LocalDate dateTo) {
        Set<UUID> ownerScope = resolveOwnerScope();
        long completed = 0;
        long missed = 0;
        long planned = 0;
        List<VisitStatusCount> statusRows;
        if (ownerScope == null) {
            statusRows = visitRepository.countGroupedByStatus(dateFrom, dateTo);
        } else {
            Set<UUID> leadIds = leadRepository.findByOwnerIdIn(ownerScope).stream()
                    .map(Lead::getId)
                    .collect(Collectors.toSet());
            statusRows = leadIds.isEmpty()
                    ? List.of()
                    : visitRepository.countGroupedByStatusForLeadIds(leadIds, dateFrom, dateTo);
        }
        for (VisitStatusCount row : statusRows) {
            switch (row.getStatus()) {
                case COMPLETED -> completed = row.getCount();
                case MISSED -> missed = row.getCount();
                case PLANNED -> planned = row.getCount();
            }
        }

        long resolved = completed + missed;
        double completionRatePercent = resolved == 0
                ? 0.0
                : roundToTwoDecimals(completed * 100.0 / resolved);

        return new VisitsCompletedVsMissedResponse(completed, missed, planned, completionRatePercent);
    }

    /** Feeds the Dashboard's "Open Visits" stat - all-time PLANNED count (no date bounds),
     * i.e. every visit still awaiting an outcome, not just those due in a specific window. */
    @Transactional(readOnly = true, noRollbackFor = AccessDeniedException.class)
    public long openVisitsCount() {
        return visitsCompletedVsMissed(null, null).planned();
    }

    /**
     * Dashboard's "Leads by Source" chart - one bucket per distinct leadSourceId, resolved to
     * its master-data label, sorted by count descending. Leads with no leadSourceId (free-text
     * leadSourceOther, or genuinely blank) are grouped into a single "Other" bucket, same
     * "group free-text/unset values together" convention this codebase already uses elsewhere
     * for reporting on creatable fields.
     */
    @Transactional(readOnly = true, noRollbackFor = AccessDeniedException.class)
    public LeadsBySourceResponse leadsBySource() {
        Set<UUID> ownerScope = resolveOwnerScope();
        List<LeadSourceCount> rows = ownerScope == null
                ? leadRepository.countGroupedByLeadSource()
                : leadRepository.countGroupedByLeadSourceForOwners(ownerScope);

        Set<UUID> sourceIds = rows.stream()
                .map(LeadSourceCount::getLeadSourceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> labelById = masterDataRepository.findAllById(sourceIds).stream()
                .collect(Collectors.toMap(MasterData::getId, MasterData::getLabel));

        Map<String, Long> countByLabel = new LinkedHashMap<>();
        for (LeadSourceCount row : rows) {
            String label = row.getLeadSourceId() == null
                    ? "Other"
                    : labelById.getOrDefault(row.getLeadSourceId(), "Other");
            countByLabel.merge(label, row.getCount(), Long::sum);
        }

        List<LeadSourceBreakdown> bySource = countByLabel.entrySet().stream()
                .map(e -> new LeadSourceBreakdown(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(LeadSourceBreakdown::count).reversed())
                .toList();
        return new LeadsBySourceResponse(bySource);
    }

    /**
     * Reports' "Visits by Type" chart (Field vs Telephonic) - byType always contains both
     * VisitType values (pre-seeded to zero) so the frontend gets a consistent chart regardless
     * of which one happens to be empty in this org, same convention as pipelineSummary's
     * byStatus.
     */
    @Transactional(readOnly = true, noRollbackFor = AccessDeniedException.class)
    public VisitsByTypeResponse visitsByType(LocalDate dateFrom, LocalDate dateTo) {
        Set<UUID> ownerScope = resolveOwnerScope();
        Map<VisitType, Long> byType = new EnumMap<>(VisitType.class);
        for (VisitType type : VisitType.values()) {
            byType.put(type, 0L);
        }
        long total = 0;
        List<VisitTypeCount> rows;
        if (ownerScope == null) {
            rows = visitRepository.countGroupedByVisitType(dateFrom, dateTo);
        } else {
            Set<UUID> leadIds = leadRepository.findByOwnerIdIn(ownerScope).stream()
                    .map(Lead::getId)
                    .collect(Collectors.toSet());
            rows = leadIds.isEmpty()
                    ? List.of()
                    : visitRepository.countGroupedByVisitTypeForLeadIds(leadIds, dateFrom, dateTo);
        }
        for (VisitTypeCount row : rows) {
            byType.put(row.getVisitType(), row.getCount());
            total += row.getCount();
        }
        return new VisitsByTypeResponse(byType, total);
    }

    /**
     * Reports' "Why is it Hot/Warm/Cold?" breakdown: per interest level, how many leads carry
     * each admin-defined reason. Same owner scoping as the other lead reports; optionally limited
     * to leads created between the (inclusive) dates.
     */
    @Transactional(readOnly = true, noRollbackFor = AccessDeniedException.class)
    public InterestReasonReportResponse interestReasons(LocalDate dateFrom, LocalDate dateTo) {
        Set<UUID> ownerScope = resolveOwnerScope();
        java.time.OffsetDateTime from = (dateFrom != null ? dateFrom : LocalDate.of(1970, 1, 1))
                .atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime();
        java.time.OffsetDateTime to = (dateTo != null ? dateTo.plusDays(1) : LocalDate.of(9999, 1, 1))
                .atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime();

        List<LeadInterestReasonCount> counts = ownerScope == null
                ? leadRepository.countReasons(from, to)
                : leadRepository.countReasonsForOwners(ownerScope, from, to);
        long leadsWithReasons = ownerScope == null
                ? leadRepository.countLeadsWithReasons(from, to)
                : leadRepository.countLeadsWithReasonsForOwners(ownerScope, from, to);

        List<MasterData> levels = masterDataRepository.findByType(MasterType.INTEREST_LEVEL, Sort.unsorted());
        Map<UUID, MasterData> levelById = levels.stream().collect(Collectors.toMap(MasterData::getId, m -> m));
        Map<UUID, String> reasonLabelById = masterDataRepository
                .findByType(MasterType.INTEREST_REASON, Sort.unsorted()).stream()
                .collect(Collectors.toMap(MasterData::getId, MasterData::getLabel));

        List<InterestReasonRow> rows = new ArrayList<>();
        for (LeadInterestReasonCount row : counts) {
            MasterData level = row.getInterestLevelId() == null ? null : levelById.get(row.getInterestLevelId());
            rows.add(new InterestReasonRow(level != null ? level.getLabel() : INTEREST_LEVEL_NOT_SET,
                    reasonLabelById.getOrDefault(row.getReasonId(), "(removed)"), row.getCount()));
        }
        // Hot, Warm, Cold first (by code, so relabelled levels keep their place), then the rest.
        Map<String, Integer> levelRank = new java.util.HashMap<>();
        for (MasterData level : levels) {
            int rank = INTEREST_LEVEL_CODE_ORDER.indexOf(level.getCode());
            levelRank.put(level.getLabel(), rank >= 0 ? rank : INTEREST_LEVEL_CODE_ORDER.size());
        }
        rows.sort(java.util.Comparator
                .comparingInt((InterestReasonRow r) -> levelRank.getOrDefault(r.interestLevel(), 99))
                .thenComparing(InterestReasonRow::count, java.util.Comparator.reverseOrder())
                .thenComparing(InterestReasonRow::reason));
        return new InterestReasonReportResponse(rows, leadsWithReasons);
    }

    /** Fixed display order for interestLevelStatusMatrix's rows - matches the business-critical,
     * never-expanded INTEREST_LEVEL codes (see MasterType's own javadoc); "Not Set" is a residual
     * bucket for a null interestLevelId (or a free-text interestLevelOther override), always
     * shown last regardless of whether any lead actually falls into it. */
    private static final List<String> INTEREST_LEVEL_CODE_ORDER = List.of("HOT", "WARM", "COLD");
    private static final String INTEREST_LEVEL_NOT_SET = "Not Set";

    /**
     * Reports' "Interest Level x Status" matrix (e.g. "how many Hot leads are stuck in
     * Contacted") - one row per interest level (Hot/Warm/Cold/Not Set), each with a count per
     * LeadStatus. Counts LEADS (one row per lead, by its current interestLevelId/status), not
     * visits - a lead's interest level and pipeline stage are both properties of the Lead
     * itself, so this is the natural aggregate granularity (see the plan's own "Hot leads stuck
     * in Contacted 2+ weeks" example use case).
     */
    @Transactional(readOnly = true, noRollbackFor = AccessDeniedException.class)
    public InterestLevelStatusMatrixResponse interestLevelStatusMatrix() {
        Set<UUID> ownerScope = resolveOwnerScope();
        List<LeadInterestStatusCount> rows = ownerScope == null
                ? leadRepository.countGroupedByInterestLevelAndStatus()
                : leadRepository.countGroupedByInterestLevelAndStatusForOwners(ownerScope);

        // Resolves an interestLevelId to its canonical "Hot"/"Warm"/"Cold" bucket via CODE
        // (stable, business-meaningful) rather than the admin-editable label directly - two
        // lookups (id -> code, code -> display label) rather than one, but this is what lets
        // the matrix's row order stay fixed (Hot, Warm, Cold, Not Set) regardless of how an org
        // has relabeled its INTEREST_LEVEL master-data rows.
        List<MasterData> interestLevelMasters = masterDataRepository.findByType(MasterType.INTEREST_LEVEL, Sort.unsorted());
        Map<UUID, String> codeById = interestLevelMasters.stream()
                .collect(Collectors.toMap(MasterData::getId, MasterData::getCode));
        Map<String, String> labelByCode = interestLevelMasters.stream()
                .collect(Collectors.toMap(MasterData::getCode, MasterData::getLabel, (a, b) -> a));

        Map<String, Map<LeadStatus, Long>> byBucket = new LinkedHashMap<>();
        for (String code : INTEREST_LEVEL_CODE_ORDER) {
            byBucket.put(labelByCode.getOrDefault(code, code), emptyStatusMap());
        }
        byBucket.put(INTEREST_LEVEL_NOT_SET, emptyStatusMap());

        for (LeadInterestStatusCount row : rows) {
            String code = row.getInterestLevelId() == null ? null : codeById.get(row.getInterestLevelId());
            String bucketLabel = code != null && labelByCode.containsKey(code)
                    ? labelByCode.get(code)
                    : INTEREST_LEVEL_NOT_SET;
            byBucket.computeIfAbsent(bucketLabel, k -> emptyStatusMap())
                    .merge(row.getStatus(), row.getCount(), Long::sum);
        }

        List<InterestLevelStatusRow> resultRows = new ArrayList<>();
        for (Map.Entry<String, Map<LeadStatus, Long>> entry : byBucket.entrySet()) {
            long rowTotal = entry.getValue().values().stream().mapToLong(Long::longValue).sum();
            resultRows.add(new InterestLevelStatusRow(entry.getKey(), entry.getValue(), rowTotal));
        }
        return new InterestLevelStatusMatrixResponse(resultRows);
    }

    private static Map<LeadStatus, Long> emptyStatusMap() {
        Map<LeadStatus, Long> map = new EnumMap<>(LeadStatus.class);
        for (LeadStatus status : LeadStatus.values()) {
            map.put(status, 0L);
        }
        return map;
    }

    /**
     * Dashboard's "Revenue (YTD)" stat - gated behind INVOICING (Invoicing is an
     * opt-in feature) so a non-entitled org gets {@code entitled=false} rather than a
     * misleading zero. resolveOwnerScope() runs FIRST regardless of entitlement, so a plain
     * EMPLOYEE without TEAM_VISIBILITY still gets the same 403 every other reporting endpoint
     * gives them - the entitlement check only decides what a permitted caller sees, not who's
     * permitted at all. dateFrom/dateTo default to the calendar-year-to-date range (Jan 1 of
     * this year through today) when omitted, matching the "YTD" label.
     */
    @Transactional(readOnly = true, noRollbackFor = AccessDeniedException.class)
    public RevenueResponse revenue(LocalDate dateFrom, LocalDate dateTo) {
        Set<UUID> ownerScope = resolveOwnerScope();
        UUID organizationId = currentUser.get().getOrganizationId();
        if (!entitlementService.isEntitled(organizationId, FeatureEntitlement.INVOICING)) {
            return new RevenueResponse(false, BigDecimal.ZERO);
        }

        LocalDate effectiveFrom = dateFrom != null ? dateFrom : LocalDate.now().withDayOfYear(1);
        LocalDate effectiveTo = dateTo != null ? dateTo : LocalDate.now();
        BigDecimal total = ownerScope == null
                ? invoiceRepository.sumGrandTotalBetween(effectiveFrom, effectiveTo)
                : invoiceRepository.sumGrandTotalForOwnersBetween(ownerScope, effectiveFrom, effectiveTo);
        return new RevenueResponse(true, total);
    }

    /**
     * Invoices Dashboard's stat-card row (section 17.4) - gated behind INVOICING
     * (same reasoning as {@link #revenue}: Quotations/Invoicing is an opt-in feature). dateFrom/
     * dateTo default to the current calendar month when omitted ("This Month", matching the
     * screenshot's default range) and scope totalQuotations/approvedQuotations/
     * convertedToInvoice/pendingInvoicesCount/monthlyBilling; "Pending Payments" and
     * "Outstanding" are deliberately NOT period-scoped (see the DTO's own javadoc for why).
     */
    @Transactional(readOnly = true, noRollbackFor = AccessDeniedException.class)
    public QuotationInvoiceSummaryResponse quotationInvoiceSummary(LocalDate dateFrom, LocalDate dateTo) {
        Set<UUID> ownerScope = resolveOwnerScope();
        UUID organizationId = currentUser.get().getOrganizationId();
        if (!entitlementService.isEntitled(organizationId, FeatureEntitlement.INVOICING)) {
            return new QuotationInvoiceSummaryResponse(0, 0, 0, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO);
        }

        LocalDate effectiveFrom = dateFrom != null ? dateFrom : LocalDate.now().withDayOfMonth(1);
        LocalDate effectiveTo = dateTo != null ? dateTo : LocalDate.now();
        LocalDate today = LocalDate.now();

        long totalQuotations;
        long approvedQuotations;
        long convertedToInvoice;
        long pendingInvoicesCount;
        BigDecimal pendingInvoicesAmount;
        long pendingPaymentsCount;
        BigDecimal pendingPaymentsAmount;
        BigDecimal monthlyBilling;
        BigDecimal outstanding;

        if (ownerScope == null) {
            totalQuotations = quotationRepository.countByQuotationDateBetween(effectiveFrom, effectiveTo);
            approvedQuotations = quotationRepository.countByStatusAndQuotationDateBetween(
                    QuotationStatus.APPROVED, effectiveFrom, effectiveTo);
            convertedToInvoice = quotationRepository.countByStatusAndQuotationDateBetween(
                    QuotationStatus.CONVERTED, effectiveFrom, effectiveTo);
            pendingInvoicesCount = invoiceRepository.countByStatusInAndInvoiceDateBetween(
                    InvoiceStatus.OPEN_STATUSES, effectiveFrom, effectiveTo);
            pendingInvoicesAmount = invoiceRepository.sumBalanceDueByStatusBetween(
                    InvoiceStatus.OPEN_STATUSES, effectiveFrom, effectiveTo);
            pendingPaymentsCount = invoiceRepository.countByStatusInAndDueDateLessThan(InvoiceStatus.OPEN_STATUSES, today);
            pendingPaymentsAmount = invoiceRepository.sumBalanceDueByStatusAndDueDateBefore(InvoiceStatus.OPEN_STATUSES, today);
            monthlyBilling = invoiceRepository.sumGrandTotalBetween(effectiveFrom, effectiveTo);
            outstanding = invoiceRepository.sumBalanceDueByStatus(InvoiceStatus.OPEN_STATUSES);
        } else {
            totalQuotations = quotationRepository.countByOwnerIdInAndQuotationDateBetween(
                    ownerScope, effectiveFrom, effectiveTo);
            approvedQuotations = quotationRepository.countByOwnerIdInAndStatusAndQuotationDateBetween(
                    ownerScope, QuotationStatus.APPROVED, effectiveFrom, effectiveTo);
            convertedToInvoice = quotationRepository.countByOwnerIdInAndStatusAndQuotationDateBetween(
                    ownerScope, QuotationStatus.CONVERTED, effectiveFrom, effectiveTo);
            pendingInvoicesCount = invoiceRepository.countByOwnerIdInAndStatusInAndInvoiceDateBetween(
                    ownerScope, InvoiceStatus.OPEN_STATUSES, effectiveFrom, effectiveTo);
            pendingInvoicesAmount = invoiceRepository.sumBalanceDueByStatusForOwnersBetween(
                    ownerScope, InvoiceStatus.OPEN_STATUSES, effectiveFrom, effectiveTo);
            pendingPaymentsCount = invoiceRepository.countByOwnerIdInAndStatusInAndDueDateLessThan(
                    ownerScope, InvoiceStatus.OPEN_STATUSES, today);
            pendingPaymentsAmount = invoiceRepository.sumBalanceDueByStatusAndDueDateBeforeForOwners(
                    ownerScope, InvoiceStatus.OPEN_STATUSES, today);
            monthlyBilling = invoiceRepository.sumGrandTotalForOwnersBetween(ownerScope, effectiveFrom, effectiveTo);
            outstanding = invoiceRepository.sumBalanceDueByStatusForOwners(ownerScope, InvoiceStatus.OPEN_STATUSES);
        }

        return new QuotationInvoiceSummaryResponse(totalQuotations, approvedQuotations, convertedToInvoice,
                pendingInvoicesCount, pendingInvoicesAmount, pendingPaymentsCount, pendingPaymentsAmount,
                monthlyBilling, outstanding);
    }

    /**
     * Reports Dashboard's global-filter-driven block (section 17.5) - every stat card/chart/
     * table is computed here, in Java, over ONE filtered {@code List<Lead>} fetch
     * (LeadService#listAllForReports), rather than ~10 separately hand-rolled JPQL group-by
     * queries each re-implementing the same ~10-filter binding. This is a deliberate departure
     * from every other method in this class (which all use fixed, narrow JPQL @Query
     * projections): the filter surface here is far wider (employee/company/state/city/product/
     * interest-level/business-type/next-follow-up-date/expected-close-date/status), and this
     * org's data volumes make an in-memory grouping pass over the filtered set perfectly
     * reasonable - see the plan's own note on this tradeoff. Owner-scoping (EMPLOYEE forced to
     * own-or-team leads, ADMIN unrestricted) is inherited from listAllForReports itself, not
     * re-applied here.
     */
    @Transactional(readOnly = true)
    public LeadDashboardResponse leadDashboard(LeadDashboardFilter filter) {
        List<Lead> leads = leadService.listAllForReports(filter);

        List<MasterData> interestLevelMasters = masterDataRepository.findByType(MasterType.INTEREST_LEVEL, Sort.unsorted());
        Map<UUID, String> interestCodeById = interestLevelMasters.stream()
                .collect(Collectors.toMap(MasterData::getId, MasterData::getCode));

        long hot = 0;
        long warm = 0;
        long cold = 0;
        long notSet = 0;
        long todayFollowUp = 0;
        long overdueFollowUp = 0;
        long closedWon = 0;
        long closedLost = 0;
        long thisWeekClose = 0;
        long thisMonthClose = 0;
        long nextMonthClose = 0;
        long followUpToday = 0;
        long followUpTomorrow = 0;
        long followUpNext7Days = 0;
        long followUpOverdue = 0;
        long followUpUnassigned = 0;

        LocalDate today = LocalDate.now();
        LocalDate endOfWeek = today.plusDays(7 - today.getDayOfWeek().getValue());
        LocalDate endOfMonth = today.withDayOfMonth(today.lengthOfMonth());
        LocalDate endOfNextMonth = today.plusMonths(1).withDayOfMonth(today.plusMonths(1).lengthOfMonth());

        Map<String, long[]> byCompany = new LinkedHashMap<>(); // [total, hot, won, lost]
        Map<String, long[]> byCity = new LinkedHashMap<>(); // [total, hot, won]
        Map<String, long[]> byProduct = new LinkedHashMap<>(); // [total, won]
        Map<String, long[]> byInterestBucket = new LinkedHashMap<>(); // [total, won]
        Map<String, long[]> byBusinessType = new LinkedHashMap<>(); // [total]
        Map<UUID, long[]> byEmployee = new LinkedHashMap<>(); // [total, hot, followUpPending, won]

        Set<UUID> productIds = leads.stream().flatMap(l -> l.getProductIds().stream()).collect(Collectors.toSet());
        Set<UUID> cityIds = leads.stream().map(Lead::getCityId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<UUID> businessTypeIds = leads.stream().map(Lead::getBusinessTypeId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> productLabelById = masterDataRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(MasterData::getId, MasterData::getLabel));
        Map<UUID, String> cityLabelById = masterDataRepository.findAllById(cityIds).stream()
                .collect(Collectors.toMap(MasterData::getId, MasterData::getLabel));
        Map<UUID, String> businessTypeLabelById = masterDataRepository.findAllById(businessTypeIds).stream()
                .collect(Collectors.toMap(MasterData::getId, MasterData::getLabel));
        Set<UUID> ownerIds = leads.stream().map(Lead::getOwnerId).collect(Collectors.toSet());
        Map<UUID, String> employeeNameById = employeeRepository.findAllById(ownerIds).stream()
                .collect(Collectors.toMap(Employee::getId, Employee::getFullName));

        for (Lead lead : leads) {
            String interestCode = lead.getInterestLevelId() == null ? null : interestCodeById.get(lead.getInterestLevelId());
            boolean isHot = "HOT".equals(interestCode);
            boolean isWon = lead.getStatus() == LeadStatus.CLOSED_WON;
            boolean isLost = lead.getStatus() == LeadStatus.LOST;

            if ("HOT".equals(interestCode)) hot++;
            else if ("WARM".equals(interestCode)) warm++;
            else if ("COLD".equals(interestCode)) cold++;
            else notSet++;

            if (isWon) closedWon++;
            if (isLost) closedLost++;

            if (lead.getNextFollowupDate() != null) {
                LocalDate nf = lead.getNextFollowupDate();
                if (nf.isEqual(today)) { todayFollowUp++; followUpToday++; }
                else if (nf.isBefore(today)) { overdueFollowUp++; followUpOverdue++; }
                if (nf.isEqual(today.plusDays(1))) followUpTomorrow++;
                if (!nf.isBefore(today) && !nf.isAfter(today.plusDays(7))) followUpNext7Days++;
            } else if (!isWon && !isLost) {
                followUpUnassigned++;
            }

            if (lead.getExpectedCloseDate() != null) {
                LocalDate ec = lead.getExpectedCloseDate();
                if (!ec.isBefore(today) && !ec.isAfter(endOfWeek)) thisWeekClose++;
                if (!ec.isBefore(today) && !ec.isAfter(endOfMonth)) thisMonthClose++;
                if (ec.isAfter(endOfMonth) && !ec.isAfter(endOfNextMonth)) nextMonthClose++;
            }

            String company = lead.getCompanyName();
            long[] companyRow = byCompany.computeIfAbsent(company, k -> new long[4]);
            companyRow[0]++;
            if (isHot) companyRow[1]++;
            if (isWon) companyRow[2]++;
            if (isLost) companyRow[3]++;

            String cityLabel = lead.getCityId() != null ? cityLabelById.getOrDefault(lead.getCityId(), "Other")
                    : (lead.getCityOther() != null ? lead.getCityOther() : "Not Set");
            long[] cityRow = byCity.computeIfAbsent(cityLabel, k -> new long[3]);
            cityRow[0]++;
            if (isHot) cityRow[1]++;
            if (isWon) cityRow[2]++;

            for (UUID productId : lead.getProductIds()) {
                String productLabel = productLabelById.getOrDefault(productId, "Other");
                long[] productRow = byProduct.computeIfAbsent(productLabel, k -> new long[2]);
                productRow[0]++;
                if (isWon) productRow[1]++;
            }

            String interestBucket = isHot ? "Hot" : "WARM".equals(interestCode) ? "Warm"
                    : "COLD".equals(interestCode) ? "Cold" : "Not Set";
            long[] interestRow = byInterestBucket.computeIfAbsent(interestBucket, k -> new long[2]);
            interestRow[0]++;
            if (isWon) interestRow[1]++;

            String businessTypeLabel = lead.getBusinessTypeId() != null
                    ? businessTypeLabelById.getOrDefault(lead.getBusinessTypeId(), "Other")
                    : (lead.getBusinessTypeOther() != null ? lead.getBusinessTypeOther() : "Not Set");
            byBusinessType.computeIfAbsent(businessTypeLabel, k -> new long[1])[0]++;

            long[] employeeRow = byEmployee.computeIfAbsent(lead.getOwnerId(), k -> new long[4]);
            employeeRow[0]++;
            if (isHot) employeeRow[1]++;
            if (lead.getNextFollowupDate() != null && !isWon && !isLost) employeeRow[2]++;
            if (isWon) employeeRow[3]++;
        }

        long totalLeads = leads.size();
        double conversionRatePercent = totalLeads == 0 ? 0.0 : roundToTwoDecimals(closedWon * 100.0 / totalLeads);

        List<LeadDashboardResponse.LabelCount> byBusinessTypeList = byBusinessType.entrySet().stream()
                .map(e -> new LeadDashboardResponse.LabelCount(e.getKey(), e.getValue()[0]))
                .sorted(Comparator.comparingLong(LeadDashboardResponse.LabelCount::count).reversed())
                .toList();
        List<LeadDashboardResponse.LabelCount> byProductList = byProduct.entrySet().stream()
                .map(e -> new LeadDashboardResponse.LabelCount(e.getKey(), e.getValue()[0]))
                .sorted(Comparator.comparingLong(LeadDashboardResponse.LabelCount::count).reversed())
                .toList();
        List<LeadDashboardResponse.CompanySummaryRow> companyRows = byCompany.entrySet().stream()
                .map(e -> new LeadDashboardResponse.CompanySummaryRow(e.getKey(), e.getValue()[0], e.getValue()[1],
                        e.getValue()[2], e.getValue()[3]))
                .sorted(Comparator.comparingLong(LeadDashboardResponse.CompanySummaryRow::total).reversed())
                .toList();
        List<LeadDashboardResponse.CitySummaryRow> cityRows = byCity.entrySet().stream()
                .map(e -> new LeadDashboardResponse.CitySummaryRow(e.getKey(), e.getValue()[0], e.getValue()[1],
                        e.getValue()[2]))
                .sorted(Comparator.comparingLong(LeadDashboardResponse.CitySummaryRow::total).reversed())
                .toList();
        List<LeadDashboardResponse.ProductPerformanceRow> productRows = byProduct.entrySet().stream()
                .map(e -> new LeadDashboardResponse.ProductPerformanceRow(e.getKey(), e.getValue()[0], e.getValue()[1],
                        e.getValue()[0] == 0 ? 0.0 : roundToTwoDecimals(e.getValue()[1] * 100.0 / e.getValue()[0])))
                .sorted(Comparator.comparingLong(LeadDashboardResponse.ProductPerformanceRow::total).reversed())
                .toList();
        List<LeadDashboardResponse.InterestLevelPerformanceRow> interestRows = byInterestBucket.entrySet().stream()
                .map(e -> new LeadDashboardResponse.InterestLevelPerformanceRow(e.getKey(), e.getValue()[0],
                        e.getValue()[1],
                        e.getValue()[0] == 0 ? 0.0 : roundToTwoDecimals(e.getValue()[1] * 100.0 / e.getValue()[0])))
                .toList();
        List<LeadDashboardResponse.EmployeePerformanceRow> employeeRows = byEmployee.entrySet().stream()
                .map(e -> new LeadDashboardResponse.EmployeePerformanceRow(
                        employeeNameById.getOrDefault(e.getKey(), "Unknown"), e.getValue()[0], e.getValue()[1],
                        e.getValue()[2], e.getValue()[3],
                        e.getValue()[0] == 0 ? 0.0 : roundToTwoDecimals(e.getValue()[3] * 100.0 / e.getValue()[0])))
                .sorted(Comparator.comparingLong(LeadDashboardResponse.EmployeePerformanceRow::total).reversed())
                .toList();

        return new LeadDashboardResponse(
                totalLeads, hot, warm, cold, notSet,
                todayFollowUp, overdueFollowUp,
                closedWon, closedLost, conversionRatePercent,
                new LeadDashboardResponse.ExpectedClosures(thisWeekClose, thisMonthClose, nextMonthClose),
                byBusinessTypeList, byProductList,
                companyRows, cityRows, productRows, interestRows, employeeRows,
                new LeadDashboardResponse.FollowUpSummary(followUpToday, followUpTomorrow, followUpNext7Days,
                        followUpOverdue, followUpUnassigned));
    }

    /**
     * Read-only per-team-member rollup (GET /reports/team-progress): each member's lead counts
     * by status, visits due today/upcoming (next 7 days), and last activity timestamp - a
     * manager's "who's doing what" view, read-only for now (no drill-down/edit actions here).
     * Sorted by employeeName. An org/manager with zero visible team members (a fresh org, or an
     * IC manager with no reports at all) gets an empty list, not an error - only the "not
     * permitted to see anyone's team at all" case 403s, via {@link #resolveTeamMemberIds()}.
     */
    @Transactional(readOnly = true, noRollbackFor = AccessDeniedException.class)
    public TeamProgressResponse teamProgress() {
        Set<UUID> memberIds = resolveTeamMemberIds();
        if (memberIds.isEmpty()) {
            return new TeamProgressResponse(List.of());
        }

        Map<UUID, Employee> employeesById = employeeRepository.findAllById(memberIds).stream()
                .collect(Collectors.toMap(Employee::getId, e -> e));

        Map<UUID, Map<LeadStatus, Long>> leadCountsByOwner = new HashMap<>();
        for (UUID id : memberIds) {
            leadCountsByOwner.put(id, emptyStatusMap());
        }
        for (LeadOwnerStatusCount row : leadRepository.countGroupedByOwnerAndStatusForOwners(memberIds)) {
            leadCountsByOwner.computeIfAbsent(row.getOwnerId(), k -> emptyStatusMap())
                    .put(row.getStatus(), row.getCount());
        }

        // leadId -> ownerId, scoped to the team - needed to attribute each Visit (which has no
        // ownerId of its own, see Visit's own javadoc) back to the right team member.
        Map<UUID, UUID> ownerIdByLeadId = leadRepository.findByOwnerIdIn(memberIds).stream()
                .collect(Collectors.toMap(Lead::getId, Lead::getOwnerId));
        Set<UUID> leadIds = ownerIdByLeadId.keySet();

        Map<UUID, Long> visitsDueTodayByOwner = new HashMap<>();
        Map<UUID, Long> visitsUpcomingByOwner = new HashMap<>();
        if (!leadIds.isEmpty()) {
            LocalDate today = LocalDate.now();
            for (Visit visit : visitRepository.findByLeadIdInAndStatusAndVisitDateLessThanEqual(
                    leadIds, VisitStatus.PLANNED, today)) {
                UUID ownerId = ownerIdByLeadId.get(visit.getLeadId());
                if (ownerId != null) {
                    visitsDueTodayByOwner.merge(ownerId, 1L, Long::sum);
                }
            }
            for (Visit visit : visitRepository.findByLeadIdInAndStatusAndVisitDateBetween(
                    leadIds, VisitStatus.PLANNED, today.plusDays(1), today.plusDays(7))) {
                UUID ownerId = ownerIdByLeadId.get(visit.getLeadId());
                if (ownerId != null) {
                    visitsUpcomingByOwner.merge(ownerId, 1L, Long::sum);
                }
            }
        }

        Map<UUID, OffsetDateTime> lastActivityByOwner = activityLogRepository.findLastActivityForOwners(memberIds)
                .stream()
                .collect(Collectors.toMap(OwnerLastActivity::getOwnerId, OwnerLastActivity::getLastActivityAt));

        List<TeamMemberProgress> members = memberIds.stream()
                .map(id -> {
                    Employee employee = employeesById.get(id);
                    Map<LeadStatus, Long> byStatus = leadCountsByOwner.getOrDefault(id, emptyStatusMap());
                    long totalLeads = byStatus.values().stream().mapToLong(Long::longValue).sum();
                    return new TeamMemberProgress(
                            id,
                            employee != null ? employee.getFullName() : "Unknown",
                            byStatus,
                            totalLeads,
                            visitsDueTodayByOwner.getOrDefault(id, 0L),
                            visitsUpcomingByOwner.getOrDefault(id, 0L),
                            lastActivityByOwner.get(id));
                })
                .sorted(Comparator.comparing(TeamMemberProgress::employeeName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        return new TeamProgressResponse(members);
    }

    /**
     * Who counts as "my team" for {@link #teamProgress()}: for an ADMIN, every other active
     * employee in the org (an Admin oversees everyone); for an EMPLOYEE, the same
     * TEAM_VISIBILITY-gated subordinate chain as {@link #resolveOwnerScope()} - but unlike that
     * method, self is deliberately NOT added here, since this view lists individual team
     * members, not an aggregate that should include the manager's own numbers. A manager with
     * no reports (or an org without TEAM_VISIBILITY) gets the same 403 every other
     * team-scoped reporting endpoint already gives them.
     */
    private Set<UUID> resolveTeamMemberIds() {
        UserPrincipal principal = currentUser.get();
        if (principal.getRole() != Role.EMPLOYEE) {
            return employeeRepository.findByActive(true).stream()
                    .map(Employee::getId)
                    .filter(id -> !id.equals(principal.getEmployeeId()))
                    .collect(Collectors.toSet());
        }
        Set<UUID> subordinateIds = employeeHierarchyService
                .getTeamVisibilityScope(principal.getOrganizationId(), principal.getEmployeeId());
        if (subordinateIds.isEmpty()) {
            throw new AccessDeniedException("Team progress is limited to Admins and managers with team visibility");
        }
        return subordinateIds;
    }

    /**
     * {@code null} means "unrestricted" (ADMIN - org-wide aggregates). For an EMPLOYEE, this is
     * themself + every subordinate at any depth via TEAM_VISIBILITY
     * (EmployeeHierarchyService#getTeamVisibilityScope) - and if that scope comes back empty
     * (entitlement off, or this employee simply has no reports), reports stay exactly as
     * inaccessible to them as before this feature existed: an AccessDeniedException, which
     * GlobalExceptionHandler maps to a 403.
     */
    private Set<UUID> resolveOwnerScope() {
        UserPrincipal principal = currentUser.get();
        if (principal.getRole() != Role.EMPLOYEE) {
            return null;
        }
        Set<UUID> subordinateIds = employeeHierarchyService
                .getTeamVisibilityScope(principal.getOrganizationId(), principal.getEmployeeId());
        if (subordinateIds.isEmpty()) {
            throw new AccessDeniedException("Reports are limited to Admins and managers with team visibility");
        }
        Set<UUID> scope = new HashSet<>(subordinateIds);
        scope.add(principal.getEmployeeId());
        return scope;
    }

    private static double roundToTwoDecimals(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
