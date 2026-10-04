-- =====================================================================
-- V160 — The Kitchen Assistant's own records (E10-S2, task T4)
--
-- Plan: docs/work/E10-KITCHEN-ASSISTANT-PLAN.md, §1 row A. A person asks
-- the assistant a question on the /assistant page; the api starts one
-- `claude -p` run to answer it; the run reads the temple's data through
-- the MCP tools and may propose a change, which nothing carries out until
-- the person confirms it with their own sign-in. Four tables hold that:
--
--   assistant_conversations    one thread of questions, owned by one user
--   assistant_messages         what was said in it, in order
--   assistant_runs             one row per `claude -p` run: what it cost,
--                              how long it took, how it ended
--   assistant_pending_actions  a change the assistant proposed, waiting
--                              for the person to confirm or reject it
--
-- Nothing reads or writes these yet. The api (S3) and the propose tools
-- (S4) are later stories; this migration lands first so that both are
-- written against a schema that already has its isolation in place.
--
-- ---------------------------------------------------------------------
-- ROW-LEVEL SECURITY
--
-- All four carry tenant_id and call enable_tenant_rls(), as every
-- tenant-owned table must (RowLevelSecurityIT.everyTenantOwnedTableIsProtected
-- finds them by their tenant_id column). This matters more here than
-- almost anywhere: the reader of these rows on the MCP side is a language
-- model acting on a token, and the only thing standing between it and
-- another temple's conversation is the policy, not the code that asked.
--
-- The links between the four tables are composite keys that include
-- tenant_id, (tenant_id, conversation_id) -> assistant_conversations
-- (tenant_id, id), the way V149 links meals to meal_series. A foreign key
-- is checked by the table owner, which row-level security does not
-- constrain, so a plain conversation_id key would accept another temple's
-- conversation if one ever reached an INSERT. The service will never pass
-- one — it reads the conversation under the policy first — but "the
-- database says no" is this project's rule for tenancy, not "the service
-- is careful".
--
-- user_id and decided_by are plain keys to users, like every other table's
-- reference to a person (ingredient_requests.requested_by, V77). users has
-- no (tenant_id, id) key to point a composite at, and adding one to a
-- shared table is a change of its own; the api takes the user from the
-- verified token, never from the request.
--
-- Grants: V1's ALTER DEFAULT PRIVILEGES gives kms_app SELECT, INSERT,
-- UPDATE and DELETE on every table the migration role creates, so there is
-- no GRANT here. make_append_only() below re-grants on its own table for
-- the foreign-key reason V49 explains.
--
-- Everything here is DDL, run as the owner. No rows are written, so there
-- is no per-tenant seed or backfill to get wrong.
--
-- ---------------------------------------------------------------------
-- WHICH TABLES ARE APPEND-ONLY, AND WHY ONLY ONE
--
-- assistant_messages: append-only (make_append_only, V49/V50). A message
-- is a record of what a person typed and what the assistant answered, and
-- a confirmed change can be traced back to the reply that proposed it.
-- An editable transcript proves nothing about what was actually said. The
-- assistant's reply is written once, when its run finishes (plan §2 step
-- 6), so nothing in the planned flow needs to change a message after it is
-- written. If deleting a conversation is ever wanted — a person's chat may
-- name people — that is a decision to take deliberately, with its own
-- path, not a privilege left lying about.
--
-- assistant_runs: NOT append-only. A run row is written when the run
-- starts (status RUNNING) and finished in place when `claude -p` exits or
-- is killed, because the per-user and per-tenant daily budgets (plan §1
-- row D) must count a run that is still going; a row written only at the
-- end would let ten simultaneous messages all pass a budget of one. The
-- CHECKs below keep the two halves consistent: RUNNING has no finish
-- fields, and every other status has finished_at and duration_ms.
--
-- assistant_pending_actions: NOT append-only. A proposal moves PROPOSED to
-- CONFIRMED, REJECTED or EXPIRED, once. What makes it trustworthy is not
-- immutability of the row but payload_sha256: the confirm endpoint
-- re-hashes the payload it is about to carry out and refuses on a
-- mismatch (plan §2 step 7). Who decided, and when, is also written to the
-- audit log by the service that carries it out.
--
-- assistant_conversations: NOT append-only. Its title is set after the
-- first exchange and updated_at moves with every message.
--
-- ---------------------------------------------------------------------
-- VALUES ARE UPPERCASE
--
-- role, status and kind hold Java enum names, uppercase, like every other
-- status column in this schema (ingredient_requests.status, V77). The plan
-- writes them in lower case; they mean the same thing.
--
-- ---------------------------------------------------------------------
-- DELETION
--
-- Every key is RESTRICT. Nothing in the application deletes a user, a
-- conversation or a run. The tenant purge (delete_tenant_cascade, V86)
-- finds tables by their tenant_id column and retries until a pass deletes
-- nothing, and it announces itself to the append-only trigger, so it needs
-- no entry for these tables. There is no cycle among the keys: runs and
-- actions point at conversations, messages and actions point at runs, and
-- nothing points back.
-- =====================================================================


-- ---------------------------------------------------------------------
-- A conversation
-- ---------------------------------------------------------------------
CREATE TABLE assistant_conversations (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    -- Whose conversation it is. Only they read it back (the api filters by
    -- the signed-in user within the temple the policy already fixes).
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,

    -- A short name for the list of past conversations. NULL until the api
    -- gives it one, typically from the first question.
    title       TEXT,

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT assistant_conversations_title_length CHECK (
        title IS NULL OR length(title) BETWEEN 1 AND 200),

    -- The target of the composite keys from the other three tables.
    CONSTRAINT assistant_conversations_tenant_id_id UNIQUE (tenant_id, id)
);

COMMENT ON TABLE assistant_conversations IS
    'E10: one Kitchen Assistant conversation, owned by one user.';

-- "My conversations, most recent first": the only list the page shows.
CREATE INDEX assistant_conversations_by_user
    ON assistant_conversations (tenant_id, user_id, updated_at DESC);

SELECT enable_tenant_rls('assistant_conversations');


-- ---------------------------------------------------------------------
-- A run: one `claude -p` process answering one message
-- ---------------------------------------------------------------------
CREATE TABLE assistant_runs (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    conversation_id    UUID        NOT NULL,

    -- Who the run acted for. The agent token carries this user's
    -- permissions and no more (E10-S2 contract), so a run is always "this
    -- person, asking through the assistant".
    user_id            UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,

    -- The model alias or id `claude -p` was started with, e.g. 'sonnet'.
    model              TEXT        NOT NULL,

    status             TEXT        NOT NULL DEFAULT 'RUNNING',

    started_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at        TIMESTAMPTZ,
    duration_ms        INTEGER,

    -- From the run's final usage line. NULL when the run died before it
    -- reported any, which is itself worth seeing.
    input_tokens       INTEGER,
    output_tokens      INTEGER,

    -- What the run would have cost at API prices. Notional: the runner
    -- signs in with a subscription, so nothing is billed per run, but this
    -- is the figure that says whether a budget is set sensibly.
    notional_cost_usd  NUMERIC(12, 6),

    tool_calls         INTEGER     NOT NULL DEFAULT 0,

    -- The KMS- code the person was shown when the run did not succeed.
    error_code         TEXT,

    CONSTRAINT assistant_runs_status_valid CHECK (
        status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT')),
    -- RUNNING has no finish fields; anything else has both.
    CONSTRAINT assistant_runs_finish_matches_status CHECK (
        (status = 'RUNNING' AND finished_at IS NULL AND duration_ms IS NULL)
        OR (status <> 'RUNNING' AND finished_at IS NOT NULL AND duration_ms IS NOT NULL)),
    CONSTRAINT assistant_runs_finish_after_start CHECK (
        finished_at IS NULL OR finished_at >= started_at),
    CONSTRAINT assistant_runs_model_present CHECK (length(model) BETWEEN 1 AND 100),
    CONSTRAINT assistant_runs_counts_non_negative CHECK (
        (duration_ms IS NULL OR duration_ms >= 0)
        AND (input_tokens IS NULL OR input_tokens >= 0)
        AND (output_tokens IS NULL OR output_tokens >= 0)
        AND (notional_cost_usd IS NULL OR notional_cost_usd >= 0)
        AND tool_calls >= 0),
    -- A code only on a run that went wrong, and only in the permanent form.
    CONSTRAINT assistant_runs_error_code_form CHECK (
        error_code IS NULL
        OR (status IN ('FAILED', 'TIMED_OUT') AND error_code ~ '^KMS-[0-9]{6}$')),

    CONSTRAINT assistant_runs_conversation_same_tenant
        FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES assistant_conversations (tenant_id, id) ON DELETE RESTRICT,

    -- The target of the composite keys from messages and pending actions.
    CONSTRAINT assistant_runs_tenant_id_id UNIQUE (tenant_id, id)
);

COMMENT ON TABLE assistant_runs IS
    'E10: one claude -p run. Written RUNNING at start so the daily budgets count it, finished in place when the process exits.';
COMMENT ON COLUMN assistant_runs.notional_cost_usd IS
    'API-price cost of the run as claude -p reports it. Not billed: the runner uses a subscription sign-in.';

CREATE INDEX assistant_runs_by_conversation
    ON assistant_runs (tenant_id, conversation_id, started_at);
-- The two daily budgets (plan §1 row D): runs today by this user, and runs
-- today at this temple.
CREATE INDEX assistant_runs_by_user_day
    ON assistant_runs (tenant_id, user_id, started_at);
CREATE INDEX assistant_runs_by_tenant_day
    ON assistant_runs (tenant_id, started_at);

SELECT enable_tenant_rls('assistant_runs');


-- ---------------------------------------------------------------------
-- A message. Append-only: see the header.
-- ---------------------------------------------------------------------
CREATE TABLE assistant_messages (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    conversation_id  UUID        NOT NULL,

    role             TEXT        NOT NULL,
    content          TEXT        NOT NULL,

    -- The run that wrote an assistant reply. NULL on the person's own
    -- message, which is saved before its run starts (plan §2 step 2).
    run_id           UUID,

    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT assistant_messages_role_valid CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT assistant_messages_user_has_no_run CHECK (role = 'ASSISTANT' OR run_id IS NULL),

    CONSTRAINT assistant_messages_conversation_same_tenant
        FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES assistant_conversations (tenant_id, id) ON DELETE RESTRICT,
    -- MATCH SIMPLE: a message with no run is not checked against runs.
    CONSTRAINT assistant_messages_run_same_tenant
        FOREIGN KEY (tenant_id, run_id)
        REFERENCES assistant_runs (tenant_id, id) ON DELETE RESTRICT
);

COMMENT ON TABLE assistant_messages IS
    'E10: what was said in a Kitchen Assistant conversation. Append-only: a transcript is a record.';

-- A conversation's messages in order: the page, and the last N turns sent
-- with each new question (plan §2 step 2).
CREATE INDEX assistant_messages_by_conversation
    ON assistant_messages (tenant_id, conversation_id, created_at);

SELECT enable_tenant_rls('assistant_messages');
SELECT make_append_only('assistant_messages');


-- ---------------------------------------------------------------------
-- A proposed change, waiting for the person
-- ---------------------------------------------------------------------
CREATE TABLE assistant_pending_actions (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    conversation_id  UUID        NOT NULL,
    run_id           UUID        NOT NULL,

    -- Which propose tool wrote it (plan §3). A new kind is a new tool, and
    -- arrives with its own migration.
    kind             TEXT        NOT NULL,

    -- Exactly what confirming will do, in the shape the existing service
    -- takes. Shown on the confirmation card; carried out only if it still
    -- hashes to payload_sha256.
    payload          JSONB       NOT NULL,
    payload_sha256   TEXT        NOT NULL,

    -- Set by the propose tool so that a retried tool call finds its first
    -- proposal instead of making a second card. Unique per temple.
    idempotency_key  TEXT        NOT NULL,

    status           TEXT        NOT NULL DEFAULT 'PROPOSED',
    expires_at       TIMESTAMPTZ NOT NULL,

    -- Who confirmed or rejected it, and when. An EXPIRED proposal was
    -- decided by nobody; decided_at is then when it was marked expired, if
    -- anything marked it.
    decided_by       UUID        REFERENCES users(id) ON DELETE RESTRICT,
    decided_at       TIMESTAMPTZ,

    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT assistant_pending_actions_kind_valid CHECK (
        kind IN ('SHOPPING_LIST', 'STOCK_ADJUSTMENT')),
    CONSTRAINT assistant_pending_actions_status_valid CHECK (
        status IN ('PROPOSED', 'CONFIRMED', 'REJECTED', 'EXPIRED')),
    CONSTRAINT assistant_pending_actions_sha256_form CHECK (
        payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT assistant_pending_actions_key_length CHECK (
        length(idempotency_key) BETWEEN 1 AND 200),
    CONSTRAINT assistant_pending_actions_expires_after_created CHECK (
        expires_at > created_at),
    -- PROPOSED: undecided. CONFIRMED / REJECTED: by someone, at a time.
    -- EXPIRED: by nobody.
    CONSTRAINT assistant_pending_actions_decision_matches_status CHECK (
        (status = 'PROPOSED' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status IN ('CONFIRMED', 'REJECTED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
        OR (status = 'EXPIRED' AND decided_by IS NULL)),

    CONSTRAINT assistant_pending_actions_conversation_same_tenant
        FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES assistant_conversations (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT assistant_pending_actions_run_same_tenant
        FOREIGN KEY (tenant_id, run_id)
        REFERENCES assistant_runs (tenant_id, id) ON DELETE RESTRICT
);

COMMENT ON TABLE assistant_pending_actions IS
    'E10: a change the assistant proposed. Nothing happens until the user confirms it with their own sign-in; confirm re-checks payload_sha256.';

CREATE UNIQUE INDEX assistant_pending_actions_idempotency_per_tenant
    ON assistant_pending_actions (tenant_id, idempotency_key);
CREATE INDEX assistant_pending_actions_by_conversation
    ON assistant_pending_actions (tenant_id, conversation_id, created_at);

SELECT enable_tenant_rls('assistant_pending_actions');
