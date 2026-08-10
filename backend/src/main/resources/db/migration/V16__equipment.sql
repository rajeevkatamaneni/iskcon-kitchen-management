-- =====================================================================
-- V16 — Equipment inventory (E3-S4)
--
-- The other half of "inventory": durable assets — a wet grinder, a steam
-- cauldron, trestle tables — that a temple owns and maintains, as opposed to
-- the consumables that flow through the ledger (V14/V15). Equipment is not a
-- quantity that moves; it is a thing in a state. So this is a plain tracked
-- record with a current condition, and an append-only history of how that
-- condition changed and why.
-- =====================================================================

CREATE TABLE equipment_items (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    name            TEXT        NOT NULL,

    -- What kind of thing it is. Fixed small vocabulary (CHECK), mirrored in code.
    category        TEXT        NOT NULL,

    storage_location TEXT,

    -- Current state. Changed only through a recorded state change, never blindly.
    condition       TEXT        NOT NULL DEFAULT 'GOOD',

    acquisition_date DATE,

    -- How it came to the temple. Null when its provenance isn't known.
    source          TEXT,

    notes           TEXT,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT equipment_name_present CHECK (length(name) > 0),
    CONSTRAINT equipment_category_valid CHECK (category IN ('MACHINE', 'TOOL', 'FURNITURE')),
    CONSTRAINT equipment_condition_valid CHECK (
        condition IN ('GOOD', 'NEEDS_REPAIR', 'IN_REPAIR', 'SCRAPPED')),
    CONSTRAINT equipment_source_valid CHECK (source IS NULL OR source IN ('PURCHASED', 'DONATED'))
);

COMMENT ON TABLE equipment_items IS
    'Durable temple assets tracked by condition (E3-S4). SCRAPPED items are hidden from default views.';

CREATE INDEX equipment_tenant_condition ON equipment_items (tenant_id, condition);
CREATE INDEX equipment_tenant_category ON equipment_items (tenant_id, category);

SELECT enable_tenant_rls('equipment_items');

-- The append-only trail of condition changes: who moved a thing from what to what,
-- and why. Readable by kitchen staff (MANAGE_INVENTORY) as the item's history —
-- which is why it is its own table rather than the admin-only audit log.
CREATE TABLE equipment_state_changes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    equipment_id    UUID        NOT NULL REFERENCES equipment_items(id) ON DELETE RESTRICT,

    from_condition  TEXT,
    to_condition    TEXT        NOT NULL,
    reason          TEXT        NOT NULL,

    actor_user_id   UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT equipment_change_to_valid CHECK (
        to_condition IN ('GOOD', 'NEEDS_REPAIR', 'IN_REPAIR', 'SCRAPPED')),
    CONSTRAINT equipment_change_from_valid CHECK (
        from_condition IS NULL OR from_condition IN ('GOOD', 'NEEDS_REPAIR', 'IN_REPAIR', 'SCRAPPED')),
    CONSTRAINT equipment_change_reason_present CHECK (length(reason) > 0)
);

CREATE INDEX equipment_changes_equipment ON equipment_state_changes (tenant_id, equipment_id, created_at DESC);

SELECT enable_tenant_rls('equipment_state_changes');
SELECT make_append_only('equipment_state_changes');
