"use client";

import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, type TenantOps } from "@/lib/api";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * Super-Admin operations (E1-S11) — the "silent failure can't hide" page.
 *
 * <p>Two things live here. **System health** — is the platform up (DB reachable, scheduler
 * running). And a **per-temple drill-in** — pick a temple and see its notifications sent / failed
 * today and any recent failed sends, read under that temple's own context. Platform-wide totals,
 * trends, and the job-failure-rate alert live in Cloud Monitoring (fed by `/actuator/prometheus`),
 * not here — this page is the lightweight in-app view.
 */

const STAT_TILES: {
  key: keyof Pick<TenantOps, "sentToday" | "failedToday" | "suppressedToday">;
  label: string;
  hint: string;
}[] = [
  { key: "sentToday", label: "Sent today", hint: "Handed off to a channel (WhatsApp, SMS or email)." },
  { key: "failedToday", label: "Failed today", hint: "A send was attempted but the channel rejected it." },
  {
    key: "suppressedToday",
    label: "Suppressed today",
    hint: "Deliberately not sent — e.g. the person hasn't consented, or has no usable channel.",
  },
];

const SCHEDULER_LABELS: Record<string, string> = {
  RUNNING: "Running",
  STANDBY: "On standby",
  ABSENT: "Not on this instance",
  ERROR: "Error",
};

export default function OperationsPage() {
  return (
    <RequireRole roles={["SUPER_ADMIN"]}>
      <OperationsView />
    </RequireRole>
  );
}

function OperationsView() {
  const health = useAuthedQuery(api.health);
  const tenants = useAuthedQuery(api.opsTenants);

  const [selected, setSelected] = useState<string>("");
  // Fetch a temple's operations only once one is chosen; before that the query resolves to null.
  const opsFetcher = useCallback(
    (token: string | undefined) => (selected ? api.tenantOps(selected, token) : Promise.resolve(null)),
    [selected]
  );
  const ops = useAuthedQuery(opsFetcher);

  const dbUp = health.data?.db === "UP";
  const schedulerState = health.data?.scheduler ?? "";

  return (
    <div className="flex min-h-screen">
      <Sidebar templeName="Platform" activeHref="/operations" />

      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-8">
            <h1>Operations</h1>
            <p className="mt-1 text-ink-secondary">
              System health, and how each temple&apos;s notifications are being delivered today.
            </p>
          </header>

          <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="health-heading">
            <h2 id="health-heading" className="text-lg">
              System health
            </h2>
            {health.loading ? (
              <p className="mt-4 text-sm text-ink-secondary">Checking…</p>
            ) : health.error || !health.data ? (
              <p className="mt-4 text-sm text-danger">
                Couldn&apos;t reach the health endpoint. The API may be down.
              </p>
            ) : (
              <dl className="mt-4 flex flex-wrap gap-x-10 gap-y-3 text-sm">
                <div>
                  <dt className="text-ink-secondary">Database</dt>
                  <dd className={`mt-1 ${dbUp ? "text-success" : "text-danger"}`}>
                    {dbUp ? "Reachable" : "Unreachable"}
                  </dd>
                </div>
                <div>
                  <dt className="text-ink-secondary">Job scheduler</dt>
                  <dd className={`mt-1 ${schedulerState === "ERROR" ? "text-danger" : "text-success"}`}>
                    {SCHEDULER_LABELS[schedulerState] ?? schedulerState}
                  </dd>
                </div>
              </dl>
            )}
            <p className="mt-3 text-sm text-ink-muted">
              Live from <span className="font-mono">/health</span>. Trends and alerts live in Cloud
              Monitoring.
            </p>
          </section>

          <section aria-labelledby="temple-heading">
            <div className="mb-1 flex flex-wrap items-end justify-between gap-4">
              <h2 id="temple-heading" className="text-lg">
                A temple&apos;s notifications
              </h2>
              <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                Temple
                <select
                  className="min-h-touch rounded border border-hairline bg-raised px-3"
                  value={selected}
                  onChange={(e) => setSelected(e.target.value)}
                >
                  <option value="">Choose a temple…</option>
                  {(tenants.data ?? []).map((t) => (
                    <option key={t.id} value={t.id}>
                      {t.name}
                    </option>
                  ))}
                </select>
              </label>
            </div>

            <p className="mb-5 max-w-prose text-sm text-ink-secondary">
              The automated reminders, digests and alerts the system sends to this temple&apos;s
              people (over WhatsApp, SMS or email) — and how delivery is going today. Not messages
              between you and the temple.
            </p>

            {ops.error ? (
              <ErrorNotice error={ops.error} />
            ) : (
              <>
                <div className="grid grid-cols-3 gap-4">
                  {STAT_TILES.map((tile) => (
                    <div key={tile.key} className="rounded-lg bg-raised px-5 py-4">
                      <p className="text-sm text-ink-secondary">{tile.label}</p>
                      <p className="mt-1 text-2xl tabular-nums">{ops.data ? ops.data[tile.key] : "—"}</p>
                      <p className="mt-2 text-xs text-ink-muted">{tile.hint}</p>
                    </div>
                  ))}
                </div>

                <div className="mt-6 rounded-lg bg-raised px-6 py-5">
                  <h3>Recent failed sends</h3>
                  <p className="mt-1 text-sm text-ink-muted">
                    Messages that failed on every channel, so the person wasn&apos;t reached at all.
                  </p>
                  {!ops.data ? (
                    <p className="mt-2 text-ink-secondary">
                      Nothing to show — pick a temple to see any messages that failed on every
                      channel.
                    </p>
                  ) : ops.data.recentFailures.length === 0 ? (
                    <p className="mt-2 text-ink-secondary">
                      No failed sends for {ops.data.tenantName} today.
                    </p>
                  ) : (
                    <ul className="mt-3 divide-y divide-hairline">
                      {ops.data.recentFailures.map((failure) => (
                        <li key={failure.id} className="flex justify-between gap-4 py-2 text-sm">
                          <span>
                            {failure.template} → {failure.recipientLabel}
                          </span>
                          <span className="text-ink-secondary">
                            {new Date(failure.failedAt).toLocaleString()}
                          </span>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>

                <p className="mt-4 text-sm text-ink-muted">
                  Last calendar precompute:{" "}
                  {ops.data?.lastCalendarPrecompute
                    ? new Date(ops.data.lastCalendarPrecompute).toLocaleString()
                    : "not available yet — the calendar engine arrives in Epic 4."}
                </p>
              </>
            )}
          </section>
        </div>
      </main>
    </div>
  );
}
