-- Invoicing gets its own entitlement (INVOICING), split out of INVENTORY_MANAGEMENT.
-- Every organization that already holds INVENTORY_MANAGEMENT is given INVOICING with the same
-- validity (expires_at / revoked_at carried over), so nobody loses access to quotations,
-- invoices, customers or the billing profile. Re-runnable: existing INVOICING rows are kept.
INSERT INTO organization_entitlements
    (organization_id, entitlement_code, granted_at, expires_at, granted_by, revoked_at)
SELECT organization_id, 'INVOICING', granted_at, expires_at, 'migration V24', revoked_at
FROM organization_entitlements
WHERE entitlement_code = 'INVENTORY_MANAGEMENT'
ON CONFLICT (organization_id, entitlement_code) DO NOTHING;
