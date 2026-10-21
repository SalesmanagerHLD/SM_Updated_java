package com.salesmanager.crm.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FinancialYearTest {

    @Test
    void startYear_aprilFirstStartsANewFinancialYear() {
        assertThat(FinancialYear.startYear(LocalDate.of(2026, 3, 31))).isEqualTo(2025);
        assertThat(FinancialYear.startYear(LocalDate.of(2026, 4, 1))).isEqualTo(2026);
        assertThat(FinancialYear.startYear(LocalDate.of(2026, 12, 31))).isEqualTo(2026);
        assertThat(FinancialYear.startYear(LocalDate.of(2027, 1, 1))).isEqualTo(2026);
    }

    @Test
    void label_isTwoDigitYearPair_andNumberStaysUnderGstLimit() {
        assertThat(FinancialYear.label(2026)).isEqualTo("26-27");
        assertThat(FinancialYear.label(2099)).isEqualTo("99-00");
        assertThat(String.format("INV/%s/%04d", FinancialYear.label(2026), 99999).length()).isLessThanOrEqualTo(16);
    }

    @Test
    void gstCalculator_splitsIntraStateInHalves_andInterStateAsIgst() {
        var intra = GstCalculator.split(new java.math.BigDecimal("1000.00"), new java.math.BigDecimal("18"), false);
        assertThat(intra.cgst()).isEqualByComparingTo("90.00");
        assertThat(intra.sgst()).isEqualByComparingTo("90.00");
        assertThat(intra.igst()).isEqualByComparingTo("0.00");

        var inter = GstCalculator.split(new java.math.BigDecimal("1000.00"), new java.math.BigDecimal("18"), true);
        assertThat(inter.igst()).isEqualByComparingTo("180.00");
        assertThat(inter.cgst()).isEqualByComparingTo("0.00");
        assertThat(inter.total()).isEqualByComparingTo("180.00");
    }

    @Test
    void gstCalculator_interStateOnlyWhenBothStatesKnownAndDiffer() {
        var a = java.util.UUID.randomUUID();
        var b = java.util.UUID.randomUUID();
        assertThat(GstCalculator.isInterState(a, b)).isTrue();
        assertThat(GstCalculator.isInterState(a, a)).isFalse();
        assertThat(GstCalculator.isInterState(null, b)).isFalse();
        assertThat(GstCalculator.isInterState(a, null)).isFalse();
    }
}
