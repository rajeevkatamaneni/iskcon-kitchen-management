"use client";

import Link from "next/link";
import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

const DAY_LABELS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

export default function StaffSchedulePage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <StaffScheduleView />
    </RequireRole>
  );
}

function StaffScheduleView() {
  const { getToken } = useAuth();
  const [weekStart, setWeekStart] = useState(mondayOfThisWeek());

  const week = useAuthedQuery(useCallback((t: string | undefined) => api.staffWeek(weekStart, t), [weekStart]));
  const profiles = useAuthedQuery(useCallback((t: string | undefined) => api.listStaffProfiles(t), []));
  const users = useAuthedQuery(api.listUsers);

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [showAdd, setShowAdd] = useState(false);

  const staffUsers = (users.data ?? []).filter((u) => u.role === "KITCHEN_STAFF");

  async function add(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const f = new FormData(form);
    setBusy(true);
    setActionError(null);
    try {
      await api.createStaffProfile(
        { userId: String(f.get("userId") ?? ""), designation: emptyToNull(String(f.get("designation") ?? "")) },
        await getToken()
      );
      form.reset();
      setShowAdd(false);
      profiles.reload();
      week.reload();
    } catch (e) {
      setActionError(toApiError(e, "We couldn't add that staff member."));
    } finally {
      setBusy(false);
    }
  }

  const rows = week.data?.staff ?? [];

  return (
    <div className="flex min-h-screen">
      <Sidebar templeName="Your temple" activeHref="/staff-schedule" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Staff schedule</h1>
              <p className="mt-1 text-ink-secondary">Who works when — the weekly pattern, with per-date exceptions.</p>
            </div>
            <button type="button" onClick={() => setShowAdd((s) => !s)} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover">
              Add staff
            </button>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

          {showAdd && (
            <section className="mb-8 rounded-lg bg-raised px-6 py-5">
              <h2 className="text-lg">New staff profile</h2>
              <form className="mt-3 flex flex-wrap items-end gap-4" aria-label="Add staff" onSubmit={add}>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">Person
                  <select name="userId" required className="min-h-touch rounded border border-hairline bg-canvas px-3">
                    <option value="">Choose a kitchen-staff member…</option>
                    {staffUsers.map((u) => <option key={u.id} value={u.id}>{u.fullName}</option>)}
                  </select>
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">Designation
                  <input name="designation" placeholder="Head Cook, Prep…" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <button type="submit" disabled={busy || staffUsers.length === 0} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">Add</button>
              </form>
            </section>
          )}

          <div className="mb-4 flex items-center gap-3">
            <button type="button" onClick={() => setWeekStart(shiftWeek(weekStart, -7))} className="min-h-touch rounded border border-hairline px-3 hover:bg-sunken">← Prev</button>
            <span className="text-sm text-ink-secondary tabular-nums">Week of {weekStart}</span>
            <button type="button" onClick={() => setWeekStart(shiftWeek(weekStart, 7))} className="min-h-touch rounded border border-hairline px-3 hover:bg-sunken">Next →</button>
          </div>

          {week.loading ? (
            <p className="text-ink-secondary">Loading schedule…</p>
          ) : week.error ? (
            <ErrorNotice error={week.error} />
          ) : rows.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">No staff yet</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">Add a kitchen-staff member above to start building the schedule.</p>
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
                        {r.designation && <div className="text-xs text-ink-muted">{r.designation}</div>}
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
  const d = new Date();
  const day = d.getDay(); // 0 Sun … 6 Sat
  d.setDate(d.getDate() + (day === 0 ? -6 : 1 - day));
  return localIso(d);
}

function shiftWeek(weekStart: string, days: number): string {
  const d = new Date(weekStart + "T00:00:00");
  d.setDate(d.getDate() + days);
  return localIso(d);
}

function emptyToNull(s: string): string | null {
  const t = s.trim();
  return t === "" ? null : t;
}
