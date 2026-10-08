-- =====================================================================
-- V163 — Kitchen Assistant drafts: the purchase-order kind, and what a
-- confirmed draft became (E10-S4)
--
-- Contract: docs/work/E10-S4-CONTRACT.md. The assistant drafts a change,
-- the person presses Confirm, and the server carries it out through the
-- existing service under the person's own sign-in. V160 made the table for
-- those drafts (assistant_pending_actions) and its header says a new kind
-- arrives with its own migration. This is that migration for the first
-- real kind, a draft purchase order for one vendor.
--
-- Two changes, both to assistant_pending_actions:
--
-- 1. kind may now be PURCHASE_ORDER. The two kinds V160 named
--    (SHOPPING_LIST, STOCK_ADJUSTMENT) stay allowed: nothing has written
--    them, and dropping a value from a CHECK is a decision for the story
--    that decides those kinds will never be built. MEAL_PLAN and the
--    others are added by the migrations that bring their handlers.
--
-- 2. result_label and result_href: what a confirmed draft became, as the
--    card shows it ("PO-2026-0047", "/orders/<id>"). Written once, in the
--    same transaction that carries the draft out and marks it CONFIRMED.
--    Kept on the row rather than looked up again, because a repeated
--    Confirm must answer with the same result without running anything
--    (the contract's idempotency rule), and because the card on a reloaded
--    conversation must say what happened without knowing every kind's
--    tables. Both or neither, and only on a CONFIRMED draft.
--
-- No new table, so no RLS call: the table already has its policy (V160).
-- DDL only, run as the owner; no rows are written, so there is no
-- per-tenant backfill.
-- =====================================================================

ALTER TABLE assistant_pending_actions
    DROP CONSTRAINT assistant_pending_actions_kind_valid;

ALTER TABLE assistant_pending_actions
    ADD CONSTRAINT assistant_pending_actions_kind_valid CHECK (
        kind IN ('SHOPPING_LIST', 'STOCK_ADJUSTMENT', 'PURCHASE_ORDER'));

ALTER TABLE assistant_pending_actions
    ADD COLUMN result_label TEXT,
    ADD COLUMN result_href  TEXT;

ALTER TABLE assistant_pending_actions
    ADD CONSTRAINT assistant_pending_actions_result_when_confirmed CHECK (
        (result_label IS NULL AND result_href IS NULL)
        OR (status = 'CONFIRMED' AND result_label IS NOT NULL AND result_href IS NOT NULL
            AND length(result_label) BETWEEN 1 AND 200
            AND length(result_href) BETWEEN 1 AND 500));

COMMENT ON COLUMN assistant_pending_actions.result_label IS
    'E10-S4: what a confirmed draft became, as a person names it, e.g. PO-2026-0047.';
COMMENT ON COLUMN assistant_pending_actions.result_href IS
    'E10-S4: the app path of what a confirmed draft became, e.g. /orders/<id>.';
