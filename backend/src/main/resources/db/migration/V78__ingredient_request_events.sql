-- =====================================================================
-- V78 — the trail one request leaves behind (E10-S6)
--
-- "Raised by Gopal · 28 Aug. Sent for review · 28 Aug. Approved by Radha
-- — 'take from the older sack' · 29 Aug." That sentence per line is what
-- the request screen shows, and it is a different thing from the audit
-- log: audit is the tamper-evident record an investigator reads, this is
-- the story a cook reads to find out where their request got to.
--
-- Same shape as po_events, which answers the same question for a purchase
-- order, so the two screens can be read the same way.
--
-- ---------------------------------------------------------------------
-- Why this one is NOT append-only, when po_events is
--
-- A purchase order is never deleted. A draft request is: its author may
-- delete it, a Temple Admin may delete anybody's, and turning the meal
-- planner on for a kitchen deletes every draft in flight for it (E10-S4).
-- A request that was submitted, withdrawn back to draft and then deleted
-- has events by then, so those events have to be able to go with it —
-- and an append-only table cannot let them, not even through ON DELETE
-- CASCADE, because the cascade runs as the application role and the
-- append-only trigger refuses it.
--
-- Losing them costs nothing that matters. Every act worth keeping past
-- the row — submitted, approved, denied, issued, deleted — is written to
-- audit_events as well, and audit_events is append-only and outlives the
-- request. What dies here is a convenience view of a request that no
-- longer exists.
-- =====================================================================

CREATE TABLE ingredient_request_events (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    request_id    UUID        NOT NULL REFERENCES ingredient_requests(id) ON DELETE CASCADE,

    -- A short verb: CREATED, EDITED, SUBMITTED, WITHDRAWN, APPROVED, DENIED,
    -- ISSUED. Text rather than a CHECK for the same reason po_events uses text:
    -- the list grows with the screen that reads it, and a constraint would make
    -- every new sentence a migration.
    event_type    TEXT        NOT NULL,
    detail        TEXT,

    -- The name is copied, not joined. A person who has since left the temple
    -- still wrote this line, and the trail should keep saying so.
    actor_user_id UUID        REFERENCES users(id) ON DELETE RESTRICT,
    actor_name    TEXT,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ingredient_request_events_type_present CHECK (length(event_type) > 0)
);

COMMENT ON TABLE ingredient_request_events IS
    'What happened to one ingredient request, in order, for a person to read (E10-S6). The audit log is the tamper-evident record; this is the readable one, and it dies with the draft it describes.';

CREATE INDEX ingredient_request_events_request
    ON ingredient_request_events (tenant_id, request_id, created_at);

SELECT enable_tenant_rls('ingredient_request_events');
