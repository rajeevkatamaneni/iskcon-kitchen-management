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
import { Loading } from "@/components/Loading";

/**
 * One person's weekly pattern (E6-S1).
 *
 * <p>The pattern, and nothing else. Per-date changes used to live here as a "Date exceptions" list
 * and moved to the week grid with the 2026-08-20 brief (§6): this page answers "what is this
 * person's ordinary week?", and a swapped Thursday is not an answer to that. Keeping both here made
 * the screen a place where a one-off and a permanent change looked like the same act.
 */

const DAY_LABELS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

export default function StaffProfilePage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER"]}>
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

  const profile = data?.profile;
  const template = data?.template ?? [];
  const byDay = new Map(template.map((d) => [d.dayOfWeek, d]));

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/staff-schedule" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <Link href="/staff-schedule" className="text-sm text-accent-text hover:underline">← All staff</Link>

          {loading ? (
            <Loading />
          ) : error ? (
            <div className="mt-6"><ErrorNotice error={error} /></div>
          ) : !profile ? null : (
            <>
              <header className="mb-6 mt-3">
                <h1>{profile.fullName}</h1>
                <p className="mt-1 text-ink-secondary">
                  {profile.jobTitleLabel} · joined {profile.dateOfJoining}
                  {profile.employmentStatus !== "ACTIVE" && ` · no longer employed (last day ${profile.lastWorkingDay})`}
                </p>
              </header>

              {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}
              {notice && <div className="mb-6 rounded border border-hairline bg-success-bg px-4 py-3 text-sm text-success">{notice}</div>}

              <section className="rounded-lg bg-raised px-6 py-5">
                <h2 className="text-lg">Weekly template</h2>
                <p className="mt-1 max-w-prose text-sm text-ink-secondary">
                  Their ordinary week. To change one date — different hours, an extra day, a swap, or
                  time off — open the{" "}
                  <Link href="/staff-schedule" className="text-accent-text hover:underline">week grid</Link>{" "}
                  and click that day.
                </p>
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

            </>
          )}
        </div>
      </main>
    </div>
  );
}
