"use client";

import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api } from "@/lib/api";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";

/**
 * Super-Admin operations (E1-S11) — the "silent failure can't hide" page.
 *
 * <p>Two things live here. **System health** — is the platform up (DB reachable, scheduler
 * running). And **notification metrics** — how many messages the platform sent and failed to send
 * today, each with a seven-day pulse split into two-hour buckets so *when* is visible, not just how
 * many. These are platform-wide aggregate counts, not a temple's business data; the per-temple
 * drill-in and channel-level detail belong to a temple's own admin (the proposed Temple System
 * Health Dashboard). Deeper trends and the job-failure-rate alert live in Cloud Monitoring, not
 * here — this page is the lightweight in-app view.
 */

/**
 * Jobs run in their own service, so what matters here is whether *a* worker is alive — read from the
 * clustered job store — not whether this API instance happens to hold a scheduler.
 */
const WORKER_LABELS: Record<string, string> = {
  RUNNING: "Running",
  STALE: "Not responding",
  ABSENT: "Never started",
  UNKNOWN: "Can't tell",
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
  const metrics = useAuthedQuery(api.opsNotifications);

  const dbUp = health.data?.db === "UP";
  const workerState = health.data?.worker ?? "";
  const workerHealthy = workerState === "RUNNING";

  const dates = metrics.data?.days.map((d) => d.date);
  const sentDays = metrics.data?.days.map((d) => d.sent);
  const failedDays = metrics.data?.days.map((d) => d.failed);

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/operations" />

      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-8">
            <h1>Operations</h1>
          </header>

          <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="health-heading">
            <h2 id="health-heading" className="text-lg">
              System health
            </h2>
            {health.loading ? (
              <Loading label="Checking…" />
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
                  <dt className="text-ink-secondary">Background worker</dt>
                  <dd className={`mt-1 ${workerHealthy ? "text-success" : "text-danger"}`}>
                    {WORKER_LABELS[workerState] ?? workerState}
                  </dd>
                </div>
              </dl>
            )}
            <p className="mt-3 text-sm text-ink-muted">
              Live from <span className="font-mono">/health</span>. The worker is what runs reminders,
              digests and the calendar; if it stops, nothing scheduled happens. Trends and alerts live
              in Cloud Monitoring.
            </p>
          </section>

          <section aria-labelledby="metrics-heading">
            <h2 id="metrics-heading" className="mb-4 text-lg">
              Notification metrics
            </h2>

            {metrics.error ? (
              <ErrorNotice error={metrics.error} />
            ) : (
              <div className="grid grid-cols-2 gap-4">
                <MetricTile
                  label="Sent today"
                  value={metrics.data?.sentToday}
                  days={sentDays}
                  dates={dates}
                  tone="neutral"
                  hint="Handed off to a channel — WhatsApp, SMS or email. Each column is a day; pixels are volume in 2-hour windows, across all temples."
                />
                <MetricTile
                  label="Failed today"
                  value={metrics.data?.failedToday}
                  days={failedDays}
                  dates={dates}
                  tone="danger"
                  hint="A send that no channel accepted, so the person wasn't reached. A quiet field is healthy; a lit pixel marks a 2-hour window that failed."
                />
              </div>
            )}
          </section>
        </div>
      </main>
    </div>
  );
}

function MetricTile({
  label,
  value,
  days,
  dates,
  tone,
  hint,
}: {
  label: string;
  value: number | undefined;
  days: number[][] | undefined;
  dates: string[] | undefined;
  tone: "neutral" | "danger";
  hint: string;
}) {
  return (
    <div className="rounded-lg bg-raised px-5 py-4">
      <p className="text-sm text-ink-secondary">{label}</p>
      <p className="mt-1 text-2xl tabular-nums">{value ?? "—"}</p>
      {days ? (
        <>
          <PixelPulse days={days} tone={tone} label={label} />
          {dates ? <WeekAxis dates={dates} /> : null}
        </>
      ) : (
        <div className="mt-3 h-14" />
      )}
      <p className="mt-2 text-xs text-ink-muted">{hint}</p>
    </div>
  );
}

/**
 * A seven-day pulse: each day is a column of twelve two-hour buckets, each bucket an eight-pixel
 * stack lit from the baseline by its volume. The full off-pixel grid is always drawn, so an empty
 * day reads as *quiet* rather than broken and a single lit pixel is unmissable — the point of the
 * matrix for the sparse Failed case. Sent volume is neutral ink; failures wear the danger token.
 * A single series per tile, so no legend — the label names it and a value-list aria-label carries
 * the shape.
 */
function PixelPulse({
  days,
  tone,
  label,
}: {
  days: number[][];
  tone: "neutral" | "danger";
  label: string;
}) {
  const ROWS = 8;
  const PW = 4;
  const PH = 4;
  const CELL_W = PW + 2; // 6
  const CELL_H = PH + 1; // 5
  const DAY_W = 12 * CELL_W - 2; // 70 — twelve buckets, no trailing gap
  const DAY_PITCH = DAY_W + 8; // 78 — a multiple of CELL_W, so the off-grid pattern stays aligned
  const H = ROWS * CELL_H - 1; // 39
  const W = days.length * DAY_PITCH - 8;

  const patternId = `pulse-off-${tone}`;
  const onFill = tone === "danger" ? "fill-danger" : "fill-ink";
  const todayIndex = days.length - 1;

  // Scale lit height against the whole chart's busiest bucket.
  const max = Math.max(1, ...days.flat());
  const totals = days.map((d) => d.reduce((a, b) => a + b, 0));

  return (
    <svg
      viewBox={`0 0 ${W} ${H}`}
      preserveAspectRatio="none"
      role="img"
      className="mt-3 block h-14 w-full"
      aria-label={`${label} in 2-hour buckets over the last 7 days. Daily totals: ${totals.join(
        ", "
      )}. The last column is today.`}
    >
      <defs>
        <pattern id={patternId} width={CELL_W} height={CELL_H} patternUnits="userSpaceOnUse">
          <rect width={PW} height={PH} rx={0.8} className="fill-hairline-strong opacity-40" />
        </pattern>
      </defs>
      {days.map((day, di) => {
        const dayX = di * DAY_PITCH;
        const isToday = di === todayIndex;
        return (
          <g key={di}>
            <rect x={dayX} y={0} width={DAY_W} height={H} fill={`url(#${patternId})`} />
            {day.flatMap((v, si) => {
              if (v <= 0) return [];
              // Perceptual (√) scale with a 1-pixel floor: a lone send is never invisible, and a
              // spike never flattens the smaller days.
              const lit = Math.min(ROWS, Math.max(1, Math.round(Math.sqrt(v / max) * ROWS)));
              const x = dayX + si * CELL_W;
              return Array.from({ length: lit }, (_, r) => (
                <rect
                  key={`${si}-${r}`}
                  x={x}
                  y={(ROWS - 1 - r) * CELL_H}
                  width={PW}
                  height={PH}
                  rx={0.8}
                  className={`${onFill} ${isToday ? "" : "opacity-50"}`}
                />
              ));
            })}
          </g>
        );
      })}
    </svg>
  );
}

/** Weekday labels under a pulse; the last column is today. */
function WeekAxis({ dates }: { dates: string[] }) {
  return (
    <div className="mt-2 flex">
      {dates.map((iso, i) => {
        const isToday = i === dates.length - 1;
        const label = isToday
          ? "Today"
          : new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, { weekday: "short" });
        return (
          <span
            key={iso}
            className={`flex-1 text-center text-xs ${
              isToday ? "text-ink-secondary" : "text-ink-muted"
            }`}
          >
            {label}
          </span>
        );
      })}
    </div>
  );
}
