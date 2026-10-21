package com.salesmanager.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.auth.dto.AuthResponse;
import com.salesmanager.crm.auth.dto.LoginRequest;
import com.salesmanager.crm.auth.dto.RegisterOrganizationRequest;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Shared HTTP/fixture helpers for the billing integration tests (customers, quotations,
 * invoices, leads, entitlements). */
public abstract class BillingTestSupport extends AbstractIntegrationTest {

    protected final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${platform.admin.key}")
    private String platformAdminKey;

    // ---- HTTP ----

    protected ResponseEntity<String> exchange(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(baseUrl() + path, method, new HttpEntity<>(body, headers), String.class);
    }

    protected ResponseEntity<String> post(String path, String token, Object body) {
        return exchange(HttpMethod.POST, path, token, body);
    }

    protected ResponseEntity<String> put(String path, String token, Object body) {
        return exchange(HttpMethod.PUT, path, token, body);
    }

    protected ResponseEntity<String> patch(String path, String token, Object body) {
        return exchange(HttpMethod.PATCH, path, token, body);
    }

    protected ResponseEntity<String> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
    }

    protected JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected JsonNode okJson(ResponseEntity<String> response, HttpStatus expected) {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(expected);
        return parse(response.getBody());
    }

    // ---- organizations / people / entitlements ----

    protected AuthResponse registerOrganization(String orgName) {
        RegisterOrganizationRequest request = new RegisterOrganizationRequest(
                orgName, "org-" + UUID.randomUUID(), "Admin User",
                "admin-" + UUID.randomUUID() + "@billing.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    protected void grant(UUID orgId, String... codes) {
        for (String code : codes) {
            patchEntitlement(orgId, code, "GRANT");
        }
    }

    protected void revoke(UUID orgId, String code) {
        patchEntitlement(orgId, code, "REVOKE");
    }

    private void patchEntitlement(UUID orgId, String code, String action) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Platform-Key", platformAdminKey);
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/internal/organizations/" + orgId + "/entitlements/" + code, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("action", action, "grantedBy", "BillingTestSupport"), headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    protected AuthResponse createAndLoginEmployee(String adminToken, String label) {
        String email = label + "-" + UUID.randomUUID() + "@billing.test";
        ResponseEntity<String> created = post("/employees", adminToken, Map.of(
                "fullName", "Employee " + label, "email", email, "password", "employeepass1", "role", "EMPLOYEE"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> login = restTemplate.postForEntity(baseUrl() + "/auth/login",
                new HttpEntity<>(new LoginRequest(email, "employeepass1"), headers), AuthResponse.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        return login.getBody();
    }

    // ---- fixtures ----

    protected static Map<String, Object> adHocLine(String description, Object quantity, Object unitPrice,
                                                    Object taxRatePercent, Object discountPercent) {
        Map<String, Object> line = new HashMap<>();
        line.put("description", description);
        line.put("quantity", quantity);
        line.put("unitPrice", unitPrice);
        line.put("taxRatePercent", taxRatePercent);
        if (discountPercent != null) {
            line.put("discountPercent", discountPercent);
        }
        return line;
    }

    protected String createProduct(String token, String name, String unitPrice, String taxRate, int stock) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("unitPrice", unitPrice);
        body.put("taxRatePercent", taxRate);
        body.put("stockQuantity", stock);
        return okJson(post("/inventory/products", token, body), HttpStatus.CREATED).get("id").asText();
    }

    protected String createCustomer(String token) {
        return okJson(post("/customers", token, Map.of("name", "Customer " + UUID.randomUUID())), HttpStatus.CREATED)
                .get("id").asText();
    }

    protected Map<String, Object> quotationBody(String customerId, List<Map<String, Object>> lines, String status,
                                                 String leadId) {
        Map<String, Object> body = new HashMap<>();
        body.put("customerId", customerId);
        body.put("quotationDate", LocalDate.now().toString());
        body.put("lineItems", lines);
        body.put("status", status);
        if (leadId != null) {
            body.put("leadId", leadId);
        }
        return body;
    }

    protected JsonNode createQuotation(String token, Map<String, Object> body) {
        return okJson(post("/quotations", token, body), HttpStatus.CREATED);
    }

    protected Map<String, Object> invoiceBody(List<Map<String, Object>> lines) {
        Map<String, Object> body = new HashMap<>();
        body.put("customerName", "Invoice Customer");
        body.put("invoiceDate", LocalDate.now().toString());
        body.put("lineItems", lines);
        return body;
    }

    protected JsonNode createInvoice(String token, List<Map<String, Object>> lines) {
        return okJson(post("/invoices", token, invoiceBody(lines)), HttpStatus.CREATED);
    }

    protected String masterId(String token, String type, String code) {
        JsonNode entries = okJson(get("/masters/" + type, token), HttpStatus.OK);
        for (JsonNode entry : entries) {
            if (code == null || entry.get("code").asText().equalsIgnoreCase(code)) {
                return entry.get("id").asText();
            }
        }
        throw new AssertionError("No " + type + " master with code " + code);
    }

    /** Creates a lead owned by whoever's token is used; {@code interestLevelCode} null = none. */
    protected String createLead(String token, String contactNo, String interestLevelCode) {
        Map<String, Object> body = new HashMap<>();
        body.put("companyName", "Lead Co " + contactNo);
        body.put("contactPerson", "Contact " + contactNo);
        body.put("contactNo", contactNo);
        body.put("cityId", masterId(token, "CITY", null));
        body.put("leadSourceId", masterId(token, "LEAD_SOURCE", null));
        body.put("industryId", masterId(token, "INDUSTRY", null));
        if (interestLevelCode != null) {
            body.put("interestLevelId", masterId(token, "INTEREST_LEVEL", interestLevelCode));
        }
        return okJson(post("/leads", token, body), HttpStatus.CREATED).get("id").asText();
    }

    protected String leadStatus(String token, String leadId) {
        return okJson(get("/leads/" + leadId, token), HttpStatus.OK).get("status").asText();
    }
}
