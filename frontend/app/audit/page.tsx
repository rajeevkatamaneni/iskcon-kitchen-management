"use client";

import { useCallback, useState } from "react";
import { DateRange } from "@/components/ds/DateRange";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, type AuditFilters } from "@/lib/api";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";
import { TABLE, TD_DATE, TD_TEXT, THEAD, TH_TEXT, TR, WRAP } from "@/components/ds/table";
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
  AUDIT_LOG_VIEWED: "Log viewed by operator",
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

      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-8">
            <h1>Audit log</h1>
            <p className="mt-1 text-ink-secondary">
              Entries can never be edited or removed.
            </p>
          </header>

          <form
            className="mb-6 flex flex-wrap items-end gap-4"
            aria-label="Filter the audit log"
            onSubmit={apply}
          >
            <DateRange
              from={{ name: "from", label: "From" }}
              to={{ name: "to", label: "To" }}
              className="min-h-touch rounded border border-hairline bg-raised px-3"
            />
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">Action</span>
              <select name="action" className="min-h-touch rounded border border-hairline bg-raised px-3">
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
              className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover"
            >
              Apply
            </button>
          </form>

          {loading ? (
            <Loading label="Loading the log…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : events.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">Nothing recorded yet</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                Role changes, overrides and payments appear here as they happen.
              </p>
            </div>
          ) : (
            <>
              <div className="overflow-x-auto rounded-lg bg-raised">
                <table className={TABLE}>
                  <thead className={THEAD}>
                    <tr>
                      <th className={TH_TEXT}>When</th>
                      <th className={`${TH_TEXT} ${WRAP}`}>Who</th>
                      <th className={TH_TEXT}>Action</th>
                      <th className={`${TH_TEXT} ${WRAP}`}>Details</th>
                    </tr>
                  </thead>
                  <tbody>
                    {events.map((event) => (
                      <tr key={event.id} className={TR}>
                        <td className={`${TD_DATE} text-ink-secondary`}>
                          {moment(event.createdAt)}
                        </td>
                        <td className={`${TD_TEXT} ${WRAP}`}>{event.actorLabel}</td>
                        <td className={TD_TEXT}>{actionLabel(event.action)}</td>
                        <td className={`${TD_TEXT} ${WRAP} text-sm text-ink-secondary`}>
                          {/* The column that takes this table's slack, so a before/after pair as long
                              as the record it describes grows downwards here rather than pushing the
                              columns beside it off the screen. A recorded state is one unbroken run
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
