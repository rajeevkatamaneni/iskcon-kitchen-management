"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type ScheduleDay } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

const DAY_LABELS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

export default function StaffProfilePage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <StaffProfileView />
    </RequireRole>
  );
}

function StaffProfileView() {
  const id = useParams<{ id: string }>().id;
  const { getToken } = useAuth();
  const { data, error, loading, reload } = useAuthedQuery(
    useCallback((t: string | undefined) => api.getStaffProfile(id, t), [id])
  );

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  async function run(mutation: (t: string | undefined) => Promise<unknown>, ok: string, failure: string) {
    setBusy(true);
    setActionError(null);
    setNotice(null);
    try {
      await mutation(await getToken());
      reload();
      setNotice(ok);
      return true;
    } catch (e) {
      setActionError(toApiError(e, failure));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function saveTemplate(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    const days: ScheduleDay[] = [1, 2, 3, 4, 5, 6, 7].map((dow) => {
      const working = f.get(`working_${dow}`) === "on";
      return {
        dayOfWeek: dow,
        working,
        startTime: working ? String(f.get(`start_${dow}`) ?? "") : null,
        endTime: working ? String(f.get(`end_${dow}`) ?? "") : null,
      };
    });
    await run((t) => api.setStaffTemplate(id, days, t), "Schedule saved; the staff member was notified.", "We couldn't save the schedule.");
  }

  async function addException(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const f = new FormData(form);
    const working = f.get("working") === "on";
    const okDone = await run(
      (t) => api.setStaffException(id, {
        exceptionDate: String(f.get("exceptionDate") ?? ""),
        working,
        startTime: working ? String(f.get("startTime") ?? "") : null,
        endTime: working ? String(f.get("endTime") ?? "") : null,
        note: emptyToNull(String(f.get("note") ?? "")),
      }, t),
      "Exception saved; the staff member was notified.",
      "We couldn't save that exception."
    );
    if (okDone) form.reset();
  }

  const profile = data?.profile;
  const template = data?.template ?? [];
  const exceptions = data?.exceptions ?? [];
  const byDay = new Map(template.map((d) => [d.dayOfWeek, d]));

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/staff-schedule" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <Link href="/staff-schedule" className="text-sm text-accent-text hover:underline">← All staff</Link>

          {loading ? (
            <p className="mt-6 text-ink-secondary">Loading…</p>
          ) : error ? (
            <div className="mt-6"><ErrorNotice error={error} /></div>
          ) : !profile ? null : (
            <>
              <header className="mb-6 mt-3">
                <h1>{profile.fullName}</h1>
                <p className="mt-1 text-ink-secondary">{profile.designation ?? "No designation"} · {profile.active ? "Active" : "Inactive"}</p>
              </header>

              {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}
              {notice && <div className="mb-6 rounded border border-hairline bg-success-bg px-4 py-3 text-sm text-success">{notice}</div>}

              <section className="mb-8 rounded-lg bg-raised px-6 py-5">
                <h2 className="text-lg">Weekly template</h2>
                <form className="mt-4 space-y-2" aria-label="Weekly template" onSubmit={saveTemplate}>
                  {[1, 2, 3, 4, 5, 6, 7].map((dow) => {
                    const d = byDay.get(dow);
                    return (
                      <div key={dow} className="flex flex-wrap items-center gap-3">
                        <span className="w-12 text-sm text-ink-secondary">{DAY_LABELS[dow - 1]}</span>
                        <label className="flex items-center gap-2 text-sm text-ink-secondary">
                          <input type="checkbox" name={`working_${dow}`} defaultChecked={d?.working ?? false} /> Working
                        </label>
                        <input type="time" name={`start_${dow}`} defaultValue={(d?.startTime ?? "").slice(0, 5)} aria-label={`${DAY_LABELS[dow - 1]} start`} className="min-h-touch rounded border border-hairline bg-canvas px-2" />
                        <span className="text-ink-muted">–</span>
                        <input type="time" name={`end_${dow}`} defaultValue={(d?.endTime ?? "").slice(0, 5)} aria-label={`${DAY_LABELS[dow - 1]} end`} className="min-h-touch rounded border border-hairline bg-canvas px-2" />
                      </div>
                    );
                  })}
                  <button type="submit" disabled={busy} className="mt-3 min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">Save template</button>
                </form>
              </section>

              <section className="rounded-lg bg-raised px-6 py-5">
                <h2 className="text-lg">Date exceptions</h2>
                {exceptions.length > 0 && (
                  <ul className="mt-3 space-y-2">
                    {exceptions.map((ex) => (
                      <li key={ex.id} className="flex items-center justify-between text-sm">
                        <span className="tabular-nums">
                          {ex.exceptionDate}: {ex.working ? `${(ex.startTime ?? "").slice(0, 5)}–${(ex.endTime ?? "").slice(0, 5)}` : "Off"}
                          {ex.note ? ` — ${ex.note}` : ""}
                        </span>
                        <button type="button" disabled={busy} onClick={() => run((t) => api.deleteStaffException(id, ex.id, t), "Exception removed.", "We couldn't remove that exception.")} className="text-ink-secondary hover:underline disabled:opacity-60">Remove</button>
                      </li>
                    ))}
                  </ul>
                )}
                <form className="mt-4 flex flex-wrap items-end gap-3" aria-label="Add exception" onSubmit={addException}>
                  <label className="flex flex-col gap-1 text-sm text-ink-secondary">Date
                    <input type="date" name="exceptionDate" required className="min-h-touch rounded border border-hairline bg-canvas px-2" />
                  </label>
                  <label className="flex items-center gap-2 text-sm text-ink-secondary"><input type="checkbox" name="working" /> Working</label>
                  <input type="time" name="startTime" aria-label="Exception start" className="min-h-touch rounded border border-hairline bg-canvas px-2" />
                  <input type="time" name="endTime" aria-label="Exception end" className="min-h-touch rounded border border-hairline bg-canvas px-2" />
                  <input name="note" placeholder="Note" className="min-h-touch rounded border border-hairline bg-canvas px-2" />
                  <button type="submit" disabled={busy} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">Add exception</button>
                </form>
              </section>
            </>
          )}
        </div>
      </main>
    </div>
  );
}

function emptyToNull(s: string): string | null {
  const t = s.trim();
  return t === "" ? null : t;
}
