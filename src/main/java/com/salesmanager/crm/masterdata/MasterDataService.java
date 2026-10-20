package com.salesmanager.crm.masterdata;

import com.salesmanager.crm.common.NotFoundException;
import com.salesmanager.crm.masterdata.dto.MasterDataCreateRequest;
import com.salesmanager.crm.masterdata.dto.MasterDataUpdateRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MasterDataService {

    private static final Sort SORT_BY_SORT_ORDER = Sort.by(Sort.Direction.ASC, "sortOrder");

    private final MasterDataRepository masterDataRepository;

    public MasterDataService(MasterDataRepository masterDataRepository) {
        this.masterDataRepository = masterDataRepository;
    }

    /**
     * Active-only by default (what dropdown consumers want); includeInactive=true returns
     * everything (what the admin management screen wants). Always ordered by sortOrder.
     */
    @Transactional(readOnly = true)
    public List<MasterData> list(MasterType type, boolean includeInactive) {
        if (includeInactive) {
            return masterDataRepository.findByType(type, SORT_BY_SORT_ORDER);
        }
        return masterDataRepository.findByTypeAndActive(type, true, SORT_BY_SORT_ORDER);
    }

    // noRollbackFor is essential here, not cosmetic: TenantFilter wraps the ENTIRE request
    // (controller + service calls) in one shared transaction opened before this method runs.
    // Without it, Spring's default rollback rule (any RuntimeException marks the transaction
    // rollback-only) poisons that SHARED transaction even though GlobalExceptionHandler goes
    // on to translate this into a normal 409 response - so when TenantFilter's outer
    // TransactionTemplate later tries to commit (since, from its perspective, the request
    // completed without an uncaught exception), it throws UnexpectedRollbackException, which
    // escapes uncaught AFTER the response has already been committed. Safe to exempt since
    // this exception is always thrown before any write in this method.
    @Transactional(noRollbackFor = DuplicateMasterDataException.class)
    public MasterData create(MasterType type, MasterDataCreateRequest request) {
        if (masterDataRepository.existsByTypeAndCodeIgnoreCase(type, request.code())) {
            throw new DuplicateMasterDataException(
                    "A " + type + " entry with code '" + request.code() + "' already exists");
        }
        MasterData masterData = MasterData.builder()
                .type(type)
                .code(request.code())
                .label(request.label())
                .sortOrder(request.sortOrder() != null ? request.sortOrder() : 0)
                .active(true)
                .parentId(request.parentId())
                .metadata(request.metadata())
                .build();
        // saveAndFlush (not save) is required: @CreationTimestamp/@UpdateTimestamp are only
        // populated in-memory when Hibernate actually flushes (builds the INSERT/UPDATE), not
        // at the moment save()/persist() is called. TenantFilter wraps the whole request in one
        // shared transaction that only commits (and would otherwise only flush) after the
        // response body has already been serialized - so a plain save() here would return
        // createdAt/updatedAt as null to the client even though the DB row itself ends up
        // correct once the outer transaction commits.
        return masterDataRepository.saveAndFlush(masterData);
    }

    // See create()'s comment above re: noRollbackFor and TenantFilter's shared transaction.
    @Transactional(noRollbackFor = NotFoundException.class)
    public MasterData update(MasterType type, UUID id, MasterDataUpdateRequest request) {
        MasterData masterData = loadOfType(type, id);
        masterData.setLabel(request.label());
        masterData.setSortOrder(request.sortOrder());
        masterData.setActive(request.active());
        // saveAndFlush - see create()'s comment above re: @CreationTimestamp/@UpdateTimestamp.
        return masterDataRepository.saveAndFlush(masterData);
    }

    @Transactional(noRollbackFor = NotFoundException.class)
    public MasterData deactivate(MasterType type, UUID id) {
        MasterData masterData = loadOfType(type, id);
        masterData.setActive(false);
        // saveAndFlush - see create()'s comment above re: @CreationTimestamp/@UpdateTimestamp.
        return masterDataRepository.saveAndFlush(masterData);
    }

    /**
     * Confirms {@code id} both exists (in-tenant, via the Hibernate filter/RLS) and has the
     * expected type. Used by EmployeeService to validate designationId/cityId/product ids
     * before an Employee is saved. A null id is treated as "no reference supplied" and
     * silently accepted - these foreign fields are all optional.
     */
    @Transactional(readOnly = true, noRollbackFor = InvalidReferenceException.class)
    public void validateReference(UUID id, MasterType expectedType, String fieldName) {
        validateReference(id, expectedType, fieldName, null);
    }

    /**
     * Overload that additionally cross-checks the referenced row's {@code parent_id} against
     * {@code parentId} - used to validate a CITY reference actually belongs to the STATE the
     * caller also selected (Employee/Lead/Visit's cityId+stateId pairing). A null
     * {@code parentId} skips this extra check entirely, so every existing lone-cityId call
     * site is unaffected; a null {@code id} is (as always) treated as "no reference supplied"
     * and silently accepted regardless of parentId.
     */
    @Transactional(readOnly = true, noRollbackFor = InvalidReferenceException.class)
    public void validateReference(UUID id, MasterType expectedType, String fieldName, UUID parentId) {
        if (id == null) {
            return;
        }
        MasterData masterData = masterDataRepository.findById(id).orElse(null);
        if (masterData == null || masterData.getType() != expectedType) {
            throw new InvalidReferenceException(fieldName,
                    fieldName + " does not reference a valid " + expectedType);
        }
        if (parentId != null && !parentId.equals(masterData.getParentId())) {
            throw new InvalidReferenceException(fieldName,
                    fieldName + " does not belong to the selected state");
        }
    }

    /**
     * Validates the reasons chosen for an interest level: each must be an INTEREST_REASON whose
     * parent is {@code interestLevelId}. Reasons without an interest level (none picked, or a
     * free-text one) are rejected - there is nothing for them to hang off.
     */
    @Transactional(readOnly = true, noRollbackFor = InvalidReferenceException.class)
    public void validateInterestReasons(java.util.Set<UUID> reasonIds, UUID interestLevelId) {
        if (reasonIds == null || reasonIds.isEmpty()) {
            return;
        }
        if (interestLevelId == null) {
            throw new InvalidReferenceException("interestReasonIds",
                    "interestReasonIds can only be set together with an interestLevelId");
        }
        for (UUID reasonId : reasonIds) {
            MasterData reason = masterDataRepository.findById(reasonId).orElse(null);
            if (reason == null || reason.getType() != MasterType.INTEREST_REASON) {
                throw new InvalidReferenceException("interestReasonIds",
                        "interestReasonIds does not reference a valid INTEREST_REASON");
            }
            if (!interestLevelId.equals(reason.getParentId())) {
                throw new InvalidReferenceException("interestReasonIds",
                        "interestReasonIds contains a reason that does not belong to the selected interest level");
            }
        }
    }

    /**
     * Validates a "creatable" field pair used by Lead/Visit capture forms: {@code fieldId} (a
     * real master_data reference) and {@code fieldOther} (operator-typed free text for a value
     * not yet in master data) are mutually exclusive - both non-null is rejected outright. Only
     * {@code fieldId}, when present, is validated against master data (via
     * {@link #validateReference(UUID, MasterType, String)}); a lone {@code fieldOther} needs no
     * master-data validation at all - its length is capped via {@code @Size} on the DTO,
     * matching the backing varchar(255) column.
     */
    @Transactional(readOnly = true, noRollbackFor = InvalidReferenceException.class)
    public void validateCreatableField(UUID id, String other, MasterType expectedType, String fieldName,
                                        String otherFieldName) {
        validateCreatableField(id, other, expectedType, fieldName, otherFieldName, null);
    }

    /**
     * Overload of {@link #validateCreatableField(UUID, String, MasterType, String, String)} that
     * additionally cross-checks {@code id}'s parent (e.g. a cityId's state) via
     * {@link #validateReference(UUID, MasterType, String, UUID)} - used for the cityId/cityOther
     * pair alongside a stateId.
     */
    @Transactional(readOnly = true, noRollbackFor = InvalidReferenceException.class)
    public void validateCreatableField(UUID id, String other, MasterType expectedType, String fieldName,
                                        String otherFieldName, UUID parentId) {
        if (id != null && other != null) {
            throw new InvalidReferenceException(otherFieldName,
                    "Only one of " + fieldName + " or " + otherFieldName + " may be set");
        }
        if (id != null) {
            validateReference(id, expectedType, fieldName, parentId);
        }
    }

    /**
     * Loads by id then verifies the loaded row's type matches the path's type, treating a
     * mismatch as not-found (not a validation error) so cross-type/cross-tenant existence
     * of an id is never leaked.
     */
    private MasterData loadOfType(MasterType type, UUID id) {
        MasterData masterData = masterDataRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Master data not found: " + id));
        if (masterData.getType() != type) {
            throw new NotFoundException("Master data not found: " + id);
        }
        return masterData;
    }
}
