-- =====================================================================
-- V91 — Equipment has no kind (E3-S4, reversed 2026-09-04)
--
-- V16 gave every piece of equipment a category from a fixed vocabulary of
-- three — MACHINE, TOOL, FURNITURE — held as text with a CHECK mirroring
-- the enum, and indexed per tenant so the list could filter on it.
--
-- Rajeev removed it on 2026-09-04, looking at the register:
--
--     "Machine, Tool which are fine, why furniture? … I am honestly not a
--      fan of the list, it is JUST asking for trouble. People will come up
--      with weird shit they want us to add and it will never end. Why not
--      just remove it altogether?"
--
-- He is right, and the reason is worth writing down because it will come
-- up again. A closed vocabulary the temple cannot extend has to be either
-- complete or wrong, and three values were never going to be complete —
-- a cold room, a gas line, a delivery van, a set of vessels. Every one of
-- those arrives as a request to add a fourth value, which is a migration,
-- a deploy and an argument about whether vessels are tools.
--
-- And nothing was reading it. No report grouped by it, no rule branched
-- on it, no permission depended on it. The one thing it fed was a filter
-- on a register of dozens, where the name already says what the thing is:
-- "Wet Grinder 10L" is not mistakable for a trestle table.
--
-- A free-text kind, the shape the ingredient catalogue uses, was
-- considered and NOT built. That trades one problem for a worse one here:
-- the ingredient catalogue has hundreds of rows and something to group,
-- while this register would collect "machine", "Machine" and "mchine" as
-- three kinds of nothing. The name is the description. There is no
-- replacement field, and that is the decision.
--
-- ---------------------------------------------------------------------
-- Nothing is backfilled anywhere, because nothing survives
--
-- Unlike V90 next door, this drops a fact rather than moving it. What is
-- lost is which of three words somebody picked for each machine, and it
-- is lost on purpose — carrying it into the notes would put "MACHINE" in
-- front of a reader in the one field they write sentences in, and leave
-- them unable to delete it without also deleting their own note.
--
-- The condition trail and the service history are untouched: neither ever
-- recorded a category. Audit rows written before today still carry it
-- inside their JSON snapshots, which is correct — an audit row is what
-- was true when it was written, and audit_events is append-only.
-- =====================================================================

-- The index goes with the column; naming it here is documentation rather
-- than instruction, so the reader of this file knows it existed.
--   CREATE INDEX equipment_tenant_category ON equipment_items (tenant_id, category);

ALTER TABLE equipment_items DROP CONSTRAINT equipment_category_valid;
ALTER TABLE equipment_items DROP COLUMN category;

COMMENT ON TABLE equipment_items IS
    'Durable temple assets tracked by condition (E3-S4). SCRAPPED items are hidden from default views. No category since V91: a fixed vocabulary of three nobody could extend was worse than none, and the name says what the thing is.';
