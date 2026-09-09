-- =====================================================================
-- V108 — Returning goods to a vendor after they were accepted (T-013)
--
-- Rejection only ever worked at the gate. goods_receipt_lines.rejected_qty is
-- a field of the receiving submission itself, so it can only say what the
-- storekeeper refused off the lorry, in the minute the lorry was there. Weevils
-- found in the sacks the next morning, or fifty kilos keyed when five arrived,
-- had no path at all: the stock was on the books and nothing in the application
-- could take it back off them without editing history.
--
-- Two halves, and they are one change:
--
--   1. The ledger admits a sixth kind of movement, RETURN_TO_VENDOR. The CHECK
--      constraint is the other half of MovementType.java, and a value added on
--      one side alone fails at runtime rather than at compile time — on the day
--      somebody presses the button, not on the day the enum was edited.
--
--   2. goods_returns records what went back, per receipt line. It is a separate
--      table and not a column on goods_receipt_lines, deliberately: that table
--      is append-only because a receipt is a fact about a moment, and a return
--      is a *later* fact about the same goods. Writing it onto the receipt would
--      mean editing what the storekeeper signed for, which is the one thing the
--      append-only guard exists to prevent.
--
-- The cap lives in the service (KMS-400140) rather than in a CHECK, because it
-- is a sum across rows this table does not have a natural constraint over: what
-- may still be returned is the receipt line's received_qty less everything
-- already returned against it, and only the reading transaction can see that.
-- =====================================================================

ALTER TABLE stock_movements DROP CONSTRAINT stock_movements_type_valid;
ALTER TABLE stock_movements ADD CONSTRAINT stock_movements_type_valid CHECK (
    movement_type IN ('PO_RECEIPT', 'DONATION_IN_KIND', 'CONSUMPTION', 'ADJUSTMENT', 'ISSUE',
                      'RETURN_TO_VENDOR'));

-- reference_type is deliberately NOT extended. A return points back at the
-- purchase order the goods came in on, which is a value the ledger already has
-- and which is the truthful answer to "what was this movement about" — the
-- vendor is on the order, and the receipt line is on goods_returns below. A
-- GOODS_RECEIPT reference type would be a second way to say the same thing.

CREATE TABLE goods_returns (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    -- Both, and not just the line. The line is what the quantity is capped
    -- against; the receipt is how the screen groups returns under the delivery
    -- they came from, without a join it would otherwise need on every read.
    receipt_id        UUID        NOT NULL REFERENCES goods_receipts(id) ON DELETE RESTRICT,
    receipt_line_id   UUID        NOT NULL REFERENCES goods_receipt_lines(id) ON DELETE RESTRICT,

    -- Client-supplied dedup key, exactly as goods_receipts uses one. A return
    -- draws stock down and the ledger is append-only, so a double-click that got
    -- through would need a compensating adjustment to undo — the unique index
    -- below turns it into a no-op instead.
    idempotency_key   TEXT        NOT NULL,

    -- Positive here, and negated on the way into the ledger. This column reads
    -- as "how much went back", which is how a person says it; the sign belongs
    -- to the movement, where every other quantity in the system carries it.
    quantity          NUMERIC(14, 3) NOT NULL,
    unit              TEXT        NOT NULL,

    reason            TEXT        NOT NULL,
    note              TEXT,

    -- The RETURN_TO_VENDOR movement this row booked. NOT NULL: a return that
    -- moved no stock is not a return, it is a note.
    stock_movement_id UUID        NOT NULL REFERENCES stock_movements(id) ON DELETE RESTRICT,

    returned_by       UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    returned_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT goods_returns_qty_positive CHECK (quantity > 0),
    CONSTRAINT goods_returns_unit_valid CHECK (unit IN ('KG', 'GM', 'L', 'ML', 'PIECES')),
    -- Mirrors ReturnReason.java. NOT_DELIVERED is the one that is not in
    -- RejectReason: it is the fifty-keyed-for-five case, where nothing is
    -- physically going back because nothing physically arrived, and calling that
    -- WRONG_ITEM or OTHER would lose the only distinction a vendor scorecard
    -- would later care about.
    CONSTRAINT goods_returns_reason_valid CHECK (
        reason IN ('DAMAGED', 'SPOILED', 'WRONG_ITEM', 'NOT_DELIVERED', 'OTHER'))
);

COMMENT ON TABLE goods_returns IS
    'Goods sent back to the vendor after they were taken into stock (T-013); append-only.';

CREATE UNIQUE INDEX goods_returns_idempotency
    ON goods_returns (tenant_id, receipt_line_id, idempotency_key);
CREATE INDEX goods_returns_line ON goods_returns (tenant_id, receipt_line_id);
CREATE INDEX goods_returns_receipt ON goods_returns (tenant_id, receipt_id, returned_at);

SELECT enable_tenant_rls('goods_returns');
SELECT make_append_only('goods_returns');
