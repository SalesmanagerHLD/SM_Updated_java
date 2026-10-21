package com.salesmanager.crm.masterdata;

import com.salesmanager.crm.security.TenantSessionManager;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds a starter set of master-data rows for a brand-new organization, so admins aren't
 * greeted with completely empty dropdowns right after registering. Relies entirely on
 * TenantContext already being active - it is called from inside AuthService's
 * activateTenant(...)/try/finally block, alongside creation of the first admin Employee.
 * Each org gets its OWN copy of these rows; they are freely editable per-org afterward.
 */
@Service
public class MasterDataSeedService {

    private final MasterDataRepository masterDataRepository;
    private final TenantSessionManager tenantSessionManager;

    public MasterDataSeedService(MasterDataRepository masterDataRepository,
                                  TenantSessionManager tenantSessionManager) {
        this.masterDataRepository = masterDataRepository;
        this.tenantSessionManager = tenantSessionManager;
    }

    /**
     * Backs the internal "seed master data" operator tool (InternalOrganizationController) for
     * an org that predates this seed set, or otherwise never got it (e.g. registered before this
     * service existed) - activates tenant context itself (unlike {@link #seedDefaults()}, which
     * assumes AuthService already did so during registration), same activate/clear-in-finally
     * pattern as AuthService#registerOrganization. Additive only: existing rows (including any
     * an admin already entered by hand) are untouched; this only adds the standard catalog
     * alongside them, skipping nothing and overwriting nothing.
     */
    @Transactional
    public void seedDefaultsForOrganization(UUID organizationId) {
        try {
            tenantSessionManager.activateTenant(organizationId);
            seedDefaults();
        } finally {
            tenantSessionManager.clearTenant();
        }
    }

    @Transactional
    public void seedDefaults() {
        // STATE must be seeded before CITY - each CITY row's parent_id is set to its STATE
        // row's freshly-generated id, looked up by code from the map this returns.
        Map<String, java.util.UUID> stateIdsByCode = seed(MasterType.STATE,
                "Andhra Pradesh", "Arunachal Pradesh", "Assam", "Bihar", "Chhattisgarh", "Goa",
                "Gujarat", "Haryana", "Himachal Pradesh", "Jharkhand", "Karnataka", "Kerala",
                "Madhya Pradesh", "Maharashtra", "Manipur", "Meghalaya", "Mizoram", "Nagaland",
                "Odisha", "Punjab", "Rajasthan", "Sikkim", "Tamil Nadu", "Telangana", "Tripura",
                "Uttar Pradesh", "Uttarakhand", "West Bengal", "Delhi");

        seedCities(stateIdsByCode);

        seed(MasterType.INDUSTRY,
                "Consumer Product Goods", "Manufacturing", "Retail", "IT Services", "Textile",
                "Healthcare", "Agriculture", "Automotive", "Construction & Real Estate", "Education",
                "Hospitality", "Logistics & Transportation", "Energy & Power", "Chemicals", "FMCG",
                "Banking & Financial Services", "Telecommunications", "Media & Entertainment",
                "Pharmaceuticals", "E-commerce");

        seed(MasterType.BUSINESS_TYPE,
                "Manufacturer", "Retailer", "Distributor", "Wholesaler", "Service Provider",
                "Trader", "Exporter", "Importer", "Franchise", "Sole Proprietorship",
                "Partnership", "Private Limited Company", "Public Limited Company",
                "Government/PSU", "Non-Profit/NGO");

        seed(MasterType.DESIGNATION,
                "Owner", "Proprietor", "Director", "Managing Director", "CEO", "CFO",
                "General Manager", "Manager", "Purchase Manager", "Purchase Head", "Engineer",
                "Site Engineer", "Supervisor", "Consultant", "Procurement Officer");

        seed(MasterType.VISIT_PURPOSE,
                "Sales Pitch", "Technical Demo", "Site Survey", "Support Inquiry",
                "Contract Negotiation", "Product Training", "Follow-up Meeting",
                "Payment Collection", "Relationship Building", "Complaint Resolution",
                "Installation Support");

        seed(MasterType.NEXT_ACTION,
                "Send Quote", "Schedule Demo", "Follow-up Call", "Send Samples", "Close Deal",
                "Send Proposal", "Arrange Site Visit", "Escalate to Manager",
                "Await Customer Decision", "Send Catalog");

        seed(MasterType.LOST_REASON,
                "Budget Cut", "Went With Competitor", "Project Cancelled", "No Response",
                "Price Too High", "Timing Not Right", "Requirement Changed",
                "Internal Politics/Decision Delay", "Product Mismatch", "Poor Past Experience");

        // Codes MUST be exactly HOT/WARM/COLD - future business logic (e.g. Lost -> Interest
        // = Cold) keys off these exact codes, so labels are deliberately chosen so the
        // standard uppercase-snake-case derivation below produces exactly that.
        Map<String, java.util.UUID> interestLevelIdsByCode =
                seed(MasterType.INTEREST_LEVEL, "Hot", "Warm", "Cold");
        seedInterestReasons(interestLevelIdsByCode);

        seed(MasterType.LEAD_SOURCE,
                "Referral", "Inbound Web Form", "Trade Show", "Cold Outreach", "Walk-in",
                "Social Media", "Advertisement", "Existing Customer Referral", "Partner/Channel",
                "Cold Call", "Email Campaign");

        seed(MasterType.PRODUCT,
                "Solar Inverter 3KVA", "Solar Inverter 5KVA", "Solar Inverter 10KVA",
                "Solar Panel 330W", "Solar Panel 550W", "Battery Backup", "Installation Service",
                "Annual Maintenance Contract", "Extended Warranty");
    }

    /**
     * Starter "why is it Hot/Warm/Cold" reasons, each with parent_id pointing at its interest
     * level. Only a starting set - admins add, rename or retire them under Masters. Labels must be
     * unique across levels (codes are unique per type).
     */
    private void seedInterestReasons(Map<String, java.util.UUID> interestLevelIdsByCode) {
        Map<String, String[]> reasonsByLevelCode = new java.util.LinkedHashMap<>();
        reasonsByLevelCode.put("HOT", new String[] {
                "Budget approved", "Urgent requirement", "Decision maker engaged", "Demo or quote requested"});
        reasonsByLevelCode.put("WARM", new String[] {
                "Evaluating options", "Waiting on budget", "Needs internal approval", "Comparing competitors"});
        reasonsByLevelCode.put("COLD", new String[] {
                "No current requirement", "Price is a concern", "Not the decision maker", "Long-term prospect"});
        int sortOrder = 0;
        for (Map.Entry<String, String[]> entry : reasonsByLevelCode.entrySet()) {
            java.util.UUID levelId = interestLevelIdsByCode.get(entry.getKey());
            if (levelId == null) {
                throw new IllegalStateException("No seeded INTEREST_LEVEL row with code " + entry.getKey());
            }
            for (String label : entry.getValue()) {
                masterDataRepository.save(MasterData.builder()
                        .type(MasterType.INTEREST_REASON)
                        .code(deriveCode(label))
                        .label(label)
                        .sortOrder(sortOrder++)
                        .active(true)
                        .parentId(levelId)
                        .build());
            }
        }
    }

    /**
     * CITY rows, each seeded with parent_id pointing at the id of its STATE row (looked up
     * from stateIdsByCode, populated by the STATE seed pass immediately before this runs).
     */
    private void seedCities(Map<String, java.util.UUID> stateIdsByCode) {
        int sortOrder = 0;
        sortOrder = seedCity("Mumbai", "MAHARASHTRA", stateIdsByCode, sortOrder);
        sortOrder = seedCity("Pune", "MAHARASHTRA", stateIdsByCode, sortOrder);
        sortOrder = seedCity("Nagpur", "MAHARASHTRA", stateIdsByCode, sortOrder);
        sortOrder = seedCity("Delhi", "DELHI", stateIdsByCode, sortOrder);
        sortOrder = seedCity("Bangalore", "KARNATAKA", stateIdsByCode, sortOrder);
        sortOrder = seedCity("Mysuru", "KARNATAKA", stateIdsByCode, sortOrder);
        sortOrder = seedCity("Chennai", "TAMIL_NADU", stateIdsByCode, sortOrder);
        sortOrder = seedCity("Coimbatore", "TAMIL_NADU", stateIdsByCode, sortOrder);
        sortOrder = seedCity("Hyderabad", "TELANGANA", stateIdsByCode, sortOrder);
        sortOrder = seedCity("Haldwani", "UTTARAKHAND", stateIdsByCode, sortOrder);
        sortOrder = seedCity("Dehradun", "UTTARAKHAND", stateIdsByCode, sortOrder);
        sortOrder = seedCity("Ahmedabad", "GUJARAT", stateIdsByCode, sortOrder);
        sortOrder = seedCity("Surat", "GUJARAT", stateIdsByCode, sortOrder);
        sortOrder = seedCity("Jaipur", "RAJASTHAN", stateIdsByCode, sortOrder);
        sortOrder = seedCity("Lucknow", "UTTAR_PRADESH", stateIdsByCode, sortOrder);
        sortOrder = seedCity("Kanpur", "UTTAR_PRADESH", stateIdsByCode, sortOrder);
        sortOrder = seedCity("Noida", "UTTAR_PRADESH", stateIdsByCode, sortOrder);
        sortOrder = seedCity("Kolkata", "WEST_BENGAL", stateIdsByCode, sortOrder);
        sortOrder = seedCity("Chandigarh", "PUNJAB", stateIdsByCode, sortOrder);
        sortOrder = seedCity("Bhopal", "MADHYA_PRADESH", stateIdsByCode, sortOrder);
        sortOrder = seedCity("Indore", "MADHYA_PRADESH", stateIdsByCode, sortOrder);
        sortOrder = seedCity("Patna", "BIHAR", stateIdsByCode, sortOrder);
        seedCity("Kochi", "KERALA", stateIdsByCode, sortOrder);
    }

    private int seedCity(String label, String stateCode, Map<String, java.util.UUID> stateIdsByCode, int sortOrder) {
        java.util.UUID stateId = stateIdsByCode.get(stateCode);
        if (stateId == null) {
            throw new IllegalStateException("No seeded STATE row with code " + stateCode);
        }
        MasterData masterData = MasterData.builder()
                .type(MasterType.CITY)
                .code(deriveCode(label))
                .label(label)
                .sortOrder(sortOrder)
                .active(true)
                .parentId(stateId)
                .build();
        masterDataRepository.save(masterData);
        return sortOrder + 1;
    }

    /** Returns the seeded rows' ids keyed by their derived code, for use by seedCities. */
    private Map<String, java.util.UUID> seed(MasterType type, String... labels) {
        Map<String, java.util.UUID> idsByCode = new LinkedHashMap<>();
        int sortOrder = 0;
        for (String label : labels) {
            String code = deriveCode(label);
            MasterData masterData = MasterData.builder()
                    .type(type)
                    .code(code)
                    .label(label)
                    .sortOrder(sortOrder++)
                    .active(true)
                    .build();
            MasterData saved = masterDataRepository.save(masterData);
            idsByCode.put(code, saved.getId());
        }
        return idsByCode;
    }

    private String deriveCode(String label) {
        String upper = label.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        return upper.replaceAll("^_+", "").replaceAll("_+$", "");
    }
}
