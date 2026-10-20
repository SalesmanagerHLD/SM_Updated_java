package com.salesmanager.crm.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.AbstractIntegrationTest;
import com.salesmanager.crm.auth.dto.AuthResponse;
import com.salesmanager.crm.auth.dto.LoginRequest;
import com.salesmanager.crm.auth.dto.RegisterOrganizationRequest;
import com.salesmanager.crm.employee.Role;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Billing profile (Phase 3: the seller header for a generated invoice PDF - GET open to any
 * entitled user, PUT ADMIN-only) and the invoice PDF download endpoint itself (correct
 * content-type/attachment header, a real, non-empty PDF byte stream, and the same
 * owner-scoped visibility as GET /invoices/{id}).
 */
class InvoicePdfIT extends AbstractIntegrationTest {

    private static final String CODE = "INVENTORY_MANAGEMENT";

    // A real, minimal 1x1 transparent PNG - valid image bytes, not a placeholder string, so
    // content-type sniffing/size checks exercise the actual validation path.
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${platform.admin.key}")
    private String platformAdminKey;

    @Test
    void billingProfile_getOpenToAnyUser_putAdminOnly() {
        AuthResponse admin = registerOrganization("Billing Profile Org");
        AuthResponse employee = createAndLoginEmployee(admin.accessToken(), "billingEmployee");
        grant(admin.orgId());

        JsonNode initial = parse(get("/organizations/me/billing-profile", employee.accessToken()).getBody());
        assertThat(initial.get("billingAddress").isNull()).isTrue();

        Map<String, Object> update = Map.of(
                "billingAddress", "123 Solar Street, Industrial Area",
                "billingGstin", "29ABCDE1234F1Z5",
                "billingPhone", "9876543210",
                "invoiceHeaderText", "Subject to Mumbai jurisdiction",
                "invoiceFooterText", "Thank you for your business - payment due within 30 days.");
        assertThat(put("/organizations/me/billing-profile", employee.accessToken(), update).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> adminUpdate = put("/organizations/me/billing-profile", admin.accessToken(), update);
        assertThat(adminUpdate.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode updated = parse(adminUpdate.getBody());
        assertThat(updated.get("billingAddress").asText()).isEqualTo("123 Solar Street, Industrial Area");
        assertThat(updated.get("billingGstin").asText()).isEqualTo("29ABCDE1234F1Z5");
        assertThat(updated.get("invoiceHeaderText").asText()).isEqualTo("Subject to Mumbai jurisdiction");
        assertThat(updated.get("invoiceFooterText").asText())
                .isEqualTo("Thank you for your business - payment due within 30 days.");
        assertThat(updated.get("hasLogo").asBoolean()).isFalse();

        // Any authenticated employee can read it (needed to render the PDF).
        JsonNode employeeRead = parse(get("/organizations/me/billing-profile", employee.accessToken()).getBody());
        assertThat(employeeRead.get("billingPhone").asText()).isEqualTo("9876543210");
    }

    @Test
    void logo_uploadDownloadDelete_adminOnly_hasLogoReflectsState() {
        AuthResponse admin = registerOrganization("Logo Org");
        AuthResponse employee = createAndLoginEmployee(admin.accessToken(), "logoEmployee");
        grant(admin.orgId());

        assertThat(get("/organizations/me/logo", admin.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(putLogo(employee.accessToken(), ONE_PIXEL_PNG, "logo.png", "image/png").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> uploadResponse = putLogo(admin.accessToken(), ONE_PIXEL_PNG, "logo.png", "image/png");
        assertThat(uploadResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(uploadResponse.getBody()).get("hasLogo").asBoolean()).isTrue();

        ResponseEntity<byte[]> download = restTemplate.exchange(baseUrl() + "/organizations/me/logo",
                HttpMethod.GET, new HttpEntity<>(bearerHeaders(employee.accessToken())), byte[].class);
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(download.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(download.getBody()).isEqualTo(ONE_PIXEL_PNG);

        assertThat(restTemplate.exchange(baseUrl() + "/organizations/me/logo", HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(employee.accessToken())), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> deleteResponse = restTemplate.exchange(baseUrl() + "/organizations/me/logo",
                HttpMethod.DELETE, new HttpEntity<>(bearerHeaders(admin.accessToken())), String.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(deleteResponse.getBody()).get("hasLogo").asBoolean()).isFalse();
        assertThat(get("/organizations/me/logo", admin.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void logo_rejectsWrongContentTypeAndOversizedFile() {
        AuthResponse admin = registerOrganization("Logo Validation Org");
        grant(admin.orgId());

        // Genuine GIF magic bytes ("GIF89a...") - actually the wrong format, not just a
        // mismatched label on real PNG bytes (see uploadLogo's content-sniffing rationale:
        // a real PNG mislabeled as image/gif is correctly ACCEPTED now, since the bytes
        // themselves are what's validated, not the client's declared type).
        byte[] gifBytes = "GIF89a".getBytes(StandardCharsets.US_ASCII);
        ResponseEntity<String> wrongType = putLogo(admin.accessToken(), gifBytes, "logo.gif", "image/gif");
        assertThat(wrongType.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        byte[] tooLarge = new byte[2 * 1024 * 1024 + 1];
        ResponseEntity<String> oversized = putLogo(admin.accessToken(), tooLarge, "logo.png", "image/png");
        assertThat(oversized.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Neither rejected attempt actually set a logo.
        assertThat(get("/organizations/me/logo", admin.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** A real image whose declared Content-Type/filename extension is simply wrong (e.g. a
     * PNG saved with a .gif extension by mistake) is still accepted - the actual bytes are
     * what's validated, matching the fix for the WebP-mislabeled-as-PNG bug below. */
    @Test
    void logo_acceptsRealImage_evenWithMismatchedDeclaredType() {
        AuthResponse admin = registerOrganization("Logo Mismatched Label Org");
        grant(admin.orgId());

        ResponseEntity<String> response = putLogo(admin.accessToken(), ONE_PIXEL_PNG, "logo.gif", "image/gif");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(response.getBody()).get("hasLogo").asBoolean()).isTrue();

        // Served back with the SNIFFED real content-type, not the originally-declared one.
        ResponseEntity<byte[]> download = restTemplate.exchange(baseUrl() + "/organizations/me/logo",
                HttpMethod.GET, new HttpEntity<>(bearerHeaders(admin.accessToken())), byte[].class);
        assertThat(download.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
    }

    /**
     * A real production bug (2026-08-02): a WebP file saved/exported with a ".png" filename gets
     * a browser-declared Content-Type of "image/png" (derived from the filename, not the actual
     * bytes) - it sailed through validation, previewed fine in Settings (a browser's <img> tag
     * renders WebP natively), but silently failed to appear in the generated invoice PDF, since
     * openhtmltopdf/PDFBox has no WebP decoder at all. BillingProfileService#uploadLogo now
     * sniffs the real magic bytes rather than trusting the declared Content-Type - this proves a
     * WebP file (RIFF container signature) is rejected up front with a clear error, regardless of
     * what content-type/filename claims otherwise.
     */
    @Test
    void logo_rejectsWebpFile_evenWhenDeclaredAsPng() {
        AuthResponse admin = registerOrganization("Logo Webp Org");
        grant(admin.orgId());

        // RIFF container header + "WEBP" fourCC - the real magic bytes of any WebP file,
        // regardless of VP8/VP8L/VP8X variant.
        byte[] webpBytes = "RIFF    WEBPVP8 ".getBytes(StandardCharsets.US_ASCII);
        ResponseEntity<String> response = putLogo(admin.accessToken(), webpBytes, "logo.png", "image/png");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/organizations/me/logo", admin.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void downloadPdf_withLogoAndHeaderFooterText_stillRendersValidPdf() {
        AuthResponse admin = registerOrganization("Logo Pdf Org");
        grant(admin.orgId());

        put("/organizations/me/billing-profile", admin.accessToken(), Map.of(
                "billingAddress", "Seller Address", "billingGstin", "GSTIN123", "billingPhone", "1234567890",
                "invoiceHeaderText", "Header note", "invoiceFooterText", "Footer note"));
        assertThat(putLogo(admin.accessToken(), ONE_PIXEL_PNG, "logo.png", "image/png").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        String invoiceId = createAdHocInvoice(admin.accessToken(), "Logo Pdf Customer");
        ResponseEntity<byte[]> response = getBytes("/invoices/" + invoiceId + "/pdf", admin.accessToken());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        byte[] body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(new String(body, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void downloadPdf_returnsRealPdfBytes_andRespectsOwnerScopedVisibility() {
        AuthResponse admin = registerOrganization("Invoice Pdf Org");
        AuthResponse employeeA = createAndLoginEmployee(admin.accessToken(), "pdfEmpA");
        AuthResponse employeeB = createAndLoginEmployee(admin.accessToken(), "pdfEmpB");
        grant(admin.orgId());

        put("/organizations/me/billing-profile", admin.accessToken(), Map.of(
                "billingAddress", "Seller Address", "billingGstin", "GSTIN123", "billingPhone", "1234567890"));

        String invoiceId = createAdHocInvoice(employeeA.accessToken(), "PDF Test Customer");

        ResponseEntity<byte[]> response = getBytes("/invoices/" + invoiceId + "/pdf", employeeA.accessToken());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getContentDisposition().getFilename()).endsWith(".pdf");
        byte[] body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.length).isGreaterThan(100);
        // A real PDF starts with the "%PDF-" magic header.
        assertThat(new String(body, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        // A colleague can't download it any more than they can view it.
        ResponseEntity<byte[]> forbidden = getBytes("/invoices/" + invoiceId + "/pdf", employeeB.accessToken());
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- helpers ----

    private String createAdHocInvoice(String token, String customerName) {
        Map<String, Object> lineItem = new HashMap<>();
        lineItem.put("description", "Misc Item");
        lineItem.put("quantity", 1);
        lineItem.put("unitPrice", 100.00);
        lineItem.put("taxRatePercent", 18.00);
        Map<String, Object> body = new HashMap<>();
        body.put("customerName", customerName);
        body.put("invoiceDate", LocalDate.now().toString());
        body.put("lineItems", List.of(lineItem));
        ResponseEntity<String> response = post("/invoices", token, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(response.getBody()).get("id").asText();
    }

    private void grant(UUID orgId) {
        Map<String, Object> body = new HashMap<>();
        body.put("action", "GRANT");
        body.put("grantedBy", "InvoicePdfIT");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Platform-Key", platformAdminKey);
        for (String code : java.util.List.of(CODE, "INVOICING")) {
            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl() + "/internal/organizations/" + orgId + "/entitlements/" + code,
                    HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    private AuthResponse createAndLoginEmployee(String adminToken, String label) {
        String email = label + "-" + UUID.randomUUID() + "@invoicepdf.test";
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

    private ResponseEntity<String> put(String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.exchange(baseUrl() + path, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(baseUrl() + path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<byte[]> getBytes(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(baseUrl() + path, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private ResponseEntity<String> putLogo(String token, byte[] fileBytes, String filename, String contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);

        ByteArrayResource resource = new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        HttpHeaders filePartHeaders = new HttpHeaders();
        filePartHeaders.setContentType(MediaType.parseMediaType(contentType));
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(resource, filePartHeaders));

        return restTemplate.exchange(baseUrl() + "/organizations/me/logo", HttpMethod.PUT,
                new HttpEntity<>(body, headers), String.class);
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
                "admin-" + UUID.randomUUID() + "@invoicepdf.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
