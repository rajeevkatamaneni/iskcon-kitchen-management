-- =====================================================================
-- V23 — Ekadashi violation flagging (E4-S6)
--
-- Grains, beans and certain flours are not eaten on Ekadashi. This adds an
-- ingredient flag (parallel to the sattvic-prohibited flag) marking those
-- items, and — on a meal plan — the record of a Temple's deliberate
-- acknowledgment when it plans a grain/bean recipe on an Ekadashi anyway
-- (legitimate: temples cook grains for non-fasting visitors and children).
--
-- Unlike the sattvic block (a hard stop — no legitimate exception at cooking
-- time), Ekadashi is a warning requiring explicit acknowledgment, not a block.
-- =====================================================================

ALTER TABLE ingredients
    ADD COLUMN is_ekadashi_prohibited BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN ingredients.is_ekadashi_prohibited IS
    'Grain/bean/flour not eaten on Ekadashi (E4-S6). Set by a Temple Admin, like the sattvic flag.';

-- Who acknowledged an Ekadashi violation on this plan, and when. Null unless a
-- non-compatible recipe was knowingly planned on an Ekadashi.
ALTER TABLE meal_plans
    ADD COLUMN ekadashi_ack_by UUID REFERENCES users(id) ON DELETE RESTRICT,
    ADD COLUMN ekadashi_ack_at TIMESTAMPTZ;
