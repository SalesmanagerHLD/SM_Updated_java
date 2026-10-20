package com.salesmanager.crm.leadattachment;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadAttachmentRepository extends JpaRepository<LeadAttachment, UUID> {

    List<LeadAttachment> findByLeadIdOrderByCreatedAtDesc(UUID leadId);

    List<LeadAttachment> findByPoIdOrderByCreatedAtDesc(UUID poId);
}
