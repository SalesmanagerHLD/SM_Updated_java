package com.salesmanager.crm.invoicing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.invoicing.design.DocumentType;
import com.salesmanager.crm.invoicing.design.InvoiceDesign;
import com.salesmanager.crm.invoicing.design.InvoiceDesignRepository;
import com.salesmanager.crm.invoicing.design.InvoiceDesignSettings;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads/saves the per-company invoice design. Settings are stored as validated JSON (see
 * {@link InvoiceDesignSettings}); a company with no saved row gets {@link InvoiceDesignSettings#defaults()}. */
@Service
public class InvoiceDesignService {

    private final InvoiceDesignRepository repository;
    private final ObjectMapper objectMapper;

    public InvoiceDesignService(InvoiceDesignRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public record DesignView(InvoiceDesignSettings settings, int version, boolean isDefault) {
    }

    @Transactional(readOnly = true)
    public DesignView get(DocumentType type) {
        return repository.findByDocumentType(type)
                .map(row -> new DesignView(parse(row.getSettings()), row.getVersion(), false))
                .orElseGet(() -> new DesignView(InvoiceDesignSettings.defaults(), 0, true));
    }

    @Transactional
    public DesignView save(DocumentType type, InvoiceDesignSettings settings) {
        InvoiceDesign row = repository.findByDocumentType(type)
                .orElseGet(() -> InvoiceDesign.builder().documentType(type).version(0).build());
        row.setSettings(toJson(settings));
        row.setVersion(row.getVersion() + 1);
        InvoiceDesign saved = repository.saveAndFlush(row);
        return new DesignView(settings, saved.getVersion(), false);
    }

    @Transactional
    public DesignView reset(DocumentType type) {
        Optional<InvoiceDesign> existing = repository.findByDocumentType(type);
        existing.ifPresent(repository::delete);
        repository.flush();
        return new DesignView(InvoiceDesignSettings.defaults(), 0, true);
    }

    /** The JSON copied onto a new invoice so it keeps the design it was issued with. */
    @Transactional(readOnly = true)
    public String currentInvoiceSettingsJson() {
        return toJson(get(DocumentType.INVOICE).settings());
    }

    /** Parses an invoice's stored snapshot; a missing/unreadable one falls back to the default
     * design rather than failing the PDF download. */
    public InvoiceDesignSettings parseOrDefault(String json) {
        if (json == null || json.isBlank()) {
            return InvoiceDesignSettings.defaults();
        }
        try {
            return parse(json);
        } catch (IllegalStateException e) {
            return InvoiceDesignSettings.defaults();
        }
    }

    private InvoiceDesignSettings parse(String json) {
        try {
            return objectMapper.readValue(json, InvoiceDesignSettings.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored invoice design is not valid", e);
        }
    }

    private String toJson(InvoiceDesignSettings settings) {
        try {
            return objectMapper.writeValueAsString(settings);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize invoice design", e);
        }
    }
}
