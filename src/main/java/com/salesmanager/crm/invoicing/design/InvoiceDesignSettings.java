package com.salesmanager.crm.invoicing.design;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The complete, closed set of things a company may change about how its invoice looks. Every
 * field is an enum, a boolean, a hex colour or short capped text - there is deliberately no
 * free-form HTML/CSS, so a saved design can never produce a broken or unsafe PDF. The
 * GST-required content (seller/buyer details, number, date, place of supply, HSN/SAC, quantity,
 * unit, values, tax, reverse charge, signature) is not represented here at all, so no design can
 * switch it off.
 */
public record InvoiceDesignSettings(
        @NotNull Preset preset,
        @NotNull PageSize pageSize,
        @NotNull Margin margin,
        @NotNull @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "accentColor must be a #RRGGBB hex colour")
        String accentColor,
        @NotNull FontFamily fontFamily,
        @NotNull TextSize textSize,
        boolean showLogo,
        @NotNull LogoSize logoSize,
        @NotNull LogoAlign logoAlign,
        boolean showSellerPhone,
        boolean showPaidBalance,
        boolean showAmountInWords,
        boolean showBankDetails,
        @Size(max = 1000) String termsAndConditions,
        @Size(max = 30) String billToLabel,
        @Size(max = 30) String shipToLabel) {

    public enum Preset { CLASSIC, BAND, COMPACT }

    public enum PageSize { A4, A5, LETTER }

    public enum Margin { NARROW, NORMAL, WIDE }

    public enum FontFamily { SANS, SERIF, MONO }

    public enum TextSize { SMALL, MEDIUM, LARGE }

    public enum LogoSize { SMALL, MEDIUM, LARGE }

    public enum LogoAlign { LEFT, CENTER, RIGHT }

    public static final String DEFAULT_BILL_TO = "Bill To";
    public static final String DEFAULT_SHIP_TO = "Ship To";

    /** What every company gets until an admin saves a design - also what invoices issued before
     * custom designs existed render with. */
    public static InvoiceDesignSettings defaults() {
        return new InvoiceDesignSettings(Preset.CLASSIC, PageSize.A4, Margin.NORMAL, "#222222",
                FontFamily.SANS, TextSize.MEDIUM, true, LogoSize.MEDIUM, LogoAlign.LEFT, true, true, true, true,
                "", DEFAULT_BILL_TO, DEFAULT_SHIP_TO);
    }

    public String billToLabelOrDefault() {
        return billToLabel == null || billToLabel.isBlank() ? DEFAULT_BILL_TO : billToLabel.trim();
    }

    public String shipToLabelOrDefault() {
        return shipToLabel == null || shipToLabel.isBlank() ? DEFAULT_SHIP_TO : shipToLabel.trim();
    }
}
