package com.salesmanager.crm.quotation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.salesmanager.crm.BillingTestSupport;
import com.salesmanager.crm.auth.dto.AuthResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Quotation module: create, edit (DRAFT/SENT only), every status transition, converting exactly
 * once, and - when a lead is attached - the CLOSED_WON side effect of conversion.
 */
class QuotationLifecycleIT extends BillingTestSupport {

    private static List<Map<String, Object>> oneLine(int quantity) {
        return List.of(adHocLine("Service", quantity, "100.00", "18", null));
    }

    private AuthResponse org(String name) {
        AuthResponse admin = registerOrganization(name);
        grant(admin.orgId(), "INVOICING");
        return admin;
    }

    private String create(AuthResponse admin, String status) {
        return createQuotation(admin.accessToken(),
                quotationBody(createCustomer(admin.accessToken()), oneLine(1), status, null)).get("id").asText();
    }

    // ---- create / edit ----

    @Test
    void create_asDraft_computesTotals_andNumbersTheQuotation() {
        AuthResponse admin = org("Quotation Create Org");
        JsonNode q = createQuotation(admin.accessToken(),
                quotationBody(createCustomer(admin.accessToken()), oneLine(1), "DRAFT", null));

        assertThat(q.get("status").asText()).isEqualTo("DRAFT");
        assertThat(q.get("quotationNumber").asText()).isNotBlank();
        assertThat(q.get("grandTotal").decimalValue()).isEqualByComparingTo("118.00");
        assertThat(q.get("lineItems")).hasSize(1);
    }

    @Test
    void create_asApprovedOrConverted_isRejected() {
        AuthResponse admin = org("Quotation Bad Create Org");
        String customerId = createCustomer(admin.accessToken());
        for (String status : List.of("APPROVED", "REJECTED", "CONVERTED")) {
            assertThat(post("/quotations", admin.accessToken(),
                    quotationBody(customerId, oneLine(1), status, null)).getStatusCode())
                    .as(status).isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Test
    void edit_whileDraftOrSent_replacesLinesAndRecomputesTotals() {
        AuthResponse admin = org("Quotation Edit Org");
        String customerId = createCustomer(admin.accessToken());
        String id = createQuotation(admin.accessToken(), quotationBody(customerId, oneLine(1), "DRAFT", null))
                .get("id").asText();

        JsonNode edited = okJson(put("/quotations/" + id, admin.accessToken(),
                quotationBody(customerId, oneLine(2), "DRAFT", null)), HttpStatus.OK);
        assertThat(edited.get("grandTotal").decimalValue()).isEqualByComparingTo("236.00");
        assertThat(edited.get("lineItems")).hasSize(1);

        // DRAFT -> SENT is done through the edit itself.
        JsonNode sent = okJson(put("/quotations/" + id, admin.accessToken(),
                quotationBody(customerId, oneLine(3), "SENT", null)), HttpStatus.OK);
        assertThat(sent.get("status").asText()).isEqualTo("SENT");
        assertThat(sent.get("grandTotal").decimalValue()).isEqualByComparingTo("354.00");

        // A SENT quotation can still be edited.
        assertThat(put("/quotations/" + id, admin.accessToken(),
                quotationBody(customerId, oneLine(1), "SENT", null)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void edit_afterApprovedRejectedOrConverted_isRejected() {
        AuthResponse admin = org("Quotation Locked Edit Org");
        String customerId = createCustomer(admin.accessToken());
        String approved = create(admin, "SENT");
        patch("/quotations/" + approved + "/status", admin.accessToken(), Map.of("status", "APPROVED"));
        String rejected = create(admin, "SENT");
        patch("/quotations/" + rejected + "/status", admin.accessToken(), Map.of("status", "REJECTED"));
        String converted = create(admin, "SENT");
        post("/quotations/" + converted + "/convert-to-invoice", admin.accessToken(), Map.of());

        for (String id : List.of(approved, rejected, converted)) {
            assertThat(put("/quotations/" + id, admin.accessToken(),
                    quotationBody(customerId, oneLine(5), "SENT", null)).getStatusCode())
                    .as(id).isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Test
    void unknownQuotation_returns404() {
        AuthResponse admin = org("Quotation 404 Org");
        assertThat(get("/quotations/" + UUID.randomUUID(), admin.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- status transitions ----

    @Test
    void status_sentCanBeApprovedOrRejected_nothingElse() {
        AuthResponse admin = org("Quotation Status Org");
        String t = admin.accessToken();

        String toApprove = create(admin, "SENT");
        assertThat(okJson(patch("/quotations/" + toApprove + "/status", t, Map.of("status", "APPROVED")),
                HttpStatus.OK).get("status").asText()).isEqualTo("APPROVED");

        String toReject = create(admin, "SENT");
        assertThat(okJson(patch("/quotations/" + toReject + "/status", t, Map.of("status", "REJECTED")),
                HttpStatus.OK).get("status").asText()).isEqualTo("REJECTED");

        // From SENT, only APPROVED/REJECTED are valid targets.
        String sent = create(admin, "SENT");
        for (String target : List.of("DRAFT", "SENT", "CONVERTED")) {
            assertThat(patch("/quotations/" + sent + "/status", t, Map.of("status", target)).getStatusCode())
                    .as(target).isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Test
    void status_draftCannotBeApproved_andDecidedQuotationCannotBeDecidedAgain() {
        AuthResponse admin = org("Quotation Status Guard Org");
        String t = admin.accessToken();

        String draft = create(admin, "DRAFT");
        assertThat(patch("/quotations/" + draft + "/status", t, Map.of("status", "APPROVED")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        String approved = create(admin, "SENT");
        patch("/quotations/" + approved + "/status", t, Map.of("status", "APPROVED"));
        assertThat(patch("/quotations/" + approved + "/status", t, Map.of("status", "REJECTED")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // ---- conversion ----

    @Test
    void convert_fromSentOrApproved_works_andMarksQuotationConverted() {
        AuthResponse admin = org("Quotation Convert Org");
        String t = admin.accessToken();

        String sent = create(admin, "SENT");
        JsonNode invoice = okJson(post("/quotations/" + sent + "/convert-to-invoice", t, Map.of()), HttpStatus.OK);
        assertThat(invoice.get("quotationId").asText()).isEqualTo(sent);
        assertThat(invoice.get("grandTotal").decimalValue()).isEqualByComparingTo("118.00");

        JsonNode afterwards = okJson(get("/quotations/" + sent, t), HttpStatus.OK);
        assertThat(afterwards.get("status").asText()).isEqualTo("CONVERTED");
        assertThat(afterwards.get("convertedInvoiceId").asText()).isEqualTo(invoice.get("id").asText());

        String approved = create(admin, "SENT");
        patch("/quotations/" + approved + "/status", t, Map.of("status", "APPROVED"));
        assertThat(post("/quotations/" + approved + "/convert-to-invoice", t, Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void convert_secondAttempt_isRejected_andOnlyOneInvoiceExists() {
        AuthResponse admin = org("Quotation Convert Twice Org");
        String t = admin.accessToken();
        String id = create(admin, "SENT");

        assertThat(post("/quotations/" + id + "/convert-to-invoice", t, Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(post("/quotations/" + id + "/convert-to-invoice", t, Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        JsonNode invoices = okJson(get("/invoices", t), HttpStatus.OK).get("content");
        assertThat(invoices).hasSize(1);
    }

    @Test
    void convert_fromDraftOrRejected_isRejected() {
        AuthResponse admin = org("Quotation Convert Guard Org");
        String t = admin.accessToken();

        String draft = create(admin, "DRAFT");
        assertThat(post("/quotations/" + draft + "/convert-to-invoice", t, Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        String rejected = create(admin, "SENT");
        patch("/quotations/" + rejected + "/status", t, Map.of("status", "REJECTED"));
        assertThat(post("/quotations/" + rejected + "/convert-to-invoice", t, Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // ---- lead side effect (option b: only where the lead's own rules already allow it) ----

    private JsonNode statusActivity(String token, String leadId) {
        return okJson(get("/activity?leadId=" + leadId + "&type=LEAD_STATUS_CHANGED", token), HttpStatus.OK)
                .get("content");
    }

    private void convertWithLead(AuthResponse who, String leadId) {
        String id = createQuotation(who.accessToken(),
                quotationBody(createCustomer(who.accessToken()), oneLine(1), "SENT", leadId)).get("id").asText();
        assertThat(post("/quotations/" + id + "/convert-to-invoice", who.accessToken(), Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void convert_hotLead_becomesClosedWon_withTheNormalActivityLogEntry() {
        AuthResponse admin = org("Quotation Lead Won Org");
        String leadId = createLead(admin.accessToken(), "9000000001", "HOT");
        assertThat(leadStatus(admin.accessToken(), leadId)).isNotEqualTo("CLOSED_WON");

        convertWithLead(admin, leadId);

        assertThat(leadStatus(admin.accessToken(), leadId)).isEqualTo("CLOSED_WON");
        JsonNode entries = statusActivity(admin.accessToken(), leadId);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("description").asText()).contains("CLOSED_WON");
    }

    @Test
    void convert_nonHotLead_isLeftUnchanged() {
        AuthResponse admin = org("Quotation Lead Warm Org");
        String warm = createLead(admin.accessToken(), "9000000002", "WARM");
        String none = createLead(admin.accessToken(), "9000000003", null);
        String warmBefore = leadStatus(admin.accessToken(), warm);
        String noneBefore = leadStatus(admin.accessToken(), none);

        convertWithLead(admin, warm);
        convertWithLead(admin, none);

        assertThat(leadStatus(admin.accessToken(), warm)).isEqualTo(warmBefore);
        assertThat(leadStatus(admin.accessToken(), none)).isEqualTo(noneBefore);
        assertThat(statusActivity(admin.accessToken(), warm)).isEmpty();
    }

    @Test
    void convert_lostLead_staysLost() {
        AuthResponse admin = org("Quotation Lead Lost Org");
        String leadId = createLead(admin.accessToken(), "9000000004", "HOT");
        assertThat(patch("/leads/" + leadId + "/status", admin.accessToken(),
                Map.of("status", "LOST", "lostReasonOther", "Went elsewhere")).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        convertWithLead(admin, leadId);

        assertThat(leadStatus(admin.accessToken(), leadId)).isEqualTo("LOST");
    }

    @Test
    void convert_alreadyClosedWonLead_isNotTouchedAgain() {
        AuthResponse admin = org("Quotation Lead Already Won Org");
        String leadId = createLead(admin.accessToken(), "9000000005", "HOT");

        convertWithLead(admin, leadId);
        convertWithLead(admin, leadId);

        assertThat(leadStatus(admin.accessToken(), leadId)).isEqualTo("CLOSED_WON");
        assertThat(statusActivity(admin.accessToken(), leadId)).hasSize(1);
    }

    @Test
    void convert_byAnEmployeeWhoDoesNotOwnTheLead_leavesTheLeadUnchanged_andStillConverts() {
        AuthResponse admin = org("Quotation Lead Not Owner Org");
        String leadId = createLead(admin.accessToken(), "9000000006", "HOT");
        AuthResponse employee = createAndLoginEmployee(admin.accessToken(), "rep");
        String before = leadStatus(admin.accessToken(), leadId);

        convertWithLead(employee, leadId);

        assertThat(leadStatus(admin.accessToken(), leadId)).isEqualTo(before);
    }

    @Test
    void directInvoiceWithALeadId_doesNotChangeTheLead() {
        AuthResponse admin = org("Invoice Direct Lead Org");
        String leadId = createLead(admin.accessToken(), "9000000007", "HOT");
        String before = leadStatus(admin.accessToken(), leadId);

        Map<String, Object> body = invoiceBody(oneLine(1));
        body.put("leadId", leadId);
        assertThat(post("/invoices", admin.accessToken(), body).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(leadStatus(admin.accessToken(), leadId)).isEqualTo(before);
    }

    @Test
    void convert_withInsufficientStock_returnsACleanConflict_andLeavesEverythingUntouched() {
        AuthResponse admin = registerOrganization("Quotation Convert No Stock Org");
        grant(admin.orgId(), "INVOICING", "INVENTORY_MANAGEMENT");
        String t = admin.accessToken();
        String productId = createProduct(t, "Scarce Widget", "100.00", "18", 1);
        Map<String, Object> line = new java.util.HashMap<>();
        line.put("productId", productId);
        line.put("quantity", 2);
        String id = createQuotation(t, quotationBody(createCustomer(t), List.of(line), "SENT", null))
                .get("id").asText();

        var response = post("/quotations/" + id + "/convert-to-invoice", t, Map.of());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("Insufficient stock");

        // Nothing was written: still SENT, no invoice, stock unchanged - and it can be retried.
        assertThat(okJson(get("/quotations/" + id, t), HttpStatus.OK).get("status").asText()).isEqualTo("SENT");
        assertThat(okJson(get("/invoices", t), HttpStatus.OK).get("content")).isEmpty();
        assertThat(okJson(get("/inventory/products/" + productId, t), HttpStatus.OK)
                .get("stockQuantity").asInt()).isEqualTo(1);
    }
}
