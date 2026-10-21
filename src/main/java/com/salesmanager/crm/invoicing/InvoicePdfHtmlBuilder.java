package com.salesmanager.crm.invoicing;

import com.salesmanager.crm.invoicing.design.InvoiceDesignSettings;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Builds the fixed HTML+CSS layout rendered to PDF by {@link InvoicePdfService}. Centralized
 * here (not scattered string concatenation in the service) specifically so every
 * user-supplied field is escaped in exactly one place - every value below that could contain
 * arbitrary customer/rep-typed text (customer name/address/notes, ad-hoc line descriptions)
 * goes through {@link #esc} before landing in the HTML, or a stray {@code <}/{@code &}/quote
 * would either break openhtmltopdf's strict XHTML parser or be misinterpreted as markup.
 */
@Component
class InvoicePdfHtmlBuilder {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    /** logoDataUri is a full {@code data:image/...;base64,...} string (or null) - built by
     * BillingProfileService#getLogoDataUri so openhtmltopdf renders it inline with no extra
     * network fetch, keeping PDF rendering self-contained and fast. */
    String build(Invoice invoice, List<InvoiceLineItem> lineItems, BigDecimal amountPaid, String logoDataUri,
                 InvoiceDesignSettings design) {
        // The seller block, bank details and header/footer text come from the SNAPSHOT taken when
        // the invoice was issued (never the live billing profile), so editing the profile later
        // never rewrites an old invoice. Only the logo is still read live.
        boolean interState = invoice.getIgstTotal().signum() > 0;
        boolean cancelled = invoice.getStatus() == InvoiceStatus.CANCELLED;
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><style>")
                .append(css(design))
                .append("</style></head><body class=\"preset-").append(design.preset().name().toLowerCase())
                .append("\">");

        // Header and party blocks are two-cell TABLES, not flex rows: openhtmltopdf implements
        // CSS 2.1 only (no flex/grid), so display:flex would stack the cells vertically.
        html.append("<table class=\"header\"><tr><td class=\"seller\">");
        if (design.showLogo() && isNotBlank(logoDataUri)) {
            html.append("<div class=\"logo-wrap\"><img class=\"logo\" src=\"").append(logoDataUri).append("\"/></div>");
        }
        html.append("<div class=\"business-name\">").append(esc(invoice.getSellerName())).append("</div>");
        if (isNotBlank(invoice.getSellerAddress())) {
            html.append("<div>").append(esc(invoice.getSellerAddress())).append("</div>");
        }
        if (isNotBlank(invoice.getSellerGstin())) {
            html.append("<div>GSTIN: ").append(esc(invoice.getSellerGstin())).append("</div>");
        }
        if (design.showSellerPhone() && isNotBlank(invoice.getSellerPhone())) {
            html.append("<div>Phone: ").append(esc(invoice.getSellerPhone())).append("</div>");
        }
        if (isNotBlank(invoice.getSellerHeaderText())) {
            html.append("<div class=\"header-text\">").append(esc(invoice.getSellerHeaderText())).append("</div>");
        }
        html.append("</td>")
                .append("<td class=\"invoice-meta\">")
                .append("<div class=\"invoice-title\">TAX INVOICE</div>")
                .append(cancelled ? "<div class=\"cancelled\">CANCELLED</div>" : "")
                .append("<div>Invoice No.: ").append(esc(invoice.getInvoiceNumber())).append("</div>")
                .append("<div>Invoice Date: ").append(esc(invoice.getInvoiceDate().format(DATE_FORMAT))).append("</div>");
        if (isNotBlank(invoice.getPoNumber())) {
            html.append("<div>PO No.: ").append(esc(invoice.getPoNumber())).append("</div>");
            if (invoice.getPoDate() != null) {
                html.append("<div>PO Date: ").append(esc(invoice.getPoDate().format(DATE_FORMAT))).append("</div>");
            }
        }
        if (invoice.getDueDate() != null) {
            html.append("<div>Due Date: ").append(esc(invoice.getDueDate().format(DATE_FORMAT))).append("</div>");
        }
        if (isNotBlank(invoice.getPlaceOfSupply())) {
            html.append("<div>Place of Supply: ").append(esc(invoice.getPlaceOfSupply())).append("</div>");
        }
        html.append("<div>Reverse Charge: ").append(invoice.isReverseCharge() ? "Yes" : "No").append("</div>")
                .append("</td></tr></table>");

        html.append("<table class=\"party-row\"><tr>");
        html.append("<td class=\"bill-to\"><div class=\"section-label\">")
                .append(esc(design.billToLabelOrDefault())).append("</div>")
                .append("<div>").append(esc(invoice.getCustomerName())).append("</div>");
        if (isNotBlank(invoice.getCustomerContactPerson())) {
            html.append("<div>").append(esc(invoice.getCustomerContactPerson())).append("</div>");
        }
        if (isNotBlank(invoice.getCustomerPhone())) {
            html.append("<div>").append(esc(invoice.getCustomerPhone())).append("</div>");
        }
        if (isNotBlank(invoice.getCustomerEmail())) {
            html.append("<div>").append(esc(invoice.getCustomerEmail())).append("</div>");
        }
        if (isNotBlank(invoice.getCustomerAddress())) {
            html.append("<div>").append(esc(invoice.getCustomerAddress())).append("</div>");
        }
        if (isNotBlank(invoice.getCustomerGstin())) {
            html.append("<div>GSTIN: ").append(esc(invoice.getCustomerGstin())).append("</div>");
        }
        html.append("</td>");

        String shipToName = isNotBlank(invoice.getShipToName()) ? invoice.getShipToName() : invoice.getCustomerName();
        String shipToAddress = isNotBlank(invoice.getShipToAddress())
                ? invoice.getShipToAddress() : invoice.getCustomerAddress();
        html.append("<td class=\"ship-to\"><div class=\"section-label\">")
                .append(esc(design.shipToLabelOrDefault())).append("</div>")
                .append("<div>").append(esc(shipToName)).append("</div>");
        if (isNotBlank(shipToAddress)) {
            html.append("<div>").append(esc(shipToAddress)).append("</div>");
        }
        if (isNotBlank(invoice.getShipToGstin())) {
            html.append("<div>GSTIN: ").append(esc(invoice.getShipToGstin())).append("</div>");
        }
        html.append("</td></tr></table>");

        // Fixed column widths (summing to 100%) so the table always fits the page. On A5 the
        // Unit joins the Qty cell, and the Discount column is dropped when no line has one -
        // nothing GST-required is lost (discount still shows in the totals block).
        boolean narrow = design.pageSize() == InvoiceDesignSettings.PageSize.A5;
        boolean anyDiscount = lineItems.stream().anyMatch(l -> l.getLineDiscountAmount().signum() > 0);
        boolean discountCol = !narrow || anyDiscount;
        int[] widths;
        if (narrow) {
            widths = interState
                    ? (discountCol ? new int[] {19, 9, 11, 11, 9, 13, 12, 16} : new int[] {22, 10, 12, 14, 14, 13, 15})
                    : (discountCol ? new int[] {17, 8, 10, 11, 8, 12, 9, 9, 16}
                            : new int[] {20, 9, 11, 12, 13, 10, 10, 15});
        } else {
            widths = interState
                    ? new int[] {20, 9, 6, 6, 11, 9, 13, 12, 14}
                    : new int[] {20, 9, 6, 6, 10, 8, 12, 9, 9, 11};
        }
        html.append("<table class=\"lines\">").append(cols(widths)).append("<thead><tr>")
                .append("<th>Description</th><th>HSN/SAC</th>")
                .append(narrow ? "<th class=\"num\">Qty</th>" : "<th class=\"num\">Qty</th><th>Unit</th>")
                .append("<th class=\"num\">Unit Price</th>")
                .append(discountCol ? "<th class=\"num\">Discount</th>" : "")
                .append("<th class=\"num\">Taxable Value</th>")
                .append(interState ? "<th class=\"num\">IGST</th>" : "<th class=\"num\">CGST</th><th class=\"num\">SGST</th>")
                .append("<th class=\"num\">Total</th>")
                .append("</tr></thead><tbody>");
        for (InvoiceLineItem line : lineItems) {
            java.math.BigDecimal taxableValue = line.getLineSubtotal().subtract(line.getLineDiscountAmount());
            java.math.BigDecimal lineTotal = taxableValue.add(line.getLineCgstAmount()).add(line.getLineSgstAmount())
                    .add(line.getLineIgstAmount());
            html.append("<tr>")
                    .append("<td>").append(esc(line.getDescription())).append("</td>")
                    .append("<td>").append(esc(line.getHsnSac())).append("</td>");
            if (narrow) {
                html.append("<td class=\"num\">").append(line.getQuantity())
                        .append(isNotBlank(line.getUnit()) ? " " + esc(line.getUnit()) : "").append("</td>");
            } else {
                html.append("<td class=\"num\">").append(line.getQuantity()).append("</td>")
                        .append("<td>").append(esc(line.getUnit())).append("</td>");
            }
            html.append("<td class=\"num\">").append(line.getUnitPrice()).append("</td>");
            if (discountCol) {
                html.append("<td class=\"num\">").append(line.getLineDiscountAmount()).append("</td>");
            }
            html.append("<td class=\"num\">").append(taxableValue).append("</td>")
                    .append(interState
                            ? "<td class=\"num\">" + line.getLineIgstAmount() + "</td>"
                            : "<td class=\"num\">" + line.getLineCgstAmount() + "</td><td class=\"num\">"
                                    + line.getLineSgstAmount() + "</td>")
                    .append("<td class=\"num\">").append(lineTotal).append("</td>")
                    .append("</tr>");
        }
        html.append("</tbody></table>");

        java.math.BigDecimal totalDiscount = lineItems.stream()
                .map(InvoiceLineItem::getLineDiscountAmount)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        html.append("<div class=\"totals\">")
                .append("<div>Subtotal: ").append(invoice.getSubtotal()).append("</div>")
                .append("<div>Total Discount: ").append(totalDiscount).append("</div>")
                .append("<div>Taxable Value: ").append(invoice.getTaxableAmount()).append("</div>")
                .append(interState
                        ? "<div>IGST: " + invoice.getIgstTotal() + "</div>"
                        : "<div>CGST: " + invoice.getCgstTotal() + "</div><div>SGST: " + invoice.getSgstTotal() + "</div>")
                .append("<div>Tax: ").append(invoice.getTaxTotal()).append("</div>")
                .append("<div class=\"grand-total\">Grand Total: ").append(invoice.getGrandTotal()).append("</div>");
        if (design.showPaidBalance() && !cancelled && amountPaid != null && amountPaid.signum() > 0) {
            html.append("<div>Paid: ").append(amountPaid).append("</div>")
                    .append("<div>Balance Due: ").append(invoice.getGrandTotal().subtract(amountPaid)).append("</div>");
        }
        html.append("</div>");
        if (design.showAmountInWords()) {
            html.append("<div class=\"in-words\"><b>Amount in words:</b> ")
                    .append(esc(AmountInWords.rupees(invoice.getGrandTotal()))).append("</div>");
        }
        if (cancelled && isNotBlank(invoice.getCancelReason())) {
            html.append("<div class=\"notes\"><div class=\"section-label\">Cancelled</div><div>")
                    .append(esc(invoice.getCancelReason())).append("</div></div>");
        }

        if (isNotBlank(invoice.getNotes())) {
            html.append("<div class=\"notes\"><div class=\"section-label\">Notes</div><div>")
                    .append(esc(invoice.getNotes())).append("</div></div>");
        }

        boolean hasBankDetails = isNotBlank(invoice.getSellerBankName()) || isNotBlank(invoice.getSellerBankAccountNumber())
                || isNotBlank(invoice.getSellerBankIfsc()) || isNotBlank(invoice.getSellerUpiId());
        if (design.showBankDetails() && hasBankDetails) {
            html.append("<div class=\"notes\"><div class=\"section-label\">Payment Details</div>");
            if (isNotBlank(invoice.getSellerBankName())) {
                html.append("<div>Bank Name: ").append(esc(invoice.getSellerBankName())).append("</div>");
            }
            if (isNotBlank(invoice.getSellerBankAccountNumber())) {
                html.append("<div>A/C No.: ").append(esc(invoice.getSellerBankAccountNumber())).append("</div>");
            }
            if (isNotBlank(invoice.getSellerBankIfsc())) {
                html.append("<div>IFSC Code: ").append(esc(invoice.getSellerBankIfsc())).append("</div>");
            }
            if (isNotBlank(invoice.getSellerBankBranch())) {
                html.append("<div>Branch: ").append(esc(invoice.getSellerBankBranch())).append("</div>");
            }
            if (isNotBlank(invoice.getSellerUpiId())) {
                html.append("<div>UPI ID: ").append(esc(invoice.getSellerUpiId())).append("</div>");
            }
            html.append("</div>");
        }

        if (isNotBlank(design.termsAndConditions())) {
            html.append("<div class=\"notes\"><div class=\"section-label\">Terms &amp; Conditions</div>")
                    .append("<div class=\"pre\">").append(esc(design.termsAndConditions())).append("</div></div>");
        }

        html.append("<div class=\"signature\">")
                .append("<div>For ").append(esc(invoice.getSellerName())).append("</div>")
                .append("<div class=\"signature-space\"></div>")
                .append("<div>Authorized Signatory</div>")
                .append("</div>");

        if (isNotBlank(invoice.getSellerFooterText())) {
            html.append("<div class=\"footer-text\">").append(esc(invoice.getSellerFooterText())).append("</div>");
        }

        html.append("</body></html>");
        return html.toString();
    }

    private static String cols(int... percents) {
        StringBuilder out = new StringBuilder("<colgroup>");
        for (int percent : percents) {
            out.append("<col style=\"width: ").append(percent).append("%\"/>");
        }
        return out.append("</colgroup>").toString();
    }

    private static String css(InvoiceDesignSettings d) {
        // Every interpolated value is an enum-derived constant or a #RRGGBB colour already
        // validated by InvoiceDesignSettings, so none of it can inject CSS.
        String accent = d.accentColor();
        int base = switch (d.textSize()) {
            case SMALL -> 9;
            case MEDIUM -> 11;
            case LARGE -> 13;
        };
        boolean compact = d.preset() == InvoiceDesignSettings.Preset.COMPACT;
        if (compact) {
            base = Math.max(8, base - 1);
        }
        if (d.pageSize() == InvoiceDesignSettings.PageSize.A5) {
            base = Math.max(8, base - 1);
        }
        String font = switch (d.fontFamily()) {
            case SANS -> "Helvetica, Arial, sans-serif";
            case SERIF -> "'Times New Roman', Times, serif";
            case MONO -> "'Courier New', Courier, monospace";
        };
        String margin = switch (d.margin()) {
            case NARROW -> "10mm";
            case NORMAL -> "15mm";
            case WIDE -> "22mm";
        };
        String page = switch (d.pageSize()) {
            case A4 -> "A4";
            case A5 -> "A5";
            case LETTER -> "letter";
        };
        int logoHeight = switch (d.logoSize()) {
            case SMALL -> 40;
            case MEDIUM -> 60;
            case LARGE -> 90;
        };
        String logoAlign = d.logoAlign().name().toLowerCase();
        int cell = compact ? 3 : 6;
        boolean a5 = d.pageSize() == InvoiceDesignSettings.PageSize.A5;
        int cellX = a5 ? 2 : 4;
        int tableFont = a5 ? base - 2 : base - 1;
        int gap = compact ? 12 : 22;
        String css = "@page { size: " + page + "; margin: " + margin + "; } "
                + "body { font-family: " + font + "; font-size: " + base + "pt; color: #222; } "
                + "table.header, table.party-row { width: 100%; border-collapse: collapse; } "
                + "table.header { margin-bottom: " + gap + "px; } "
                + "table.party-row { margin-bottom: " + (gap - 2) + "px; } "
                + "table.header td, table.party-row td { vertical-align: top; padding: 0; } "
                + "td.seller { width: 55%; } td.invoice-meta { width: 45%; text-align: right; } "
                + "td.bill-to, td.ship-to { width: 50%; } "
                + ".logo-wrap { text-align: " + logoAlign + "; margin-bottom: 8px; } "
                + ".logo { max-height: " + logoHeight + "px; max-width: 220px; } "
                + ".business-name { font-size: " + (base + 3) + "pt; font-weight: bold; color: " + accent + "; } "
                + ".header-text { margin-top: 6px; font-size: " + Math.max(7, base - 2) + "pt; color: #555; } "
                + ".invoice-title { font-size: " + (base + 7) + "pt; font-weight: bold; letter-spacing: 2px; color: "
                + accent + "; } "
                + ".cancelled { color: #b00020; font-weight: bold; font-size: " + (base + 3) + "pt; } "
                + ".section-label { font-weight: bold; text-transform: uppercase; font-size: " + Math.max(7, base - 2)
                + "pt; color: #666; margin-bottom: 4px; } "
                + "table.lines { width: 100%; table-layout: fixed; border-collapse: collapse; margin-bottom: 16px; } "
                + "table.lines th, table.lines td { border-bottom: 1px solid #ddd; padding: " + cell + "px " + cellX + "px; "
                + "text-align: left; font-size: " + tableFont + "pt; } "

                + "table.lines th { border-bottom: 2px solid " + accent + "; color: " + accent + "; } "
                + "table.lines th.num, table.lines td.num { text-align: right; } "
                + ".totals { text-align: right; } "
                + ".totals .grand-total { font-size: " + (base + 2) + "pt; font-weight: bold; margin-top: 6px; color: "
                + accent + "; } "
                + ".in-words { margin-top: 10px; } "
                + ".notes { margin-top: " + (compact ? 12 : 20) + "px; } "
                + ".pre { white-space: pre-wrap; } "
                + ".signature { margin-top: " + (compact ? 24 : 40) + "px; text-align: right; } "
                + ".signature-space { height: " + (compact ? 28 : 40) + "px; } "
                + ".footer-text { margin-top: 24px; padding-top: 8px; border-top: 1px solid #ddd; "
                + "font-size: " + Math.max(7, base - 2) + "pt; color: #555; white-space: pre-wrap; } ";
        if (d.preset() == InvoiceDesignSettings.Preset.BAND) {
            css += "table.header { background-color: " + accent + "; color: #ffffff; } "
                    + "table.header td { padding: 12px; } "
                    + "body.preset-band .business-name, body.preset-band .invoice-title, "
                    + "body.preset-band .header-text { color: #ffffff; } "
                    + "table.lines th { background-color: " + accent + "; color: #ffffff; "
                    + "border-bottom: 0; } ";
        }
        return css;
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String esc(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }
}
