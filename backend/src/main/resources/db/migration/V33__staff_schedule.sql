-- =====================================================================
-- V33 — Staff profiles and weekly schedule (E6-S1)
--
-- Full-time staff (KITCHEN_STAFF users) get a profile with a designation and a
-- recurring weekly pattern — one row per weekday, working hours or a day off.
-- A single date is overridden with an exception row, leaving the template
-- untouched. The resolved schedule for a date is: its exception if one exists,
-- otherwise the template for that weekday. No payroll, attendance, or leave
-- accounting in release 1 — that is Phase 2.
-- =====================================================================

CREATE TABLE staff_profiles (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    -- The staff member. One profile per user; the app enforces the user is KITCHEN_STAFF.
    user_id      UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,

    -- Free text: "Head Cook", "Prep", … — no fixed vocabulary in release 1.
    designation  TEXT,
    active       BOOLEAN     NOT NULL DEFAULT true,

    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX staff_profiles_one_per_user ON staff_profiles (tenant_id, user_id);
SELECT enable_tenant_rls('staff_profiles');

-- The recurring weekly pattern: one row per (staff, ISO weekday 1=Mon … 7=Sun).
CREATE TABLE staff_schedule_template (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    staff_profile_id UUID        NOT NULL REFERENCES staff_profiles(id) ON DELETE CASCADE,
    day_of_week      SMALLINT    NOT NULL,
    working          BOOLEAN     NOT NULL DEFAULT false,
    start_time       TIME,
    end_time         TIME,

    CONSTRAINT staff_template_day_valid CHECK (day_of_week BETWEEN 1 AND 7),
    -- A working day has a time range; a day off has neither.
    CONSTRAINT staff_template_hours_shape CHECK (
        (working AND start_time IS NOT NULL AND end_time IS NOT NULL AND end_time > start_time)
        OR (NOT working AND start_time IS NULL AND end_time IS NULL))
);

CREATE UNIQUE INDEX staff_template_one_per_day
    ON staff_schedule_template (tenant_id, staff_profile_id, day_of_week);
SELECT enable_tenant_rls('staff_schedule_template');

-- A per-date override of the template: a swapped shift, or a one-off day off.
CREATE TABLE staff_schedule_exceptions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    staff_profile_id UUID        NOT NULL REFERENCES staff_profiles(id) ON DELETE CASCADE,
    exception_date   DATE        NOT NULL,
    working          BOOLEAN     NOT NULL,
    start_time       TIME,
    end_time         TIME,
    note             TEXT,

    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT staff_exception_hours_shape CHECK (
        (working AND start_time IS NOT NULL AND end_time IS NOT NULL AND end_time > start_time)
        OR (NOT working AND start_time IS NULL AND end_time IS NULL))
);

CREATE UNIQUE INDEX staff_exception_one_per_date
    ON staff_schedule_exceptions (tenant_id, staff_profile_id, exception_date);
CREATE INDEX staff_exception_by_date ON staff_schedule_exceptions (tenant_id, exception_date);
SELECT enable_tenant_rls('staff_schedule_exceptions');
