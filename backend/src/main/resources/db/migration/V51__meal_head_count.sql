-- =====================================================================
-- V51 — who the meal is for, and what the kitchen was told
--
-- A meal has always carried a servings figure. What it never carried is where
-- that figure came from: a temple counts the hall as adults, children and
-- seniors, and only then arrives at servings — children eat about six tenths of
-- a portion, seniors about eight tenths. Keeping the count means the planner can
-- show its working, and re-scale a meal when the expected turnout changes rather
-- than asking someone to redo the arithmetic.
--
-- The columns are nullable: every meal planned before today has a servings
-- figure and no breakdown, and inventing one for it would be a lie.
-- =====================================================================

ALTER TABLE meal_plans
    ADD COLUMN adults        INTEGER CHECK (adults        IS NULL OR adults        >= 0),
    ADD COLUMN children      INTEGER CHECK (children      IS NULL OR children      >= 0),
    ADD COLUMN seniors       INTEGER CHECK (seniors       IS NULL OR seniors       >= 0),
    ADD COLUMN kitchen_notes TEXT    CHECK (kitchen_notes IS NULL OR length(kitchen_notes) <= 2000);

COMMENT ON COLUMN meal_plans.adults IS
    'Expected adults. The servings figure is derived from the three counts, then may be overridden per dish.';
COMMENT ON COLUMN meal_plans.children IS
    'Expected children, counted at 0.6 of a portion when servings are derived.';
COMMENT ON COLUMN meal_plans.seniors IS
    'Expected seniors, counted at 0.8 of a portion when servings are derived.';
COMMENT ON COLUMN meal_plans.kitchen_notes IS
    'What the planner wants the cooks to know about this meal — "cook the kheer thin".';
