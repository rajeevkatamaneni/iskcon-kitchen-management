-- =====================================================================
-- V17 — In-kind donations (E3-S5)
--
-- A record of a gift given to the temple rather than bought. Release 1 records
-- in-kind donations (food into the stock ledger, equipment into the asset
-- register); Epic 6 extends this same table with monetary donations, which is
-- why the `type` column exists from the start.
--
-- The donated *things* are not duplicated here. Donated food is DONATION_IN_KIND
-- movements that reference this donation (V14.reference_type = 'DONATION'); a
-- donated asset is an equipment row whose donation_id points back here. The
-- donation is the intake event; the movements and equipment are the goods.
-- =====================================================================

CREATE TABLE donations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    type            TEXT        NOT NULL,

    -- Donor identity. Null name is allowed only for an anonymous gift; contact is
    -- optional and used only to send a thank-you.
    donor_name      TEXT,
    donor_phone     TEXT,
    donor_email     TEXT,
    is_anonymous    BOOLEAN     NOT NULL DEFAULT false,

    -- The temple's own estimate of worth, for its records. Optional.
    estimated_value_inr NUMERIC(12, 2),

    donated_on      DATE        NOT NULL,
    notes           TEXT,

    -- When the thank-you was queued; null if anonymous, unreachable, or not yet sent.
    acknowledged_at TIMESTAMPTZ,

    recorded_by     UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT donations_type_valid CHECK (type IN ('IN_KIND')),
    CONSTRAINT donations_named_unless_anonymous CHECK (is_anonymous OR donor_name IS NOT NULL),
    CONSTRAINT donations_value_nonnegative CHECK (
        estimated_value_inr IS NULL OR estimated_value_inr >= 0)
);

COMMENT ON TABLE donations IS
    'Gifts to the temple. In-kind in release 1 (goods live as movements/equipment); monetary added in Epic 6.';

CREATE INDEX donations_tenant_date ON donations (tenant_id, donated_on DESC);

SELECT enable_tenant_rls('donations');

-- A donated asset points back at the donation it came in on. Nullable — most
-- equipment is purchased, not donated.
ALTER TABLE equipment_items
    ADD COLUMN donation_id UUID REFERENCES donations(id) ON DELETE RESTRICT;
