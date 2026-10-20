package com.salesmanager.crm.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.AbstractIntegrationTest;
import com.salesmanager.crm.auth.dto.AuthResponse;
import com.salesmanager.crm.auth.dto.RegisterOrganizationRequest;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * Stock deduction correctness for invoice creation (invoicing.InvoiceService#create): full
 * atomicity when one of several catalog lines has insufficient stock (no ghost invoice, no
 * partial stock leak on ANY referenced product - not just the one that failed), and the
 * lock-ordering discipline actually preventing deadlock when two concurrent invoices reference
 * the same two products in opposite line-item order.
 */
class InvoiceStockDeductionIT extends AbstractIntegrationTest {

    private static final String CODE = "INVENTORY_MANAGEMENT";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${platform.admin.key}")
    private String platformAdminKey;

    @Test
    void create_oneOfSeveralLinesInsufficientStock_rejectsWholeInvoice_noPartialLeakOnEitherProduct() {
        AuthResponse admin = registerOrganization("Stock Deduction Atomicity Org");
        grant(admin.orgId());
        String productAId = createProduct(admin.accessToken(), "Product A", "100.00", 5);
        String productBId = createProduct(admin.accessToken(), "Product B", "200.00", 10);

        Map<String, Object> lineA = new HashMap<>();
        lineA.put("productId", productAId);
        lineA.put("quantity", 3); // within Product A's stock of 5

        Map<String, Object> lineB = new HashMap<>();
        lineB.put("productId", productBId);
        lineB.put("quantity", 20); // exceeds Product B's stock of 10

        Map<String, Object> body = new HashMap<>();
        body.put("customerName", "Atomicity Test Customer");
        body.put("invoiceDate", LocalDate.now().toString());
        body.put("lineItems", List.of(lineA, lineB));

        ResponseEntity<String> response = post("/invoices", admin.accessToken(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Product A's stock must be UNCHANGED (still 5) - proving the whole invoice was
        // rejected atomically, not partially applied just because Product A's line was fine.
        JsonNode productA = parse(get("/inventory/products/" + productAId, admin.accessToken()).getBody());
        assertThat(productA.get("stockQuantity").asInt()).isEqualTo(5);

        JsonNode productB = parse(get("/inventory/products/" + productBId, admin.accessToken()).getBody());
        assertThat(productB.get("stockQuantity").asInt()).isEqualTo(10);

        // No invoice was created at all.
        JsonNode invoices = parse(get("/invoices", admin.accessToken()).getBody());
        assertThat(invoices.get("content")).isEmpty();
    }

    @Test
    void concurrentInvoices_referencingSameTwoProductsInOppositeOrder_bothSucceed_noDeadlock() throws Exception {
        AuthResponse admin = registerOrganization("Stock Lock Ordering Org");
        grant(admin.orgId());
        String productAId = createProduct(admin.accessToken(), "Lock Order Product A", "50.00", 100);
        String productBId = createProduct(admin.accessToken(), "Lock Order Product B", "75.00", 100);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            // Thread 1: line order A then B.
            tasks.add(() -> createInvoiceWithTwoLines(admin.accessToken(), productAId, productBId));
            // Thread 2: line order B then A - the REVERSE order, the exact shape that would
            // deadlock two concurrent transactions if products weren't locked in a canonical
            // (ascending-id) order regardless of how the caller listed them.
            tasks.add(() -> createInvoiceWithTwoLines(admin.accessToken(), productBId, productAId));

            List<Future<Integer>> futures = executor.invokeAll(tasks, 20, TimeUnit.SECONDS);
            for (Future<Integer> future : futures) {
                assertThat(future.get()).isEqualTo(HttpStatus.CREATED.value());
            }
        } finally {
            executor.shutdown();
        }

        // Both invoices succeeded - each deducted 1 unit from both products.
        JsonNode productA = parse(get("/inventory/products/" + productAId, admin.accessToken()).getBody());
        assertThat(productA.get("stockQuantity").asInt()).isEqualTo(98); // 100 - 1 - 1
        JsonNode productB = parse(get("/inventory/products/" + productBId, admin.accessToken()).getBody());
        assertThat(productB.get("stockQuantity").asInt()).isEqualTo(98);
    }

    // ---- helpers ----

    private int createInvoiceWithTwoLines(String token, String firstProductId, String secondProductId) {
        Map<String, Object> lineFirst = new HashMap<>();
        lineFirst.put("productId", firstProductId);
        lineFirst.put("quantity", 1);
        Map<String, Object> lineSecond = new HashMap<>();
        lineSecond.put("productId", secondProductId);
        lineSecond.put("quantity", 1);

        Map<String, Object> body = new HashMap<>();
        body.put("customerName", "Lock Ordering Customer");
        body.put("invoiceDate", LocalDate.now().toString());
        body.put("lineItems", List.of(lineFirst, lineSecond));

        return post("/invoices", token, body).getStatusCode().value();
    }

    private String createProduct(String token, String name, String unitPrice, int stockQuantity) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("unitPrice", unitPrice);
        body.put("stockQuantity", stockQuantity);
        ResponseEntity<String> response = post("/inventory/products", token, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(response.getBody()).get("id").asText();
    }

    private void grant(UUID orgId) {
        Map<String, Object> body = new HashMap<>();
        body.put("action", "GRANT");
        body.put("grantedBy", "InvoiceStockDeductionIT");
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

    private ResponseEntity<String> post(String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.postForEntity(baseUrl() + path, new HttpEntity<>(body, headers), String.class);
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
                "admin-" + UUID.randomUUID() + "@stockdeduction.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
