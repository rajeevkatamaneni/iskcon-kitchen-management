-- =====================================================================
-- V83 — why a vendor was dropped, and when their contract runs out (E5-S1)
--
-- Two halves of the same review comment, deliberately kept apart.
--
-- 1. A reason, captured at the moment of deactivation, kept as history.
--
--    vendors.notes has existed since V24, but it is one overwritable line with
--    no author and no date, and neither the deactivate nor the reactivate
--    endpoint ever touched it. So the thing the reviewers asked for — reading
--    why somebody was dropped, before bringing them back — genuinely did not
--    work. A reason is not a field: successive deactivations each leave their
--    own record, and none of them is ever edited away. That is the shape
--    equipment_state_changes (V16) already has, and this follows it exactly,
--    down to the reason it is its own table rather than the audit log:
--
--      the audit log is read behind VIEW_AUDIT_LOG, which only a Temple Admin
--      holds. Vendors are managed behind MANAGE_VENDORS, which a Kitchen
--      Manager and Kitchen Staff hold too. Putting this history in the audit
--      log would mean the very person doing the reactivating cannot read why
--      the vendor was dropped — unless we hand out the permission that also
--      exposes every donation amount and pay change in the temple. The audit
--      log keeps recording VENDOR_DEACTIVATED / VENDOR_REACTIVATED as it
--      always has; that is the compliance record, and it has its own retention
--      rules. This table is the operational one, and it is what the vendor's
--      own page shows.
--
--    notes stays exactly as it is, for everything else.
--
-- 2. A contract end date that warns and never acts.
--
--    The other half of the comment was "validity dates". An automatic version
--    was rejected: a date-bounded vendor needs a job to flip the active flag,
--    and one morning it silently drops a vendor out of the preferred-vendor
--    lookup that feeds the shopping list. The list then suggests somebody else
--    and nobody can say why, because the cause was a date set months ago and
--    forgotten. So the date is recorded and warned about, and nothing else:
--    no job reads this column, no query filters on it, and a vendor whose
--    contract ended last March is still fully active and fully selectable
--    until a person decides otherwise — and when they do, they leave a reason
--    above.
-- =====================================================================

ALTER TABLE vendors
    ADD COLUMN contract_end_date DATE;

COMMENT ON COLUMN vendors.contract_end_date IS
    'When the temple''s agreement with this vendor runs out. Shown and warned about only — nothing filters on it, no job reads it, and it never changes the active flag (E5-S1, 2026-08-31).';

-- The append-only trail of active/inactive changes: who dropped a vendor, when,
-- and why. Readable by whoever holds MANAGE_VENDORS as the vendor's own history —
-- which is why it is its own table rather than the admin-only audit log.
CREATE TABLE vendor_status_changes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    vendor_id       UUID        NOT NULL REFERENCES vendors(id) ON DELETE RESTRICT,

    from_active     BOOLEAN,
    to_active       BOOLEAN     NOT NULL,

    -- Required on the way out, optional on the way back in. Somebody bringing a
    -- vendor back may have nothing to add beyond the fact that they did.
    reason          TEXT,

    actor_user_id   UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT vendor_status_change_reason_on_deactivate CHECK (
        to_active OR (reason IS NOT NULL AND length(reason) > 0)),
    CONSTRAINT vendor_status_change_reason_not_blank CHECK (
        reason IS NULL OR length(reason) > 0)
);

COMMENT ON TABLE vendor_status_changes IS
    'Append-only history of a vendor being deactivated or brought back, with the reason (E5-S1). The reason is required on deactivation. Read behind MANAGE_VENDORS, not VIEW_AUDIT_LOG.';

CREATE INDEX vendor_status_changes_vendor
    ON vendor_status_changes (tenant_id, vendor_id, created_at DESC);

SELECT enable_tenant_rls('vendor_status_changes');
SELECT make_append_only('vendor_status_changes');
