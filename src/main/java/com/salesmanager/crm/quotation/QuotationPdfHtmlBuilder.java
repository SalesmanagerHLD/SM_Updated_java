package com.salesmanager.crm.quotation;

import com.salesmanager.crm.invoicing.dto.BillingProfileResponse;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Builds the fixed HTML+CSS layout rendered to PDF by {@link QuotationPdfService} - same
 * structure/escaping discipline as invoicing.InvoicePdfHtmlBuilder, extended with an HSN/SAC +
 * Discount column and a CGST/SGST tax-column pair, plus Quotation Notes/Terms &amp; Conditions/
 * attachments-list sections (internalNote is deliberately never rendered here - team-only).
 */
@Component
class QuotationPdfHtmlBuilder {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    String build(Quotation quotation, List<QuotationLineItem> lineItems, List<QuotationAttachment> attachments,
                 BillingProfileResponse billingProfile, String logoDataUri) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><style>")
                .append(css())
                .append("</style></head><body>");

        html.append("<div class=\"header\">").append("<div class=\"seller\">");
        if (isNotBlank(logoDataUri)) {
            html.append("<img class=\"logo\" src=\"").append(logoDataUri).append("\"/>");
        }
        html.append("<div class=\"business-name\">").append(esc(billingProfile.businessName())).append("</div>");
        if (isNotBlank(billingProfile.billingAddress())) {
            html.append("<div>").append(esc(billingProfile.billingAddress())).append("</div>");
        }
        if (isNotBlank(billingProfile.billingGstin())) {
            html.append("<div>GSTIN: ").append(esc(billingProfile.billingGstin())).append("</div>");
        }
        if (isNotBlank(billingProfile.billingPhone())) {
            html.append("<div>Phone: ").append(esc(billingProfile.billingPhone())).append("</div>");
        }
        html.append("</div>")
                .append("<div class=\"invoice-meta\">")
                .append("<div class=\"invoice-title\">QUOTATION</div>")
                .append("<div>Quotation #: ").append(esc(quotation.getQuotationNumber())).append("</div>")
                .append("<div>Date: ").append(esc(quotation.getQuotationDate().format(DATE_FORMAT))).append("</div>");
        if (quotation.getValidTillDate() != null) {
            html.append("<div>Valid Till: ").append(esc(quotation.getValidTillDate().format(DATE_FORMAT))).append("</div>");
        }
        html.append("</div>").append("</div>");

        html.append("<div class=\"bill-to\"><div class=\"section-label\">Customer</div>")
                .append("<div>").append(esc(quotation.getCustomerName())).append("</div>");
        if (isNotBlank(quotation.getCustomerContactPerson())) {
            html.append("<div>").append(esc(quotation.getCustomerContactPerson())).append("</div>");
        }
        if (isNotBlank(quotation.getCustomerPhone())) {
            html.append("<div>").append(esc(quotation.getCustomerPhone())).append("</div>");
        }
        if (isNotBlank(quotation.getCustomerEmail())) {
            html.append("<div>").append(esc(quotation.getCustomerEmail())).append("</div>");
        }
        if (isNotBlank(quotation.getCustomerBillingAddress())) {
            html.append("<div>").append(esc(quotation.getCustomerBillingAddress())).append("</div>");
        }
        if (isNotBlank(quotation.getCustomerGstin())) {
            html.append("<div>GSTIN: ").append(esc(quotation.getCustomerGstin())).append("</div>");
        }
        html.append("</div>");

        boolean interState = quotation.getIgstTotal().signum() > 0;
        html.append("<table class=\"lines\"><thead><tr>")
                .append("<th>Description</th><th>HSN/SAC</th><th class=\"num\">Qty</th>")
                .append("<th class=\"num\">Unit Price</th><th class=\"num\">Discount %</th>")
                .append(interState ? "<th class=\"num\">IGST</th>" : "<th class=\"num\">CGST</th><th class=\"num\">SGST</th>")
                .append("<th class=\"num\">Total</th>")
                .append("</tr></thead><tbody>");
        for (QuotationLineItem line : lineItems) {
            html.append("<tr>")
                    .append("<td>").append(esc(line.getDescription())).append("</td>")
                    .append("<td>").append(esc(line.getHsnSac())).append("</td>")
                    .append("<td class=\"num\">").append(line.getQuantity()).append("</td>")
                    .append("<td class=\"num\">").append(line.getUnitPrice()).append("</td>")
                    .append("<td class=\"num\">").append(line.getDiscountPercent()).append("</td>")
                    .append(interState
                            ? "<td class=\"num\">" + line.getLineIgstAmount() + "</td>"
                            : "<td class=\"num\">" + line.getLineCgstAmount() + "</td><td class=\"num\">"
                                    + line.getLineSgstAmount() + "</td>")
                    .append("<td class=\"num\">").append(line.getLineTotal()).append("</td>")
                    .append("</tr>");
        }
        html.append("</tbody></table>");

        html.append("<div class=\"totals\">")
                .append("<div>Subtotal: ").append(quotation.getSubtotal()).append("</div>")
                .append("<div>Discount: ").append(quotation.getDiscountTotal()).append("</div>")
                .append("<div>Taxable Amount: ").append(quotation.getTaxableAmount()).append("</div>")
                .append(interState
                        ? "<div>IGST: " + quotation.getIgstTotal() + "</div>"
                        : "<div>CGST: " + quotation.getCgstTotal() + "</div><div>SGST: " + quotation.getSgstTotal() + "</div>")
                .append("<div class=\"grand-total\">Grand Total: ").append(quotation.getGrandTotal()).append("</div>")
                .append("</div>");

        if (isNotBlank(quotation.getQuotationNotes())) {
            html.append("<div class=\"notes\"><div class=\"section-label\">Notes</div><div>")
                    .append(esc(quotation.getQuotationNotes())).append("</div></div>");
        }

        if (isNotBlank(quotation.getTermsAndConditions())) {
            html.append("<div class=\"notes\"><div class=\"section-label\">Terms &amp; Conditions</div><div>")
                    .append(esc(quotation.getTermsAndConditions())).append("</div></div>");
        }

        if (!attachments.isEmpty()) {
            html.append("<div class=\"notes\"><div class=\"section-label\">Attachments</div>");
            for (QuotationAttachment attachment : attachments) {
                html.append("<div>").append(esc(attachment.getFileName())).append("</div>");
            }
            html.append("</div>");
        }

        html.append("</body></html>");
        return html.toString();
    }

    private static String css() {
        return "body { font-family: Helvetica, Arial, sans-serif; font-size: 11pt; color: #222; } "
                + ".header { display: flex; justify-content: space-between; margin-bottom: 24px; } "
                + ".logo { max-height: 60px; max-width: 220px; display: block; margin-bottom: 8px; } "
                + ".business-name { font-size: 14pt; font-weight: bold; } "
                + ".invoice-meta { text-align: right; } "
                + ".invoice-title { font-size: 18pt; font-weight: bold; letter-spacing: 2px; } "
                + ".section-label { font-weight: bold; text-transform: uppercase; font-size: 9pt; "
                + "color: #666; margin-bottom: 4px; } "
                + ".bill-to { margin-bottom: 20px; } "
                + "table.lines { width: 100%; border-collapse: collapse; margin-bottom: 16px; } "
                + "table.lines th, table.lines td { border-bottom: 1px solid #ddd; padding: 6px 8px; "
                + "text-align: left; } "
                + "table.lines th.num, table.lines td.num { text-align: right; } "
                + ".totals { text-align: right; } "
                + ".totals .grand-total { font-size: 13pt; font-weight: bold; margin-top: 6px; } "
                + ".notes { margin-top: 20px; }";
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String esc(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }
}
