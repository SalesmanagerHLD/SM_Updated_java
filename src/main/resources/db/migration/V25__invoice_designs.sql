-- Custom invoice design (settings-driven PDF layout, see "Custom Invoice Format Plan").
--
-- invoice_designs: one row per (organization, document_type) holding the admin's chosen design
-- as a JSON block that is validated against a fixed set of allowed values in the application
-- (InvoiceDesignSettings) - never free HTML. version bumps on every save.
CREATE TABLE invoice_designs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    document_type varchar(20) NOT NULL CHECK (document_type IN ('INVOICE', 'QUOTATION')),
    settings jsonb NOT NULL,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, document_type)
);

ALTER TABLE invoice_designs ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoice_designs FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_invoice_designs ON invoice_designs
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON invoice_designs TO salesmanager_app;

-- Each invoice remembers the design it was issued with (a copy of the settings, not a pointer),
-- so changing the design later never rewrites an already-issued invoice. NULL on invoices issued
-- before this migration: they render with the default design.
ALTER TABLE invoices
    ADD COLUMN design_settings jsonb NULL;

-- Logo copy per invoice, in its own table so loading an Invoice never drags up to 2 MB of
-- bytea along with it. Absent for invoices issued without a logo (and before this migration,
-- which fall back to the organization's live logo).
CREATE TABLE invoice_logo_snapshots (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    invoice_id uuid NOT NULL UNIQUE REFERENCES invoices(id),
    image bytea NOT NULL,
    content_type varchar(50) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE invoice_logo_snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoice_logo_snapshots FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_invoice_logo_snapshots ON invoice_logo_snapshots
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

GRANT SELECT, INSERT, UPDATE, DELETE ON invoice_logo_snapshots TO salesmanager_app;
