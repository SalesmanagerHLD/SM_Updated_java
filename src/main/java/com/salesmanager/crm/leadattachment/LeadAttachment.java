package com.salesmanager.crm.leadattachment;

import com.salesmanager.crm.tenant.TenantAware;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;

/**
 * A single uploaded file attached to a Lead (business card photo, brochure, PO copy, etc.) -
 * see AttachmentStorageService for where the actual bytes live. Same tenantFilter/@Filter
 * pattern as every other tenant entity (Lead, Employee, MasterData).
 */
@Entity
@Table(name = "lead_attachments")
@Filter(name = "tenantFilter", condition = "organization_id = :tenantId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class LeadAttachment extends TenantAware {

    @Column(name = "lead_id", nullable = false)
    private UUID leadId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    /**
     * Opaque handle into whichever AttachmentStorageService wrote this file - a relative path
     * under LocalFilesystemAttachmentStorageService today, would become an S3 object key if
     * that implementation is swapped in later. Never interpreted directly outside the storage
     * service that produced it.
     */
    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "uploaded_by", nullable = false)
    private UUID uploadedBy;

    /** Set when this file is a customer's purchase-order scan (see purchaseorder.PurchaseOrderService). */
    @Column(name = "po_id")
    private UUID poId;
}
