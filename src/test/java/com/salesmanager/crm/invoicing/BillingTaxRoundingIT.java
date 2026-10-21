package com.salesmanager.crm.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.salesmanager.crm.BillingTestSupport;
import com.salesmanager.crm.auth.dto.AuthResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Tax and rounding on both billing documents (intra-state CGST+SGST): odd paise, 0% tax, 100%
 * discount, several lines, and header totals always equalling the sum of the lines.
 * Rule under test: each line rounds HALF_UP to 2 dp at every step, and CGST and SGST are each
 * half the rate, rounded independently.
 */
class BillingTaxRoundingIT extends BillingTestSupport {

    private AuthResponse org(String name) {
        AuthResponse admin = registerOrganization(name);
        grant(admin.orgId(), "INVOICING");
        return admin;
    }

    /** Creates the same lines as an invoice and as a SENT quotation, and runs the assertions
     * against both - the two documents must always agree. */
    private void forBothDocuments(AuthResponse admin, List<Map<String, Object>> lines,
                                   java.util.function.Consumer<JsonNode> assertions) {
        String t = admin.accessToken();
        assertions.accept(createInvoice(t, lines));
        assertions.accept(createQuotation(t, quotationBody(createCustomer(t), lines, "SENT", null)));
    }

    private static void assertMoney(JsonNode node, String field, String expected) {
        assertThat(node.get(field).decimalValue()).as(field).isEqualByComparingTo(expected);
    }

    @Test
    void oddPaise_roundHalfUp_andEachTaxHalfIsRoundedIndependently() {
        // 33.33 x 3 = 99.99 ; 18% -> 9% each = 8.9991 -> 9.00 ; total 117.99
        forBothDocuments(org("Rounding Odd Paise Org"),
                List.of(adHocLine("Odd", 3, "33.3300", "18", null)), doc -> {
                    assertMoney(doc, "subtotal", "99.99");
                    assertMoney(doc, "cgstTotal", "9.00");
                    assertMoney(doc, "sgstTotal", "9.00");
                    assertMoney(doc, "grandTotal", "117.99");
                });
    }

    @Test
    void halfRateRoundingIsPerHalf_notOnTheWholeTax() {
        // 10.05 @ 5%: half = 2.5% of 10.05 = 0.25125 -> 0.25 each (0.50 total, not the exact 0.5025)
        forBothDocuments(org("Rounding Half Rate Org"),
                List.of(adHocLine("Half", 1, "10.05", "5", null)), doc -> {
                    assertMoney(doc, "cgstTotal", "0.25");
                    assertMoney(doc, "sgstTotal", "0.25");
                    assertMoney(doc, "grandTotal", "10.55");
                });
        // 41.67 @ 12%: 6% = 2.5002 -> 2.50 each ; total 46.67
        forBothDocuments(org("Rounding Half Rate Org 2"),
                List.of(adHocLine("Half", 1, "41.67", "12", null)), doc -> {
                    assertMoney(doc, "cgstTotal", "2.50");
                    assertMoney(doc, "grandTotal", "46.67");
                });
    }

    @Test
    void zeroPercentTax_addsNoTax() {
        forBothDocuments(org("Rounding Zero Tax Org"),
                List.of(adHocLine("Exempt", 2, "250.00", "0", null)), doc -> {
                    assertMoney(doc, "subtotal", "500.00");
                    assertMoney(doc, "cgstTotal", "0");
                    assertMoney(doc, "sgstTotal", "0");
                    assertMoney(doc, "grandTotal", "500.00");
                });
    }

    @Test
    void hundredPercentDiscount_leavesNothingToTaxOrPay() {
        forBothDocuments(org("Rounding Full Discount Org"),
                List.of(adHocLine("Free", 1, "200.00", "18", "100")), doc -> {
                    assertMoney(doc, "subtotal", "200.00");
                    assertMoney(doc, "discountTotal", "200.00");
                    assertMoney(doc, "taxableAmount", "0");
                    assertMoney(doc, "cgstTotal", "0");
                    assertMoney(doc, "sgstTotal", "0");
                    assertMoney(doc, "grandTotal", "0");
                });
    }

    @Test
    void severalLines_headerTotalsEqualTheSumOfTheLines() {
        // L1 100.00 @18%            -> taxable 100.00, 9.00+9.00,  total 118.00
        // L2 33.33x3=99.99, 10% off -> discount 10.00 (9.999 up), taxable 89.99, 12% -> 5.40+5.40, total 100.79
        // L3 0.10x7=0.70 @0%        -> total 0.70
        List<Map<String, Object>> lines = List.of(
                adHocLine("L1", 1, "100.00", "18", null),
                adHocLine("L2", 3, "33.33", "12", "10"),
                adHocLine("L3", 7, "0.10", "0", null));
        forBothDocuments(org("Rounding Several Lines Org"), lines, doc -> {
            assertMoney(doc, "subtotal", "200.69");
            assertMoney(doc, "discountTotal", "10.00");
            assertMoney(doc, "taxableAmount", "190.69");
            assertMoney(doc, "cgstTotal", "14.40");
            assertMoney(doc, "sgstTotal", "14.40");
            assertMoney(doc, "grandTotal", "219.49");

            // ...and they must equal the sums of the line-level figures the API returns.
            BigDecimal subtotal = BigDecimal.ZERO;
            BigDecimal discount = BigDecimal.ZERO;
            BigDecimal cgst = BigDecimal.ZERO;
            BigDecimal sgst = BigDecimal.ZERO;
            for (JsonNode line : doc.get("lineItems")) {
                subtotal = subtotal.add(line.get("lineSubtotal").decimalValue());
                discount = discount.add(line.get("lineDiscountAmount").decimalValue());
                cgst = cgst.add(line.get("lineCgstAmount").decimalValue());
                sgst = sgst.add(line.get("lineSgstAmount").decimalValue());
            }
            assertThat(doc.get("subtotal").decimalValue()).isEqualByComparingTo(subtotal);
            assertThat(doc.get("discountTotal").decimalValue()).isEqualByComparingTo(discount);
            assertThat(doc.get("cgstTotal").decimalValue()).isEqualByComparingTo(cgst);
            assertThat(doc.get("sgstTotal").decimalValue()).isEqualByComparingTo(sgst);
            assertThat(doc.get("grandTotal").decimalValue())
                    .isEqualByComparingTo(subtotal.subtract(discount).add(cgst).add(sgst));
        });
    }

    @Test
    void discountOver100Percent_isRejected() {
        AuthResponse admin = org("Rounding Bad Discount Org");
        assertThat(post("/invoices", admin.accessToken(),
                invoiceBody(List.of(adHocLine("Bad", 1, "10.00", "18", "100.01")))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
