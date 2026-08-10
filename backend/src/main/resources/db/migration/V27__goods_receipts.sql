-- =====================================================================
-- V27 — Goods receipts (E5-S6)
--
-- Recording exactly what arrived against a purchase order, so inventory reflects
-- the truck, not the order. One receipt per delivery; a PO may be received in
-- several deliveries. Received quantities write PO_RECEIPT movements (with batch,
-- expiry, received-date) into the immutable ledger; rejected quantities are
-- recorded with a reason and never touch stock. What is still outstanding
-- (ordered − received) re-feeds the order list (E5-S2).
--
-- A receipt is a fact about a moment: append-only, like the ledger it drives.
-- Idempotency: each submission carries a client key, unique per tenant, so a
-- double-click or a retried request can never double-book stock (SYSTEM_DESIGN §6).
-- =====================================================================

CREATE TABLE goods_receipts (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    po_id             UUID        NOT NULL REFERENCES purchase_orders(id) ON DELETE RESTRICT,

    -- Client-supplied dedup key. A retried submission carries the same key, so the
    -- unique index below turns a double-post into a no-op instead of a second receipt.
    idempotency_key   TEXT        NOT NULL,

    -- Optional delivery-note scan/photo in object storage (GCS object name).
    delivery_note_ref TEXT,
    note              TEXT,

    received_by       UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    received_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE goods_receipts IS 'One delivery received against a PO (E5-S6); append-only.';

CREATE UNIQUE INDEX goods_receipts_idempotency ON goods_receipts (tenant_id, idempotency_key);
CREATE INDEX goods_receipts_po ON goods_receipts (tenant_id, po_id, received_at);

SELECT enable_tenant_rls('goods_receipts');
SELECT make_append_only('goods_receipts');

CREATE TABLE goods_receipt_lines (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    receipt_id        UUID        NOT NULL REFERENCES goods_receipts(id) ON DELETE CASCADE,
    po_line_id        UUID        NOT NULL REFERENCES purchase_order_lines(id) ON DELETE RESTRICT,
    ingredient_id     UUID        NOT NULL REFERENCES ingredients(id) ON DELETE RESTRICT,

    received_qty      NUMERIC(14, 3) NOT NULL DEFAULT 0,
    rejected_qty      NUMERIC(14, 3) NOT NULL DEFAULT 0,
    reject_reason     TEXT,
    unit              TEXT        NOT NULL,

    -- Set on the received portion: the batch it established in the ledger, and the
    -- food-safety fields carried onto that movement.
    batch_id          UUID,
    expiry_date       DATE,
    received_date     DATE,

    -- The PO_RECEIPT movement this line booked, when received_qty > 0. Links the
    -- receipt to the immutable ledger row it produced.
    stock_movement_id UUID        REFERENCES stock_movements(id) ON DELETE RESTRICT,

    CONSTRAINT gr_lines_qty_nonneg CHECK (received_qty >= 0 AND rejected_qty >= 0),
    CONSTRAINT gr_lines_some_qty CHECK (received_qty > 0 OR rejected_qty > 0),
    CONSTRAINT gr_lines_reason_valid CHECK (
        reject_reason IS NULL OR reject_reason IN ('DAMAGED', 'SPOILED', 'WRONG_ITEM', 'OTHER')),
    -- A rejection must say why; a reason without a rejected quantity is meaningless.
    CONSTRAINT gr_lines_reason_iff_rejected CHECK (
        (rejected_qty > 0) = (reject_reason IS NOT NULL))
);

CREATE INDEX gr_lines_receipt ON goods_receipt_lines (receipt_id);
CREATE INDEX gr_lines_po_line ON goods_receipt_lines (tenant_id, po_line_id);

SELECT enable_tenant_rls('goods_receipt_lines');
SELECT make_append_only('goods_receipt_lines');
