-- Billing Phase 0 (GST Billing Module Research, "Backend audit" fixes 1-9): IGST support, a
-- seller snapshot on every invoice, a payments ledger, cancellation, a one-invoice-per-
-- quotation guarantee, and financial-year invoice numbering support.

-- 1. Seller state: the state side of the CGST+SGST vs IGST decision. master_data STATE row.
ALTER TABLE organizations
    ADD COLUMN billing_state_id uuid NULL REFERENCES master_data(id);

-- IGST on quotations (stored at write time like CGST/SGST).
ALTER TABLE quotations
    ADD COLUMN igst_total numeric(12,2) NOT NULL DEFAULT 0;
ALTER TABLE quotation_line_items
    ADD COLUMN line_igst_amount numeric(12,2) NOT NULL DEFAULT 0;

-- Invoice: customer link (#9), place-of-supply state (#1), seller snapshot (#7), header
-- totals (#8), cancellation (#6).
ALTER TABLE invoices
    ADD COLUMN customer_id uuid NULL REFERENCES customers(id),
    ADD COLUMN place_of_supply_state_id uuid NULL REFERENCES master_data(id),
    ADD COLUMN seller_name varchar(255) NULL,
    ADD COLUMN seller_gstin varchar(20) NULL,
    ADD COLUMN seller_address varchar(500) NULL,
    ADD COLUMN seller_phone varchar(50) NULL,
    ADD COLUMN seller_state_id uuid NULL REFERENCES master_data(id),
    ADD COLUMN seller_bank_name varchar(255) NULL,
    ADD COLUMN seller_bank_account_number varchar(50) NULL,
    ADD COLUMN seller_bank_ifsc varchar(20) NULL,
    ADD COLUMN seller_bank_branch varchar(255) NULL,
    ADD COLUMN seller_upi_id varchar(100) NULL,
    ADD COLUMN seller_header_text varchar(1000) NULL,
    ADD COLUMN seller_footer_text varchar(1000) NULL,
    ADD COLUMN discount_total numeric(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN taxable_amount numeric(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN cgst_total numeric(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN sgst_total numeric(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN igst_total numeric(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN cancelled_at timestamptz NULL,
    ADD COLUMN cancel_reason varchar(500) NULL,
    ADD COLUMN cancelled_by uuid NULL REFERENCES employees(id);

ALTER TABLE invoice_line_items
    ADD COLUMN unit varchar(20) NULL,
    ADD COLUMN line_igst_amount numeric(12,2) NOT NULL DEFAULT 0;

-- Backfill header totals from the existing lines, and the seller snapshot from the
-- organization's CURRENT profile (the best information available for already-issued invoices).
UPDATE invoices i SET
    discount_total = COALESCE(s.discount, 0),
    cgst_total = COALESCE(s.cgst, 0),
    sgst_total = COALESCE(s.sgst, 0),
    taxable_amount = i.subtotal - COALESCE(s.discount, 0)
FROM (
    SELECT invoice_id, SUM(line_discount_amount) AS discount, SUM(line_cgst_amount) AS cgst,
           SUM(line_sgst_amount) AS sgst
    FROM invoice_line_items GROUP BY invoice_id
) s
WHERE s.invoice_id = i.id;

UPDATE invoices i SET
    seller_name = o.name, seller_gstin = o.billing_gstin, seller_address = o.billing_address,
    seller_phone = o.billing_phone, seller_bank_name = o.bank_name,
    seller_bank_account_number = o.bank_account_number, seller_bank_ifsc = o.bank_ifsc,
    seller_bank_branch = o.bank_branch, seller_upi_id = o.upi_id,
    seller_header_text = o.invoice_header_text, seller_footer_text = o.invoice_footer_text
FROM organizations o
WHERE o.id = i.organization_id;

-- 6. Status now covers partial payment and cancellation. It is a cache maintained in exactly
-- one place (InvoiceService) from the payments ledger below - never set directly.
ALTER TABLE invoices DROP CONSTRAINT invoices_status_check;
ALTER TABLE invoices ADD CONSTRAINT invoices_status_check
    CHECK (status IN ('UNPAID', 'PARTIALLY_PAID', 'PAID', 'CANCELLED'));

-- 3. One live invoice per quotation, enforced by the database as well as by the row lock
-- (a cancelled invoice no longer occupies the quotation).
CREATE UNIQUE INDEX uq_invoices_quotation_id ON invoices (quotation_id)
    WHERE quotation_id IS NOT NULL AND status <> 'CANCELLED';

-- 6. Payments ledger: one row per payment, per mode. INSERT-only for the app role - a wrong
-- payment is corrected by cancelling the invoice, never by editing history.
CREATE TABLE invoice_payments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    invoice_id uuid NOT NULL REFERENCES invoices(id),
    amount numeric(12,2) NOT NULL CHECK (amount > 0),
    mode varchar(20) NOT NULL CHECK (mode IN ('CASH', 'UPI', 'CARD', 'BANK_TRANSFER', 'CHEQUE', 'OTHER')),
    payment_date date NOT NULL,
    reference varchar(100) NULL,
    notes varchar(500) NULL,
    recorded_by uuid NOT NULL REFERENCES employees(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_invoice_payments_invoice ON invoice_payments (organization_id, invoice_id);

ALTER TABLE invoice_payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoice_payments FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_invoice_payments ON invoice_payments
    USING (
        current_setting('app.bypass_rls', true) = 'on'
        OR organization_id = NULLIF(current_setting('app.current_org', true), '')::uuid
    );

GRANT SELECT, INSERT ON invoice_payments TO salesmanager_app;

-- Existing PAID invoices keep their meaning: one full payment each, so the derived status
-- agrees with the stored one.
INSERT INTO invoice_payments (organization_id, invoice_id, amount, mode, payment_date, recorded_by)
SELECT organization_id, id, grand_total, 'OTHER', updated_at::date, owner_id
FROM invoices WHERE status = 'PAID' AND grand_total > 0;

-- 6. Stock reversal on cancellation needs its own ledger reason.
ALTER TABLE stock_movements DROP CONSTRAINT stock_movements_reason_check;
ALTER TABLE stock_movements ADD CONSTRAINT stock_movements_reason_check
    CHECK (reason IN ('MANUAL_ADJUSTMENT', 'INVOICE', 'INVOICE_CANCELLATION'));

-- 6. An issued invoice is never deleted - only cancelled.
REVOKE DELETE ON invoices FROM salesmanager_app;
REVOKE DELETE ON invoice_line_items FROM salesmanager_app;
