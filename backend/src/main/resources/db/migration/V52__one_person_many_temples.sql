-- =====================================================================
-- V52 — a person may belong to more than one temple
--
-- Until now firebase_uid was globally unique: one person, one row, one temple,
-- for good. That made a volunteer who cooks at two temples impossible to
-- express, and it made the sign-in screen unanswerable — a devotee signing in
-- with Google could not be placed at any temple, because the model had nowhere
-- to put the answer.
--
-- A users row keeps its meaning: this person, at this temple, in this role. What
-- changes is that a person may now have several — one per temple — joined by
-- their Firebase uid. Nothing else in the schema moves: every table that points
-- at a user still points at that temple's own row, so created_by, shift signups
-- and the audit trail are untouched.
--
-- Tenant resolution is unchanged in kind: the tenant still comes from a verified
-- record, never from the request. It is now chosen from among the rows that
-- verified record spans.
-- =====================================================================

ALTER TABLE users DROP CONSTRAINT users_firebase_uid_key;

-- One membership per person per temple.
CREATE UNIQUE INDEX users_uid_per_tenant ON users (firebase_uid, tenant_id);

-- NULLs are distinct to a unique index, so the constraint above would let a
-- platform operator (no tenant) be created twice. They are the one kind of user
-- that must stay singular.
CREATE UNIQUE INDEX users_uid_platform ON users (firebase_uid) WHERE tenant_id IS NULL;

COMMENT ON COLUMN users.firebase_uid IS
    'Subject of the verified Firebase ID token. Shared by every membership one person holds; unique per temple, and singular for a platform operator.';
