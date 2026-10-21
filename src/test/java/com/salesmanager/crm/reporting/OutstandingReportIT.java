package com.salesmanager.crm.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.salesmanager.crm.BillingTestSupport;
import com.salesmanager.crm.auth.dto.AuthResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** "Outstanding" is what is still owed (invoice total minus payments received), not the full
 * total of every unpaid or partly paid invoice. Cancelled invoices owe nothing. */
class OutstandingReportIT extends BillingTestSupport {

    private static final String SUMMARY = "/reports/quotation-invoice-summary";

    /** 1000.00 + 18% = 1180.00 */
    private static final List<Map<String, Object>> LINE = List.of(adHocLine("Svc", 1, "1000.00", "18", null));

    private String invoice(String t, LocalDate dueDate) {
        Map<String, Object> body = invoiceBody(LINE);
        if (dueDate != null) {
            body.put("dueDate", dueDate.toString());
        }
        return okJson(post("/invoices", t, body), HttpStatus.CREATED).get("id").asText();
    }

    private void pay(String t, String invoiceId, String amount) {
        assertThat(post("/invoices/" + invoiceId + "/payments", t,
                Map.of("amount", amount, "mode", "CASH")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private JsonNode summary(String t) {
        return okJson(get(SUMMARY, t), HttpStatus.OK);
    }

    @Test
    void outstanding_isTotalMinusPayments_acrossOutstandingPendingInvoicesAndOverdue() {
        AuthResponse admin = registerOrganization("Outstanding Report Org");
        grant(admin.orgId(), "INVOICING");
        String t = admin.accessToken();

        JsonNode empty = summary(t);
        assertThat(empty.get("outstanding").decimalValue()).isEqualByComparingTo("0");

        String a = invoice(t, null);                           // 1180.00, unpaid
        String b = invoice(t, LocalDate.now().minusDays(1));   // 1180.00, overdue
        pay(t, b, "180.00");                                   // partly paid -> 1000.00 left

        JsonNode s = summary(t);
        assertThat(s.get("outstanding").decimalValue()).isEqualByComparingTo("2180.00");
        assertThat(s.get("pendingInvoicesAmount").decimalValue()).isEqualByComparingTo("2180.00");
        assertThat(s.get("pendingInvoicesCount").asInt()).isEqualTo(2);
        assertThat(s.get("pendingPaymentsAmount").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(s.get("pendingPaymentsCount").asInt()).isEqualTo(1);

        // Part-paying A reduces what is owed by exactly the amount paid.
        pay(t, a, "500.00");
        assertThat(summary(t).get("outstanding").decimalValue()).isEqualByComparingTo("1680.00");

        // Settling B fully removes it from every open figure.
        pay(t, b, "1000.00");
        JsonNode afterB = summary(t);
        assertThat(afterB.get("outstanding").decimalValue()).isEqualByComparingTo("680.00");
        assertThat(afterB.get("pendingPaymentsAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(afterB.get("pendingPaymentsCount").asInt()).isZero();

        // A cancelled (never paid) invoice owes nothing.
        String c = invoice(t, null);
        assertThat(summary(t).get("outstanding").decimalValue()).isEqualByComparingTo("1860.00");
        assertThat(post("/invoices/" + c + "/cancel", t, Map.of("reason", "Duplicate")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(summary(t).get("outstanding").decimalValue()).isEqualByComparingTo("680.00");

        // Fully paid: nothing outstanding.
        pay(t, a, "680.00");
        assertThat(summary(t).get("outstanding").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void monthlyBilling_stillCountsTheFullInvoicedAmount_notWhatsLeftToPay() {
        AuthResponse admin = registerOrganization("Billing Total Org");
        grant(admin.orgId(), "INVOICING");
        String t = admin.accessToken();
        String id = invoice(t, null);
        pay(t, id, "1180.00");

        JsonNode s = summary(t);
        assertThat(s.get("monthlyBilling").decimalValue()).isEqualByComparingTo("1180.00");
        assertThat(s.get("outstanding").decimalValue()).isEqualByComparingTo("0");
    }
}
