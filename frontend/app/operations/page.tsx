import { Sidebar } from "@/components/Sidebar";
import { PLATFORM_NAV } from "@/lib/nav";

/**
 * Super-Admin operations (E1-S11) — the "silent failure can't hide" page.
 *
 * <p>Two things live here. **System health** — is the platform up (DB reachable, scheduler
 * running). And a **per-temple drill-in** — pick a temple and see its notifications sent / failed
 * today and any recent failed sends, read under that temple's own context. Platform-wide totals,
 * trends, and the job-failure-rate alert live in Cloud Monitoring (fed by `/actuator/prometheus`),
 * not here — this page is the lightweight in-app view.
 *
 * <p>Like the other screens, this is the shape for now, filled from `api.opsTenants` /
 * `api.tenantOps` when the app is wired to live data.
 */

const STAT_TILES = [
  { key: "sentToday", label: "Sent today" },
  { key: "failedToday", label: "Failed today" },
  { key: "suppressedToday", label: "Suppressed today" },
];

export default function OperationsPage() {
  return (
    <div className="flex min-h-screen">
      <Sidebar templeName="Platform" items={PLATFORM_NAV} activeHref="/operations" />

      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-8">
            <h1>Operations</h1>
            <p className="mt-1 text-ink-secondary">
              System health, and how each temple&apos;s messages are going today.
            </p>
          </header>

          <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="health-heading">
            <h2 id="health-heading" className="text-lg">
              System health
            </h2>
            <dl className="mt-4 flex flex-wrap gap-x-10 gap-y-3 text-sm">
              <div>
                <dt className="text-ink-secondary">Database</dt>
                <dd className="mt-1 text-success">Reachable</dd>
              </div>
              <div>
                <dt className="text-ink-secondary">Job scheduler</dt>
                <dd className="mt-1 text-success">Running</dd>
              </div>
            </dl>
            <p className="mt-3 text-sm text-ink-muted">
              Live from <span className="font-mono">/health</span>. Trends and alerts live in Cloud
              Monitoring.
            </p>
          </section>

          <section aria-labelledby="temple-heading">
            <div className="mb-4 flex flex-wrap items-end justify-between gap-4">
              <h2 id="temple-heading" className="text-lg">
                A temple&apos;s messages
              </h2>
              <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                Temple
                <select className="min-h-touch rounded border border-hairline bg-raised px-3">
                  <option>Choose a temple…</option>
                </select>
              </label>
            </div>

            <div className="grid grid-cols-3 gap-4">
              {STAT_TILES.map((tile) => (
                <div key={tile.key} className="rounded-lg bg-raised px-5 py-4">
                  <p className="text-sm text-ink-secondary">{tile.label}</p>
                  <p className="mt-1 text-2xl">—</p>
                </div>
              ))}
            </div>

            <div className="mt-6 rounded-lg bg-raised px-6 py-5">
              <h3>Recent failed sends</h3>
              <p className="mt-2 text-ink-secondary">
                Nothing to show — pick a temple to see any messages that failed on every channel.
              </p>
            </div>

            <p className="mt-4 text-sm text-ink-muted">
              Last calendar precompute: not available yet — the calendar engine arrives in Epic 4.
            </p>
          </section>
        </div>
      </main>
    </div>
  );
}
