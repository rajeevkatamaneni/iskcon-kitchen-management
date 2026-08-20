-- =====================================================================
-- V63 — Salary, payments, advances and docking (B8)
--
-- The temple came back and asked for staff pay in Phase 1. What it asked for
-- is a record, not a payroll: the app writes down what was paid, when, how,
-- and against what — it never works out what is owed. Computing salary owed
-- needs a pay period, a start date and a ledger of settled periods, and
-- nobody asked for any of that. So the admin types the settlement figure and
-- this schema holds facts they can check against a bank statement.
--
-- Four things arrive here.
--
-- 1. **A salary on the employment record**, monthly and nullable. Hourly was
--    dropped, so a monthly figure is the only thing it could be. Nullable
--    because a temple takes somebody on before the pay is agreed, and a
--    part-timer paid daily in cash may have nothing recorded at all — the
--    termination screen has to be able to say "no salary recorded" rather
--    than show a confident zero. A DEFAULT 0 here would make that impossible
--    to distinguish, which is exactly why there isn't one.
--
-- 2. **Payments** — what actually left the temple's hands, with the reference
--    that lets somebody trace it: a cheque number, a payroll reference, or
--    nothing at all when it was cash.
--
-- 3. **Advances**, which are the same act with a different meaning: money paid
--    ahead of the work, creating a balance the temple expects back.
--
-- 4. **Deductions**, each one linking a payment to the advance it repays. This
--    is the whole reason docking works without anybody maintaining a running
--    total: the advance balance is advances given minus deductions recovered,
--    both of which are rows here, so it is arithmetic rather than a stored
--    number that can drift from the entries it claims to summarise. There is
--    deliberately no balance column anywhere in this migration.
--
-- ---------------------------------------------------------------------
-- Currency
--
-- tenants.currency has existed since V1 and nothing has ever read it. It is
-- read now, and the columns below are named for what they hold — `amount`,
-- not `amount_inr` — so a temple that keeps its books in another currency
-- needs no schema change here.
--
-- The rupee-named columns elsewhere (amount_inr, price_inr, cash_amount_inr
-- across donations, the wish list, invoices and purchase orders) are left
-- exactly as they are. A second country is not close, and retrofitting a
-- dozen columns, their views, exports and screens for a temple that does not
-- exist is churn spent on a guess. When such a temple appears it becomes a
-- bounded piece of work with a real requirement behind it.
--
-- The CHECK is the one part of that worth doing now: CHAR(3) already blank-pads
-- anything shorter, so without it a temple could be provisioned holding 'r  '
-- and every screen would render a currency nobody recognises.
-- ---------------------------------------------------------------------
ALTER TABLE tenants
    ADD CONSTRAINT tenants_currency_iso4217 CHECK (currency ~ '^[A-Z]{3}$');

COMMENT ON COLUMN tenants.currency IS
    'ISO-4217, the temple''s own currency. Read by staff pay (B8); the older amount_inr columns predate it and are unaffected.';

-- ---------------------------------------------------------------------
-- Salary
-- ---------------------------------------------------------------------
ALTER TABLE staff_profiles
    ADD COLUMN monthly_salary NUMERIC(12, 2),

    -- Nothing is gained by recording that somebody is paid nothing: that is
    -- what null already says, and it says it without pretending to be a figure.
    ADD CONSTRAINT staff_monthly_salary_positive CHECK (
        monthly_salary IS NULL OR monthly_salary > 0);

COMMENT ON COLUMN staff_profiles.monthly_salary IS
    'A monthly figure in the temple''s currency, or null when no pay has been agreed. Never defaulted to zero — "none recorded" and "nothing" are different answers.';

-- ---------------------------------------------------------------------
-- Payments
--
-- Not append-only, and the exception is deliberate. make_append_only() is the
-- right tool for a ledger nobody reads a single row of — the stock ledger
-- corrects itself with a compensating entry because its only consumer is a
-- sum. This table is read one row at a time by an administrator answering
-- "what did we pay Ramesh in July", and a mistyped 50,000 sitting next to a
-- -50,000 next to a 5,000 answers that question badly three times over.
--
-- So a wrong entry is voided rather than reversed: the row stays, stamped with
-- who struck it and when, and every sum here ignores it. That is one UPDATE,
-- which is precisely what the append-only trigger forbids, and lifting the
-- trigger for it would mean teaching the tenant purge (V44–V46) about yet
-- another exception for no gain. The invariant that matters — nothing is ever
-- deleted, and a correction names its author — is kept by the void columns.
--
-- A payment that has already had advances docked against it cannot be voided
-- at all (KMS-4961), because doing so would quietly hand somebody their
-- advance balance back.
-- ---------------------------------------------------------------------
CREATE TABLE staff_payments (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    staff_profile_id UUID        NOT NULL REFERENCES staff_profiles(id) ON DELETE RESTRICT,

    paid_on          DATE        NOT NULL,
    -- Gross: what the payment is before anything is recovered from it. The net is
    -- gross minus the deductions below, and is computed rather than stored so the
    -- two can never disagree.
    gross_amount     NUMERIC(12, 2) NOT NULL,
    mode             TEXT        NOT NULL,
    reference        TEXT,
    -- Salary, or the final settlement when somebody leaves. The distinction is
    -- what lets the termination screen name the last *salary* payment rather than
    -- the settlement it is in the middle of recording.
    purpose          TEXT        NOT NULL,
    note             TEXT,

    recorded_by      UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    voided_at        TIMESTAMPTZ,
    voided_by        UUID        REFERENCES users(id) ON DELETE RESTRICT,

    CONSTRAINT staff_payments_amount_positive CHECK (gross_amount > 0),
    CONSTRAINT staff_payments_mode_valid CHECK (mode IN ('CHEQUE', 'CASH', 'PAYROLL')),
    CONSTRAINT staff_payments_purpose_valid CHECK (purpose IN ('SALARY', 'SETTLEMENT')),

    -- A cheque or a payroll run leaves a trace somewhere else, and the reference is
    -- how anybody finds it again. Cash leaves none, and demanding one there would
    -- only teach people to type a full stop.
    CONSTRAINT staff_payments_reference_present CHECK (
        mode = 'CASH' OR reference IS NOT NULL),

    CONSTRAINT staff_payments_void_attributed CHECK (
        (voided_at IS NULL) = (voided_by IS NULL))
);

CREATE INDEX staff_payments_by_staff
    ON staff_payments (tenant_id, staff_profile_id, paid_on DESC, created_at DESC);

SELECT enable_tenant_rls('staff_payments');

COMMENT ON TABLE staff_payments IS
    'What the temple paid a member of staff (B8). Gross; the net is this minus the deductions in staff_payment_deductions. Corrected by voiding, never by editing or deleting.';

-- ---------------------------------------------------------------------
-- Advances
--
-- Money paid before it is earned, which is ordinary in a temple kitchen — a
-- cook needs it for a wedding or a hospital bill and it comes off the next
-- salary. Cheque or cash only: an advance is by definition not part of a
-- payroll run, so offering PAYROLL as a mode would describe something that
-- cannot happen.
-- ---------------------------------------------------------------------
CREATE TABLE staff_advances (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    staff_profile_id UUID        NOT NULL REFERENCES staff_profiles(id) ON DELETE RESTRICT,

    paid_on          DATE        NOT NULL,
    amount           NUMERIC(12, 2) NOT NULL,
    mode             TEXT        NOT NULL,
    reference        TEXT,
    note             TEXT,

    recorded_by      UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    voided_at        TIMESTAMPTZ,
    voided_by        UUID        REFERENCES users(id) ON DELETE RESTRICT,

    CONSTRAINT staff_advances_amount_positive CHECK (amount > 0),
    CONSTRAINT staff_advances_mode_valid CHECK (mode IN ('CHEQUE', 'CASH')),
    CONSTRAINT staff_advances_reference_present CHECK (
        mode = 'CASH' OR reference IS NOT NULL),
    CONSTRAINT staff_advances_void_attributed CHECK (
        (voided_at IS NULL) = (voided_by IS NULL))
);

CREATE INDEX staff_advances_by_staff
    ON staff_advances (tenant_id, staff_profile_id, paid_on DESC, created_at DESC);

SELECT enable_tenant_rls('staff_advances');

COMMENT ON TABLE staff_advances IS
    'Money paid ahead of the work (B8). What is still outstanding on one is its amount minus the deductions recorded against it — there is no balance column, on purpose.';

-- ---------------------------------------------------------------------
-- Deductions — the link that makes docking work
--
-- One line per advance per payment. Two lines against the same advance on the
-- same payment would be one deduction written twice, so the unique index says
-- so and the application adds them up before it writes.
--
-- No void column here: a deduction has no life of its own. It exists because a
-- payment recovered something, and the payment carrying it cannot be voided
-- while it does.
--
-- ON DELETE RESTRICT both ways, like everything else in this schema. The rows
-- disappear only when the whole temple does.
-- ---------------------------------------------------------------------
CREATE TABLE staff_payment_deductions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    payment_id  UUID        NOT NULL REFERENCES staff_payments(id) ON DELETE RESTRICT,
    advance_id  UUID        NOT NULL REFERENCES staff_advances(id) ON DELETE RESTRICT,
    amount      NUMERIC(12, 2) NOT NULL,

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT staff_payment_deductions_amount_positive CHECK (amount > 0)
);

CREATE UNIQUE INDEX staff_payment_deductions_one_per_advance
    ON staff_payment_deductions (payment_id, advance_id);

CREATE INDEX staff_payment_deductions_by_advance
    ON staff_payment_deductions (tenant_id, advance_id);

SELECT enable_tenant_rls('staff_payment_deductions');

COMMENT ON TABLE staff_payment_deductions IS
    'What a payment recovered from an advance (B8). The advance balance falls out of these rows; nothing maintains it by hand.';

-- The two totals the whole feature rests on. Deliberately not a view: they are
-- always asked for one member of staff at a time, and the sums that need to
-- exclude voided rows are safer written once in SQL the service reads than
-- spread across the places that need them.
COMMENT ON COLUMN staff_payments.gross_amount IS
    'Before deductions. Net = gross_amount - SUM(staff_payment_deductions.amount) for this payment.';
COMMENT ON COLUMN staff_advances.amount IS
    'As given. Outstanding = amount - SUM(deductions on non-voided payments); the balance for a person is the sum of those, over non-voided advances.';
