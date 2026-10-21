package com.salesmanager.crm.purchaseorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.salesmanager.crm.BillingTestSupport;
import com.salesmanager.crm.auth.dto.AuthResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Customer purchase orders end to end: create/accept, partial billing by two invoices, status
 * movement through every state, over-billing and the admin override, and tenant isolation. */
class PurchaseOrderIT extends BillingTestSupport {

    private String admin(AuthResponse org) {
        return org.accessToken();
    }

    private Map<String, Object> poLine(String description, int quantity, String rate) {
        Map<String, Object> line = new HashMap<>();
        line.put("description", description);
        line.put("quantity", quantity);
        line.put("rate", rate);
        line.put("taxRatePercent", "18");
        return line;
    }

    private Map<String, Object> poBody(String leadId, String poNumber, List<Map<String, Object>> lines) {
        Map<String, Object> body = new HashMap<>();
        body.put("leadId", leadId);
        body.put("poNumber", poNumber);
        body.put("poDate", LocalDate.now().toString());
        body.put("paymentTermsDays", 30);
        body.put("customerName", "Acme Industries");
        body.put("lineItems", lines);
        return body;
    }

    private JsonNode createPo(String token, String leadId, String poNumber, List<Map<String, Object>> lines) {
        return okJson(post("/purchase-orders", token, poBody(leadId, poNumber, lines)), HttpStatus.CREATED);
    }

    private JsonNode acceptedPo(String token, String leadId, String poNumber, List<Map<String, Object>> lines) {
        JsonNode po = createPo(token, leadId, poNumber, lines);
        return okJson(post("/purchase-orders/" + po.get("id").asText() + "/accept", token, null), HttpStatus.OK);
    }

    private ResponseEntity<String> invoiceAgainstPo(String token, JsonNode po, int[] quantities, String overrideReason) {
        Map<String, Object> body = invoiceBody(new ArrayList<>());
        body.put("poId", po.get("id").asText());
        body.put("leadId", po.get("leadId").asText());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 0; i < quantities.length; i++) {
            if (quantities[i] > 0) {
                Map<String, Object> line = new HashMap<>();
                line.put("poLineId", po.get("lineItems").get(i).get("id").asText());
                line.put("quantity", quantities[i]);
                lines.add(line);
            }
        }
        body.put("lineItems", lines);
        if (overrideReason != null) {
            body.put("overBillingReason", overrideReason);
        }
        return post("/invoices", token, body);
    }

    private JsonNode refresh(String token, JsonNode po) {
        return okJson(get("/purchase-orders/" + po.get("id").asText(), token), HttpStatus.OK);
    }

    private static String qty(JsonNode node, int line, String field) {
        return node.get("lineItems").get(line).get(field).decimalValue().stripTrailingZeros().toPlainString();
    }

    @Test
    void onePoBilledByTwoInvoices_thenOneCancelled_movesThroughEveryStatus() {
        AuthResponse org = registerOrganization("PO Flow Org");
        grant(org.orgId(), "INVOICING");
        String t = admin(org);
        String leadId = createLead(t, "9100000001", null);

        JsonNode po = createPo(t, leadId, "CUST/PO/001", List.of(poLine("Panels", 10, "1000.00"), poLine("Install", 4, "500.00")));
        assertThat(po.get("status").asText()).isEqualTo("RECEIVED");
        assertThat(po.get("grandTotal").decimalValue()).isEqualByComparingTo("14160.00"); // (10000+2000) * 1.18

        // A PO that is only RECEIVED cannot be billed.
        assertThat(invoiceAgainstPo(t, po, new int[] {1, 0}, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        po = okJson(post("/purchase-orders/" + po.get("id").asText() + "/accept", t, null), HttpStatus.OK);
        assertThat(po.get("status").asText()).isEqualTo("ACCEPTED");

        // The draft is pre-filled with the remaining quantities and the PO's terms; nothing saved.
        JsonNode draft = okJson(post("/purchase-orders/" + po.get("id").asText() + "/invoice", t, null), HttpStatus.OK);
        assertThat(draft.get("lineItems")).hasSize(2);
        assertThat(draft.get("poNumber").asText()).isEqualTo("CUST/PO/001");
        assertThat(draft.get("dueDate").asText()).isEqualTo(LocalDate.now().plusDays(30).toString());
        assertThat(get("/invoices", t).getBody()).doesNotContain("CUST/PO/001");

        // Invoice 1: 6 of 10 panels, 4 of 4 installs -> PARTLY_BILLED
        JsonNode inv1 = okJson(invoiceAgainstPo(t, po, new int[] {6, 4}, null), HttpStatus.CREATED);
        assertThat(inv1.get("poNumber").asText()).isEqualTo("CUST/PO/001");
        assertThat(inv1.get("poId").asText()).isEqualTo(po.get("id").asText());
        assertThat(inv1.get("dueDate").asText()).isEqualTo(LocalDate.now().plusDays(30).toString());
        assertThat(inv1.get("taxableAmount").decimalValue()).isEqualByComparingTo("8000.00"); // PO prices
        po = refresh(t, po);
        assertThat(po.get("status").asText()).isEqualTo("PARTLY_BILLED");
        assertThat(qty(po, 0, "quantityBilled")).isEqualTo("6");
        assertThat(qty(po, 0, "quantityRemaining")).isEqualTo("4");

        // Invoice 2: the rest of the panels -> FULLY_BILLED
        JsonNode inv2 = okJson(invoiceAgainstPo(t, po, new int[] {4, 0}, null), HttpStatus.CREATED);
        po = refresh(t, po);
        assertThat(po.get("status").asText()).isEqualTo("FULLY_BILLED");
        assertThat(po.get("invoices")).hasSize(2);

        // A fully billed PO cannot be billed again, nor cancelled while invoices are active.
        assertThat(invoiceAgainstPo(t, po, new int[] {1, 0}, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(post("/purchase-orders/" + po.get("id").asText() + "/cancel", t, Map.of("reason", "x"))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Cancelling invoice 1 gives its quantities back -> PARTLY_BILLED
        okJson(post("/invoices/" + inv1.get("id").asText() + "/cancel", t, Map.of("reason", "Wrong rate")), HttpStatus.OK);
        po = refresh(t, po);
        assertThat(po.get("status").asText()).isEqualTo("PARTLY_BILLED");
        assertThat(qty(po, 0, "quantityBilled")).isEqualTo("4");
        assertThat(qty(po, 1, "quantityBilled")).isEqualTo("0");

        // Cancelling invoice 2 -> back to ACCEPTED; now the PO itself may be cancelled.
        okJson(post("/invoices/" + inv2.get("id").asText() + "/cancel", t, Map.of("reason", "Redo")), HttpStatus.OK);
        po = refresh(t, po);
        assertThat(po.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(qty(po, 0, "quantityBilled")).isEqualTo("0");
        po = okJson(post("/purchase-orders/" + po.get("id").asText() + "/cancel", t, Map.of("reason", "Customer withdrew")),
                HttpStatus.OK);
        assertThat(po.get("status").asText()).isEqualTo("CANCELLED");

        // Lead timeline shows the PO events.
        String activity = get("/activity?leadId=" + leadId + "&size=50", t).getBody();
        assertThat(activity).contains("PO_RECEIVED", "PO_ACCEPTED", "PO_BILLED", "PO_CANCELLED");
    }

    @Test
    void overBilling_isRejected_andOnlyAnAdminWithAReasonCanOverride() {
        AuthResponse org = registerOrganization("PO Overbill Org");
        grant(org.orgId(), "INVOICING");
        String adminToken = admin(org);
        AuthResponse rep = createAndLoginEmployee(adminToken, "rep");
        String repToken = rep.accessToken();

        // An employee's own PO: over-billing is refused outright, a reason doesn't help.
        String repLead = createLead(repToken, "9100000002", null);
        JsonNode repPo = acceptedPo(repToken, repLead, "REP-1", List.of(poLine("Item", 5, "100.00")));
        ResponseEntity<String> refused = invoiceAgainstPo(repToken, repPo, new int[] {6}, "please");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(parse(refused.getBody()).get("error").asText()).isEqualTo("OVER_BILLING");
        assertThat(refresh(repToken, repPo).get("status").asText()).isEqualTo("ACCEPTED"); // nothing was written
        assertThat(parse(get("/invoices", repToken).getBody()).get("content")).isEmpty();

        // An admin gets told an override is possible, but must give a reason.
        String adminLead = createLead(adminToken, "9100000003", null);
        JsonNode po = acceptedPo(adminToken, adminLead, "ADM-1", List.of(poLine("Item", 5, "100.00")));
        ResponseEntity<String> needsReason = invoiceAgainstPo(adminToken, po, new int[] {6}, null);
        assertThat(needsReason.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(parse(needsReason.getBody()).get("error").asText()).isEqualTo("OVER_BILLING_OVERRIDE_REQUIRED");
        assertThat(parse(get("/invoices", adminToken).getBody()).get("content")).isEmpty();

        JsonNode overridden = okJson(invoiceAgainstPo(adminToken, po, new int[] {6}, "Customer approved 1 extra unit"),
                HttpStatus.CREATED);
        assertThat(overridden.get("poOverrideReason").asText()).isEqualTo("Customer approved 1 extra unit");
        po = refresh(adminToken, po);
        assertThat(po.get("status").asText()).isEqualTo("FULLY_BILLED");
        assertThat(qty(po, 0, "quantityBilled")).isEqualTo("6");
        assertThat(get("/activity?leadId=" + adminLead + "&size=50", adminToken).getBody())
                .contains("admin override");
    }

    @Test
    void poNumberIsUniquePerCustomerLead_butReusableOnAnotherLead() {
        AuthResponse org = registerOrganization("PO Unique Org");
        grant(org.orgId(), "INVOICING");
        String t = admin(org);
        String leadA = createLead(t, "9100000004", null);
        String leadB = createLead(t, "9100000005", null);

        createPo(t, leadA, "SAME-1", List.of(poLine("Item", 1, "10.00")));
        assertThat(post("/purchase-orders", t, poBody(leadA, " SAME-1 ", List.of(poLine("Item", 1, "10.00"))))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        createPo(t, leadB, "SAME-1", List.of(poLine("Item", 1, "10.00")));
    }

    @Test
    void priceDifferenceFromQuotation_isFlagged_notBlocked() {
        AuthResponse org = registerOrganization("PO Price Org");
        grant(org.orgId(), "INVOICING");
        String t = admin(org);
        String leadId = createLead(t, "9100000006", null);
        String customerId = createCustomer(t);
        JsonNode quotation = createQuotation(t, quotationBody(customerId,
                List.of(adHocLine("Panels", 10, "1000.00", "18", null)), "SENT", leadId));
        String quotationLineId = get("/quotations/" + quotation.get("id").asText(), t).getBody();
        JsonNode qLine = parse(quotationLineId).get("lineItems").get(0);

        Map<String, Object> line = poLine("Panels", 10, "950.00");
        line.put("quotationLineId", qLine.get("id").asText());
        Map<String, Object> body = poBody(leadId, "PRICE-1", List.of(line));
        body.put("quotationId", quotation.get("id").asText());
        JsonNode po = okJson(post("/purchase-orders", t, body), HttpStatus.CREATED);

        assertThat(po.get("hasPriceDifference").asBoolean()).isTrue();
        assertThat(po.get("lineItems").get(0).get("quotationRate").decimalValue()).isEqualByComparingTo("1000.00");

        // prefill copies the quotation's lines
        JsonNode prefill = okJson(get("/purchase-orders/prefill?quotationId=" + quotation.get("id").asText(), t), HttpStatus.OK);
        assertThat(prefill.get("lineItems")).hasSize(1);
        assertThat(prefill.get("lineItems").get(0).get("rate").decimalValue()).isEqualByComparingTo("1000.00");
    }

    @Test
    void organizationA_cannotReadOrganizationBsPurchaseOrders() {
        AuthResponse orgA = registerOrganization("PO Org A");
        AuthResponse orgB = registerOrganization("PO Org B");
        grant(orgA.orgId(), "INVOICING");
        grant(orgB.orgId(), "INVOICING");
        String a = admin(orgA);
        String b = admin(orgB);

        JsonNode poB = createPo(b, createLead(b, "9100000007", null), "B-1", List.of(poLine("Item", 1, "10.00")));

        assertThat(get("/purchase-orders/" + poB.get("id").asText(), a).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(parse(get("/purchase-orders", a).getBody()).get("content")).isEmpty();
        assertThat(post("/purchase-orders/" + poB.get("id").asText() + "/accept", a, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(parse(get("/purchase-orders", b).getBody()).get("content")).hasSize(1);
    }

    @Test
    void employeeSeesOnlyTheirOwnPurchaseOrders_andInvoicingEntitlementIsRequired() {
        AuthResponse org = registerOrganization("PO Visibility Org");
        grant(org.orgId(), "INVOICING");
        String adminToken = admin(org);
        String repToken = createAndLoginEmployee(adminToken, "rep").accessToken();
        JsonNode adminPo = createPo(adminToken, createLead(adminToken, "9100000008", null), "V-1",
                List.of(poLine("Item", 1, "10.00")));

        assertThat(get("/purchase-orders/" + adminPo.get("id").asText(), repToken).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(parse(get("/purchase-orders", repToken).getBody()).get("content")).isEmpty();
        assertThat(parse(get("/purchase-orders", adminToken).getBody()).get("content")).hasSize(1);

        AuthResponse noEntitlement = registerOrganization("PO No Entitlement Org");
        assertThat(get("/purchase-orders", noEntitlement.accessToken()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void invoiceWithoutAPo_canCarryATypedPoNumber_andAPoLineIdNeedsAPoId() {
        AuthResponse org = registerOrganization("PO Typed Org");
        grant(org.orgId(), "INVOICING");
        String t = admin(org);

        Map<String, Object> body = invoiceBody(List.of(adHocLine("Svc", 1, "100.00", "18", null)));
        body.put("poNumber", "TYPED-9");
        body.put("poDate", "2026-01-15");
        JsonNode invoice = okJson(post("/invoices", t, body), HttpStatus.CREATED);
        assertThat(invoice.get("poNumber").asText()).isEqualTo("TYPED-9");
        assertThat(invoice.get("poDate").asText()).isEqualTo("2026-01-15");
        assertThat(invoice.get("poId").isNull()).isTrue();

        Map<String, Object> stray = adHocLine("Svc", 1, "100.00", "18", null);
        stray.put("poLineId", java.util.UUID.randomUUID().toString());
        assertThat(post("/invoices", t, invoiceBody(List.of(stray))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
