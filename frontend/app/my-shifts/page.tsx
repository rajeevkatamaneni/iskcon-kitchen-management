"use client";

import { useCallback } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { VOLUNTEER_NAV } from "@/lib/nav";
import { api, toApiError, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { useState } from "react";

export default function MyShiftsPage() {
  return (
    <RequireRole roles={["VOLUNTEER", "KITCHEN_STAFF"]}>
      <MyShiftsView />
    </RequireRole>
  );
}

function MyShiftsView() {
  const { getToken } = useAuth();
  const shifts = useAuthedQuery(useCallback((t: string | undefined) => api.myShifts(t), []));
  const waitlist = useAuthedQuery(useCallback((t: string | undefined) => api.myWaitlist(t), []));

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);

  async function run(mutation: (token: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    setActionError(null);
    try {
      await mutation(await getToken());
      shifts.reload();
      waitlist.reload();
    } catch (e) {
      setActionError(toApiError(e, failure));
    } finally {
      setBusy(false);
    }
  }

  const myShifts = shifts.data ?? [];
  const myWaitlist = waitlist.data ?? [];

  return (
    <div className="flex min-h-screen">
      <Sidebar templeName="Your temple" items={VOLUNTEER_NAV} activeHref="/my-shifts" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6">
            <h1>My shifts</h1>
            <p className="mt-1 text-ink-secondary">The seva you&rsquo;ve signed up for, and where you&rsquo;re waiting.</p>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

          {shifts.loading ? (
            <p className="text-ink-secondary">Loading…</p>
          ) : shifts.error ? (
            <ErrorNotice error={shifts.error} />
          ) : myShifts.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">No upcoming shifts</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">Browse available shifts to offer seva.</p>
            </div>
          ) : (
            <ul className="space-y-3">
              {myShifts.map((s) => (
                <li key={s.signupId} className="flex flex-wrap items-center justify-between gap-3 rounded-lg bg-raised px-5 py-4">
                  <div>
                    <p className="font-medium">
                      {s.title}
                      {s.source === "PROMOTION" && <span className="ml-2 rounded-sm bg-accent-bg px-2 py-0.5 text-xs text-accent-text">from waitlist</span>}
                    </p>
                    <p className="text-sm text-ink-secondary tabular-nums">{s.shiftDate} · {s.startTime}–{s.endTime}</p>
                    {s.location && <p className="text-sm text-ink-muted">{s.location}</p>}
                  </div>
                  <button type="button" disabled={busy} onClick={() => run((t) => api.releaseShift(s.shiftId, t), "We couldn't release your spot.")} className="min-h-touch rounded border border-hairline px-4 text-sm hover:bg-sunken disabled:opacity-60">
                    Can&rsquo;t make it? Release my spot
                  </button>
                </li>
              ))}
            </ul>
          )}

          {myWaitlist.length > 0 && (
            <section className="mt-10">
              <h2 className="mb-3 text-lg">On the waitlist</h2>
              <ul className="space-y-3">
                {myWaitlist.map((w) => (
                  <li key={w.shiftId} className="flex flex-wrap items-center justify-between gap-3 rounded-lg bg-raised px-5 py-4">
                    <div>
                      <p className="font-medium">{w.title} <span className="ml-2 text-sm text-ink-secondary">position {w.position}</span></p>
                      <p className="text-sm text-ink-secondary tabular-nums">{w.shiftDate} · {w.startTime}–{w.endTime}</p>
                    </div>
                    <button type="button" disabled={busy} onClick={() => run((t) => api.leaveWaitlist(w.shiftId, t), "We couldn't remove you from the waitlist.")} className="min-h-touch rounded border border-hairline px-4 text-sm hover:bg-sunken disabled:opacity-60">
                      Leave waitlist
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </div>
      </main>
    </div>
  );
}
