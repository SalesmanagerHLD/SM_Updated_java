package com.salesmanager.crm.invoicing;

import com.salesmanager.crm.invoicing.design.InvoiceDesignSettings;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.math.BigDecimal;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.springframework.stereotype.Service;

/** Thin wrapper around the openhtmltopdf render call - keeps InvoiceController simple and
 * gives this one operation (HTML -> PDF bytes) a single, testable seam. */
@Service
public class InvoicePdfService {

    private final InvoicePdfHtmlBuilder htmlBuilder;

    public InvoicePdfService(InvoicePdfHtmlBuilder htmlBuilder) {
        this.htmlBuilder = htmlBuilder;
    }

    public byte[] renderPdf(Invoice invoice, List<InvoiceLineItem> lineItems, BigDecimal amountPaid,
                             String logoDataUri, InvoiceDesignSettings design) {
        String html = htmlBuilder.build(invoice, lineItems, amountPaid, logoDataUri, design);
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.withHtmlContent(html, null);
            builder.toStream(outputStream);
            builder.run();
            return outputStream.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render invoice PDF for invoice " + invoice.getId(), e);
        }
    }
}
