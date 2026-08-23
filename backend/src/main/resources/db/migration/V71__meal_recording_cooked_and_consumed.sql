-- =====================================================================
-- V71 — What was cooked, and what was eaten (B5, follow-up)
--
-- Recording a meal collected one figure per dish: actual_servings, "what
-- actually went out". A returned job card carries two, and the office was being
-- asked to fold them into one. The card says how much was cooked, and how much
-- of it was served — the difference is what came back, and that difference is
-- the whole reason a temple records anything at all. A rasam planned at 60 L,
-- cooked at 60 L and consumed at 38 L is a plan that is wrong by twenty-two
-- litres every week, and the single-figure recording could not say so.
--
-- So the figure already collected keeps its meaning as WHAT WAS COOKED — it is
-- the one stock is drawn against, and drawing against anything else would be
-- wrong — and what was eaten arrives beside it. Both are in the preparation's
-- own yield unit (V69), the same unit the plan is written in, so planned,
-- cooked and consumed can be read down a column and compared without arithmetic.
--
-- Nullable: every meal recorded before today has a cooked figure and no
-- consumed one, and inventing one from the cooked figure would state that
-- nothing ever came back. Null means nobody said.
-- =====================================================================

ALTER TABLE meal_plans
    ADD COLUMN consumed_quantity NUMERIC(12, 3)
        CHECK (consumed_quantity IS NULL OR consumed_quantity >= 0);

COMMENT ON COLUMN meal_plans.consumed_quantity IS
    'How much of what was cooked was actually served, in the recipe yield unit (B5). Null until recorded, and null for meals recorded before V71 — never inferred from actual_servings.';

COMMENT ON COLUMN meal_plans.actual_servings IS
    'How much this dish was actually COOKED, in the recipe yield unit — the figure stock is drawn against (B5). Beside it, consumed_quantity says how much of it went out. Null until the meal is recorded; 0 for a dish that was not made.';
