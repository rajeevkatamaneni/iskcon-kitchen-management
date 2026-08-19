-- =====================================================================
-- V57 — Staff become employees: the hire record behind the schedule (E6-S8)
--
-- staff_profiles was a scheduling hook: a user, a free-text designation, and
-- an active flag. A temple hiring a cook needs rather more than that — when
-- they joined, how to reach them, who to call if something happens to them
-- at the stove, and, when they leave, the fact that they did and why.
--
-- Three shape changes carry the weight:
--
-- 1. **user_id becomes nullable.** A janitor does not need an app login, and
--    an employment record that cannot exist without one is a record that
--    forces every temple to mint accounts nobody will ever sign into. So the
--    profile carries its own name, phone and email, and a users row is
--    created only when the temple grants access.
--
-- 2. **designation becomes job_title**, from a controlled vocabulary rather
--    than free text, because "Head cook", "head Cook" and "HC" are three
--    values a list cannot group. The vocabulary lives in Java (JobTitle),
--    not in a CHECK constraint — the same reasoning as AuditAction: adding a
--    title should not be a migration. A title is a label and gates nothing;
--    what someone may *do* is their users.role, and the two are deliberately
--    separate fields.
--
-- 3. **active becomes employment_status.** A boolean cannot tell a resignation
--    from a dismissal, and the difference is exactly what an admin looks back
--    for. Non-ACTIVE rows keep the last working day and the reason.
--
-- The backfill matters as much as the shape. Every TEMPLE_ADMIN and
-- KITCHEN_STAFF user that predates this has no profile, and once /staff is
-- the only register of staff they would exist on no screen at all. So they
-- are hired retrospectively here, with what is actually known and no more —
-- a title of UNRECORDED rather than a guessed one.
-- =====================================================================

ALTER TABLE staff_profiles
    ALTER COLUMN user_id DROP NOT NULL,

    -- The employment record's own identity. For someone with a login these
    -- agree with their users row; for someone without one, this is all there is.
    ADD COLUMN full_name                      TEXT,
    ADD COLUMN phone                          TEXT,
    ADD COLUMN email                          TEXT,

    ADD COLUMN job_title                      TEXT,
    -- Only when job_title = 'OTHER': the temple's own words for a job the
    -- vocabulary does not have.
    ADD COLUMN job_title_other                TEXT,

    ADD COLUMN employment_type                TEXT,
    ADD COLUMN date_of_joining                DATE,
    ADD COLUMN date_of_birth                  DATE,
    ADD COLUMN address                        TEXT,

    -- Who to call. A kitchen is a room full of fire, knives and hot oil.
    ADD COLUMN emergency_contact_name         TEXT,
    ADD COLUMN emergency_contact_relationship TEXT,
    ADD COLUMN emergency_contact_phone        TEXT,

    -- PAN, encrypted in the application exactly as a donor's is (E7-S4): the
    -- database never sees the clear value and a dump leaks nothing without the
    -- key. The last four are stored in clear so a list can show a masked value
    -- without decrypting every row.
    ADD COLUMN pan_ciphertext                 BYTEA,
    ADD COLUMN pan_last4                      TEXT,

    ADD COLUMN employment_status              TEXT NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN last_working_day               DATE,
    ADD COLUMN end_reason                     TEXT,
    ADD COLUMN notes                          TEXT;

-- ---------------------------------------------------------------------
-- Backfill, per tenant. A data statement in a migration is subject to the RLS
-- policy the schema declares — a cross-tenant UPDATE matches nothing and
-- reports success — so each tenant is adopted in turn, as the application does.
-- ---------------------------------------------------------------------
DO $$
DECLARE t RECORD;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t.id::text, true);

        -- Existing profiles: take the person's details from their users row, and
        -- keep the temple's own designation as written rather than guessing which
        -- vocabulary term it meant.
        UPDATE staff_profiles sp
        SET full_name       = u.full_name,
            phone           = u.phone,
            email           = u.email,
            job_title       = CASE WHEN sp.designation IS NULL THEN 'UNRECORDED' ELSE 'OTHER' END,
            job_title_other = sp.designation,
            employment_type = 'FULL_TIME',
            -- The best proxy we have. Nobody recorded a joining date because
            -- nothing asked for one.
            date_of_joining = sp.created_at::date,
            employment_status = CASE WHEN sp.active THEN 'ACTIVE' ELSE 'RESIGNED' END,
            last_working_day  = CASE WHEN sp.active THEN NULL ELSE sp.updated_at::date END,
            end_reason        = CASE WHEN sp.active THEN NULL
                                     ELSE 'Recorded as inactive before employment records existed.' END
        FROM users u
        WHERE u.id = sp.user_id;

        -- Everyone employed here who never had a profile at all. Without this they
        -- would vanish: /users now lists devotees only, and /staff lists profiles.
        INSERT INTO staff_profiles (
            id, tenant_id, user_id, full_name, phone, email,
            job_title, employment_type, date_of_joining, employment_status)
        SELECT gen_random_uuid(), u.tenant_id, u.id, u.full_name, u.phone, u.email,
               CASE u.role WHEN 'TEMPLE_ADMIN' THEN 'TEMPLE_ADMINISTRATOR' ELSE 'UNRECORDED' END,
               'FULL_TIME', u.created_at::date, 'ACTIVE'
        FROM users u
        WHERE u.role IN ('TEMPLE_ADMIN', 'KITCHEN_STAFF')
          AND NOT EXISTS (SELECT 1 FROM staff_profiles sp WHERE sp.user_id = u.id);

        -- Those retrospective hires need the seven days-off rows the grid edits,
        -- exactly as createProfile seeds them.
        INSERT INTO staff_schedule_template (id, tenant_id, staff_profile_id, day_of_week, working)
        SELECT gen_random_uuid(), sp.tenant_id, sp.id, d, false
        FROM staff_profiles sp CROSS JOIN generate_series(1, 7) AS d
        WHERE NOT EXISTS (
            SELECT 1 FROM staff_schedule_template t
            WHERE t.staff_profile_id = sp.id AND t.day_of_week = d);
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;

-- The assertion that the loop above reached every tenant: DDL cannot be
-- filtered by a row policy, so a tenant the backfill missed fails the migration
-- here rather than surfacing as a null on a screen months later.
ALTER TABLE staff_profiles
    ALTER COLUMN full_name       SET NOT NULL,
    ALTER COLUMN job_title       SET NOT NULL,
    ALTER COLUMN employment_type SET NOT NULL,
    ALTER COLUMN date_of_joining SET NOT NULL;

ALTER TABLE staff_profiles DROP COLUMN designation;
ALTER TABLE staff_profiles DROP COLUMN active;

ALTER TABLE staff_profiles
    ADD CONSTRAINT staff_employment_status_valid CHECK (
        employment_status IN ('ACTIVE', 'RESIGNED', 'TERMINATED', 'CONTRACT_ENDED')),

    ADD CONSTRAINT staff_employment_type_valid CHECK (
        employment_type IN ('FULL_TIME', 'PART_TIME', 'CONTRACT')),

    -- Employment that has ended has an end date. A former employee with no last
    -- working day is a record nobody can use.
    ADD CONSTRAINT staff_ended_has_last_day CHECK (
        employment_status = 'ACTIVE' OR last_working_day IS NOT NULL),

    -- "Other" without the temple's own words is worse than no title at all.
    ADD CONSTRAINT staff_other_title_named CHECK (
        job_title <> 'OTHER' OR job_title_other IS NOT NULL),

    ADD CONSTRAINT staff_phone_format CHECK (
        phone IS NULL OR phone ~ '^\+[1-9][0-9]{7,14}$'),

    ADD CONSTRAINT staff_emergency_phone_format CHECK (
        emergency_contact_phone IS NULL OR emergency_contact_phone ~ '^\+[1-9][0-9]{7,14}$'),

    -- Stored and displayed masked; the shape is the government's.
    ADD CONSTRAINT staff_pan_last4_shape CHECK (
        pan_last4 IS NULL OR pan_last4 ~ '^[0-9]{3}[A-Z]$'),

    -- A last-four without the value it came from would be a mask over nothing.
    ADD CONSTRAINT staff_pan_paired CHECK (
        (pan_ciphertext IS NULL) = (pan_last4 IS NULL));

-- One employment record per person per temple, while user_id is set. Several
-- staff without logins is ordinary and must stay possible, which a plain unique
-- index would allow by accident (nulls are distinct) rather than by intent.
DROP INDEX IF EXISTS staff_profiles_one_per_user;
CREATE UNIQUE INDEX staff_profiles_one_per_user
    ON staff_profiles (tenant_id, user_id) WHERE user_id IS NOT NULL;

CREATE INDEX staff_profiles_by_status ON staff_profiles (tenant_id, employment_status, full_name);

COMMENT ON TABLE staff_profiles IS
    'One person''s employment at one temple (E6-S8). Independent of whether they hold a login: user_id is null for staff the temple gave no app access.';
COMMENT ON COLUMN staff_profiles.job_title IS
    'Vocabulary in Java (JobTitle), not a CHECK — a label, never a permission. What someone may do is users.role.';
COMMENT ON COLUMN staff_profiles.pan_ciphertext IS
    'AES-GCM, encrypted in the application (PanCipher). Reading the clear value is audited.';
