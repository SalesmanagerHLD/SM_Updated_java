package com.salesmanager.crm.leadattachment;

import com.salesmanager.crm.common.NotFoundException;
import com.salesmanager.crm.lead.LeadService;
import com.salesmanager.crm.security.CurrentUser;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Orchestrates Lead attachment upload/list/download/delete. Deliberately delegates every
 * visibility check to LeadService#getById rather than re-implementing owner/team-scoping here -
 * an attachment is only ever reachable through its parent Lead, so "can this caller see the
 * lead" is exactly "can this caller see its attachments", same information-hiding rule (a
 * colleague's attachment 404s, never 403s) as every other Lead sub-resource.
 */
@Service
public class LeadAttachmentService {

    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp",
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/plain");

    private final LeadAttachmentRepository leadAttachmentRepository;
    private final AttachmentStorageService storageService;
    private final LeadService leadService;
    private final CurrentUser currentUser;

    public LeadAttachmentService(LeadAttachmentRepository leadAttachmentRepository,
                                  AttachmentStorageService storageService,
                                  LeadService leadService,
                                  CurrentUser currentUser) {
        this.leadAttachmentRepository = leadAttachmentRepository;
        this.storageService = storageService;
        this.leadService = leadService;
        this.currentUser = currentUser;
    }

    @Transactional(noRollbackFor = {NotFoundException.class, InvalidAttachmentException.class})
    public LeadAttachment upload(UUID leadId, MultipartFile file) {
        return upload(leadId, null, file);
    }

    /** Same as {@link #upload(UUID, MultipartFile)} but links the file to a purchase order too. */
    @Transactional(noRollbackFor = {NotFoundException.class, InvalidAttachmentException.class})
    public LeadAttachment upload(UUID leadId, UUID poId, MultipartFile file) {
        leadService.getById(leadId);
        validate(file);

        UUID organizationId = currentUser.get().getOrganizationId();
        String storageKey;
        try {
            storageKey = storageService.store(organizationId, leadId, file.getOriginalFilename(),
                    file.getInputStream());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store lead attachment", e);
        }

        LeadAttachment attachment = LeadAttachment.builder()
                .leadId(leadId)
                .fileName(file.getOriginalFilename() != null ? file.getOriginalFilename() : "attachment")
                .storageKey(storageKey)
                .contentType(file.getContentType())
                .fileSize(file.getSize())
                .uploadedBy(currentUser.get().getEmployeeId())
                .poId(poId)
                .build();
        // saveAndFlush - see EmployeeService#create's comment re: @CreationTimestamp/@UpdateTimestamp.
        return leadAttachmentRepository.saveAndFlush(attachment);
    }

    @Transactional(readOnly = true, noRollbackFor = NotFoundException.class)
    public List<LeadAttachment> list(UUID leadId) {
        leadService.getById(leadId);
        return leadAttachmentRepository.findByLeadIdOrderByCreatedAtDesc(leadId);
    }

    @Transactional(readOnly = true, noRollbackFor = NotFoundException.class)
    public LoadedAttachment download(UUID attachmentId) {
        LeadAttachment attachment = leadAttachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("Attachment not found: " + attachmentId));
        leadService.getById(attachment.getLeadId());
        try {
            byte[] content = storageService.load(attachment.getStorageKey());
            return new LoadedAttachment(attachment.getFileName(), attachment.getContentType(), content);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load lead attachment", e);
        }
    }

    @Transactional(noRollbackFor = NotFoundException.class)
    public void delete(UUID attachmentId) {
        LeadAttachment attachment = leadAttachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("Attachment not found: " + attachmentId));
        leadService.getById(attachment.getLeadId());
        leadAttachmentRepository.delete(attachment);
        try {
            storageService.delete(attachment.getStorageKey());
        } catch (IOException e) {
            // Best-effort - an orphaned file on disk is a cleanup nuisance, not a correctness
            // problem worth failing the request over: the DB row (the source of truth for what
            // attachments exist) is already gone.
        }
    }

    private void validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new InvalidAttachmentException("Attachment file is empty");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new InvalidAttachmentException("Attachment exceeds the 10MB size limit");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new InvalidAttachmentException(
                    "Unsupported attachment type: " + contentType
                            + " - allowed types are images, PDF, Word/Excel documents, and plain text");
        }
    }

    public record LoadedAttachment(String fileName, String contentType, byte[] content) {
    }
}
