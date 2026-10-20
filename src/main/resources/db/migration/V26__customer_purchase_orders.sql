-- Customer purchase orders: the order a buyer sends after accepting a quotation. One PO can be
-- billed by many invoices (partial deliveries); an invoice belongs to at most one PO.
-- Money is numeric(12,2); billed quantity is stored per PO line, remaining is always calculated.

CREATE TABLE customer_po (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    lead_id uuid NOT NULL REFERENCES leads(id),
    quotation_id uuid NULL REFERENCES quotations(id),
    customer_id uuid NULL REFERENCES customers(id),
    owner_id uuid NOT NULL REFERENCES employees(id),
    created_by uuid NOT NULL REFERENCES employees(id),
    po_number varchar(100) NOT NULL,
    po_date date NOT NULL,
    delivery_date date NULL,
    payment_terms_days integer NULL CHECK (payment_terms_days IS NULL OR payment_terms_days BETWEEN 0 AND 365),
    status varchar(20) NOT NULL DEFAULT 'RECEIVED'
        CHECK (status IN ('RECEIVED', 'ACCEPTED', 'PARTLY_BILLED', 'FULLY_BILLED', 'CANCELLED')),
    -- Snapshot of the customer at save time, same approach as invoices.
    customer_name varchar(255) NOT NULL,
    customer_gstin varchar(20) NULL,
    billing_address varchar(1000) NULL,
    shipping_address varchar(1000) NULL,
    place_of_supply_state_id uuid NULL,
    taxable_total numeric(12,2) NOT NULL,
    cgst_total numeric(12,2) NOT NULL DEFAULT 0,
    sgst_total numeric(12,2) NOT NULL DEFAULT 0,
    igst_total numeric(12,2) NOT NULL DEFAULT 0,
    tax_total numeric(12,2) NOT NULL,
    grand_total numeric(12,2) NOT NULL,
    notes varchar(2000) NULL,
    cancel_reason varchar(500) NULL,
    cancelled_at timestamptz NULL,
    cancelled_by uuid NULL REFERENCES employees(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_customer_po_number UNIQUE (organization_id, lead_id, po_number)
);

CREATE INDEX idx_customer_po_org_lead ON customer_po (organization_id, lead_id);
CREATE INDEX idx_customer_po_org_status ON customer_po (organization_id, status);
CREATE INDEX idx_customer_po_org_owner ON customer_po (organization_id, owner_id);

CREATE TABLE customer_po_line (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    po_id uuid NOT NULL REFERENCES customer_po(id),
    product_id uuid NULL REFERENCES products(id),
    quotation_line_id uuid NULL REFERENCES quotation_line_items(id),
    description varchar(500) NOT NULL,
    hsn_sac varchar(20) NULL,
    unit varchar(50) NULL,
    quantity_ordered numeric(12,2) NOT NULL CHECK (quantity_ordered > 0),
    quantity_billed numeric(12,2) NOT NULL DEFAULT 0 CHECK (quantity_billed >= 0),
    rate numeric(12,2) NOT NULL CHECK (rate >= 0),
    tax_rate_percent numeric(5,2) NOT NULL CHECK (tax_rate_percent >= 0),
    line_taxable_amount numeric(12,2) NOT NULL,
    line_cgst_amount numeric(12,2) NOT NULL DEFAULT 0,
    line_sgst_amount numeric(12,2) NOT NULL DEFAULT 0,
    line_igst_amount numeric(12,2) NOT NULL DEFAULT 0,
    line_total numeric(12,2) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_customer_po_line_po ON customer_po_line (organization_id, po_id);

ALTER TABLE customer_po ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer_po FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_customer_po ON customer_po
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

ALTER TABLE customer_po_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer_po_line FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_customer_po_line ON customer_po_line
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

-- A PO is cancelled, never deleted.
GRANT SELECT, INSERT, UPDATE ON customer_po TO salesmanager_app;
-- Lines may be replaced while a PO has never been invoiced (see PurchaseOrderService#update).
GRANT SELECT, INSERT, UPDATE, DELETE ON customer_po_line TO salesmanager_app;

-- Invoice -> PO link. po_number / po_date are snapshots (and may be typed on an invoice that has
-- no PO at all, in which case po_id stays null). po_override_reason is set when an ADMIN
-- billed past a line's remaining quantity.
ALTER TABLE invoices
    ADD COLUMN po_id uuid NULL REFERENCES customer_po(id),
    ADD COLUMN po_number varchar(100) NULL,
    ADD COLUMN po_date date NULL,
    ADD COLUMN po_override_reason varchar(500) NULL;
CREATE INDEX idx_invoices_po ON invoices (organization_id, po_id) WHERE po_id IS NOT NULL;

ALTER TABLE invoice_line_items
    ADD COLUMN po_line_id uuid NULL REFERENCES customer_po_line(id);
CREATE INDEX idx_invoice_line_items_po_line ON invoice_line_items (po_line_id) WHERE po_line_id IS NOT NULL;

-- The customer's PO scan reuses the lead attachment feature, linked to the PO.
ALTER TABLE lead_attachments ADD COLUMN po_id uuid NULL REFERENCES customer_po(id);
