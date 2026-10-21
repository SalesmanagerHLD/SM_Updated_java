package com.salesmanager.crm.leadattachment.dto;

import com.salesmanager.crm.leadattachment.LeadAttachment;
import java.time.OffsetDateTime;
import java.util.UUID;

public record LeadAttachmentResponse(
        UUID id,
        UUID leadId,
        String fileName,
        String contentType,
        long fileSize,
        UUID uploadedBy,
        UUID poId,
        OffsetDateTime createdAt) {

    public static LeadAttachmentResponse from(LeadAttachment attachment) {
        return new LeadAttachmentResponse(
                attachment.getId(),
                attachment.getLeadId(),
                attachment.getFileName(),
                attachment.getContentType(),
                attachment.getFileSize(),
                attachment.getUploadedBy(),
                attachment.getPoId(),
                attachment.getCreatedAt());
    }
}
