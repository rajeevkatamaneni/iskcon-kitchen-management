"use client";

import Link from "next/link";
import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api } from "@/lib/api";
import { todayIso } from "@/lib/format";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";

/**
 * The week grid (E6-S1): who works when.
 *
 * <p>It used to carry its own "Add staff" form, which created a bare profile for a kitchen-staff
 * user. Since E6-S8 an employment record is how somebody comes to work here at all, so this screen
 * shows the people the register already holds and sends the admin to /staff to add one. Two places
 * to create the same thing is two places for them to disagree.
 */

const DAY_LABELS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

export default function StaffSchedulePage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER"]}>
      <StaffScheduleView />
    </RequireRole>
  );
}

function StaffScheduleView() {
  const [weekStart, setWeekStart] = useState(mondayOfThisWeek());

  const week = useAuthedQuery(useCallback((t: string | undefined) => api.staffWeek(weekStart, t), [weekStart]));

  const rows = week.data?.staff ?? [];

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/staff-schedule" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Staff schedule</h1>
              <p className="mt-1 text-ink-secondary">Who works when — the weekly pattern, with per-date exceptions.</p>
            </div>
            <Link href="/staff" className="flex min-h-touch items-center rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover">
              Staff register
            </Link>
          </header>

          <div className="mb-4 flex items-center gap-3">
            <button type="button" onClick={() => setWeekStart(shiftWeek(weekStart, -7))} className="min-h-touch rounded border border-hairline px-3 hover:bg-sunken">← Prev</button>
            <span className="text-sm text-ink-secondary tabular-nums">Week of {weekStart}</span>
            <button type="button" onClick={() => setWeekStart(shiftWeek(weekStart, 7))} className="min-h-touch rounded border border-hairline px-3 hover:bg-sunken">Next →</button>
          </div>

          {week.loading ? (
            <Loading label="Loading schedule…" />
          ) : week.error ? (
            <ErrorNotice error={week.error} />
          ) : rows.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">No staff yet</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                Hire someone on the <Link href="/staff" className="text-accent-text hover:underline">staff register</Link> and they will appear here.
              </p>
            </div>
          ) : (
            <div className="overflow-x-auto rounded-lg bg-raised">
              <table className="w-full text-left text-sm">
                <thead className="bg-sunken text-ink-secondary">
                  <tr>
                    <th className="px-4 py-3 font-medium">Staff</th>
                    {DAY_LABELS.map((d) => <th key={d} className="px-3 py-3 font-medium">{d}</th>)}
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.staffProfileId} className="border-t border-hairline align-middle">
                      <td className="px-4 py-3">
                        <Link href={`/staff-schedule/${r.staffProfileId}`} className="font-medium text-accent-text hover:underline">{r.fullName}</Link>
                        {r.jobTitleLabel && <div className="text-xs text-ink-muted">{r.jobTitleLabel}</div>}
                      </td>
                      {r.days.map((d) => (
                        <td key={d.date} className={`px-3 py-3 tabular-nums ${d.fromException ? "text-warning" : ""}`}>
                          {d.working ? `${(d.startTime ?? "").slice(0, 5)}–${(d.endTime ?? "").slice(0, 5)}` : "Off"}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </main>
    </div>
  );
}

function localIso(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

function mondayOfThisWeek(): string {
  // From the temple's own day, not the device's — a schedule week must not shift with the reader.
  const d = new Date(`${todayIso()}T00:00:00`);
  const day = d.getDay(); // 0 Sun … 6 Sat
  d.setDate(d.getDate() + (day === 0 ? -6 : 1 - day));
  return localIso(d);
}

function shiftWeek(weekStart: string, days: number): string {
  const d = new Date(weekStart + "T00:00:00");
  d.setDate(d.getDate() + days);
  return localIso(d);
}
