"use client";

import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Loading } from "@/components/Loading";
import { api, toApiError, type ApiError, type LeaveType, type LeaveView } from "@/lib/api";
import { todayIso } from "@/lib/format";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Badge } from "@/components/ds/Badge";
import { Button } from "@/components/ds/Button";
import { EmptyState } from "@/components/ds/EmptyState";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { SegmentedControl } from "@/components/ds/SegmentedControl";

/**
 * The leave queue (B7): what is waiting, and what has been granted.
 *
 * <p>One list, filtered, rather than two screens. "Pending" and "approved" are the same records with
 * a different answer on them, and fetching them separately is how approving something makes it
 * vanish from one list without appearing in the other.
 *
 * <p>Behind TEMPLE_ADMIN and KITCHEN_MANAGER, which are the two roles holding APPROVE_LEAVE. A
 * manager runs the roster and leave is what the roster bends around; they are still deliberately not
 * shown the staff register, which is the only screen salary and PAN appear on.
 */

type Filter = "PENDING" | "APPROVED" | "ALL";

const FILTERS: readonly { value: Filter; label: string }[] = [
  { value: "PENDING", label: "Waiting" },
  { value: "APPROVED", label: "Approved" },
  { value: "ALL", label: "Everything" },
];

const LEAVE_TYPES: { value: LeaveType; label: string }[] = [
  { value: "TIME_OFF", label: "Time off" },
  { value: "SICK", label: "Sick leave" },
  { value: "UNPAID", label: "Unpaid leave" },
];

export default function LeavePage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER"]}>
      <LeaveQueueView />
    </RequireRole>
  );
}

function LeaveQueueView() {
  const { getToken } = useAuth();
  const [filter, setFilter] = useState<Filter>("PENDING");
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [recording, setRecording] = useState(false);

  const queue = useAuthedQuery(useCallback((t: string | undefined) => api.leaveQueue(t), []));

  // The roster, for the "record it for them" form. Not the staff register: that is behind
  // MANAGE_STAFF, which a kitchen manager deliberately does not hold, and this week's grid already
  // names every actively employed person — which is exactly who can take leave.
  const roster = useAuthedQuery(useCallback((t: string | undefined) => api.staffWeek(mondayOfThisWeek(), t), []));

  const all = queue.data ?? [];
  const shown = all.filter((l) => (filter === "ALL" ? true : l.status === filter));

  async function run(mutation: (t: string | undefined) => Promise<unknown>, ok: string, failure: string) {
    setBusy(true);
    setActionError(null);
    setNotice(null);
    try {
      await mutation(await getToken());
      queue.reload();
      setNotice(ok);
      return true;
    } catch (e) {
      setActionError(toApiError(e, failure));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function recordForSomeone(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const f = new FormData(form);
    const done = await run(
      (t) =>
        api.recordLeave(
          {
            staffProfileId: String(f.get("staffProfileId") ?? ""),
            leaveType: String(f.get("leaveType") ?? "TIME_OFF") as LeaveType,
            fromDate: String(f.get("fromDate") ?? ""),
            toDate: String(f.get("toDate") ?? ""),
            halfDay: f.get("halfDay") === "on",
            reason: emptyToNull(String(f.get("reason") ?? "")),
          },
          t
        ),
      "Recorded and approved.",
      "We couldn't record that leave."
    );
    if (done) {
      form.reset();
      setRecording(false);
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/leave" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Leave</h1>
              <p className="mt-1 max-w-prose text-ink-secondary">
                Time off, sick and unpaid leave. Approved leave takes the person off the schedule and
                out of the day&apos;s head count.
              </p>
            </div>
            <Button onClick={() => setRecording((open) => !open)}>
              {recording ? "Close" : "Record leave for someone"}
            </Button>
          </header>

          {actionError && <div className="mb-4"><ErrorNotice error={actionError} /></div>}
          {notice && <div className="mb-4"><InlineNotice tone="success">{notice}</InlineNotice></div>}

          {recording && (
            <section className="mb-6 rounded-lg bg-raised px-6 py-5" aria-labelledby="record-heading">
              <h2 id="record-heading" className="text-lg">Record leave for someone</h2>
              <p className="mt-1 max-w-prose text-sm text-ink-secondary">
                For staff with no app of their own. It is approved as you record it, because you are
                the person who would have approved it.
              </p>
              <form className="mt-4 flex flex-wrap items-end gap-3" aria-label="Record leave" onSubmit={recordForSomeone}>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Staff member
                  <select name="staffProfileId" required className="min-h-touch rounded border border-hairline bg-canvas px-2">
                    {(roster.data?.staff ?? []).map((s) => (
                      <option key={s.staffProfileId} value={s.staffProfileId}>{s.fullName}</option>
                    ))}
                  </select>
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Kind
                  <select name="leaveType" className="min-h-touch rounded border border-hairline bg-canvas px-2">
                    {LEAVE_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
                  </select>
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  First day
                  <input type="date" name="fromDate" required defaultValue={todayIso()} className="min-h-touch rounded border border-hairline bg-canvas px-2" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Last day
                  <input type="date" name="toDate" required defaultValue={todayIso()} className="min-h-touch rounded border border-hairline bg-canvas px-2" />
                </label>
                <label className="flex min-h-touch items-center gap-2 text-sm text-ink-secondary">
                  <input type="checkbox" name="halfDay" /> Half day
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Note
                  <input name="reason" className="min-h-touch rounded border border-hairline bg-canvas px-2" />
                </label>
                <Button type="submit" disabled={busy}>Record it</Button>
              </form>
            </section>
          )}

          <div className="mb-4">
            <SegmentedControl options={FILTERS} value={filter} onChange={setFilter} label="Which leave to show" />
          </div>

          {queue.loading ? (
            <Loading label="Loading leave…" />
          ) : queue.error ? (
            <ErrorNotice error={queue.error} />
          ) : shown.length === 0 ? (
            <EmptyState title={filter === "PENDING" ? "Nothing waiting" : "Nothing to show"}>
              {filter === "PENDING"
                ? "Every request has been answered. Staff with a login ask from their own account page; for everyone else, record it above."
                : "Leave will appear here once somebody asks for it or you record it."}
            </EmptyState>
          ) : (
            <ul className="grid gap-3">
              {shown.map((leave) => (
                <li key={leave.id} className="rounded-lg bg-raised px-6 py-4">
                  <div className="flex flex-wrap items-start justify-between gap-4">
                    <div>
                      <p className="font-medium text-ink">
                        {leave.staffName} <span className="text-ink-muted">· {leave.jobTitleLabel}</span>
                      </p>
                      <p className="mt-1 text-sm text-ink-secondary tabular-nums">
                        {leave.leaveTypeLabel} · {dateRange(leave)}
                      </p>
                      {leave.reason && <p className="mt-1 max-w-prose text-sm text-ink-secondary">{leave.reason}</p>}
                      <p className="mt-1 text-xs text-ink-muted">
                        {leave.requestedByName ? `Asked for by ${leave.requestedByName}` : "Recorded by the temple"}
                        {leave.decidedByName ? ` · answered by ${leave.decidedByName}` : ""}
                      </p>
                      {leave.decisionNote && <p className="mt-1 text-xs text-ink-muted">{leave.decisionNote}</p>}
                    </div>
                    <div className="flex flex-none flex-col items-end gap-2">
                      <StatusBadge status={leave.status} />
                      {leave.status === "PENDING" && (
                        <div className="flex gap-2">
                          <Button size="sm" disabled={busy} onClick={() => run((t) => api.decideLeave(leave.id, "approve", null, t), "Approved; they were told.", "We couldn't approve that.")}>
                            Approve
                          </Button>
                          <Button size="sm" variant="secondary" disabled={busy} onClick={() => run((t) => api.decideLeave(leave.id, "decline", null, t), "Declined; they were told.", "We couldn't decline that.")}>
                            Decline
                          </Button>
                        </div>
                      )}
                      {leave.status === "APPROVED" && (
                        <Button size="sm" variant="ghost" disabled={busy} onClick={() => run((t) => api.decideLeave(leave.id, "revoke", null, t), "Revoked; they were told.", "We couldn't revoke that.")}>
                          Revoke
                        </Button>
                      )}
                    </div>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>
      </main>
    </div>
  );
}

function StatusBadge({ status }: { status: LeaveView["status"] }) {
  if (status === "APPROVED") return <Badge tone="success">Approved</Badge>;
  if (status === "DECLINED") return <Badge tone="danger">Declined</Badge>;
  if (status === "REVOKED") return <Badge tone="neutral">Revoked</Badge>;
  return <Badge tone="warning">Waiting</Badge>;
}

function dateRange(leave: LeaveView): string {
  if (leave.halfDay) return `${leave.fromDate} (half day)`;
  return leave.fromDate === leave.toDate ? leave.fromDate : `${leave.fromDate} to ${leave.toDate}`;
}

function emptyToNull(s: string): string | null {
  const t = s.trim();
  return t === "" ? null : t;
}

function mondayOfThisWeek(): string {
  // From the temple's own day, not the device's — the roster week must not shift with the reader.
  const d = new Date(`${todayIso()}T00:00:00`);
  const day = d.getDay(); // 0 Sun … 6 Sat
  d.setDate(d.getDate() + (day === 0 ? -6 : 1 - day));
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const dd = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${dd}`;
}
