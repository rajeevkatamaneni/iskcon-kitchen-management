-- =====================================================================
-- V77 — A kitchen asks the store for ingredients (E10-S5 to S7)
--
-- Until now stock left the store by exactly one door: a meal recorded in
-- the planner. That works for the kitchen whose meals are in this
-- application and for no other. A temple's other kitchens draw from the
-- same store room and cook food this system never sees, and the ledger
-- had no way to say so.
--
-- This is the second door. A kitchen writes down what it needs and when,
-- somebody with the authority answers, and the storekeeper records what
-- actually went over the counter. The recording is what moves the stock —
-- approval is a decision, issuing is a physical event, and the system
-- already draws that line between sending a purchase order and receiving
-- one.
--
-- ---------------------------------------------------------------------
-- Why the dish list is a table and not a note
--
-- Rajeev, 2026-08-30: "when we force the requester to list down what they
-- are cooking as part of this request, that will make them think what
-- ingredients they need and how much they need. That way they will have
-- everything they need and they don't resort to 'oh, let me get that too
-- just in case'. […] when someone looks at the filed inventory reports
-- during an audit, they can see issues if someone is over-provisioning
-- ingredients for the amount of food they are actually preparing."
--
-- So it is structured, it is required at submission, and it goes on the
-- printed work order — an auditor comparing 40 kg of rice against 200
-- servings of khichdi needs both halves on the same sheet.
--
-- It is a name and a number and nothing else. No recipe_id: most child
-- kitchens cook things this temple has never written a recipe for, and a
-- dish field that wants to be a recipe reference would either block them
-- or train them to pick the nearest wrong thing from a dropdown.
-- =====================================================================

CREATE TABLE ingredient_requests (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    -- Human-readable, minted per temple: IR-2026-0041. It exists so somebody
    -- can say it down a phone, which a UUID cannot survive.
    reference     TEXT        NOT NULL,

    kitchen_id    UUID        NOT NULL REFERENCES kitchens(id) ON DELETE RESTRICT,
    needed_on     DATE        NOT NULL,
    purpose       TEXT,

    status        TEXT        NOT NULL DEFAULT 'DRAFT',

    requested_by  UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    submitted_at  TIMESTAMPTZ,

    -- Who answered it, and what they said. On a request settled by the
    -- meal-planner cascade (E10-S4) this is the administrator who turned the
    -- planner on for that kitchen: they caused it, and an automatic denial
    -- with nobody's name on it is one nobody can ask about.
    decided_by    UUID        REFERENCES users(id) ON DELETE RESTRICT,
    decided_at    TIMESTAMPTZ,
    decision_note TEXT,

    issued_by     UUID        REFERENCES users(id) ON DELETE RESTRICT,
    issued_at     TIMESTAMPTZ,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ingredient_requests_status_valid CHECK (
        status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'DENIED', 'ISSUED')),
    CONSTRAINT ingredient_requests_purpose_length CHECK (
        purpose IS NULL OR length(purpose) <= 2000)
);

COMMENT ON TABLE ingredient_requests IS
    'One kitchen asking the store for ingredients (E10-S5). DRAFT to SUBMITTED to APPROVED or DENIED; an approved one is ISSUED once the store records what went out.';

CREATE UNIQUE INDEX ingredient_requests_reference_per_tenant
    ON ingredient_requests (tenant_id, reference);
CREATE INDEX ingredient_requests_tenant_status
    ON ingredient_requests (tenant_id, status, needed_on DESC);
CREATE INDEX ingredient_requests_tenant_kitchen
    ON ingredient_requests (tenant_id, kitchen_id, needed_on DESC);

SELECT enable_tenant_rls('ingredient_requests');

-- ---------------------------------------------------------------------
-- What the kitchen is asking for.
--
-- issued_quantity is the storekeeper's own figure, and it may be less than
-- what was approved or zero — the store hands over what it has, and a line
-- nobody could fill is a fact worth keeping rather than a row to delete. A
-- zero line writes no stock movement at all, the same rule a dish recorded
-- as not made already follows.
-- ---------------------------------------------------------------------

CREATE TABLE ingredient_request_lines (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    request_id      UUID        NOT NULL REFERENCES ingredient_requests(id) ON DELETE CASCADE,
    line_no         INTEGER     NOT NULL,

    ingredient_id   UUID        NOT NULL REFERENCES ingredients(id) ON DELETE RESTRICT,
    quantity        NUMERIC(14, 3) NOT NULL,
    unit            TEXT        NOT NULL,

    issued_quantity NUMERIC(14, 3),
    issued_unit     TEXT,

    note            TEXT,

    CONSTRAINT ingredient_request_lines_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ingredient_request_lines_unit_valid CHECK (
        unit IN ('KG', 'GM', 'L', 'ML', 'PIECES')),
    CONSTRAINT ingredient_request_lines_issued_nonnegative CHECK (
        issued_quantity IS NULL OR issued_quantity >= 0),
    CONSTRAINT ingredient_request_lines_issued_unit_valid CHECK (
        issued_unit IS NULL OR issued_unit IN ('KG', 'GM', 'L', 'ML', 'PIECES')),
    CONSTRAINT ingredient_request_lines_issued_complete CHECK (
        (issued_quantity IS NULL AND issued_unit IS NULL)
        OR (issued_quantity IS NOT NULL AND issued_unit IS NOT NULL))
);

COMMENT ON TABLE ingredient_request_lines IS
    'What one request asks for, and what the store actually issued against it (E10-S5, E10-S7).';

CREATE UNIQUE INDEX ingredient_request_lines_order
    ON ingredient_request_lines (request_id, line_no);
CREATE INDEX ingredient_request_lines_tenant_ingredient
    ON ingredient_request_lines (tenant_id, ingredient_id);

SELECT enable_tenant_rls('ingredient_request_lines');

-- ---------------------------------------------------------------------
-- What the kitchen is cooking. See this file's header for why it exists.
--
-- SERVINGS is admitted here and nowhere else in this schema, because a
-- dish genuinely is counted that way — 200 servings of khichdi — while an
-- ingredient never can be.
-- ---------------------------------------------------------------------

CREATE TABLE ingredient_request_dishes (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID    NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    request_id UUID    NOT NULL REFERENCES ingredient_requests(id) ON DELETE CASCADE,
    line_no    INTEGER NOT NULL,

    dish_name  TEXT    NOT NULL,
    quantity   NUMERIC(14, 3) NOT NULL,
    unit       TEXT    NOT NULL,

    CONSTRAINT ingredient_request_dishes_name_present CHECK (length(dish_name) > 0),
    CONSTRAINT ingredient_request_dishes_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ingredient_request_dishes_unit_valid CHECK (
        unit IN ('KG', 'GM', 'L', 'ML', 'PIECES', 'SERVINGS'))
);

COMMENT ON TABLE ingredient_request_dishes IS
    'What the kitchen says it is cooking, and how much of it (E10-S5). Required at submission: writing it down is what stops a request padded "just in case", and it is the other half of the comparison an auditor needs.';

CREATE UNIQUE INDEX ingredient_request_dishes_order
    ON ingredient_request_dishes (request_id, line_no);

SELECT enable_tenant_rls('ingredient_request_dishes');

-- ---------------------------------------------------------------------
-- The reference sequence, one per temple. Same shape as meal_card_sequence.
-- ---------------------------------------------------------------------

CREATE TABLE ingredient_request_sequence (
    tenant_id   UUID PRIMARY KEY REFERENCES tenants(id) ON DELETE RESTRICT,
    last_number INTEGER NOT NULL DEFAULT 0
);

SELECT enable_tenant_rls('ingredient_request_sequence');

-- ---------------------------------------------------------------------
-- The ledger admits a fifth kind of movement and a fifth kind of reference.
--
-- The kitchen is not copied onto the movement: reference_id points at the
-- request and the request carries the kitchen, so the two can never come to
-- disagree. storage_location stays null, as it is on every consumption
-- movement — it describes where in the store a thing sits, not where it went.
-- ---------------------------------------------------------------------

ALTER TABLE stock_movements DROP CONSTRAINT stock_movements_type_valid;
ALTER TABLE stock_movements ADD CONSTRAINT stock_movements_type_valid CHECK (
    movement_type IN ('PO_RECEIPT', 'DONATION_IN_KIND', 'CONSUMPTION', 'ADJUSTMENT', 'ISSUE'));

ALTER TABLE stock_movements DROP CONSTRAINT stock_movements_reference_valid;
ALTER TABLE stock_movements ADD CONSTRAINT stock_movements_reference_valid CHECK (
    reference_type IS NULL OR reference_type IN (
        'PURCHASE_ORDER', 'MEAL_PLAN', 'DONATION', 'CORRECTION', 'INGREDIENT_REQUEST'));
