package com.salesmanager.crm.lead;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.salesmanager.crm.BillingTestSupport;
import com.salesmanager.crm.auth.dto.AuthResponse;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Admin-managed reasons behind an interest level: master data under a level, lead/visit
 * capture and validation, sync-back from a visit, and the lead filter and report. */
class InterestReasonIT extends BillingTestSupport {

    private String createReason(String token, String code, String label, String levelId) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("label", label);
        body.put("parentId", levelId);
        return okJson(post("/masters/INTEREST_REASON", token, body), HttpStatus.CREATED).get("id").asText();
    }

    private Map<String, Object> leadBody(String token, String phone, String levelId, List<String> reasons) {
        Map<String, Object> body = new HashMap<>();
        body.put("companyName", "Reason Co " + phone);
        body.put("contactPerson", "Contact " + phone);
        body.put("contactNo", phone);
        body.put("cityId", masterId(token, "CITY", null));
        body.put("leadSourceId", masterId(token, "LEAD_SOURCE", null));
        body.put("industryId", masterId(token, "INDUSTRY", null));
        body.put("logAsVisitToday", false);
        if (levelId != null) {
            body.put("interestLevelId", levelId);
        }
        if (reasons != null) {
            body.put("interestReasonIds", reasons);
        }
        return body;
    }

    private static List<String> ids(JsonNode array) {
        List<String> out = new java.util.ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    void reasonsAreAdminMasterData_validatedAgainstTheirLevel_andReported() {
        AuthResponse admin = registerOrganization("Interest Reason Org");
        AuthResponse employee = createAndLoginEmployee(admin.accessToken(), "reasonEmployee");
        String token = admin.accessToken();
        String hot = masterId(token, "INTEREST_LEVEL", "HOT");
        String warm = masterId(token, "INTEREST_LEVEL", "WARM");

        // Only an admin can manage the reasons.
        assertThat(post("/masters/INTEREST_REASON", employee.accessToken(),
                Map.of("code", "X", "label", "X", "parentId", hot)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String budget = createReason(token, "T_BUDGET_OK", "Test budget ok", hot);
        String urgent = createReason(token, "T_URGENT", "Test urgent", hot);
        String followUp = createReason(token, "T_FOLLOW_UP", "Test follow-up", warm);

        // Reasons are listed like any master data, with their level as parentId. A new org also
        // starts with a seeded starter set (4 per level) that admins can edit.
        JsonNode listed = okJson(get("/masters/INTEREST_REASON", token), HttpStatus.OK);
        assertThat(listed).hasSize(12 + 3);
        listed.forEach(r -> assertThat(r.get("parentId").isNull()).isFalse());

        // Create: multiple reasons under the chosen level are stored and returned.
        JsonNode lead = okJson(post("/leads", token, leadBody(token, "9000000001", hot, List.of(budget, urgent))),
                HttpStatus.CREATED);
        assertThat(ids(lead.get("interestReasonIds"))).containsExactlyInAnyOrder(budget, urgent);
        String leadId = lead.get("id").asText();

        // A reason that belongs to a different level, or with no level at all, is rejected.
        assertThat(post("/leads", token, leadBody(token, "9000000002", hot, List.of(followUp))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/leads", token, leadBody(token, "9000000003", null, List.of(budget))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // Reasons are optional.
        okJson(post("/leads", token, leadBody(token, "9000000004", hot, null)), HttpStatus.CREATED);

        // Filtering the lead list by a reason finds exactly the lead that has it.
        JsonNode filtered = okJson(get("/leads?interestReasonId=" + budget, token), HttpStatus.OK);
        assertThat(filtered.get("totalElements").asInt()).isEqualTo(1);
        assertThat(filtered.get("content").get(0).get("id").asText()).isEqualTo(leadId);

        // Report: per level and reason counts.
        JsonNode report = okJson(get("/reports/interest-reasons", token), HttpStatus.OK);
        assertThat(report.get("leadsWithReasons").asInt()).isEqualTo(1);
        assertThat(report.get("rows")).hasSize(2);
        assertThat(report.get("rows").get(0).get("interestLevel").asText()).isEqualTo("Hot");
        assertThat(report.get("rows").get(0).get("count").asInt()).isEqualTo(1);

        // Update: changing the level without sending reasons clears the old level's reasons.
        JsonNode moved = okJson(put("/leads/" + leadId, token, Map.of("interestLevelId", warm)), HttpStatus.OK);
        assertThat(moved.get("interestReasonIds")).isEmpty();
        // Sending reasons for the (new) level sets them.
        JsonNode reasoned = okJson(put("/leads/" + leadId, token, Map.of("interestReasonIds", List.of(followUp))),
                HttpStatus.OK);
        assertThat(ids(reasoned.get("interestReasonIds"))).containsExactly(followUp);
        // Re-saving without reasons and without a level change keeps them.
        JsonNode kept = okJson(put("/leads/" + leadId, token, Map.of("companyName", "Renamed Co")), HttpStatus.OK);
        assertThat(ids(kept.get("interestReasonIds"))).containsExactly(followUp);
    }

    @Test
    void visitRecordsReasons_andSyncsThemBackToTheLead() {
        AuthResponse admin = registerOrganization("Visit Reason Org");
        String token = admin.accessToken();
        String hot = masterId(token, "INTEREST_LEVEL", "HOT");
        String cold = masterId(token, "INTEREST_LEVEL", "COLD");
        String budget = createReason(token, "T_BUDGET", "Test budget", hot);
        String leadId = okJson(post("/leads", token, leadBody(token, "9100000001", cold, null)), HttpStatus.CREATED)
                .get("id").asText();

        Map<String, Object> visit = new HashMap<>();
        visit.put("leadId", leadId);
        visit.put("visitDate", LocalDate.now().toString());
        visit.put("visitType", "FIELD");
        visit.put("interestLevelId", hot);
        visit.put("interestReasonIds", List.of(budget));
        JsonNode created = okJson(post("/visits", token, visit), HttpStatus.CREATED);
        assertThat(ids(created.get("interestReasonIds"))).containsExactly(budget);

        // The lead mirrors the visit's level and reasons.
        JsonNode lead = okJson(get("/leads/" + leadId, token), HttpStatus.OK);
        assertThat(lead.get("interestLevelId").asText()).isEqualTo(hot);
        assertThat(ids(lead.get("interestReasonIds"))).containsExactly(budget);

        // A visit reason from the wrong level is rejected.
        visit.put("interestLevelId", cold);
        assertThat(post("/visits", token, visit).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
