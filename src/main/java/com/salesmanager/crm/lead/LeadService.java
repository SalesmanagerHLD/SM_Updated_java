package com.salesmanager.crm.lead;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.activity.ActivityLogService;
import com.salesmanager.crm.activity.ActivityType;
import com.salesmanager.crm.common.NotFoundException;
import com.salesmanager.crm.common.event.FollowUpScheduledEvent;
import com.salesmanager.crm.common.event.LeadCreatedEvent;
import com.salesmanager.crm.employee.Employee;
import com.salesmanager.crm.employee.EmployeeHierarchyService;
import com.salesmanager.crm.employee.EmployeeRepository;
import com.salesmanager.crm.employee.Role;
import com.salesmanager.crm.lead.dto.LeadCreateRequest;
import com.salesmanager.crm.lead.dto.LeadReassignRequest;
import com.salesmanager.crm.lead.dto.LeadStatusUpdateRequest;
import com.salesmanager.crm.lead.dto.LeadUpdateRequest;
import com.salesmanager.crm.masterdata.InvalidReferenceException;
import com.salesmanager.crm.masterdata.MasterData;
import com.salesmanager.crm.masterdata.MasterDataRepository;
import com.salesmanager.crm.masterdata.MasterDataService;
import com.salesmanager.crm.masterdata.MasterType;
import com.salesmanager.crm.notification.NotificationService;
import com.salesmanager.crm.notification.NotificationType;
import com.salesmanager.crm.security.CurrentUser;
import com.salesmanager.crm.security.UserPrincipal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates Lead CRUD, the duplicate-lead check, owner-scoped visibility, the Lost-lead
 * workflow, reassignment, and (Phase 3) publishing the domain events that drive
 * auto-generated stub Visits. Follows the same TenantFilter-shared-transaction conventions as
 * EmployeeService/MasterDataService (see their class/method comments for the full
 * "noRollbackFor is essential, not cosmetic" and "saveAndFlush, not save" rationale - not
 * repeated at every method here).
 */
@Service
public class LeadService {

    private static final Logger log = LoggerFactory.getLogger(LeadService.class);

    private final LeadRepository leadRepository;
    private final MasterDataService masterDataService;
    private final MasterDataRepository masterDataRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeHierarchyService employeeHierarchyService;
    private final NotificationService notificationService;
    private final ActivityLogService activityLogService;
    private final CurrentUser currentUser;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public LeadService(LeadRepository leadRepository,
                        MasterDataService masterDataService,
                        MasterDataRepository masterDataRepository,
                        EmployeeRepository employeeRepository,
                        EmployeeHierarchyService employeeHierarchyService,
                        NotificationService notificationService,
                        ActivityLogService activityLogService,
                        CurrentUser currentUser,
                        ApplicationEventPublisher eventPublisher,
                        ObjectMapper objectMapper) {
        this.leadRepository = leadRepository;
        this.masterDataService = masterDataService;
        this.masterDataRepository = masterDataRepository;
        this.employeeRepository = employeeRepository;
        this.employeeHierarchyService = employeeHierarchyService;
        this.notificationService = notificationService;
        this.activityLogService = activityLogService;
        this.currentUser = currentUser;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Transactional(noRollbackFor = InvalidReferenceException.class)
    public Lead create(LeadCreateRequest request) {
        validateCreatableReferences(request.industryId(), request.industryOther(),
                request.businessTypeId(), request.businessTypeOther(),
                request.leadSourceId(), request.leadSourceOther(),
                request.designationId(), request.designationOther(),
                request.stateId(), request.stateOther(),
                request.cityId(), request.cityOther(),
                request.interestLevelId(), request.interestLevelOther(),
                request.productIds());
        // industryId/leadSourceId/cityId used to be @NotNull on the DTO; now that each has a
        // free-text fallback, "required" generalizes to "one of the pair must be supplied".
        requireProvided(request.industryId(), request.industryOther(), "industryId");
        requireProvided(request.leadSourceId(), request.leadSourceOther(), "leadSourceId");
        requireProvided(request.cityId(), request.cityOther(), "cityId");

        UUID currentEmployeeId = currentUser.get().getEmployeeId();
        Lead lead = Lead.builder()
                .companyName(request.companyName())
                .industryId(request.industryId())
                .industryOther(request.industryOther())
                .businessTypeId(request.businessTypeId())
                .businessTypeOther(request.businessTypeOther())
                .leadSourceId(request.leadSourceId())
                .leadSourceOther(request.leadSourceOther())
                .turnover(request.turnover())
                .contactPerson(request.contactPerson())
                .designationId(request.designationId())
                .designationOther(request.designationOther())
                .contactNo(request.contactNo())
                .email(request.email())
                .stateId(request.stateId())
                .stateOther(request.stateOther())
                .cityId(request.cityId())
                .cityOther(request.cityOther())
                .address(request.address())
                .requirements(request.requirements())
                .interestLevelId(request.interestLevelId())
                .interestLevelOther(request.interestLevelOther())
                .currentProductSolution(request.currentProductSolution())
                .budgetRange(request.budgetRange())
                .decisionMakerIdentified(request.decisionMakerIdentified())
                .objections(request.objections())
                .remarks(request.remarks())
                .nextFollowupDate(request.nextFollowupDate())
                .expectedCloseDate(request.expectedCloseDate())
                .status(isHotInterestLevel(request.interestLevelId()) ? LeadStatus.NEW : LeadStatus.INTERESTED)
                .ownerId(currentEmployeeId)
                .createdBy(currentEmployeeId)
                .productIds(new HashSet<>(request.productIds() != null ? request.productIds() : Set.of()))
                .productsOther(request.productsOther())
                .build();
        // saveAndFlush - see EmployeeService#create's comment re: @CreationTimestamp/@UpdateTimestamp.
        Lead saved = leadRepository.saveAndFlush(lead);

        // Phase 3 domain events - listened to by visit.LeadVisitEventListener, which creates
        // the corresponding stub Visit(s) AFTER this request's transaction commits. See that
        // listener's javadoc for why it needs to reactivate the tenant context itself.
        // Defaults to true when omitted (most leads are entered right after a live interaction) -
        // only an EXPLICIT false suppresses the auto-visit, e.g. for leads entered secondhand
        // from a web form with no direct contact yet. A previous version of this line
        // (`!= null && logAsVisitToday()`) defaulted to false on omission - the exact opposite
        // of the spec - which silently disabled the auto-visit for every real caller that just
        // omits the field rather than sending `true` explicitly.
        boolean logAsVisitToday = request.logAsVisitToday() == null || request.logAsVisitToday();
        eventPublisher.publishEvent(
                new LeadCreatedEvent(saved.getId(), saved.getOrganizationId(), logAsVisitToday, request.visitType()));
        if (request.nextFollowupDate() != null) {
            eventPublisher.publishEvent(new FollowUpScheduledEvent(
                    saved.getId(), saved.getOrganizationId(), request.nextFollowupDate(), null));
        }

        activityLogService.record(saved.getId(), saved.getOwnerId(), saved.getCompanyName(),
                ActivityType.LEAD_CREATED, currentEmployeeId, "Lead created");

        return saved;
    }

    @Transactional(noRollbackFor = {NotFoundException.class, InvalidReferenceException.class})
    public Lead update(UUID id, LeadUpdateRequest request) {
        Lead lead = loadForCurrentUser(id);
        // Captured BEFORE applying request.nextFollowupDate() below, so the "changed to a
        // new, non-null value" comparison never fires on an unrelated resave with the same date.
        LocalDate oldNextFollowupDate = lead.getNextFollowupDate();

        applyCreatableField(request.industryId(), request.industryOther(), MasterType.INDUSTRY,
                "industryId", "industryOther", null, lead::setIndustryId, lead::setIndustryOther);
        applyCreatableField(request.businessTypeId(), request.businessTypeOther(), MasterType.BUSINESS_TYPE,
                "businessTypeId", "businessTypeOther", null, lead::setBusinessTypeId, lead::setBusinessTypeOther);
        applyCreatableField(request.leadSourceId(), request.leadSourceOther(), MasterType.LEAD_SOURCE,
                "leadSourceId", "leadSourceOther", null, lead::setLeadSourceId, lead::setLeadSourceOther);
        applyCreatableField(request.designationId(), request.designationOther(), MasterType.DESIGNATION,
                "designationId", "designationOther", null, lead::setDesignationId, lead::setDesignationOther);
        applyCreatableField(request.stateId(), request.stateOther(), MasterType.STATE,
                "stateId", "stateOther", null, lead::setStateId, lead::setStateOther);
        // Cross-checks cityId's parent against stateId when both are supplied on this
        // request - see MasterDataService#validateReference's 4-arg overload javadoc.
        applyCreatableField(request.cityId(), request.cityOther(), MasterType.CITY,
                "cityId", "cityOther", request.stateId(), lead::setCityId, lead::setCityOther);
        applyCreatableField(request.interestLevelId(), request.interestLevelOther(), MasterType.INTEREST_LEVEL,
                "interestLevelId", "interestLevelOther", null, lead::setInterestLevelId, lead::setInterestLevelOther);
        if (request.productIds() != null) {
            for (UUID productId : request.productIds()) {
                masterDataService.validateReference(productId, MasterType.PRODUCT, "productIds");
            }
            lead.setProductIds(new HashSet<>(request.productIds()));
        }
        if (request.productsOther() != null) {
            lead.setProductsOther(request.productsOther());
        }
        if (request.companyName() != null) {
            lead.setCompanyName(request.companyName());
        }
        if (request.turnover() != null) {
            lead.setTurnover(request.turnover());
        }
        if (request.contactPerson() != null) {
            lead.setContactPerson(request.contactPerson());
        }
        if (request.contactNo() != null) {
            lead.setContactNo(request.contactNo());
        }
        if (request.email() != null) {
            lead.setEmail(request.email());
        }
        if (request.address() != null) {
            lead.setAddress(request.address());
        }
        if (request.requirements() != null) {
            lead.setRequirements(request.requirements());
        }
        if (request.currentProductSolution() != null) {
            lead.setCurrentProductSolution(request.currentProductSolution());
        }
        if (request.budgetRange() != null) {
            lead.setBudgetRange(request.budgetRange());
        }
        if (request.decisionMakerIdentified() != null) {
            lead.setDecisionMakerIdentified(request.decisionMakerIdentified());
        }
        if (request.objections() != null) {
            lead.setObjections(request.objections());
        }
        if (request.remarks() != null) {
            lead.setRemarks(request.remarks());
        }
        if (request.nextFollowupDate() != null) {
            lead.setNextFollowupDate(request.nextFollowupDate());
        }
        if (request.expectedCloseDate() != null) {
            lead.setExpectedCloseDate(request.expectedCloseDate());
        }

        // saveAndFlush - see EmployeeService#create's comment re: @CreationTimestamp/@UpdateTimestamp.
        Lead saved = leadRepository.saveAndFlush(lead);

        boolean nextFollowupDateChangedToNewValue = request.nextFollowupDate() != null
                && !request.nextFollowupDate().equals(oldNextFollowupDate);
        if (nextFollowupDateChangedToNewValue) {
            eventPublisher.publishEvent(new FollowUpScheduledEvent(
                    saved.getId(), saved.getOrganizationId(), request.nextFollowupDate(), null));
        }

        return saved;
    }

    /**
     * status == LOST requires exactly one of lostReasonId (validated as a real LOST_REASON
     * reference)/lostReasonOther (free text), and auto-sets interestLevelId to this org's
     * INTEREST_LEVEL/COLD row - every org gets that seeded by MasterDataSeedService, but a
     * determined admin could since have deleted it, so a missing COLD row is logged and
     * otherwise ignored rather than crashing the request. Any other status value is a plain
     * status change - lostReasonId/lostReasonOther/interestLevelId are left untouched.
     */
    @Transactional(noRollbackFor = {NotFoundException.class, InvalidReferenceException.class,
            InvalidLeadStatusException.class})
    public Lead updateStatus(UUID id, LeadStatusUpdateRequest request) {
        Lead lead = loadForCurrentUser(id);
        // Captured BEFORE mutating below, so the activity-log entry at the end of this method
        // can describe the actual transition ("from X to Y") rather than just the new value.
        LeadStatus oldStatus = lead.getStatus();
        // Only set (to the pre-reassignment owner) inside the LOST branch below - stays null,
        // and the post-save reassignment notification/activity-log block is skipped, for every
        // other status value.
        UUID previousOwnerId = null;

        // A lead only progresses through the normal pipeline while its Interest Level is Hot;
        // anything else is locked to INTERESTED, mirroring the frontend hiding the status
        // dropdown entirely for a non-Hot lead. LOST is always reachable regardless (marking
        // any lead - Warm, Cold, or unset - lost is a normal outcome, not gated by temperature).
        if (request.status() != LeadStatus.LOST
                && request.status() != LeadStatus.INTERESTED
                && !isHotInterestLevel(lead.getInterestLevelId())) {
            throw new InvalidLeadStatusException(
                    "Status can only be set to Interested or Lost while Interest Level isn't Hot");
        }

        if (request.status() == LeadStatus.LOST) {
            if (request.lostReasonId() == null && request.lostReasonOther() == null) {
                throw new InvalidReferenceException("lostReasonId",
                        "lostReasonId or lostReasonOther is required when status is LOST");
            }
            masterDataService.validateCreatableField(request.lostReasonId(), request.lostReasonOther(),
                    MasterType.LOST_REASON, "lostReasonId", "lostReasonOther");
            if (request.lostReasonId() != null) {
                lead.setLostReasonId(request.lostReasonId());
                lead.setLostReasonOther(null);
            } else {
                lead.setLostReasonOther(request.lostReasonOther());
                lead.setLostReasonId(null);
            }
            lead.setStatus(LeadStatus.LOST);

            masterDataRepository.findByTypeAndCodeIgnoreCase(MasterType.INTEREST_LEVEL, "COLD")
                    .map(MasterData::getId)
                    .ifPresentOrElse(
                            lead::setInterestLevelId,
                            () -> log.warn("No INTEREST_LEVEL master row with code COLD found for org {}; "
                                            + "leaving interestLevelId unchanged on lead {}",
                                    lead.getOrganizationId(), lead.getId()));

            // Unassign from the current owner and hand back to the org's single Admin (see
            // MultipleAdminNotAllowedException - every org has exactly one) so a Lost lead
            // re-enters the reassignable pool rather than sitting invisibly under whichever
            // rep lost it.
            previousOwnerId = lead.getOwnerId();
            List<Employee> admins = employeeRepository.findByOrganizationIdAndRole(lead.getOrganizationId(), Role.ADMIN);
            if (!admins.isEmpty()) {
                lead.setOwnerId(admins.get(0).getId());
            } else {
                log.warn("No ADMIN found for org {}; leaving Lost lead {} with its current owner",
                        lead.getOrganizationId(), lead.getId());
            }
        } else {
            lead.setStatus(request.status());
        }

        // saveAndFlush - see EmployeeService#create's comment re: @CreationTimestamp/@UpdateTimestamp.
        Lead saved = leadRepository.saveAndFlush(lead);

        if (oldStatus != saved.getStatus()) {
            activityLogService.record(saved.getId(), saved.getOwnerId(), saved.getCompanyName(),
                    ActivityType.LEAD_STATUS_CHANGED, currentUser.get().getEmployeeId(),
                    "Status changed from " + oldStatus + " to " + saved.getStatus());
        }

        if (previousOwnerId != null && !previousOwnerId.equals(saved.getOwnerId())) {
            String actorName = employeeRepository.findById(currentUser.get().getEmployeeId())
                    .map(Employee::getFullName)
                    .orElse(null);
            notificationService.create(saved.getOwnerId(), NotificationType.LEAD_REASSIGNED,
                    buildReassignmentPayload(saved, actorName));
            activityLogService.record(saved.getId(), saved.getOwnerId(), saved.getCompanyName(),
                    ActivityType.LEAD_REASSIGNED, currentUser.get().getEmployeeId(),
                    "Lead auto-reassigned to Admin after being marked Lost");
        }

        return saved;
    }

    /**
     * Called when a Quotation tied to this lead is converted to an invoice. Marks the lead
     * CLOSED_WON through {@link #updateStatus} (so the activity-log entry is written exactly as
     * for a manual status change) - but ONLY when that existing path would already allow it for
     * the current user; otherwise the lead is left untouched and {@code false} is returned.
     * That means no existing rule is bypassed:
     * <ul>
     *   <li>a lead that is already LOST or CLOSED_WON is never changed;</li>
     *   <li>the lead must be Hot (updateStatus's gate on every status other than
     *       Interested/Lost);</li>
     *   <li>an EMPLOYEE can only update a lead they own (an ADMIN can update any).</li>
     * </ul>
     * Every condition is checked up front without throwing, so nothing here can mark the shared
     * request transaction rollback-only (or, via updateStatus's noRollbackFor, half-commit)
     * after the invoice has already been written.
     */
    @Transactional
    public boolean markClosedWonIfAllowed(UUID leadId) {
        Lead lead = leadRepository.findById(leadId).orElse(null);
        if (lead == null) {
            return false;
        }
        if (lead.getStatus() == LeadStatus.LOST || lead.getStatus() == LeadStatus.CLOSED_WON) {
            return false;
        }
        UserPrincipal principal = currentUser.get();
        if (principal.getRole() == Role.EMPLOYEE && !lead.getOwnerId().equals(principal.getEmployeeId())) {
            return false;
        }
        if (!isHotInterestLevel(lead.getInterestLevelId())) {
            return false;
        }
        updateStatus(leadId, new LeadStatusUpdateRequest(LeadStatus.CLOSED_WON, null, null));
        return true;
    }

    /**
     * True only when {@code interestLevelId} resolves to the fixed INTEREST_LEVEL/HOT master
     * row - a null id, a deleted/invalid id, or a free-text interestLevelOther (which has no id
     * to resolve at all) are all treated as not-Hot. Drives both create()'s initial status and
     * updateStatus()'s gating.
     */
    private boolean isHotInterestLevel(UUID interestLevelId) {
        if (interestLevelId == null) {
            return false;
        }
        return masterDataRepository.findById(interestLevelId)
                .map(MasterData::getCode)
                .map(code -> code.equalsIgnoreCase("HOT"))
                .orElse(false);
    }

    /**
     * ADMIN-only (enforced via @PreAuthorize on LeadController#reassign). Deliberately does
     * NOT go through loadForCurrentUser - an ADMIN may reassign any lead in their org
     * regardless of its current owner. newOwnerId must reference a real, active Employee in
     * the same org; EmployeeRepository#findById relies on the Postgres RLS backstop (not the
     * Hibernate filter, which doesn't apply to primary-key lookups) to reject a cross-tenant
     * id, same as every other id lookup in this codebase.
     */
    @Transactional(noRollbackFor = {NotFoundException.class, InvalidReferenceException.class})
    public Lead reassign(UUID id, LeadReassignRequest request) {
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Lead not found: " + id));

        Employee newOwner = employeeRepository.findById(request.newOwnerId())
                .filter(Employee::isActive)
                .orElseThrow(() -> new InvalidReferenceException("newOwnerId",
                        "newOwnerId does not reference an active employee in this organization"));

        lead.setOwnerId(newOwner.getId());
        // saveAndFlush - see EmployeeService#create's comment re: @CreationTimestamp/@UpdateTimestamp.
        Lead saved = leadRepository.saveAndFlush(lead);

        String reassignedByName = employeeRepository.findById(currentUser.get().getEmployeeId())
                .map(Employee::getFullName)
                .orElse(null);
        notificationService.create(newOwner.getId(), NotificationType.LEAD_REASSIGNED,
                buildReassignmentPayload(saved, reassignedByName));

        // saved.getOwnerId() is already the NEW owner (set above) - the activity row records
        // who owned this lead AS OF this event, per ActivityLog's denormalization rationale.
        activityLogService.record(saved.getId(), saved.getOwnerId(), saved.getCompanyName(),
                ActivityType.LEAD_REASSIGNED, currentUser.get().getEmployeeId(), "Lead reassigned");

        return saved;
    }

    @Transactional(readOnly = true)
    public Page<Lead> list(LeadFilter filter, Pageable pageable) {
        UserPrincipal principal = currentUser.get();
        Specification<Lead> spec = Specification
                .where(LeadSpecifications.hasStatus(filter.status()))
                .and(LeadSpecifications.hasInterestLevel(filter.interestLevelId()))
                .and(LeadSpecifications.matchesSearch(filter.search()))
                .and(LeadSpecifications.hasState(filter.stateId()))
                .and(LeadSpecifications.hasCity(filter.cityId()))
                .and(LeadSpecifications.hasProduct(filter.productId()))
                .and(LeadSpecifications.createdBetween(filter.dateFrom(), filter.dateTo()));

        if (principal.getRole() == Role.EMPLOYEE) {
            // TEAM_VISIBILITY (see EmployeeHierarchyService#getTeamVisibilityScope): empty
            // when the org hasn't licensed it or this employee has no reports, in which case
            // this is exactly the old rule - force the ownerId filter to their own id
            // regardless of what was requested, so an employee can never list a colleague's
            // leads via query manipulation.
            Set<UUID> subordinateIds = employeeHierarchyService
                    .getTeamVisibilityScope(principal.getOrganizationId(), principal.getEmployeeId());
            if (subordinateIds.isEmpty()) {
                spec = spec.and(LeadSpecifications.hasOwner(principal.getEmployeeId()));
            } else {
                Set<UUID> teamScope = new HashSet<>(subordinateIds);
                teamScope.add(principal.getEmployeeId());
                // An explicitly requested ownerId is honored only if it falls within the
                // manager's own team scope - never used to peek at leads outside it.
                if (filter.ownerId() != null && teamScope.contains(filter.ownerId())) {
                    spec = spec.and(LeadSpecifications.hasOwner(filter.ownerId()));
                } else {
                    spec = spec.and(LeadSpecifications.hasOwnerIn(teamScope));
                }
            }
        } else {
            // ADMIN gets whatever ownerId filter (or none) was requested, honored as-is.
            spec = spec.and(LeadSpecifications.hasOwner(filter.ownerId()));
        }

        return leadRepository.findAll(spec, pageable);
    }

    /**
     * Unpaged, filtered lead list for the Reports Dashboard (section 17.5) - reporting.
     * ReportingService computes every stat card/chart/table grouping in Java (streams) over this
     * one filtered fetch rather than N separately-filtered hand-rolled JPQL group-by queries, a
     * pragmatic choice given this org's data volumes (see that method's own javadoc). Owner-
     * scoping is identical to {@link #list}, just against a different filter shape.
     */
    @Transactional(readOnly = true)
    public List<Lead> listAllForReports(LeadDashboardFilter filter) {
        UserPrincipal principal = currentUser.get();
        Specification<Lead> spec = Specification
                .where(LeadSpecifications.hasStatus(filter.status()))
                .and(LeadSpecifications.hasInterestLevel(filter.interestLevelId()))
                .and(LeadSpecifications.hasState(filter.stateId()))
                .and(LeadSpecifications.hasCity(filter.cityId()))
                .and(LeadSpecifications.hasProduct(filter.productId()))
                .and(LeadSpecifications.hasBusinessType(filter.businessTypeId()))
                .and(LeadSpecifications.hasNextFollowupDate(filter.nextFollowupDate()))
                .and(LeadSpecifications.hasExpectedCloseDate(filter.expectedCloseDate()))
                .and(LeadSpecifications.createdBetween(filter.dateFrom(), filter.dateTo()));

        if (principal.getRole() == Role.EMPLOYEE) {
            Set<UUID> subordinateIds = employeeHierarchyService
                    .getTeamVisibilityScope(principal.getOrganizationId(), principal.getEmployeeId());
            if (subordinateIds.isEmpty()) {
                spec = spec.and(LeadSpecifications.hasOwner(principal.getEmployeeId()));
            } else {
                Set<UUID> teamScope = new HashSet<>(subordinateIds);
                teamScope.add(principal.getEmployeeId());
                if (filter.ownerId() != null && teamScope.contains(filter.ownerId())) {
                    spec = spec.and(LeadSpecifications.hasOwner(filter.ownerId()));
                } else {
                    spec = spec.and(LeadSpecifications.hasOwnerIn(teamScope));
                }
            }
        } else {
            spec = spec.and(LeadSpecifications.hasOwner(filter.ownerId()));
        }

        return leadRepository.findAll(spec);
    }

    /**
     * ADMIN can fetch any lead in their org; EMPLOYEE gets a NotFoundException (never a 403)
     * for a colleague's lead unless TEAM_VISIBILITY is entitled and the lead's owner is in
     * their subordinate chain - same information-hiding principle already established for
     * cross-tenant reads elsewhere, so "not yours" is indistinguishable from "doesn't exist".
     */
    @Transactional(readOnly = true, noRollbackFor = NotFoundException.class)
    public Lead getById(UUID id) {
        return loadForCurrentUser(id, true);
    }

    @Transactional(readOnly = true)
    public List<Lead> checkDuplicates(String contactNo, String companyName) {
        return leadRepository.findByContactNoOrCompanyNameIgnoreCase(contactNo, companyName);
    }

    private Lead loadForCurrentUser(UUID id) {
        return loadForCurrentUser(id, false);
    }

    /**
     * @param allowTeamVisibility only true for read paths (getById) - TEAM_VISIBILITY is a
     *                            READ-only grant, not an edit right, so update()/updateStatus()
     *                            always pass false and a manager still can't modify a
     *                            subordinate's lead through this feature alone (reassign() is
     *                            ADMIN-only regardless, see its own comment).
     */
    private Lead loadForCurrentUser(UUID id, boolean allowTeamVisibility) {
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Lead not found: " + id));
        UserPrincipal principal = currentUser.get();
        if (principal.getRole() == Role.EMPLOYEE && !lead.getOwnerId().equals(principal.getEmployeeId())) {
            boolean withinTeamScope = allowTeamVisibility && employeeHierarchyService
                    .getTeamVisibilityScope(principal.getOrganizationId(), principal.getEmployeeId())
                    .contains(lead.getOwnerId());
            if (!withinTeamScope) {
                throw new NotFoundException("Lead not found: " + id);
            }
        }
        return lead;
    }

    /**
     * create()'s counterpart to update()'s per-field applyCreatableField calls - validates
     * every id/other creatable pair up front (throwing on "both set" or an invalid id) before
     * any part of the Lead is built. cityId/cityOther is cross-checked against stateId (see
     * MasterDataService#validateReference's 4-arg overload) since both are known from this
     * single request.
     */
    private void validateCreatableReferences(UUID industryId, String industryOther,
                                              UUID businessTypeId, String businessTypeOther,
                                              UUID leadSourceId, String leadSourceOther,
                                              UUID designationId, String designationOther,
                                              UUID stateId, String stateOther,
                                              UUID cityId, String cityOther,
                                              UUID interestLevelId, String interestLevelOther,
                                              Set<UUID> productIds) {
        masterDataService.validateCreatableField(industryId, industryOther, MasterType.INDUSTRY,
                "industryId", "industryOther");
        masterDataService.validateCreatableField(businessTypeId, businessTypeOther, MasterType.BUSINESS_TYPE,
                "businessTypeId", "businessTypeOther");
        masterDataService.validateCreatableField(leadSourceId, leadSourceOther, MasterType.LEAD_SOURCE,
                "leadSourceId", "leadSourceOther");
        masterDataService.validateCreatableField(designationId, designationOther, MasterType.DESIGNATION,
                "designationId", "designationOther");
        masterDataService.validateCreatableField(stateId, stateOther, MasterType.STATE,
                "stateId", "stateOther");
        masterDataService.validateCreatableField(cityId, cityOther, MasterType.CITY,
                "cityId", "cityOther", stateId);
        masterDataService.validateCreatableField(interestLevelId, interestLevelOther, MasterType.INTEREST_LEVEL,
                "interestLevelId", "interestLevelOther");
        if (productIds != null) {
            for (UUID productId : productIds) {
                masterDataService.validateReference(productId, MasterType.PRODUCT, "productIds");
            }
        }
    }

    /**
     * Throws when neither {@code id} nor {@code other} is supplied - used for the three fields
     * (industryId, leadSourceId, cityId) that were previously {@code @NotNull} on
     * LeadCreateRequest and are now "required unless the free-text fallback is used instead".
     */
    private void requireProvided(UUID id, String other, String fieldName) {
        if (id == null && (other == null || other.isBlank())) {
            throw new InvalidReferenceException(fieldName,
                    fieldName + " (or its free-text alternative) is required");
        }
    }

    /**
     * update()'s per-field handling of a creatable id/other pair: validates the pair (both set
     * is rejected; a supplied id is checked against master data, optionally cross-checked
     * against parentId), then - only if at least one of the pair was actually supplied on this
     * request - applies whichever one won and explicitly clears the other, so a later update
     * touching only one member of the pair never leaves a stale value on the other sitting
     * alongside it.
     */
    private void applyCreatableField(UUID id, String other, MasterType type, String fieldName, String otherFieldName,
                                      UUID parentId, java.util.function.Consumer<UUID> idSetter,
                                      java.util.function.Consumer<String> otherSetter) {
        if (id == null && other == null) {
            return;
        }
        masterDataService.validateCreatableField(id, other, type, fieldName, otherFieldName, parentId);
        if (id != null) {
            idSetter.accept(id);
            otherSetter.accept(null);
        } else {
            otherSetter.accept(other);
            idSetter.accept(null);
        }
    }

    /** Small hand-built JSON payload for the LEAD_REASSIGNED notification - see reassign(). */
    /** reassignedByName is best-effort (omitted if the acting employee couldn't be resolved,
     * which shouldn't happen in practice) so the notification message can say "Priya Sharma
     * reassigned you the lead ..." instead of just naming the lead. */
    private String buildReassignmentPayload(Lead lead, String reassignedByName) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("leadId", lead.getId().toString());
            payload.put("companyName", lead.getCompanyName());
            if (reassignedByName != null) {
                payload.put("reassignedByName", reassignedByName);
            }
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize LEAD_REASSIGNED notification payload", e);
        }
    }
}
