package com.salesmanager.crm.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.AbstractIntegrationTest;
import com.salesmanager.crm.auth.dto.AuthResponse;
import com.salesmanager.crm.auth.dto.RegisterOrganizationRequest;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Billing Phase 0 fixes (GST Billing Module Research, "Backend audit"): IGST, quotation price
 * preserved on conversion, double-conversion guard, payments + cancellation (with stock
 * reversal), and the seller snapshot on issued invoices.
 */
class BillingGstFixesIT extends AbstractIntegrationTest {

    private static final String CODE = "INVENTORY_MANAGEMENT";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${platform.admin.key}")
    private String platformAdminKey;

    // ---- #1 IGST ----

    @Test
    void interStateSupply_chargesIgst_intraStateChargesCgstSgst() {
        AuthResponse admin = setUpOrg("Gst Split Org");
        String maharashtra = createState(admin.accessToken(), "MH", "Maharashtra");
        String karnataka = createState(admin.accessToken(), "KA", "Karnataka");
        setBillingState(admin.accessToken(), maharashtra);

        JsonNode inter = parse(createInvoice(admin.accessToken(), karnataka).getBody());
        assertThat(inter.get("igstTotal").decimalValue()).isEqualByComparingTo("180.00");
        assertThat(inter.get("cgstTotal").decimalValue()).isEqualByComparingTo("0");
        assertThat(inter.get("sgstTotal").decimalValue()).isEqualByComparingTo("0");
        assertThat(inter.get("grandTotal").decimalValue()).isEqualByComparingTo("1180.00");
        assertThat(inter.get("lineItems").get(0).get("lineIgstAmount").decimalValue()).isEqualByComparingTo("180.00");

        JsonNode intra = parse(createInvoice(admin.accessToken(), maharashtra).getBody());
        assertThat(intra.get("igstTotal").decimalValue()).isEqualByComparingTo("0");
        assertThat(intra.get("cgstTotal").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(intra.get("sgstTotal").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(intra.get("grandTotal").decimalValue()).isEqualByComparingTo("1180.00");
    }

    @Test
    void buyerStateKnownButSellerStateMissing_isRejected_ratherThanGuessing() {
        AuthResponse admin = setUpOrg("Gst Missing Seller State Org");
        String karnataka = createState(admin.accessToken(), "KA", "Karnataka");

        ResponseEntity<String> response = createInvoice(admin.accessToken(), karnataka);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ---- #2 / #3 quotation conversion ----

    @Test
    void conversion_keepsTheApprovedQuotationPrice_notTheProductsCurrentPrice() {
        AuthResponse admin = setUpOrg("Conversion Price Org");
        String productId = createProduct(admin.accessToken(), "Widget", "100.00", "18", 10);
        String customerId = createCustomer(admin.accessToken());
        String quotationId = createQuotation(admin.accessToken(), customerId, productId, null);

        // Price rises after the customer approved the quotation.
        ResponseEntity<String> update = put("/inventory/products/" + productId, admin.accessToken(),
                Map.of("name", "Widget", "unitPrice", "500.00", "taxRatePercent", "18", "active", true));
        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> converted = post("/quotations/" + quotationId + "/convert-to-invoice",
                admin.accessToken(), Map.of());
        assertThat(converted.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode invoice = parse(converted.getBody());
        assertThat(invoice.get("lineItems").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(invoice.get("grandTotal").decimalValue()).isEqualByComparingTo("118.00");
        assertThat(invoice.get("customerId").asText()).isEqualTo(customerId);
    }

    @Test
    void secondConversion_isRejectedWith409_andStockIsDeductedOnlyOnce() {
        AuthResponse admin = setUpOrg("Double Convert Org");
        String productId = createProduct(admin.accessToken(), "Widget", "100.00", "18", 10);
        String quotationId = createQuotation(admin.accessToken(), createCustomer(admin.accessToken()), productId, null);

        assertThat(post("/quotations/" + quotationId + "/convert-to-invoice", admin.accessToken(), Map.of())
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/quotations/" + quotationId + "/convert-to-invoice", admin.accessToken(), Map.of())
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stock(admin.accessToken(), productId)).isEqualTo(9);
    }

    @Test
    void concurrentConversions_createExactlyOneInvoice() throws Exception {
        AuthResponse admin = setUpOrg("Concurrent Convert Org");
        String productId = createProduct(admin.accessToken(), "Widget", "100.00", "18", 10);
        String quotationId = createQuotation(admin.accessToken(), createCustomer(admin.accessToken()), productId, null);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<HttpStatus>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                tasks.add(() -> HttpStatus.valueOf(post("/quotations/" + quotationId + "/convert-to-invoice",
                        admin.accessToken(), Map.of()).getStatusCode().value()));
            }
            int created = 0;
            for (Future<HttpStatus> f : executor.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
                if (f.get() == HttpStatus.OK) {
                    created++;
                } else {
                    assertThat(f.get()).isEqualTo(HttpStatus.CONFLICT);
                }
            }
            assertThat(created).isEqualTo(1);
            assertThat(stock(admin.accessToken(), productId)).isEqualTo(9);
        } finally {
            executor.shutdown();
        }
    }

    // ---- #6 payments + cancellation ----

    @Test
    void payments_driveTheStatus_andOverpaymentIsRejected() {
        AuthResponse admin = setUpOrg("Payments Org");
        JsonNode invoice = parse(createInvoice(admin.accessToken(), null).getBody());
        String id = invoice.get("id").asText();
        assertThat(invoice.get("balanceDue").decimalValue()).isEqualByComparingTo("1180.00");

        JsonNode afterFirst = parse(post("/invoices/" + id + "/payments", admin.accessToken(),
                Map.of("amount", "500.00", "mode", "CASH")).getBody());
        assertThat(afterFirst.get("status").asText()).isEqualTo("PARTIALLY_PAID");
        assertThat(afterFirst.get("balanceDue").decimalValue()).isEqualByComparingTo("680.00");

        assertThat(post("/invoices/" + id + "/payments", admin.accessToken(),
                Map.of("amount", "700.00", "mode", "UPI")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        JsonNode afterSecond = parse(post("/invoices/" + id + "/payments", admin.accessToken(),
                Map.of("amount", "680.00", "mode", "UPI")).getBody());
        assertThat(afterSecond.get("status").asText()).isEqualTo("PAID");

        JsonNode payments = parse(get("/invoices/" + id + "/payments", admin.accessToken()).getBody());
        assertThat(payments).hasSize(2);

        // A paid invoice can't be toggled back to UNPAID any more.
        assertThat(patch("/invoices/" + id + "/status", admin.accessToken(), Map.of("status", "UNPAID"))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void cancel_restoresStock_blocksFurtherPayments_andCannotRepeat() {
        AuthResponse admin = setUpOrg("Cancel Org");
        String productId = createProduct(admin.accessToken(), "Widget", "100.00", "18", 10);
        Map<String, Object> line = new HashMap<>();
        line.put("productId", productId);
        line.put("quantity", 3);
        Map<String, Object> body = invoiceBody(null);
        body.put("lineItems", List.of(line));
        JsonNode invoice = parse(post("/invoices", admin.accessToken(), body).getBody());
        String id = invoice.get("id").asText();
        assertThat(stock(admin.accessToken(), productId)).isEqualTo(7);

        ResponseEntity<String> cancelled = post("/invoices/" + id + "/cancel", admin.accessToken(),
                Map.of("reason", "Wrong customer"));
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(cancelled.getBody()).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(stock(admin.accessToken(), productId)).isEqualTo(10);

        assertThat(post("/invoices/" + id + "/cancel", admin.accessToken(), Map.of("reason", "again"))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(post("/invoices/" + id + "/payments", admin.accessToken(),
                Map.of("amount", "10.00", "mode", "CASH")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stock(admin.accessToken(), productId)).isEqualTo(10);
    }

    @Test
    void invoiceWithPayments_cannotBeCancelled() {
        AuthResponse admin = setUpOrg("Cancel Paid Org");
        String id = parse(createInvoice(admin.accessToken(), null).getBody()).get("id").asText();
        post("/invoices/" + id + "/payments", admin.accessToken(), Map.of("amount", "100.00", "mode", "CASH"));

        assertThat(post("/invoices/" + id + "/cancel", admin.accessToken(), Map.of("reason", "oops"))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ---- #7 seller snapshot ----

    @Test
    void sellerDetails_areSnapshottedOnTheInvoice_laterProfileEditsDontRewriteIt() {
        AuthResponse admin = setUpOrg("Snapshot Org");
        put("/organizations/me/billing-profile", admin.accessToken(),
                Map.of("billingGstin", "27ABCDE1234F1Z5", "billingAddress", "Old Address"));
        JsonNode invoice = parse(createInvoice(admin.accessToken(), null).getBody());
        String id = invoice.get("id").asText();
        assertThat(invoice.get("sellerGstin").asText()).isEqualTo("27ABCDE1234F1Z5");

        put("/organizations/me/billing-profile", admin.accessToken(),
                Map.of("billingGstin", "29ABCDE1234F1Z5", "billingAddress", "New Address"));
        JsonNode reloaded = parse(get("/invoices/" + id, admin.accessToken()).getBody());
        assertThat(reloaded.get("sellerGstin").asText()).isEqualTo("27ABCDE1234F1Z5");
    }

    @Test
    void invalidGstin_isRejected() {
        AuthResponse admin = setUpOrg("Gstin Format Org");
        assertThat(put("/organizations/me/billing-profile", admin.accessToken(),
                Map.of("billingGstin", "NOT-A-GSTIN")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- helpers ----

    private AuthResponse setUpOrg(String name) {
        AuthResponse admin = registerOrganization(name);
        grant(admin.orgId());
        return admin;
    }

    private Map<String, Object> invoiceBody(String placeOfSupplyStateId) {
        Map<String, Object> line = new HashMap<>();
        line.put("description", "Consulting");
        line.put("quantity", 1);
        line.put("unitPrice", 1000.00);
        line.put("taxRatePercent", 18);
        Map<String, Object> body = new HashMap<>();
        body.put("customerName", "Buyer");
        body.put("invoiceDate", LocalDate.now().toString());
        body.put("lineItems", List.of(line));
        if (placeOfSupplyStateId != null) {
            body.put("placeOfSupplyStateId", placeOfSupplyStateId);
        }
        return body;
    }

    private ResponseEntity<String> createInvoice(String token, String placeOfSupplyStateId) {
        return post("/invoices", token, invoiceBody(placeOfSupplyStateId));
    }

    private String createState(String token, String code, String label) {
        ResponseEntity<String> r = post("/masters/STATE", token, Map.of("code", code, "label", label));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(r.getBody()).get("id").asText();
    }

    private void setBillingState(String token, String stateId) {
        ResponseEntity<String> r = put("/organizations/me/billing-profile", token, Map.of("billingStateId", stateId));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String createProduct(String token, String name, String unitPrice, String taxRate, int stock) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("unitPrice", unitPrice);
        body.put("taxRatePercent", taxRate);
        body.put("stockQuantity", stock);
        ResponseEntity<String> r = post("/inventory/products", token, body);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(r.getBody()).get("id").asText();
    }

    private int stock(String token, String productId) {
        return parse(get("/inventory/products/" + productId, token).getBody()).get("stockQuantity").asInt();
    }

    private String createCustomer(String token) {
        ResponseEntity<String> r = post("/customers", token, Map.of("name", "Quote Customer"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(r.getBody()).get("id").asText();
    }

    private String createQuotation(String token, String customerId, String productId, String stateId) {
        Map<String, Object> line = new HashMap<>();
        line.put("productId", productId);
        line.put("quantity", 1);
        Map<String, Object> body = new HashMap<>();
        body.put("customerId", customerId);
        body.put("quotationDate", LocalDate.now().toString());
        body.put("lineItems", List.of(line));
        body.put("status", "SENT");
        if (stateId != null) {
            body.put("stateId", stateId);
        }
        ResponseEntity<String> r = post("/quotations", token, body);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(r.getBody()).get("id").asText();
    }

    private void grant(UUID orgId) {
        Map<String, Object> body = new HashMap<>();
        body.put("action", "GRANT");
        body.put("grantedBy", "BillingGstFixesIT");
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

    private ResponseEntity<String> exchange(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.exchange(baseUrl() + path, method, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> post(String path, String token, Object body) {
        return exchange(HttpMethod.POST, path, token, body);
    }

    private ResponseEntity<String> put(String path, String token, Object body) {
        return exchange(HttpMethod.PUT, path, token, body);
    }

    private ResponseEntity<String> patch(String path, String token, Object body) {
        return exchange(HttpMethod.PATCH, path, token, body);
    }

    private ResponseEntity<String> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
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
                "admin-" + UUID.randomUUID() + "@billinggst.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
