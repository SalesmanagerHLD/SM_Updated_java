package com.salesmanager.crm.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import com.salesmanager.crm.invoicing.design.InvoiceDesignSettings;
import com.salesmanager.crm.invoicing.design.InvoiceDesignSettings.FontFamily;
import com.salesmanager.crm.invoicing.design.InvoiceDesignSettings.LogoAlign;
import com.salesmanager.crm.invoicing.design.InvoiceDesignSettings.LogoSize;
import com.salesmanager.crm.invoicing.design.InvoiceDesignSettings.Margin;
import com.salesmanager.crm.invoicing.design.InvoiceDesignSettings.PageSize;
import com.salesmanager.crm.invoicing.design.InvoiceDesignSettings.Preset;
import com.salesmanager.crm.invoicing.design.InvoiceDesignSettings.TextSize;
import com.salesmanager.crm.invoicing.dto.BillingProfileResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure rendering tests (no database): every preset x page size x option combination must still
 * show the GST-required content and produce a real PDF. */
class InvoiceDesignRenderTest {

    private static final String PNG_DATA_URI = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";

    private final InvoicePdfHtmlBuilder builder = new InvoicePdfHtmlBuilder();
    private final InvoicePdfService pdfService = new InvoicePdfService(builder);

    private static Invoice sample() {
        return InvoiceDesignSample.invoice(new BillingProfileResponse("Acme Traders", "1 Main Road", "27AAAAA0000A1Z5",
                null, "9999999999", false, "Header note", "Footer note", "HDFC", "123456", "HDFC0001", "Pune",
                "acme@upi"));
    }

    private static List<InvoiceDesignSettings> allVariants() {
        List<InvoiceDesignSettings> variants = new ArrayList<>();
        InvoiceDesignSettings base = InvoiceDesignSettings.defaults();
        for (Preset preset : Preset.values()) {
            for (PageSize page : PageSize.values()) {
                for (boolean toggles : new boolean[] {true, false}) {
                    variants.add(new InvoiceDesignSettings(preset, page, Margin.WIDE, "#1F4E78", FontFamily.SERIF,
                            TextSize.LARGE, toggles, LogoSize.LARGE, LogoAlign.CENTER, toggles, toggles, toggles,
                            toggles, "Goods once sold are not returnable.", "Billed To", "Delivered To"));
                }
            }
        }
        variants.add(base);
        return variants;
    }

    @Test
    void everyVariantKeepsTheGstRequiredContent() {
        for (InvoiceDesignSettings design : allVariants()) {
            String html = builder.build(sample(), InvoiceDesignSample.lines(), BigDecimal.ZERO, PNG_DATA_URI, design);
            assertThat(html).as(design.toString())
                    .contains("TAX INVOICE", "INV/26-27/0001", "Acme Traders", "27AAAAA0000A1Z5", "Place of Supply",
                            "Reverse Charge", "HSN/SAC", "Qty", "Unit", "Taxable Value", "CGST", "SGST",
                            "Grand Total", "Authorized Signatory", "27ABCDE1234F1Z5");
            assertThat(html).doesNotContain("display: flex");
        }
    }

    @Test
    void togglesHideOptionalContentOnly() {
        InvoiceDesignSettings off = new InvoiceDesignSettings(Preset.CLASSIC, PageSize.A4, Margin.NORMAL, "#222222",
                FontFamily.SANS, TextSize.MEDIUM, false, LogoSize.MEDIUM, LogoAlign.LEFT, false, false, false, false,
                "", "", "");
        String html = builder.build(sample(), InvoiceDesignSample.lines(), BigDecimal.ZERO, PNG_DATA_URI, off);
        assertThat(html).doesNotContain("class=\"logo\"", "Amount in words", "Payment Details", "Phone:",
                "Terms &amp; Conditions");
        assertThat(html).contains("Bill To", "Ship To");
    }

    @Test
    void customLabelsAndTermsAreEscaped() {
        InvoiceDesignSettings d = new InvoiceDesignSettings(Preset.BAND, PageSize.LETTER, Margin.NARROW, "#00AA00",
                FontFamily.MONO, TextSize.SMALL, true, LogoSize.SMALL, LogoAlign.RIGHT, true, true, true, true,
                "<script>x</script> & more", "A<b>", "Ship");
        String html = builder.build(sample(), InvoiceDesignSample.lines(), BigDecimal.ZERO, null, d);
        assertThat(html).doesNotContain("<script>", "A<b>").contains("&lt;script&gt;", "A&lt;b&gt;");
        assertThat(html).contains("size: letter");
    }

    @Test
    void everyVariantRendersARealPdf() {
        for (InvoiceDesignSettings design : allVariants()) {
            byte[] pdf = pdfService.renderPdf(sample(), InvoiceDesignSample.lines(), BigDecimal.ZERO, PNG_DATA_URI,
                    design);
            assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).as(design.toString()).isEqualTo("%PDF");
        }
    }

    @Test
    void amountInWordsUsesIndianGrouping() {
        assertThat(AmountInWords.rupees(new BigDecimal("35400.00")))
                .isEqualTo("Rupees Thirty Five Thousand Four Hundred Only");
        assertThat(AmountInWords.rupees(new BigDecimal("123456.78")))
                .isEqualTo("Rupees One Lakh Twenty Three Thousand Four Hundred Fifty Six and Seventy Eight Paise Only");
        assertThat(AmountInWords.rupees(new BigDecimal("0"))).isEqualTo("Rupees Zero Only");
        assertThat(AmountInWords.rupees(new BigDecimal("10000000"))).isEqualTo("Rupees One Crore Only");
    }
}
