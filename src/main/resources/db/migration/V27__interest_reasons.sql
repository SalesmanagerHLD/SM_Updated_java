-- "Why is this lead Hot/Warm/Cold?" - an admin-managed INTEREST_REASON master type whose rows
-- hang off an INTEREST_LEVEL row via master_data.parent_id (same parent link CITY uses for
-- STATE), plus the lead/visit join tables recording which reasons were picked.
ALTER TABLE master_data DROP CONSTRAINT master_data_type_check;
ALTER TABLE master_data ADD CONSTRAINT master_data_type_check CHECK (type IN (
    'INDUSTRY', 'CITY', 'PRODUCT', 'BUSINESS_TYPE', 'DESIGNATION', 'VISIT_PURPOSE',
    'NEXT_ACTION', 'LOST_REASON', 'INTEREST_LEVEL', 'LEAD_SOURCE', 'STATE', 'INTEREST_REASON'
));

-- Same shape as lead_products / visit_products: scoping flows transitively through the parent
-- row, never queried independently, so no RLS policy of its own.
CREATE TABLE lead_interest_reasons (
    lead_id uuid NOT NULL REFERENCES leads(id),
    reason_id uuid NOT NULL REFERENCES master_data(id),
    PRIMARY KEY (lead_id, reason_id)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON lead_interest_reasons TO salesmanager_app;

CREATE TABLE visit_interest_reasons (
    visit_id uuid NOT NULL REFERENCES visits(id),
    reason_id uuid NOT NULL REFERENCES master_data(id),
    PRIMARY KEY (visit_id, reason_id)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON visit_interest_reasons TO salesmanager_app;

CREATE INDEX idx_lead_interest_reasons_reason ON lead_interest_reasons (reason_id);
