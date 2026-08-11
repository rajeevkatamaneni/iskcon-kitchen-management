"use client";

import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, type AuditFilters } from "@/lib/api";
import { useAuthedQuery } from "@/lib/use-authed-query";

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
      <Sidebar templeName="Your temple" activeHref="/audit" />

      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-8">
            <h1>Audit log</h1>
            <p className="mt-1 text-ink-secondary">
              A permanent record of sensitive actions in your temple. Entries can never be edited
              or removed.
            </p>
          </header>

          <form
            className="mb-6 flex flex-wrap items-end gap-4"
            aria-label="Filter the audit log"
            onSubmit={apply}
          >
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              From
              <input type="date" name="from" className="min-h-touch rounded border border-hairline bg-raised px-3" />
            </label>
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              To
              <input type="date" name="to" className="min-h-touch rounded border border-hairline bg-raised px-3" />
            </label>
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              Action
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
            <p className="text-ink-secondary">Loading the log…</p>
          ) : error ? (
            <ErrorNotice error={error} />
          ) : events.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">Nothing recorded yet</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                As people change roles, receive overrides, or record payments, each action will
                appear here with who did it and what changed.
              </p>
            </div>
          ) : (
            <>
              <div className="overflow-hidden rounded-lg bg-raised">
                <table className="w-full text-left">
                  <thead className="bg-sunken text-sm text-ink-secondary">
                    <tr>
                      <th className="px-5 py-3 font-medium">When</th>
                      <th className="px-5 py-3 font-medium">Who</th>
                      <th className="px-5 py-3 font-medium">Action</th>
                      <th className="px-5 py-3 font-medium">Details</th>
                    </tr>
                  </thead>
                  <tbody>
                    {events.map((event) => (
                      <tr key={event.id} className="border-t border-hairline align-top">
                        <td className="px-5 py-4 text-ink-secondary">
                          {new Date(event.createdAt).toLocaleString()}
                        </td>
                        <td className="px-5 py-4">{event.actorLabel}</td>
                        <td className="px-5 py-4">{actionLabel(event.action)}</td>
                        <td className="px-5 py-4 text-sm text-ink-secondary">
                          {event.reason ??
                            [event.before, event.after]
                              .filter(Boolean)
                              .map((state) => JSON.stringify(state))
                              .join(" → ")}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {data?.nextCursor && (
                <p className="mt-4 text-sm text-ink-muted">
                  Showing the most recent entries. Narrow the date range to see older ones.
                </p>
              )}
            </>
          )}
        </div>
      </main>
    </div>
  );
}
