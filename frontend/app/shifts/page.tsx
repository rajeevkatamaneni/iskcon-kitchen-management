"use client";

import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { VOLUNTEER_NAV } from "@/lib/nav";
import { api, toApiError, type ApiError, type AvailableShiftView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

export default function AvailableShiftsPage() {
  return (
    <RequireRole roles={["VOLUNTEER"]}>
      <AvailableShiftsView />
    </RequireRole>
  );
}

function AvailableShiftsView() {
  const { getToken } = useAuth();
  const { data, error, loading, reload } = useAuthedQuery(
    useCallback((token: string | undefined) => api.availableShifts({}, token), [])
  );
  const shifts = data ?? [];

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  async function run(mutation: (token: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    setActionError(null);
    setNotice(null);
    try {
      const result = await mutation(await getToken());
      reload();
      return result;
    } catch (e) {
      setActionError(toApiError(e, failure));
      return null;
    } finally {
      setBusy(false);
    }
  }

  async function signUp(shift: AvailableShiftView) {
    const result = (await run((t) => api.signUpShift(shift.id, t), "We couldn't sign you up.")) as
      | { overlapWarning: boolean }
      | null;
    if (result) {
      setNotice(result.overlapWarning
        ? "You're signed up — note this overlaps another shift you're on."
        : "You're signed up. Thank you for your seva!");
    }
  }

  // Group by date for the wireframe's date-grouped cards.
  const byDate = new Map<string, AvailableShiftView[]>();
  for (const s of shifts) {
    if (!byDate.has(s.shiftDate)) byDate.set(s.shiftDate, []);
    byDate.get(s.shiftDate)!.push(s);
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar templeName="Your temple" items={VOLUNTEER_NAV} activeHref="/shifts" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6">
            <h1>Available shifts</h1>
            <p className="mt-1 text-ink-secondary">Offer seva — claim a spot in seconds.</p>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}
          {notice && <div className="mb-6 rounded border border-hairline bg-success-bg px-4 py-3 text-sm text-success">{notice}</div>}

          {loading ? (
            <p className="text-ink-secondary">Loading shifts…</p>
          ) : error ? (
            <ErrorNotice error={error} />
          ) : shifts.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">No open shifts right now</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">Check back soon — new shifts appear here as they&rsquo;re posted.</p>
            </div>
          ) : (
            <div className="space-y-8">
              {[...byDate.entries()].map(([date, dayShifts]) => (
                <section key={date}>
                  <h2 className="mb-3 text-lg">{date}</h2>
                  <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                    {dayShifts.map((s) => (
                      <article key={s.id} className="rounded-lg bg-raised px-5 py-4">
                        <div className="flex items-start justify-between gap-3">
                          <div>
                            <h3 className="font-medium">{s.title}</h3>
                            <p className="text-sm text-ink-secondary tabular-nums">{s.startTime}–{s.endTime}</p>
                            {s.location && <p className="text-sm text-ink-muted">{s.location}</p>}
                          </div>
                          {actionFor(s, busy, signUp,
                            () => run((t) => api.joinWaitlist(s.id, t), "We couldn't add you to the waitlist."),
                            () => run((t) => api.leaveWaitlist(s.id, t), "We couldn't remove you from the waitlist."))}
                        </div>
                        <CapacityBar signedUp={s.signedUpCount} capacity={s.capacity} />
                        <p className="mt-1 text-xs text-ink-muted tabular-nums">
                          {s.signedUpCount}/{s.capacity} filled{s.waitlistCount > 0 ? ` · ${s.waitlistCount} waiting` : ""}
                        </p>
                      </article>
                    ))}
                  </div>
                </section>
              ))}
            </div>
          )}
        </div>
      </main>
    </div>
  );
}

function actionFor(
  s: AvailableShiftView,
  busy: boolean,
  signUp: (s: AvailableShiftView) => void,
  join: () => void,
  leave: () => void
) {
  if (s.callerState === "SIGNED_UP") {
    return <span className="rounded-sm bg-success-bg px-2 py-1 text-xs text-success">You&rsquo;re in</span>;
  }
  if (s.callerState === "WAITLISTED") {
    return (
      <button type="button" disabled={busy} onClick={leave} className="min-h-touch rounded border border-hairline px-3 text-sm hover:bg-sunken disabled:opacity-60">
        Leave waitlist
      </button>
    );
  }
  if (s.callerState === "FULL") {
    return (
      <button type="button" disabled={busy} onClick={join} className="min-h-touch rounded border border-hairline px-3 text-sm hover:bg-sunken disabled:opacity-60">
        Join waitlist
      </button>
    );
  }
  return (
    <button type="button" disabled={busy} onClick={() => signUp(s)} className="min-h-touch rounded bg-accent px-4 text-sm text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">
      Sign up
    </button>
  );
}

function CapacityBar({ signedUp, capacity }: { signedUp: number; capacity: number }) {
  const pct = capacity > 0 ? Math.min(100, Math.round((signedUp / capacity) * 100)) : 0;
  return (
    <div className="mt-3 h-2 w-full overflow-hidden rounded-full bg-sunken">
      <div className="h-full bg-accent" style={{ width: `${pct}%` }} />
    </div>
  );
}
