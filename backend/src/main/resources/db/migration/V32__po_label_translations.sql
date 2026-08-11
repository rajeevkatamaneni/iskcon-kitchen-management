-- =====================================================================
-- V32 — PO sheet label translation cache (E5-S5 refinement)
--
-- The PO sheet's fixed labels ("Purchase Order", "Item", "Quantity"…) are now
-- translated the same way the sheet's content is — glossary first, then machine
-- translation — instead of being hand-curated per language. That lets a vendor's
-- sheet render in any of the scheduled languages the picker offers, not just the
-- two that were curated by hand.
--
-- Labels are a small fixed set that changes only when we ship a new template, so
-- their translation is cached per (tenant, language, label-set version) — the
-- Postgres translation cache SYSTEM_DESIGN §6 calls for, so a language is
-- translated once, not on every render. A tenant glossary override participates,
-- which is why the cache is tenant-scoped. Bumping the label-set version in code
-- invalidates the cache without a data migration.
-- =====================================================================

CREATE TABLE po_label_translations (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    language          TEXT        NOT NULL,
    label_set_version INTEGER     NOT NULL,

    -- The translated labels, a JSON array in the template's fixed label order.
    content           JSONB       NOT NULL,
    -- Which MT engine produced them, for provenance (mirrors recipe translations).
    provider          TEXT        NOT NULL,

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX po_label_translations_key
    ON po_label_translations (tenant_id, language, label_set_version);

SELECT enable_tenant_rls('po_label_translations');
