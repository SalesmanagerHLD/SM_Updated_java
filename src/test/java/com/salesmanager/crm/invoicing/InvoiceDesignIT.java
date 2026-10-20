package com.salesmanager.crm.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salesmanager.crm.BillingTestSupport;
import com.salesmanager.crm.auth.dto.AuthResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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

/** Custom invoice design: defaults, admin-only save/reset, validation, live-preview PDF, and the
 * per-invoice design + logo snapshot that stops old invoices changing. */
class InvoiceDesignIT extends BillingTestSupport {

    private static final String DESIGN = "/organizations/me/invoice-design";

    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Value("${spring.flyway.url}")
    private String dbUrl;

    @Value("${spring.flyway.user}")
    private String dbUser;

    @Value("${spring.flyway.password}")
    private String dbPassword;

    private AuthResponse admin(String name) {
        AuthResponse admin = registerOrganization(name);
        grant(admin.orgId(), "INVOICING");
        return admin;
    }

    private ObjectNode defaults(String token) {
        return ((ObjectNode) parse(get(DESIGN, token).getBody()).get("settings")).deepCopy();
    }

    @Test
    void newOrganization_getsDefaultDesign_anyUserCanRead_onlyAdminCanSave() {
        AuthResponse admin = admin("Design Default Org");
        AuthResponse employee = createAndLoginEmployee(admin.accessToken(), "designEmployee");

        JsonNode initial = okJson(get(DESIGN, employee.accessToken()), HttpStatus.OK);
        assertThat(initial.get("isDefault").asBoolean()).isTrue();
        assertThat(initial.get("version").asInt()).isZero();
        assertThat(initial.get("settings").get("preset").asText()).isEqualTo("CLASSIC");
        assertThat(initial.get("settings").get("pageSize").asText()).isEqualTo("A4");

        ObjectNode changed = defaults(admin.accessToken());
        changed.put("preset", "BAND");
        assertThat(put(DESIGN, employee.accessToken(), changed).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(HttpMethod.DELETE, DESIGN, employee.accessToken(), null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminSave_bumpsVersion_validationRejectsBadValues_resetRestoresDefault() {
        AuthResponse admin = admin("Design Save Org");
        ObjectNode design = defaults(admin.accessToken());
        design.put("preset", "BAND");
        design.put("pageSize", "A5");
        design.put("accentColor", "#1F4E78");
        design.put("termsAndConditions", "No returns after 7 days.");

        JsonNode saved = okJson(put(DESIGN, admin.accessToken(), design), HttpStatus.OK);
        assertThat(saved.get("version").asInt()).isEqualTo(1);
        assertThat(saved.get("isDefault").asBoolean()).isFalse();

        design.put("billToLabel", "Billed To");
        assertThat(okJson(put(DESIGN, admin.accessToken(), design), HttpStatus.OK).get("version").asInt()).isEqualTo(2);
        JsonNode reloaded = okJson(get(DESIGN, admin.accessToken()), HttpStatus.OK);
        assertThat(reloaded.get("settings").get("billToLabel").asText()).isEqualTo("Billed To");
        assertThat(reloaded.get("settings").get("pageSize").asText()).isEqualTo("A5");

        ObjectNode badColour = design.deepCopy();
        badColour.put("accentColor", "red; } body { display:none");
        assertThat(put(DESIGN, admin.accessToken(), badColour).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ObjectNode badEnum = design.deepCopy();
        badEnum.put("preset", "NOT_A_PRESET");
        assertThat(put(DESIGN, admin.accessToken(), badEnum).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ObjectNode tooLong = design.deepCopy();
        tooLong.put("billToLabel", "x".repeat(31));
        assertThat(put(DESIGN, admin.accessToken(), tooLong).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        JsonNode reset = okJson(exchange(HttpMethod.DELETE, DESIGN, admin.accessToken(), null), HttpStatus.OK);
        assertThat(reset.get("isDefault").asBoolean()).isTrue();
        assertThat(okJson(get(DESIGN, admin.accessToken()), HttpStatus.OK).get("settings").get("preset").asText())
                .isEqualTo("CLASSIC");
    }

    @Test
    void preview_rendersUnsavedSettingsToPdf_forAnyUser_andStoresNothing() {
        AuthResponse admin = admin("Design Preview Org");
        AuthResponse employee = createAndLoginEmployee(admin.accessToken(), "previewEmployee");
        ObjectNode design = defaults(admin.accessToken());
        design.put("preset", "COMPACT");
        design.put("pageSize", "LETTER");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(employee.accessToken());
        ResponseEntity<byte[]> pdf = restTemplate.exchange(baseUrl() + DESIGN + "/preview", HttpMethod.POST,
                new HttpEntity<>(Map.of("settings", design), headers), byte[].class);
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pdf.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(new String(pdf.getBody(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");

        // Nothing was saved by previewing.
        assertThat(okJson(get(DESIGN, admin.accessToken()), HttpStatus.OK).get("isDefault").asBoolean()).isTrue();

        design.put("accentColor", "nope");
        ResponseEntity<String> invalid = restTemplate.exchange(baseUrl() + DESIGN + "/preview", HttpMethod.POST,
                new HttpEntity<>(Map.of("settings", design), headers), String.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void issuedInvoice_keepsItsDesignAndLogo_whenDesignOrLogoChangesLater() throws Exception {
        AuthResponse admin = admin("Design Snapshot Org");
        assertThat(putLogo(admin.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
        ObjectNode band = defaults(admin.accessToken());
        band.put("preset", "BAND");
        band.put("accentColor", "#AA0000");
        okJson(put(DESIGN, admin.accessToken(), band), HttpStatus.OK);

        JsonNode invoice = createInvoice(admin.accessToken(),
                List.of(adHocLine("Widget", 1, "1000.00", "18", null)));
        String invoiceId = invoice.get("id").asText();

        // Change the design and delete the logo AFTER issuing.
        ObjectNode classic = defaults(admin.accessToken());
        classic.put("preset", "CLASSIC");
        classic.put("accentColor", "#00AA00");
        okJson(put(DESIGN, admin.accessToken(), classic), HttpStatus.OK);
        okJson(exchange(HttpMethod.DELETE, "/organizations/me/logo", admin.accessToken(), null), HttpStatus.OK);

        try (Connection c = DriverManager.getConnection(dbUrl, dbUser, dbPassword); Statement s = c.createStatement()) {
            ResultSet design = s.executeQuery(
                    "select design_settings::text from invoices where id = '" + invoiceId + "'");
            assertThat(design.next()).isTrue();
            assertThat(design.getString(1)).contains("\"BAND\"").contains("#AA0000").doesNotContain("#00AA00");
            ResultSet logo = s.executeQuery(
                    "select count(*) from invoice_logo_snapshots where invoice_id = '" + invoiceId + "'");
            assertThat(logo.next()).isTrue();
            assertThat(logo.getInt(1)).isEqualTo(1);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(admin.accessToken());
        ResponseEntity<byte[]> pdf = restTemplate.exchange(baseUrl() + "/invoices/" + invoiceId + "/pdf",
                HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(pdf.getBody(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    private ResponseEntity<String> putLogo(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        ByteArrayResource resource = new ByteArrayResource(ONE_PIXEL_PNG) {
            @Override
            public String getFilename() {
                return "logo.png";
            }
        };
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.IMAGE_PNG);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(resource, partHeaders));
        return restTemplate.exchange(baseUrl() + "/organizations/me/logo", HttpMethod.PUT,
                new HttpEntity<>(body, headers), String.class);
    }
}
