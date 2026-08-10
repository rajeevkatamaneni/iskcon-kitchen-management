-- =====================================================================
-- V12 — Generated documents (E2-S5)
--
-- Tracks a generated artifact — first use: a recipe PDF at a chosen scale — as
-- it moves PENDING -> READY (or FAILED). The bytes live in object storage (GCS,
-- or a local dir in dev); this table holds the record and the storage key, so
-- the UI can poll status and then fetch the file.
--
-- Not append-only: a document is regenerated in place (re-render bumps it back
-- through PENDING). Tenant-owned like everything else.
-- =====================================================================

CREATE TABLE documents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    -- What this document is of. First kind: a recipe PDF.
    kind            TEXT        NOT NULL,
    recipe_id       UUID        REFERENCES recipes(id) ON DELETE CASCADE,

    -- The language the document was rendered in (E2-S6 renders non-English), and
    -- the scale it was rendered at (null = the recipe's base yield).
    language        TEXT        NOT NULL DEFAULT 'en',
    target_yield    NUMERIC(12, 3),

    status          TEXT        NOT NULL DEFAULT 'PENDING',

    -- Where the bytes are in object storage once READY; null while pending/failed.
    storage_key     TEXT,
    -- Why it failed, for the record; null otherwise.
    error           TEXT,

    created_by      UUID        REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    ready_at        TIMESTAMPTZ,

    CONSTRAINT documents_kind_valid CHECK (kind IN ('RECIPE_PDF')),
    CONSTRAINT documents_status_valid CHECK (status IN ('PENDING', 'READY', 'FAILED'))
);

CREATE INDEX documents_tenant_recipe ON documents (tenant_id, recipe_id, created_at DESC);

SELECT enable_tenant_rls('documents');
