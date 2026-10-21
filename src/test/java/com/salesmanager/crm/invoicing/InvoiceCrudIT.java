package com.salesmanager.crm.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.AbstractIntegrationTest;
import com.salesmanager.crm.auth.dto.AuthResponse;
import com.salesmanager.crm.auth.dto.LoginRequest;
import com.salesmanager.crm.auth.dto.RegisterOrganizationRequest;
import com.salesmanager.crm.employee.Role;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Invoice creation (Phase 2 of the Inventory + Invoicing plan): ad-hoc-only, catalog-only, and
 * mixed line items with correctly computed totals, standalone (no Lead) invoices, catalog-line
 * stock deduction + price/name snapshotting, line-item validation (exactly one of
 * productId/description, whole-number quantity for catalog lines), owner-scoped visibility,
 * status update, and tenant isolation. Every endpoint requires INVENTORY_MANAGEMENT.
 */
class InvoiceCrudIT extends AbstractIntegrationTest {

    private static final String CODE = "INVENTORY_MANAGEMENT";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${platform.admin.key}")
    private String platformAdminKey;

    @Test
    void notEntitled_everyEndpointReturns403() {
        AuthResponse admin = registerOrganization("Invoice NotEntitled Org");
        assertThat(post("/invoices", admin.accessToken(), adHocOnlyBody()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/invoices", admin.accessToken()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void create_adHocLineOnly_computesTotals_noStockTouched_invoiceNumberFormatted() {
        AuthResponse admin = registerOrganization("Invoice AdHoc Org");
        grant(admin.orgId());

        Map<String, Object> lineItem = new HashMap<>();
        lineItem.put("description", "Custom Consulting Hours");
        lineItem.put("quantity", 2.5);
        lineItem.put("unitPrice", 1000.00);
        lineItem.put("taxRatePercent", 18.00);

        Map<String, Object> body = baseInvoiceBody(null, "Walk-in Customer");
        body.put("lineItems", List.of(lineItem));

        ResponseEntity<String> response = post("/invoices", admin.accessToken(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode invoice = parse(response.getBody());

        // 2.5 * 1000 = 2500 subtotal; 2500 * 18% = 450 tax; 2950 grand total.
        assertThat(invoice.get("subtotal").asDouble()).isEqualTo(2500.00);
        assertThat(invoice.get("taxTotal").asDouble()).isEqualTo(450.00);
        assertThat(invoice.get("grandTotal").asDouble()).isEqualTo(2950.00);
        assertThat(invoice.get("status").asText()).isEqualTo("UNPAID");
        assertThat(invoice.get("leadId").isNull()).isTrue();
        assertThat(invoice.get("invoiceNumber").asText())
                .isEqualTo("INV/" + FinancialYear.label(FinancialYear.startYear(LocalDate.now())) + "/0001");
        assertThat(invoice.get("lineItems").size()).isEqualTo(1);
        assertThat(invoice.get("lineItems").get(0).get("productId").isNull()).isTrue();
    }

    @Test
    void create_catalogLine_deductsStock_snapshotsCurrentPriceAndName() {
        AuthResponse admin = registerOrganization("Invoice Catalog Org");
        grant(admin.orgId());
        String productId = createProduct(admin.accessToken(), "Solar Inverter 5KVA", "20000.00", "5.00", 10);

        Map<String, Object> lineItem = new HashMap<>();
        lineItem.put("productId", productId);
        lineItem.put("quantity", 3);

        Map<String, Object> body = baseInvoiceBody(null, "Catalog Buyer");
        body.put("lineItems", List.of(lineItem));

        ResponseEntity<String> response = post("/invoices", admin.accessToken(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode invoice = parse(response.getBody());

        JsonNode line = invoice.get("lineItems").get(0);
        assertThat(line.get("description").asText()).isEqualTo("Solar Inverter 5KVA");
        assertThat(line.get("unitPrice").asDouble()).isEqualTo(20000.00);
        assertThat(line.get("taxRatePercent").asDouble()).isEqualTo(5.00);
        // 3 * 20000 = 60000 subtotal; 5% tax = 3000; grand total 63000.
        assertThat(invoice.get("subtotal").asDouble()).isEqualTo(60000.00);
        assertThat(invoice.get("grandTotal").asDouble()).isEqualTo(63000.00);

        JsonNode product = parse(get("/inventory/products/" + productId, admin.accessToken()).getBody());
        assertThat(product.get("stockQuantity").asInt()).isEqualTo(7); // 10 - 3
    }

    @Test
    void create_lineItemValidation_rejectsBothOrNeitherSet_andFractionalCatalogQuantity() {
        AuthResponse admin = registerOrganization("Invoice Validation Org");
        grant(admin.orgId());
        String productId = createProduct(admin.accessToken(), "Battery Backup", "4500.00", "0", 5);

        Map<String, Object> bothSet = new HashMap<>();
        bothSet.put("productId", productId);
        bothSet.put("description", "Also has a description");
        bothSet.put("quantity", 1);
        assertThat(createInvoiceExpecting(admin.accessToken(), bothSet, HttpStatus.BAD_REQUEST)).isTrue();

        Map<String, Object> neitherSet = new HashMap<>();
        neitherSet.put("quantity", 1);
        assertThat(createInvoiceExpecting(admin.accessToken(), neitherSet, HttpStatus.BAD_REQUEST)).isTrue();

        Map<String, Object> fractionalCatalog = new HashMap<>();
        fractionalCatalog.put("productId", productId);
        fractionalCatalog.put("quantity", 1.5);
        assertThat(createInvoiceExpecting(admin.accessToken(), fractionalCatalog, HttpStatus.BAD_REQUEST)).isTrue();
    }

    @Test
    void getById_employeeSeesOwnInvoice_notColleagues_adminSeesBoth() {
        AuthResponse admin = registerOrganization("Invoice Visibility Org");
        AuthResponse employeeA = createAndLoginEmployee(admin.accessToken(), "invoiceEmpA");
        AuthResponse employeeB = createAndLoginEmployee(admin.accessToken(), "invoiceEmpB");
        grant(admin.orgId());

        String invoiceAId = createAdHocInvoice(employeeA.accessToken(), "Employee A Customer");

        assertThat(get("/invoices/" + invoiceAId, employeeA.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(get("/invoices/" + invoiceAId, employeeB.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/invoices/" + invoiceAId, admin.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void updateStatus_marksInvoicePaid() {
        AuthResponse admin = registerOrganization("Invoice Status Org");
        grant(admin.orgId());
        String invoiceId = createAdHocInvoice(admin.accessToken(), "Paying Customer");

        ResponseEntity<String> response = patch("/invoices/" + invoiceId + "/status", admin.accessToken(),
                Map.of("status", "PAID"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(response.getBody()).get("status").asText()).isEqualTo("PAID");
    }

    @Test
    void tenantIsolation_orgBNeverSeesOrgAsInvoices() {
        AuthResponse orgA = registerOrganization("Invoice Tenant Org A");
        AuthResponse orgB = registerOrganization("Invoice Tenant Org B");
        grant(orgA.orgId());
        grant(orgB.orgId());
        String orgAInvoiceId = createAdHocInvoice(orgA.accessToken(), "Org A Customer");

        assertThat(get("/invoices/" + orgAInvoiceId, orgB.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- helpers ----

    private boolean createInvoiceExpecting(String token, Map<String, Object> lineItem, HttpStatus expected) {
        Map<String, Object> body = baseInvoiceBody(null, "Validation Customer");
        body.put("lineItems", List.of(lineItem));
        ResponseEntity<String> response = post("/invoices", token, body);
        return response.getStatusCode() == expected;
    }

    private String createAdHocInvoice(String token, String customerName) {
        Map<String, Object> lineItem = new HashMap<>();
        lineItem.put("description", "Misc Item");
        lineItem.put("quantity", 1);
        lineItem.put("unitPrice", 100.00);
        Map<String, Object> body = baseInvoiceBody(null, customerName);
        body.put("lineItems", List.of(lineItem));
        ResponseEntity<String> response = post("/invoices", token, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(response.getBody()).get("id").asText();
    }

    private Map<String, Object> adHocOnlyBody() {
        Map<String, Object> lineItem = new HashMap<>();
        lineItem.put("description", "Item");
        lineItem.put("quantity", 1);
        lineItem.put("unitPrice", 10.00);
        Map<String, Object> body = baseInvoiceBody(null, "Customer");
        body.put("lineItems", List.of(lineItem));
        return body;
    }

    private Map<String, Object> baseInvoiceBody(String leadId, String customerName) {
        Map<String, Object> body = new HashMap<>();
        if (leadId != null) {
            body.put("leadId", leadId);
        }
        body.put("customerName", customerName);
        body.put("invoiceDate", LocalDate.now().toString());
        return body;
    }

    private String createProduct(String token, String name, String unitPrice, String taxRatePercent,
                                  int stockQuantity) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("unitPrice", unitPrice);
        body.put("taxRatePercent", taxRatePercent);
        body.put("stockQuantity", stockQuantity);
        ResponseEntity<String> response = post("/inventory/products", token, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(response.getBody()).get("id").asText();
    }

    private void grant(UUID orgId) {
        Map<String, Object> body = new HashMap<>();
        body.put("action", "GRANT");
        body.put("grantedBy", "InvoiceCrudIT");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Platform-Key", platformAdminKey);
        // Invoicing has its own entitlement now; these tests also use products, so grant both.
        for (String code : java.util.List.of(CODE, "INVOICING")) {
            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl() + "/internal/organizations/" + orgId + "/entitlements/" + code,
                    HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    private AuthResponse createAndLoginEmployee(String adminToken, String label) {
        String email = label + "-" + UUID.randomUUID() + "@invoicecrud.test";
        String password = "employeepass1";
        Map<String, Object> body = Map.of(
                "fullName", "Test Employee " + label,
                "email", email,
                "password", password,
                "role", Role.EMPLOYEE.name());
        ResponseEntity<String> createResponse = post("/employees", adminToken, body);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        LoginRequest loginRequest = new LoginRequest(email, password);
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> loginResponse = restTemplate.postForEntity(
                baseUrl() + "/auth/login", new HttpEntity<>(loginRequest, loginHeaders), AuthResponse.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        return loginResponse.getBody();
    }

    private ResponseEntity<String> post(String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.postForEntity(baseUrl() + path, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> patch(String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.exchange(baseUrl() + path, HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(baseUrl() + path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private AuthResponse registerOrganization(String orgName) {
        RegisterOrganizationRequest request = new RegisterOrganizationRequest(
                orgName, "org-" + UUID.randomUUID(), "Admin User",
                "admin-" + UUID.randomUUID() + "@invoicecrud.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
