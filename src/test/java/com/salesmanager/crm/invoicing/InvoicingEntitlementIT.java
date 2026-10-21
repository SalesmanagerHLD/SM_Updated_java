package com.salesmanager.crm.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.salesmanager.crm.BillingTestSupport;
import com.salesmanager.crm.auth.dto.AuthResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StreamUtils;

/** INVOICING is its own entitlement: invoicing-only companies work without inventory, and
 * inventory-only companies no longer get invoicing. V24 gave INVOICING to existing holders. */
class InvoicingEntitlementIT extends BillingTestSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void invoicingOnly_canBill_butNotUseProductsOrStock() {
        AuthResponse admin = registerOrganization("Invoicing Only Org");
        grant(admin.orgId(), "INVOICING");
        String t = admin.accessToken();

        assertThat(post("/customers", t, Map.of("name", "C")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(get("/customers", t).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/invoices", t, invoiceBody(List.of(adHocLine("Svc", 1, "100.00", "18", null))))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(get("/invoices", t).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/quotations", t).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/organizations/me/billing-profile", t).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(get("/inventory/products", t).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/inventory/products", t, Map.of("name", "P", "unitPrice", "1", "stockQuantity", 1))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Dashboard billing cards follow INVOICING.
        assertThat(okJson(get("/reports/revenue", t), HttpStatus.OK).get("entitled").asBoolean()).isTrue();
    }

    @Test
    void inventoryOnly_noLongerGetsInvoicing() {
        AuthResponse admin = registerOrganization("Inventory Only Org");
        grant(admin.orgId(), "INVENTORY_MANAGEMENT");
        String t = admin.accessToken();

        assertThat(get("/inventory/products", t).getStatusCode()).isEqualTo(HttpStatus.OK);
        for (String path : List.of("/customers", "/invoices", "/quotations", "/organizations/me/billing-profile")) {
            assertThat(get(path, t).getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(post("/invoices", t, invoiceBody(List.of(adHocLine("Svc", 1, "100.00", "18", null))))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(okJson(get("/reports/revenue", t), HttpStatus.OK).get("entitled").asBoolean()).isFalse();
    }

    @Test
    void migrationV24_givesInvoicingToEveryHolderOfInventory_keepingValidity_andIsRerunnable() throws Exception {
        AuthResponse holder = registerOrganization("V24 Holder Org");
        AuthResponse revoked = registerOrganization("V24 Revoked Org");
        AuthResponse neither = registerOrganization("V24 Neither Org");
        grant(holder.orgId(), "INVENTORY_MANAGEMENT");
        grant(revoked.orgId(), "INVENTORY_MANAGEMENT");
        revoke(revoked.orgId(), "INVENTORY_MANAGEMENT");
        assertThat(rows(holder.orgId(), "INVOICING")).isZero();

        String sql = StreamUtils.copyToString(
                new ClassPathResource("db/migration/V24__invoicing_entitlement.sql").getInputStream(),
                StandardCharsets.UTF_8);
        jdbcTemplate.execute(sql);
        jdbcTemplate.execute(sql); // idempotent: a second run adds nothing

        assertThat(rows(holder.orgId(), "INVOICING")).isEqualTo(1);
        assertThat(rows(revoked.orgId(), "INVOICING")).isEqualTo(1);
        assertThat(rows(neither.orgId(), "INVOICING")).isZero();
        // Validity is carried over: the revoked org's copy is revoked too, the holder's is not.
        assertThat(revokedAt(revoked.orgId())).isNotNull();
        assertThat(revokedAt(holder.orgId())).isNull();

        // And the holder can now actually use invoicing.
        assertThat(get("/invoices", holder.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/invoices", revoked.accessToken()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private int rows(UUID orgId, String code) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM organization_entitlements WHERE organization_id = ? AND entitlement_code = ?",
                Integer.class, orgId, code);
    }

    private Object revokedAt(UUID orgId) {
        return jdbcTemplate.queryForObject(
                "SELECT revoked_at FROM organization_entitlements WHERE organization_id = ? "
                        + "AND entitlement_code = 'INVOICING'", Object.class, orgId);
    }
}
