-- =====================================================================
-- V47 — Drop tenants.status
--
-- V1 gave tenants a status of ACTIVE / SUSPENDED / ARCHIVED, anticipating a
-- temple lifecycle — suspend a temple, archive one that has left. That lifecycle
-- was never built and, as of E1-S15, will not be: a temple either exists or is
-- permanently deleted (with a data export taken first).
--
-- So nothing ever wrote this column. Every row was ACTIVE from its default and
-- stayed ACTIVE, which made the four `WHERE status = 'ACTIVE'` filters that read
-- it — the calendar precompute, the order-list refresh, the pending-donation
-- sweep, and public donation-page resolution — tautologies that could never
-- exclude a row, and made the "Status: Active" line on the temple page a
-- constant dressed up as information.
--
-- Removed rather than left in place: a column nobody sets, guarded by a CHECK
-- listing two states nothing can reach, is exactly the kind of thing that later
-- reads as a feature and gets built around. If a temple lifecycle is ever wanted,
-- it comes back as a migration and a story that says what it means.
-- =====================================================================

ALTER TABLE tenants DROP CONSTRAINT tenants_status_valid;
ALTER TABLE tenants DROP COLUMN status;
