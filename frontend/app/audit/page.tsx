"use client";

import { useCallback, useState } from "react";
import { DateRange } from "@/components/ds/DateRange";
import { Form } from "@/components/ds/Form";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, type AuditFilters } from "@/lib/api";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";
import { RULED_TABLE, TD_FIXED, TD_PRIMARY, TD_SECOND, THEAD, TH_FIXED, TH_PRIMARY, TH_SECOND, TR } from "@/components/ds/table";
import { moment } from "@/lib/format";

/**
 * The audit log viewer (E1-S7).
 *
 * <p>A temple's own history of the actions that have to be explainable — role changes,
 * provisioning, and, as later epics land, overrides and payments. Scoped by the backend to the
 * viewer's own temple; a super-admin reaches a temple's log by a different, recorded route. Only a
 * Temple Admin holds VIEW_AUDIT_LOG.
 */

const ACTION_LABELS: Record<string, string> = {
  TENANT_PROVISIONED: "Temple provisioned",
  ROLE_CHANGED: "Role changed",
  ROLE_CHANGE_REJECTED: "Role change refused",
  STAFF_EMPLOYMENT_END_REJECTED: "Tried to end their own employment",
  AUDIT_LOG_VIEWED: "Log viewed by operator",
  MEAL_CORRECTED: "Meal figures corrected",
  COMMUNICATION_RETRIED: "Message sent again",
  ATTENDANCE_CORRECTED: "Attendance mark changed",
};

function actionLabel(action: string): string {
  return ACTION_LABELS[action] ?? action;
}

export default function AuditPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <AuditView />
    </RequireRole>
  );
}

function AuditView() {
  // Only the *applied* filters drive the query, so typing into the form doesn't re-fetch — a new
  // request happens when Apply changes this object's identity.
  const [filters, setFilters] = useState<AuditFilters>({});
  const fetcher = useCallback(
    (token: string | undefined) => api.listAuditEvents(filters, token),
    [filters]
  );
  const { data, error, loading } = useAuthedQuery(fetcher);
  const events = data?.events ?? [];

  function apply(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setFilters({
      from: String(form.get("from") ?? ""),
      to: String(form.get("to") ?? ""),
      action: String(form.get("action") ?? ""),
    });
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/audit" />

      <main className="min-w-0 flex-1 px-4 py-10 sm:px-8">
        <div className="mx-auto max-w-content">
          <header className="mb-8">
            <h1>Audit log</h1>
            <p className="mt-1 text-ink-secondary">
              Entries can never be edited or removed.
            </p>
          </header>

          <Form
            className="mb-6 flex flex-wrap items-end gap-4"
            aria-label="Filter the audit log"
            onSubmit={apply}
          >
            <DateRange
              from={{ name: "from", label: "From" }}
              to={{ name: "to", label: "To" }}
              className="min-h-touch rounded-control border border-hairline px-3"
            />
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">Action</span>
              <select name="action" className="min-h-touch rounded-control border border-hairline px-3">
                <option value="">Any action</option>
                {Object.entries(ACTION_LABELS).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
            <button
              type="submit"
              className="btn btn-primary min-h-touch px-5 transition-colors duration-state"
            >
              Apply
            </button>
          </Form>

          {loading ? (
            <Loading label="Loading the log…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : events.length === 0 ? (
            <div className="card px-6 py-14 text-center">
              <p className="text-lg">Nothing recorded yet</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                Role changes, overrides and payments appear here as they happen.
              </p>
            </div>
          ) : (
            <>
              <div className="table-wrap overflow-x-auto">
                {/* On the table rule since 2026-09-18 (T-233), classified by Rajeev: Details is
                    the primary flexible column and Who the secondary one; the action's label and
                    the time are fixed (one line). Every column reads left since T-236. */}
                <table className={RULED_TABLE}>
                  <thead className={THEAD}>
                    <tr>
                      <th className={TH_PRIMARY}>Details</th>
                      <th className={TH_SECOND}>Who</th>
                      <th className={TH_FIXED}>Action</th>
                      <th className={TH_FIXED}>When</th>
                    </tr>
                  </thead>
                  <tbody>
                    {events.map((event) => (
                      <tr key={event.id} className={TR}>
                        <td className={`${TD_PRIMARY} text-sm text-ink-secondary`}>
                          {/* Wraps when the table is short of room, so a before/after pair as long as the record it
                              describes grows downwards here rather than pushing the columns beside
                              it off the screen. A recorded state is one unbroken run
                              of punctuation with nowhere to break, so it is allowed to break
                              anywhere; somebody's own words are not, and break between words. */}
                          {event.reason != null ? (
                            <div className="break-words">{event.reason}</div>
                          ) : (
                            <div className="break-all">
                              {[event.before, event.after]
                                .filter(Boolean)
                                .map((state) => JSON.stringify(state))
                                .join(" → ")}
                            </div>
                          )}
                        </td>
                        <td className={TD_SECOND}>{event.actorLabel}</td>
                        <td className={TD_FIXED}>{actionLabel(event.action)}</td>
                        <td className={`${TD_FIXED} text-ink-secondary`}>{moment(event.createdAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {data?.nextCursor && (
                <p className="mt-4 text-sm text-ink-muted">
                  Narrow the date range to see older entries.
                </p>
              )}
            </>
          )}
        </div>
      </main>
    </div>
  );
}
