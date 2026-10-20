package com.salesmanager.crm.visit;

import com.salesmanager.crm.activity.ActivityLogService;
import com.salesmanager.crm.activity.ActivityType;
import com.salesmanager.crm.calendar.VisitCalendarSyncEvent;
import com.salesmanager.crm.common.NotFoundException;
import com.salesmanager.crm.common.event.FollowUpScheduledEvent;
import com.salesmanager.crm.employee.EmployeeHierarchyService;
import com.salesmanager.crm.employee.Role;
import com.salesmanager.crm.lead.Lead;
import com.salesmanager.crm.lead.LeadRepository;
import com.salesmanager.crm.masterdata.InvalidReferenceException;
import com.salesmanager.crm.masterdata.MasterDataService;
import com.salesmanager.crm.masterdata.MasterType;
import com.salesmanager.crm.security.CurrentUser;
import com.salesmanager.crm.security.UserPrincipal;
import com.salesmanager.crm.visit.dto.VisitCreateRequest;
import com.salesmanager.crm.visit.dto.VisitStatusUpdateRequest;
import com.salesmanager.crm.visit.dto.VisitUpdateRequest;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates Visit CRUD, the "carry forward a snapshot + sync it back onto the parent
 * Lead" pre-fill behavior, and the ownership-via-parent-lead visibility rule. Follows the
 * same TenantFilter-shared-transaction conventions as LeadService/EmployeeService (see their
 * class/method comments for the full "noRollbackFor is essential, not cosmetic" and
 * "saveAndFlush, not save" rationale - not repeated at every method here).
 */
@Service
public class VisitService {

    private final VisitRepository visitRepository;
    private final LeadRepository leadRepository;
    private final MasterDataService masterDataService;
    private final ActivityLogService activityLogService;
    private final CurrentUser currentUser;
    private final ApplicationEventPublisher eventPublisher;
    private final EmployeeHierarchyService employeeHierarchyService;

    public VisitService(VisitRepository visitRepository,
                         LeadRepository leadRepository,
                         MasterDataService masterDataService,
                         ActivityLogService activityLogService,
                         CurrentUser currentUser,
                         ApplicationEventPublisher eventPublisher,
                         EmployeeHierarchyService employeeHierarchyService) {
        this.visitRepository = visitRepository;
        this.leadRepository = leadRepository;
        this.masterDataService = masterDataService;
        this.activityLogService = activityLogService;
        this.currentUser = currentUser;
        this.eventPublisher = eventPublisher;
        this.employeeHierarchyService = employeeHierarchyService;
    }

    @Transactional(noRollbackFor = {NotFoundException.class, InvalidReferenceException.class})
    public Visit create(VisitCreateRequest request) {
        rejectClientSuppliedMissed(request.status());
        Lead lead = loadLeadForCurrentUser(request.leadId());
        validateCreatableReferences(request.purposeId(), request.purposeOther(),
                request.interestLevelId(), request.interestLevelOther(),
                request.designationId(), request.designationOther(),
                request.stateId(), request.stateOther(),
                request.cityId(), request.cityOther(),
                request.productIds());
        masterDataService.validateInterestReasons(request.interestReasonIds(), request.interestLevelId());

        UUID currentEmployeeId = currentUser.get().getEmployeeId();
        Visit visit = Visit.builder()
                .leadId(request.leadId())
                .visitDate(request.visitDate())
                .scheduledTime(request.scheduledTime())
                .visitType(request.visitType())
                .purposeId(request.purposeId())
                .purposeOther(request.purposeOther())
                .interestLevelId(request.interestLevelId())
                .interestLevelOther(request.interestLevelOther())
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
                .budgetRange(request.budgetRange())
                .decisionMakerIdentified(request.decisionMakerIdentified())
                .objections(request.objections())
                .remarks(request.remarks())
                .nextVisitDate(request.nextVisitDate())
                .status(request.status() != null ? request.status() : VisitStatus.PLANNED)
                .createdBy(currentEmployeeId)
                .productIds(new HashSet<>(request.productIds() != null ? request.productIds() : Set.of()))
                .interestReasonIds(new HashSet<>(
                        request.interestReasonIds() != null ? request.interestReasonIds() : Set.of()))
                .productsOther(request.productsOther())
                .build();

        // Pre-fill sync-back: the Lead stays the single source of truth for contact/
        // qualification data - a Visit only carries forward a snapshot plus adds what's new
        // about that interaction. Only fields actually present on this request are synced.
        syncBackToLead(lead, request.contactPerson(), request.designationId(), request.contactNo(),
                request.email(), request.cityId(), request.address(), request.budgetRange(),
                request.interestLevelId(), request.interestReasonIds());

        // saveAndFlush - see EmployeeService#create's comment re: @CreationTimestamp/@UpdateTimestamp.
        Visit saved = visitRepository.saveAndFlush(visit);

        if (request.nextVisitDate() != null) {
            eventPublisher.publishEvent(new FollowUpScheduledEvent(
                    lead.getId(), lead.getOrganizationId(), request.nextVisitDate(), saved.getPurposeId()));
        }
        eventPublisher.publishEvent(new VisitCalendarSyncEvent(saved.getId(), lead.getOrganizationId()));

        // Recorded on the PARENT lead - lead's ownerId/companyName here are its current values
        // (syncBackToLead above never touches either field), matching "who owns it / what it's
        // called as of this event".
        activityLogService.record(lead.getId(), lead.getOwnerId(), lead.getCompanyName(),
                ActivityType.VISIT_LOGGED, currentEmployeeId, "Visit logged (" + saved.getVisitType() + ")");

        return saved;
    }

    @Transactional(noRollbackFor = {NotFoundException.class, InvalidReferenceException.class})
    public Visit update(UUID id, VisitUpdateRequest request) {
        Visit visit = loadForCurrentUser(id);
        Lead lead = leadRepository.findById(visit.getLeadId())
                .orElseThrow(() -> new NotFoundException("Visit not found: " + id));

        applyCreatableField(request.purposeId(), request.purposeOther(), MasterType.VISIT_PURPOSE,
                "purposeId", "purposeOther", null, visit::setPurposeId, visit::setPurposeOther);
        UUID oldInterestLevelId = visit.getInterestLevelId();
        applyCreatableField(request.interestLevelId(), request.interestLevelOther(), MasterType.INTEREST_LEVEL,
                "interestLevelId", "interestLevelOther", null, visit::setInterestLevelId, visit::setInterestLevelOther);
        applyCreatableField(request.designationId(), request.designationOther(), MasterType.DESIGNATION,
                "designationId", "designationOther", null, visit::setDesignationId, visit::setDesignationOther);
        applyCreatableField(request.stateId(), request.stateOther(), MasterType.STATE,
                "stateId", "stateOther", null, visit::setStateId, visit::setStateOther);
        // Cross-checks cityId's parent against stateId when both are supplied on this
        // request - see MasterDataService#validateReference's 4-arg overload javadoc.
        applyCreatableField(request.cityId(), request.cityOther(), MasterType.CITY,
                "cityId", "cityOther", request.stateId(), visit::setCityId, visit::setCityOther);
        if (request.productIds() != null) {
            for (UUID productId : request.productIds()) {
                masterDataService.validateReference(productId, MasterType.PRODUCT, "productIds");
            }
        }

        // Rescheduling a MISSED visit (a new date and/or time) puts it back on the calendar -
        // flip it back to PLANNED so it reappears in "due today"/upcoming views instead of
        // staying permanently excluded from them. COMPLETED is deliberately left untouched
        // here - it's a terminal state set only via updateStatus(); correcting an incidental
        // date/time detail on an already-completed visit shouldn't silently un-complete it.
        boolean isReschedule = request.visitDate() != null || request.scheduledTime() != null;
        if (isReschedule && visit.getStatus() == VisitStatus.MISSED) {
            visit.setStatus(VisitStatus.PLANNED);
        }

        if (request.visitDate() != null) {
            visit.setVisitDate(request.visitDate());
        }
        if (request.visitType() != null) {
            visit.setVisitType(request.visitType());
        }
        if (request.scheduledTime() != null) {
            visit.setScheduledTime(request.scheduledTime());
        }
        if (request.contactPerson() != null) {
            visit.setContactPerson(request.contactPerson());
        }
        if (request.contactNo() != null) {
            visit.setContactNo(request.contactNo());
        }
        if (request.email() != null) {
            visit.setEmail(request.email());
        }
        if (request.address() != null) {
            visit.setAddress(request.address());
        }
        if (request.requirements() != null) {
            visit.setRequirements(request.requirements());
        }
        if (request.productIds() != null) {
            visit.setProductIds(new HashSet<>(request.productIds()));
        }
        if (request.interestReasonIds() != null) {
            masterDataService.validateInterestReasons(request.interestReasonIds(), visit.getInterestLevelId());
            visit.setInterestReasonIds(new HashSet<>(request.interestReasonIds()));
        } else if (!java.util.Objects.equals(oldInterestLevelId, visit.getInterestLevelId())) {
            // A different level with no reasons sent: the old level's reasons no longer apply.
            visit.getInterestReasonIds().clear();
        }
        if (request.productsOther() != null) {
            visit.setProductsOther(request.productsOther());
        }
        if (request.budgetRange() != null) {
            visit.setBudgetRange(request.budgetRange());
        }
        if (request.decisionMakerIdentified() != null) {
            visit.setDecisionMakerIdentified(request.decisionMakerIdentified());
        }
        if (request.objections() != null) {
            visit.setObjections(request.objections());
        }
        if (request.remarks() != null) {
            visit.setRemarks(request.remarks());
        }

        // Capture the PRE-update value before applying the change, so the "new, different,
        // non-null value" comparison below never fires on an unrelated resave with the same date.
        LocalDate oldNextVisitDate = visit.getNextVisitDate();
        if (request.nextVisitDate() != null) {
            visit.setNextVisitDate(request.nextVisitDate());
        }

        syncBackToLead(lead, request.contactPerson(), request.designationId(), request.contactNo(),
                request.email(), request.cityId(), request.address(), request.budgetRange(),
                request.interestLevelId(), request.interestReasonIds());

        // saveAndFlush - see EmployeeService#create's comment re: @CreationTimestamp/@UpdateTimestamp.
        Visit saved = visitRepository.saveAndFlush(visit);

        boolean nextVisitDateChangedToNewValue = request.nextVisitDate() != null
                && !request.nextVisitDate().equals(oldNextVisitDate);
        if (nextVisitDateChangedToNewValue) {
            eventPublisher.publishEvent(new FollowUpScheduledEvent(
                    lead.getId(), lead.getOrganizationId(), request.nextVisitDate(), saved.getPurposeId()));
        }
        eventPublisher.publishEvent(new VisitCalendarSyncEvent(saved.getId(), lead.getOrganizationId()));

        return saved;
    }

    /**
     * The "edit-in-place Planned->Completed" transition. PLANNED<->COMPLETED move freely
     * (including MISSED->COMPLETED - "it turned out the visit did happen after all"); a
     * client-supplied MISSED is rejected - only a future scheduled job may set that.
     */
    @Transactional(noRollbackFor = {NotFoundException.class, InvalidReferenceException.class})
    public Visit updateStatus(UUID id, VisitStatusUpdateRequest request) {
        rejectClientSuppliedMissed(request.status());
        Visit visit = loadForCurrentUser(id);
        visit.setStatus(request.status());
        // saveAndFlush - see EmployeeService#create's comment re: @CreationTimestamp/@UpdateTimestamp.
        Visit saved = visitRepository.saveAndFlush(visit);

        // Only on a transition TO Completed - not on every status call (e.g. PLANNED is never
        // logged as its own activity entry here).
        if (request.status() == VisitStatus.COMPLETED) {
            Lead lead = leadRepository.findById(saved.getLeadId())
                    .orElseThrow(() -> new NotFoundException("Visit not found: " + id));
            activityLogService.record(lead.getId(), lead.getOwnerId(), lead.getCompanyName(),
                    ActivityType.VISIT_COMPLETED, currentUser.get().getEmployeeId(), "Visit marked completed");
        }

        return saved;
    }

    @Transactional(readOnly = true)
    public Page<Visit> list(VisitFilter filter, Pageable pageable) {
        UserPrincipal principal = currentUser.get();

        Specification<Visit> spec = Specification
                .where(VisitSpecifications.hasLeadId(filter.leadId()))
                .and(VisitSpecifications.hasStatus(filter.status()))
                .and(VisitSpecifications.visitDateFrom(filter.dateFrom()))
                .and(VisitSpecifications.visitDateTo(filter.dateTo()));

        // EMPLOYEE visibility rule: an EMPLOYEE sees visits only for leads owned within their
        // visible scope, regardless of who created the visit record itself - same shape as
        // LeadService#list's ownerId forcing, but scoped through the PARENT lead's owner since
        // Visit itself has no ownerId of its own. TEAM_VISIBILITY (see
        // EmployeeHierarchyService#getTeamVisibilityScope) expands that scope from "just their
        // own leads" to "themself + every subordinate's leads, at any depth".
        if (principal.getRole() == Role.EMPLOYEE) {
            Set<UUID> subordinateIds = employeeHierarchyService
                    .getTeamVisibilityScope(principal.getOrganizationId(), principal.getEmployeeId());
            Set<UUID> visibleOwnerIds;
            if (subordinateIds.isEmpty()) {
                visibleOwnerIds = Set.of(principal.getEmployeeId());
            } else {
                visibleOwnerIds = new HashSet<>(subordinateIds);
                visibleOwnerIds.add(principal.getEmployeeId());
            }
            Set<UUID> ownedLeadIds = leadRepository.findByOwnerIdIn(visibleOwnerIds).stream()
                    .map(Lead::getId)
                    .collect(Collectors.toSet());
            spec = spec.and(VisitSpecifications.hasLeadIdIn(ownedLeadIds));
        }

        return visitRepository.findAll(spec, pageable);
    }

    /**
     * ADMIN can fetch any visit in their org; EMPLOYEE gets a NotFoundException (never a
     * 403) for a visit under a colleague's lead unless TEAM_VISIBILITY is entitled and that
     * lead's owner is in their subordinate chain - same information-hiding principle as
     * LeadService#getById.
     */
    @Transactional(readOnly = true, noRollbackFor = NotFoundException.class)
    public Visit getById(UUID id) {
        return loadForCurrentUser(id, true);
    }

    /**
     * Always scoped to the caller's OWN leads regardless of role - a personal daily agenda,
     * not an org-wide report (even an ADMIN only sees their own here; ADMINs use the full
     * filterable list() for org-wide views).
     */
    @Transactional(readOnly = true)
    public List<Visit> getTodaysFollowUps() {
        UUID employeeId = currentUser.get().getEmployeeId();
        Set<UUID> ownedLeadIds = leadRepository.findByOwnerId(employeeId).stream()
                .map(Lead::getId)
                .collect(Collectors.toSet());
        if (ownedLeadIds.isEmpty()) {
            return List.of();
        }

        Sort sort = Sort.by(Sort.Direction.ASC, "visitDate").and(Sort.by(Sort.Direction.ASC, "scheduledTime"));
        return visitRepository.findByVisitDateLessThanEqualAndStatus(LocalDate.now(), VisitStatus.PLANNED, sort)
                .stream()
                .filter(visit -> ownedLeadIds.contains(visit.getLeadId()))
                .toList();
    }

    /**
     * Non-blocking same-day-visit advisory check (GET /visits/same-day) - read-only, reuses
     * the exact same owner-scoped visibility rule as create()'s loadLeadForCurrentUser (an
     * EMPLOYEE can only check dates for leads they own; a colleague's lead reports "Lead not
     * found", not 403 - same info-hiding principle as every other ownership check in this
     * service). Purely advisory: this does NOT block or alter VisitService#create's own
     * behavior in any way - a caller uses this result to decide whether to show a warning
     * before still calling the normal create endpoint regardless of choice.
     */
    @Transactional(readOnly = true, noRollbackFor = NotFoundException.class)
    public List<Visit> checkSameDay(UUID leadId, LocalDate visitDate) {
        loadLeadForCurrentUser(leadId);
        return visitRepository.findByLeadIdAndVisitDate(leadId, visitDate);
    }

    private void rejectClientSuppliedMissed(VisitStatus status) {
        if (status == VisitStatus.MISSED) {
            throw new InvalidReferenceException("status",
                    "status MISSED cannot be set directly - it is set automatically by a scheduled process");
        }
    }

    /** Same ownership rule as LeadService#loadForCurrentUser, replicated here for Visit's create(). */
    private Lead loadLeadForCurrentUser(UUID leadId) {
        Lead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new NotFoundException("Lead not found: " + leadId));
        UserPrincipal principal = currentUser.get();
        if (principal.getRole() == Role.EMPLOYEE && !lead.getOwnerId().equals(principal.getEmployeeId())) {
            throw new NotFoundException("Lead not found: " + leadId);
        }
        return lead;
    }

    /**
     * Loads a Visit by id and enforces ownership via its PARENT lead's owner. Reports
     * "Visit not found" (not "Lead not found") on a visibility failure, since the caller
     * asked for a specific visit id - info-hiding is scoped to the resource actually
     * requested.
     */
    private Visit loadForCurrentUser(UUID id) {
        return loadForCurrentUser(id, false);
    }

    /**
     * @param allowTeamVisibility only true for read paths (getById) - TEAM_VISIBILITY is a
     *                            READ-only grant, not an edit right, same rule and rationale as
     *                            LeadService#loadForCurrentUser's overload.
     */
    private Visit loadForCurrentUser(UUID id, boolean allowTeamVisibility) {
        Visit visit = visitRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Visit not found: " + id));
        Lead lead = leadRepository.findById(visit.getLeadId())
                .orElseThrow(() -> new NotFoundException("Visit not found: " + id));
        UserPrincipal principal = currentUser.get();
        if (principal.getRole() == Role.EMPLOYEE && !lead.getOwnerId().equals(principal.getEmployeeId())) {
            boolean withinTeamScope = allowTeamVisibility && employeeHierarchyService
                    .getTeamVisibilityScope(principal.getOrganizationId(), principal.getEmployeeId())
                    .contains(lead.getOwnerId());
            if (!withinTeamScope) {
                throw new NotFoundException("Visit not found: " + id);
            }
        }
        return visit;
    }

    /**
     * create()'s counterpart to update()'s per-field applyCreatableField calls - validates
     * every id/other creatable pair up front (throwing on "both set" or an invalid id) before
     * any part of the Visit is built. cityId/cityOther is cross-checked against stateId (see
     * MasterDataService#validateReference's 4-arg overload) since both are known from this
     * single request.
     */
    private void validateCreatableReferences(UUID purposeId, String purposeOther,
                                              UUID interestLevelId, String interestLevelOther,
                                              UUID designationId, String designationOther,
                                              UUID stateId, String stateOther,
                                              UUID cityId, String cityOther,
                                              Set<UUID> productIds) {
        masterDataService.validateCreatableField(purposeId, purposeOther, MasterType.VISIT_PURPOSE,
                "purposeId", "purposeOther");
        masterDataService.validateCreatableField(interestLevelId, interestLevelOther, MasterType.INTEREST_LEVEL,
                "interestLevelId", "interestLevelOther");
        masterDataService.validateCreatableField(designationId, designationOther, MasterType.DESIGNATION,
                "designationId", "designationOther");
        masterDataService.validateCreatableField(stateId, stateOther, MasterType.STATE,
                "stateId", "stateOther");
        masterDataService.validateCreatableField(cityId, cityOther, MasterType.CITY,
                "cityId", "cityOther", stateId);
        if (productIds != null) {
            for (UUID productId : productIds) {
                masterDataService.validateReference(productId, MasterType.PRODUCT, "productIds");
            }
        }
    }

    /**
     * update()'s per-field handling of a creatable id/other pair - see LeadService's identical
     * helper javadoc for the full rationale (validates the pair, then applies whichever member
     * was supplied and explicitly clears the other).
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

    /**
     * Whenever contactPerson/designationId/contactNo/email/cityId/address/budgetRange/
     * interestLevelId are supplied (non-null) on a Visit create/update request, ALSO update
     * those same fields on the parent Lead - the Lead stays the single source of truth for
     * contact/qualification data. Only fields actually present in the request are touched;
     * fields the request simply didn't mention are left as-is on the Lead.
     */
    private void syncBackToLead(Lead lead, String contactPerson, UUID designationId, String contactNo,
                                String email, UUID cityId, String address, String budgetRange,
                                UUID interestLevelId, Set<UUID> interestReasonIds) {
        boolean changed = false;
        if (contactPerson != null) {
            lead.setContactPerson(contactPerson);
            changed = true;
        }
        if (designationId != null) {
            lead.setDesignationId(designationId);
            changed = true;
        }
        if (contactNo != null) {
            lead.setContactNo(contactNo);
            changed = true;
        }
        if (email != null) {
            lead.setEmail(email);
            changed = true;
        }
        if (cityId != null) {
            lead.setCityId(cityId);
            changed = true;
        }
        if (address != null) {
            lead.setAddress(address);
            changed = true;
        }
        if (budgetRange != null) {
            lead.setBudgetRange(budgetRange);
            changed = true;
        }
        if (interestLevelId != null) {
            boolean levelChanged = !interestLevelId.equals(lead.getInterestLevelId());
            lead.setInterestLevelId(interestLevelId);
            // The lead mirrors the visit's reasons; a different level with no reasons sent means
            // the old level's reasons no longer apply.
            if (interestReasonIds != null) {
                lead.setInterestReasonIds(new HashSet<>(interestReasonIds));
            } else if (levelChanged) {
                lead.getInterestReasonIds().clear();
            }
            changed = true;
        }
        if (changed) {
            // saveAndFlush - see EmployeeService#create's comment re: @CreationTimestamp/@UpdateTimestamp.
            leadRepository.saveAndFlush(lead);
        }
    }
}
