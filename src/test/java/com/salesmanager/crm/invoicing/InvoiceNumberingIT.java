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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Invoice numbering (invoicing.InvoiceNumberService): sequential per (organizationId,
 * invoiceDate financial year), restarting at 0001 each 1 April rather than drifting, and a genuine
 * concurrent-creation race - several threads racing to allocate a number for the SAME
 * org+year must each get a distinct, sequential number with no duplicates and no gaps.
 */
class InvoiceNumberingIT extends AbstractIntegrationTest {

    private static final String CODE = "INVENTORY_MANAGEMENT";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${platform.admin.key}")
    private String platformAdminKey;

    @Test
    void invoiceNumber_restartsEachFinancialYear_ratherThanDrifting() {
        AuthResponse admin = registerOrganization("Invoice Numbering Year Org");
        grant(admin.orgId());

        // Numbers follow the Indian financial year (Apr-Mar): INV/26-27/0001.
        String thisYear = FinancialYear.label(FinancialYear.startYear(LocalDate.now()));
        String nextYear = FinancialYear.label(FinancialYear.startYear(LocalDate.now()) + 1);

        JsonNode first = parse(createInvoice(admin.accessToken(), LocalDate.now()).getBody());
        assertThat(first.get("invoiceNumber").asText()).isEqualTo("INV/" + thisYear + "/0001");

        JsonNode second = parse(createInvoice(admin.accessToken(), LocalDate.now()).getBody());
        assertThat(second.get("invoiceNumber").asText()).isEqualTo("INV/" + thisYear + "/0002");

        // A future-dated invoice starts its OWN year's counter at 0001, not continuing from 2.
        JsonNode futureYear = parse(createInvoice(admin.accessToken(), LocalDate.now().plusYears(1)).getBody());
        assertThat(futureYear.get("invoiceNumber").asText()).isEqualTo("INV/" + nextYear + "/0001");

        // Back to this year - continues from where it left off (3rd invoice of thisYear), not
        // reset again.
        JsonNode third = parse(createInvoice(admin.accessToken(), LocalDate.now()).getBody());
        assertThat(third.get("invoiceNumber").asText()).isEqualTo("INV/" + thisYear + "/0003");
    }

    @Test
    void concurrentInvoiceCreation_sameOrgSameYear_neverAllocatesDuplicateNumbers() throws Exception {
        AuthResponse admin = registerOrganization("Invoice Numbering Race Org");
        grant(admin.orgId());

        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                tasks.add(() -> parse(createInvoice(admin.accessToken(), LocalDate.now()).getBody())
                        .get("invoiceNumber").asText());
            }
            List<Future<String>> futures = executor.invokeAll(tasks, 30, TimeUnit.SECONDS);

            Set<String> invoiceNumbers = new HashSet<>();
            for (Future<String> future : futures) {
                invoiceNumbers.add(future.get());
            }
            // All 8 concurrent requests must have gotten distinct numbers - a race in the
            // counter allocation would show up here as fewer than 8 unique values.
            assertThat(invoiceNumbers).hasSize(threadCount);

            String fy = FinancialYear.label(FinancialYear.startYear(LocalDate.now()));
            for (int i = 1; i <= threadCount; i++) {
                assertThat(invoiceNumbers).contains(String.format("INV/%s/%04d", fy, i));
            }
        } finally {
            executor.shutdown();
        }
    }

    // ---- helpers ----

    private ResponseEntity<String> createInvoice(String token, LocalDate invoiceDate) {
        Map<String, Object> lineItem = new HashMap<>();
        lineItem.put("description", "Item");
        lineItem.put("quantity", 1);
        lineItem.put("unitPrice", 100.00);

        Map<String, Object> body = new HashMap<>();
        body.put("customerName", "Numbering Test Customer");
        body.put("invoiceDate", invoiceDate.toString());
        body.put("lineItems", List.of(lineItem));

        ResponseEntity<String> response = post("/invoices", token, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response;
    }

    private void grant(UUID orgId) {
        Map<String, Object> body = new HashMap<>();
        body.put("action", "GRANT");
        body.put("grantedBy", "InvoiceNumberingIT");
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
                "admin-" + UUID.randomUUID() + "@invoicenumbering.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
