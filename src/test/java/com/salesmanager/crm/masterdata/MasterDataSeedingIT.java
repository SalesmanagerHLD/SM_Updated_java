package com.salesmanager.crm.masterdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salesmanager.crm.AbstractIntegrationTest;
import com.salesmanager.crm.auth.dto.AuthResponse;
import com.salesmanager.crm.auth.dto.RegisterOrganizationRequest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Proves that registering a new organization seeds a sensible starter set of master data
 * across all 11 dropdown-driving types (including Phase 7's new STATE type, with CITY rows
 * correctly linked to their STATE via parentId), so an admin never sees completely empty
 * dropdowns.
 */
class MasterDataSeedingIT extends AbstractIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void registeringOrganization_seedsAllTwelveMasterTypes_withASensibleTotal() {
        AuthResponse admin = registerOrganization();

        int total = 0;
        for (MasterType type : MasterType.values()) {
            JsonNode entries = listMasters(admin.accessToken(), type);
            assertThat(entries.isArray()).isTrue();
            assertThat(entries.size())
                    .as("expected at least one seeded entry for %s", type)
                    .isGreaterThan(0);
            total += entries.size();
        }

        // Not an exact count (seed data may be tweaked over time) but proves it's a
        // real, non-trivial starter set rather than a handful of stub rows. Phase 7's richer
        // seed lists (plus the new STATE type and 12 starter interest reasons) push the real total
        // to ~168.
        assertThat(total).isBetween(140, 220);
    }

    @Test
    void registeringOrganization_seedsAllTwentyNineStates() {
        AuthResponse admin = registerOrganization();

        JsonNode states = listMasters(admin.accessToken(), MasterType.STATE);
        assertThat(states.size()).isEqualTo(29);

        Set<String> codes = new HashSet<>();
        states.forEach(node -> codes.add(node.get("code").asText()));
        assertThat(codes).contains("MAHARASHTRA", "UTTARAKHAND", "DELHI", "KARNATAKA", "TAMIL_NADU");
    }

    @Test
    void everySeededCity_hasANonNullParentIdPointingAtTheCorrectSeededState() {
        AuthResponse admin = registerOrganization();

        JsonNode states = listMasters(admin.accessToken(), MasterType.STATE);
        Map<String, String> stateIdByCode = new HashMap<>();
        states.forEach(node -> stateIdByCode.put(node.get("code").asText(), node.get("id").asText()));

        JsonNode cities = listMasters(admin.accessToken(), MasterType.CITY);
        assertThat(cities.size()).isGreaterThan(0);

        Map<String, String> stateCodeByCityCode = Map.of(
                "HALDWANI", "UTTARAKHAND",
                "MUMBAI", "MAHARASHTRA",
                "DELHI", "DELHI",
                "CHENNAI", "TAMIL_NADU",
                "KOCHI", "KERALA");

        for (JsonNode city : cities) {
            assertThat(city.get("parentId").isNull())
                    .as("city %s must have a non-null parentId", city.get("code").asText())
                    .isFalse();
        }

        for (JsonNode city : cities) {
            String cityCode = city.get("code").asText();
            String expectedStateCode = stateCodeByCityCode.get(cityCode);
            if (expectedStateCode != null) {
                String expectedStateId = stateIdByCode.get(expectedStateCode);
                assertThat(expectedStateId)
                        .as("seeded STATE %s must exist", expectedStateCode)
                        .isNotNull();
                assertThat(city.get("parentId").asText())
                        .as("city %s should belong to state %s", cityCode, expectedStateCode)
                        .isEqualTo(expectedStateId);
            }
        }
    }

    @Test
    void eachNewOrganization_getsItsOwnIndependentCopyOfStateSeedData() {
        AuthResponse orgA = registerOrganization();
        AuthResponse orgB = registerOrganization();

        JsonNode orgAStates = listMasters(orgA.accessToken(), MasterType.STATE);
        JsonNode orgBStates = listMasters(orgB.accessToken(), MasterType.STATE);

        assertThat(orgAStates.size()).isEqualTo(orgBStates.size());

        Set<String> orgAIds = new HashSet<>();
        orgAStates.forEach(node -> orgAIds.add(node.get("id").asText()));
        Set<String> orgBIds = new HashSet<>();
        orgBStates.forEach(node -> orgBIds.add(node.get("id").asText()));

        // Same seed content, but each org has its own distinct rows (different ids) - proves
        // the STATE type is tenant-isolated the same way every other master type already is.
        assertThat(orgAIds).doesNotContainAnyElementsOf(orgBIds);
    }

    @Test
    void interestLevel_seedsExactlyHotWarmColdCodes() {
        AuthResponse admin = registerOrganization();

        JsonNode entries = listMasters(admin.accessToken(), MasterType.INTEREST_LEVEL);
        assertThat(entries.size()).isEqualTo(3);

        Set<String> codes = new HashSet<>();
        entries.forEach(node -> codes.add(node.get("code").asText()));
        assertThat(codes).containsExactlyInAnyOrder("HOT", "WARM", "COLD");
    }

    @Test
    void eachNewOrganization_getsItsOwnIndependentCopyOfSeedData() {
        AuthResponse orgA = registerOrganization();
        AuthResponse orgB = registerOrganization();

        JsonNode orgACities = listMasters(orgA.accessToken(), MasterType.CITY);
        JsonNode orgBCities = listMasters(orgB.accessToken(), MasterType.CITY);

        assertThat(orgACities.size()).isEqualTo(orgBCities.size());

        Set<String> orgAIds = new HashSet<>();
        orgACities.forEach(node -> orgAIds.add(node.get("id").asText()));
        Set<String> orgBIds = new HashSet<>();
        orgBCities.forEach(node -> orgBIds.add(node.get("id").asText()));

        // Same seed content, but each org has its own distinct rows (different ids).
        assertThat(orgAIds).doesNotContainAnyElementsOf(orgBIds);
    }

    private JsonNode listMasters(String accessToken, MasterType type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/masters/" + type + "?includeInactive=true",
                HttpMethod.GET, entity, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private AuthResponse registerOrganization() {
        RegisterOrganizationRequest request = new RegisterOrganizationRequest(
                "Seed Test Org", "seed-org-" + UUID.randomUUID(), "Admin User",
                "admin-" + UUID.randomUUID() + "@seed.test", "password123");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl() + "/auth/register-organization", new HttpEntity<>(request, headers), AuthResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
