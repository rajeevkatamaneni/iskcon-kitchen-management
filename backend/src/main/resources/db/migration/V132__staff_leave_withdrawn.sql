-- =====================================================================
-- V132 — Staff withdraw their own leave, and the row is kept (T-184)
--
-- Until now a member of staff could take back only a request nobody had
-- answered, and LeaveService deleted the row when they did. Its comment
-- said "Removed rather than kept as a fifth status": an unanswered request
-- taken back was thought to say nothing anybody would need.
--
-- That is reversed here, on purpose. Rajeev's ruling of 2026-09-13 lets
-- staff withdraw approved leave as well as pending, before its first day,
-- and has whoever approves leave told when they do ("Should the manager
-- be told? YES"). Approved leave is something a manager arranged a week
-- around. Its withdrawal is a fact they will want to point at, exactly
-- the argument REVOKED was kept for, so the row stays and says WITHDRAWN.
--
-- ---------------------------------------------------------------------
-- The stamp: a column of its own, not decided_at/decided_by
--
-- V62's staff_leave_decided_is_stamped says a row that is not PENDING
-- carries decided_at. A withdrawn row needs a when, and there were two ways
-- to give it one.
--
-- Writing decided_at/decided_by as the withdrawer was rejected. On a row
-- that had been approved it would overwrite who approved it, when, and the
-- note they wrote. That approver is the person the withdrawal notice goes
-- to, and the approver's queue prints "decided by" from that column, so a
-- cook's withdrawal would read as the cook having decided their own leave.
-- The audit log would still hold the approval, but the row would lie about
-- it, and this table's own comment says the row keeps "the last answer
-- given". A withdrawal is not an answer.
--
-- So withdrawn_at is its own column. The decided stamp is widened to let a
-- WITHDRAWN row keep whatever it had (an approval, or nothing if it was
-- still pending), and a new CHECK holds withdrawn_at to the status in both
-- directions: a WITHDRAWN row always says when, and no other row ever
-- carries a withdrawal time. Who withdrew it needs no column. Only the
-- person the leave belongs to can withdraw it, and the audit log records
-- the actor.
--
-- ---------------------------------------------------------------------
-- Readers of status, checked when this was written
--
--   ScheduleResolver (the week grid, the head count, the cook's own list,
--   the schedule guard) reads status = 'APPROVED' only, so a withdrawn row
--   leaves the rota with no change there. LeaveService's approver count reads
--   'PENDING' and its overlap check reads IN ('PENDING', 'APPROVED'), so a
--   withdrawn row neither waits for an answer nor blocks a new request for
--   the same days. Nothing sums this table.
--
-- No data statement: no row is WITHDRAWN before this migration, because the
-- old code deleted instead. DDL is not filtered by the row policy, so the
-- constraints below are checked against every tenant's rows.
-- =====================================================================

ALTER TABLE staff_leave ADD COLUMN withdrawn_at TIMESTAMPTZ;

ALTER TABLE staff_leave DROP CONSTRAINT staff_leave_status_valid;
ALTER TABLE staff_leave ADD CONSTRAINT staff_leave_status_valid CHECK (status IN ('PENDING', 'APPROVED', 'DECLINED', 'REVOKED', 'WITHDRAWN'));

ALTER TABLE staff_leave DROP CONSTRAINT staff_leave_decided_is_stamped;
ALTER TABLE staff_leave ADD CONSTRAINT staff_leave_decided_is_stamped CHECK (status IN ('PENDING', 'WITHDRAWN') OR decided_at IS NOT NULL);

ALTER TABLE staff_leave ADD CONSTRAINT staff_leave_withdrawn_is_stamped CHECK ((status = 'WITHDRAWN') = (withdrawn_at IS NOT NULL));

COMMENT ON COLUMN staff_leave.withdrawn_at IS
    'When the person withdrew this leave themselves, before its first day (T-184). Set exactly when status is WITHDRAWN. decided_by/decided_at keep the approval, if there was one.';
