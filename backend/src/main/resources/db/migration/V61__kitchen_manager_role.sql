-- =====================================================================
-- V61 — A fifth role: KITCHEN_MANAGER (build brief 2026-08-20, §5)
--
-- "The kitchen manager can approve leave" collided with E6-S8's rule that a
-- job title is a label and gates nothing. Rather than invent a second concept
-- beside roles, the resolution recorded in BL-4 was taken: more roles.
--
-- What the role is, is decided in Java (RolePermissions) — everything kitchen
-- staff hold, plus MANAGE_STAFF_SCHEDULE and APPROVE_LEAVE. Deliberately not
-- MANAGE_STAFF, which is what gates hiring, salary and PAN: the staff register
-- is the only screen pay appears on, and a manager running the roster has no
-- business there.
--
-- This migration is only the database's half of it — widening the CHECK that
-- keeps users.role honest. A constraint that lists the roles has to be edited
-- when a role is added; that is the point of it.
-- =====================================================================

ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_valid;

ALTER TABLE users
    ADD CONSTRAINT users_role_valid CHECK (
        role IN ('SUPER_ADMIN', 'TEMPLE_ADMIN', 'KITCHEN_MANAGER', 'KITCHEN_STAFF', 'VOLUNTEER'));

COMMENT ON COLUMN users.role IS
    'What this person may do here. The policy itself lives in RolePermissions.java, deliberately in code rather than in rows somebody can edit in production.';
