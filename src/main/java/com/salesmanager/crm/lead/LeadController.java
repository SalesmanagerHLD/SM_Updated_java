package com.salesmanager.crm.lead;

import com.salesmanager.crm.lead.dto.LeadCreateRequest;
import com.salesmanager.crm.lead.dto.LeadDuplicateMatch;
import com.salesmanager.crm.lead.dto.LeadReassignRequest;
import com.salesmanager.crm.lead.dto.LeadResponse;
import com.salesmanager.crm.lead.dto.LeadStatusUpdateRequest;
import com.salesmanager.crm.lead.dto.LeadUpdateRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin controller - all business logic (validation, ownership enforcement, the Lost-lead
 * workflow) lives in LeadService, same layering as EmployeeController/MasterDataController.
 * Every endpoint here is open to both ADMIN and EMPLOYEE (per the Phase 2 spec, both roles
 * can create/view/manage leads) - LeadService itself enforces the owner-scoped visibility
 * rule rather than a controller-level @PreAuthorize.
 */
@RestController
@RequestMapping("/leads")
public class LeadController {

    private final LeadService leadService;

    public LeadController(LeadService leadService) {
        this.leadService = leadService;
    }

    @GetMapping
    public Page<LeadResponse> list(@RequestParam(required = false) LeadStatus status,
                                    @RequestParam(required = false) UUID ownerId,
                                    @RequestParam(required = false) UUID interestLevelId,
                                    @RequestParam(required = false) String search,
                                    @RequestParam(required = false) UUID stateId,
                                    @RequestParam(required = false) UUID cityId,
                                    @RequestParam(required = false) UUID productId,
                                    @RequestParam(required = false) UUID interestReasonId,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                                    Pageable pageable) {
        LeadFilter filter = new LeadFilter(status, ownerId, interestLevelId, search,
                stateId, cityId, productId, dateFrom, dateTo, interestReasonId);
        return leadService.list(filter, pageable).map(LeadResponse::from);
    }

    // Must be declared (or otherwise resolved) ahead of GET /{id} in Spring MVC's route
    // matching for the literal "/duplicates" segment to win over the {id} path variable -
    // Spring MVC's PathPattern matching in fact already prefers the more specific literal
    // match regardless of declaration order, but keeping it here first documents that intent.
    @GetMapping("/duplicates")
    public List<LeadDuplicateMatch> duplicates(@RequestParam(required = false) String contactNo,
                                                @RequestParam(required = false) String companyName) {
        return leadService.checkDuplicates(contactNo, companyName).stream()
                .map(LeadDuplicateMatch::from)
                .toList();
    }

    @GetMapping("/{id}")
    public LeadResponse getById(@PathVariable UUID id) {
        return LeadResponse.from(leadService.getById(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LeadResponse create(@Valid @RequestBody LeadCreateRequest request) {
        return LeadResponse.from(leadService.create(request));
    }

    @PutMapping("/{id}")
    public LeadResponse update(@PathVariable UUID id, @Valid @RequestBody LeadUpdateRequest request) {
        return LeadResponse.from(leadService.update(id, request));
    }

    @PatchMapping("/{id}/status")
    public LeadResponse updateStatus(@PathVariable UUID id, @Valid @RequestBody LeadStatusUpdateRequest request) {
        return LeadResponse.from(leadService.updateStatus(id, request));
    }

    /**
     * ADMIN-only, unlike every other endpoint on this controller - reassigning ownership is
     * an administrative action, not something an owning EMPLOYEE (or anyone else) can do to
     * themselves or a colleague.
     */
    @PatchMapping("/{id}/reassign")
    @PreAuthorize("hasRole('ADMIN')")
    public LeadResponse reassign(@PathVariable UUID id, @Valid @RequestBody LeadReassignRequest request) {
        return LeadResponse.from(leadService.reassign(id, request));
    }
}
