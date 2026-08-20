-- =====================================================================
-- V62 — Leave, and the week grid's own overrides (B7; build brief 2026-08-20 §4 and §6)
--
-- Two changes that are really one idea: there must be exactly one answer to
-- "why is this person not in on Thursday", stored in exactly one place.
--
-- 1. **staff_leave** is a request-and-approve log and nothing more. No accrual,
--    no balances, no carry-forward, no encashment — the temple never asked for
--    any of it, and a balance column nobody maintains is a number that lies to
--    whoever reads it next. What it records is: who, what kind, which dates,
--    who asked, who answered, and what they said. Back-dating is allowed on
--    purpose; somebody rings in sick at six in the morning and the record is
--    written afterwards, which is how sick leave actually arrives.
--
-- 2. **staff_schedule_exceptions stops being a page of its own** and becomes
--    the week grid's override. Since E6-S1 it has been edited from the staff
--    member's template screen, which answers "what is this person's pattern?";
--    a swapped Thursday is not a pattern. From here the grid writes them, and
--    the template screen carries the template alone.
--
-- The join between the two is the reason they land in the same migration.
-- "Mark them off" on the grid used to write an exception with working = false;
-- it now writes an approved leave record instead, recorded and approved in the
-- same act by the same person. That leaves exactly one legitimate use for an
-- exception that says "not working": the outbound half of a swap, where the
-- person is not absent at all — they are in on a different day. So the column
-- swap_link_id ties the two halves together and a CHECK below refuses any
-- other kind of day off, which is what stops the two concepts drifting apart
-- again the first time somebody adds a form.
--
-- Rows written before today under the old meaning are converted rather than
-- left as a second answer: every working = false exception becomes the
-- approved leave record it was always standing in for.
-- =====================================================================

CREATE TABLE staff_leave (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    -- The employment record, not the user: a janitor with no login takes leave
    -- like anyone else, and their record is the only place they exist.
    staff_profile_id UUID        NOT NULL REFERENCES staff_profiles(id) ON DELETE CASCADE,

    -- TIME_OFF | SICK | UNPAID. Kept as text against a CHECK rather than in Java
    -- alone, unlike job_title: these three are the whole vocabulary, they carry
    -- meaning the database itself reasons about (an unpaid day is the one a
    -- future pay run must see), and there is no prospect of a fourth arriving
    -- without a conversation.
    leave_type       TEXT        NOT NULL,

    from_date        DATE        NOT NULL,
    to_date          DATE        NOT NULL,

    -- A half day is a half day off, and it covers one date. Anything else is a
    -- range of full days, which is what to_date is for.
    half_day         BOOLEAN     NOT NULL DEFAULT false,

    -- Why they are asking, in their own words. Optional: a temple that does not
    -- ask its cooks to justify a Tuesday should not be made to store a reason.
    reason           TEXT,

    -- PENDING | APPROVED | DECLINED | REVOKED.
    status           TEXT        NOT NULL,

    -- Null when the record was written on behalf of somebody who holds no
    -- account and could not have asked for it themselves.
    requested_by     UUID        REFERENCES users(id) ON DELETE RESTRICT,
    requested_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- The last answer given, whichever it was. A revocation overwrites the
    -- approval that preceded it rather than adding a second row: the audit log
    -- already holds the sequence of decisions with who made each one, and a
    -- second copy of that history here would be one nobody keeps in step.
    decided_by       UUID        REFERENCES users(id) ON DELETE RESTRICT,
    decided_at       TIMESTAMPTZ,
    decision_note    TEXT,

    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT staff_leave_type_valid CHECK (
        leave_type IN ('TIME_OFF', 'SICK', 'UNPAID')),

    CONSTRAINT staff_leave_status_valid CHECK (
        status IN ('PENDING', 'APPROVED', 'DECLINED', 'REVOKED')),

    CONSTRAINT staff_leave_dates_ordered CHECK (to_date >= from_date),

    -- The application refuses this too, with a message that explains itself
    -- (KMS-4006). It is repeated here because a half day spanning a fortnight
    -- is not a validation preference, it is a contradiction, and the one thing
    -- a CHECK is genuinely for is the row that could never have been true.
    CONSTRAINT staff_leave_half_day_is_one_date CHECK (
        NOT half_day OR from_date = to_date),

    -- Answered means stamped. A record showing DECLINED with nothing saying
    -- when is a record whose history cannot be read back.
    CONSTRAINT staff_leave_decided_is_stamped CHECK (
        status = 'PENDING' OR decided_at IS NOT NULL)
);

-- One person's leave, in date order: the account page's own list, and the
-- overlap check that runs before every write.
CREATE INDEX staff_leave_by_person ON staff_leave (tenant_id, staff_profile_id, from_date);

-- The two reads that are not about one person: the approver's queue, and the
-- week grid asking who across the whole temple is out between two dates.
CREATE INDEX staff_leave_by_status ON staff_leave (tenant_id, status, from_date);

-- Overlapping leave is refused in the service, inside the transaction that
-- writes, rather than by an exclusion constraint. An EXCLUDE would state it in
-- the database where it cannot be forgotten — but a range exclusion that is
-- also scoped by tenant and person needs the btree_gist extension installed in
-- every environment, and the race it closes is two people at one temple
-- recording the same cook's leave in the same instant. That is a deployment
-- step bought with a risk nobody has.
SELECT enable_tenant_rls('staff_leave');

COMMENT ON TABLE staff_leave IS
    'Time off, sick and unpaid leave (B7). A request-and-approve log: no accrual, no balances, and no attendance.';
COMMENT ON COLUMN staff_leave.requested_by IS
    'Null where the temple recorded leave for somebody who holds no login and so could not ask.';

-- ---------------------------------------------------------------------
-- The grid's override
-- ---------------------------------------------------------------------

ALTER TABLE staff_schedule_exceptions
    -- Both halves of a swap share one id. It exists so that undoing a swap
    -- undoes the whole of it: this is the case people get wrong by removing the
    -- day the person was added to and leaving them marked off the day they were
    -- moved from, which reads on the grid as a cook who simply vanished.
    ADD COLUMN swap_link_id UUID;

CREATE INDEX staff_exception_swap_link
    ON staff_schedule_exceptions (tenant_id, swap_link_id) WHERE swap_link_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- Converting the old meaning of "not working", per tenant.
--
-- A data statement in a migration is subject to the RLS policy the schema
-- declares — a cross-tenant read matches nothing and reports success — so each
-- tenant is adopted in turn, exactly as V57 does. The loop variable is named
-- for what it is and never reused as a table alias: PL/pgSQL substitutes its
-- own variables into every statement before the planner sees them, so a table
-- aliased `t` inside a block declaring `t RECORD` fails with "record t has no
-- field ...", and only on a database that actually has tenants in it.
-- ---------------------------------------------------------------------
DO $$
DECLARE tenant_row RECORD;
BEGIN
    FOR tenant_row IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', tenant_row.id::text, true);

        -- Every day off already on the grid becomes the approved leave record it
        -- was standing in for. Nobody is named as the approver because the old
        -- row never said who made it — an invented name would be worse than an
        -- honest blank, and the note says so where an admin will read it.
        INSERT INTO staff_leave (
            tenant_id, staff_profile_id, leave_type, from_date, to_date, half_day,
            reason, status, requested_at, decided_at, decision_note)
        SELECT ex.tenant_id, ex.staff_profile_id, 'TIME_OFF',
               ex.exception_date, ex.exception_date, false,
               ex.note, 'APPROVED', ex.created_at, ex.created_at,
               'Carried over from a day off recorded on the schedule grid before leave existed; the original row did not record who approved it.'
        FROM staff_schedule_exceptions ex
        WHERE NOT ex.working;

        DELETE FROM staff_schedule_exceptions WHERE NOT working;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;

-- The assertion that the loop reached every tenant. DDL cannot be filtered by a
-- row policy, so a day off the conversion missed fails the migration here rather
-- than sitting on a grid for months as the second answer this whole change
-- exists to remove.
ALTER TABLE staff_schedule_exceptions
    ADD CONSTRAINT staff_exception_off_only_as_a_swap CHECK (working OR swap_link_id IS NOT NULL);

COMMENT ON TABLE staff_schedule_exceptions IS
    'The week grid''s per-date override (B7 §6). Changed hours, an added day, or half of a swap — never an absence, which is a staff_leave record.';
COMMENT ON COLUMN staff_schedule_exceptions.swap_link_id IS
    'Shared by the two halves of a swap so that undoing either removes both.';
