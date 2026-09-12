"use client";

import { useCallback } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type MyReleasedShiftView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { useState } from "react";
import { Loading } from "@/components/Loading";
import { dateWithYear, shiftWindow } from "@/lib/format";

/**
 * Why a volunteer is no longer on a shift, in the words the volunteer was sent (T-149).
 *
 * <p>Deliberately not the coordinator's labels on the volunteer page ("Shift cancelled", "No longer
 * needed", "Rota changed", "Other"). Those are what a coordinator picks between, and that file says
 * so: "the label is the coordinator's; the sentence the volunteer reads is the server's". "Other" is
 * a fine option on a form and tells the person it happened to nothing. These are the four clauses of
 * `RemoveVolunteerRequest.Reason.volunteerText()`, copied exactly, which the REMOVED_FROM_SHIFT
 * message prints after "Reason:" — so a volunteer who did get the message reads the same words here
 * as on their phone. If one is reworded there it must be reworded here.
 *
 * <p>A `Record` over the reason union, so a fifth reason added to the API type stops this compiling
 * rather than rendering "Reason: undefined".
 */
const TAKEN_OFF_BECAUSE: Record<MyReleasedShiftView["reason"], string> = {
  SHIFT_CANCELLED: "the shift was cancelled",
  NO_LONGER_NEEDED: "help is no longer needed for this shift",
  ROTA_CHANGED: "the rota changed",
  OTHER: "a change at the temple",
};

export default function MyShiftsPage() {
  return (
    // Volunteers alone (D-16, closing D-10's table). Not because the page is structurally empty
    // for a cook — it is, since every write behind it needs SIGN_UP_FOR_SHIFTS and that belongs to
    // VOLUNTEER alone — but because seva was never theirs to be offered. Somebody already working
    // a double shift should not be invited to sign up for another, and a tone-deaf screen reached
    // by typing the URL is still tone-deaf, so this is a guard and not merely a hidden menu row.
    // Staff lose nothing: /my-schedule is the working days they are rostered for, on
    // VIEW_OWN_SHIFTS. *My shifts* means seva; *My schedule* means your work.
    <RequireRole roles={["VOLUNTEER"]}>
      <MyShiftsView />
    </RequireRole>
  );
}

function MyShiftsView() {
  const { getToken } = useAuth();

  const shifts = useAuthedQuery(useCallback((t: string | undefined) => api.myShifts(t), []));
  const waitlist = useAuthedQuery(useCallback((t: string | undefined) => api.myWaitlist(t), []));
  // Third, and kept third: my-shifts.test.tsx hands its mocked queries out in call order.
  const released = useAuthedQuery(useCallback((t: string | undefined) => api.myReleasedShifts(t), []));

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
  const takenOff = released.data ?? [];

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/my-shifts" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6">
            <h1>My shifts</h1>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

          {shifts.loading ? (
            <Loading />
          ) : shifts.error ? (
            <ErrorNotice error={shifts.error} />
          ) : myShifts.length === 0 ? (
            <div className="card px-6 py-14 text-center">
              <p className="text-lg">No upcoming shifts</p>
              {/*
                One sentence, and no longer a ternary on the reader's role. The other branch —
                "…this list stays empty for you. Who is covering which shift is on the Volunteer
                shifts screen." — was written for the cook and manager this page used to admit, and
                it broke T-002's own rule that an empty state must never point somewhere its reader
                is refused: Rajeev opened it as a real cook on staging (2026-09-07), followed it,
                and got "Not your page". With the guard narrowed to VOLUNTEER that branch is
                unreachable, so it is deleted rather than corrected. What is left is safe by the
                same rule read forwards: the only role that can reach this page is the only role
                /shifts admits, so the reader of this sentence can always open what it names.
              */}
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                Browse available shifts to offer seva.
              </p>
            </div>
          ) : (
            <ul className="space-y-3">
              {myShifts.map((s) => (
                <li key={s.signupId} className="card flex flex-wrap items-center justify-between gap-3 px-5 py-4">
                  <div>
                    <p className="font-medium">
                      {s.title}
                      {s.source === "PROMOTION" && <span className="ml-2 rounded-sm bg-accent-bg px-2 py-0.5 text-xs text-accent-text font-semibold">From waitlist</span>}
                    </p>
                    <p className="text-sm text-ink-secondary tabular-nums">{dateWithYear(s.shiftDate)} · {shiftWindow(s.startTime, s.endTime)}</p>
                    {s.location && <p className="text-sm text-ink-muted">{s.location}</p>}
                  </div>
                  <button type="button" disabled={busy} onClick={() => run((t) => api.releaseShift(s.shiftId, t), "We couldn’t release your spot.")} className="min-h-touch rounded border border-hairline px-4 text-sm hover:bg-sunken disabled:opacity-60">
                    Release my spot
                  </button>
                </li>
              ))}
            </ul>
          )}

          {/*
            Taken off in the last week (T-149). Without it, a volunteer a coordinator removed — or
            whose shift was cancelled with them on it — simply vanished from the list above, and the
            notice telling them why is best-effort and can be lost (on staging the mail sandbox drops
            exactly these). Absent when there is nothing to say, rather than an empty state: this is
            news when it happens and noise when it has not. A failed load is shown, though, because
            a quiet blank here would look exactly like "nothing happened", which is the one wrong
            thing this section must not tell somebody.
          */}
          {released.error ? (
            <section className="mt-10">
              <h2 className="mb-3 text-lg">Taken off in the last week</h2>
              <ErrorNotice error={released.error} />
            </section>
          ) : takenOff.length > 0 && (
            <section className="mt-10">
              <h2 className="mb-3 text-lg">Taken off in the last week</h2>
              <ul className="space-y-3">
                {takenOff.map((r) => (
                  <li key={r.signupId} className="card px-5 py-4">
                    <p className="font-medium">{r.title}</p>
                    <p className="text-sm text-ink-secondary tabular-nums">{dateWithYear(r.shiftDate)} · {shiftWindow(r.startTime, r.endTime)}</p>
                    {r.location && <p className="text-sm text-ink-muted">{r.location}</p>}
                    <p className="mt-1 text-sm">Reason: {TAKEN_OFF_BECAUSE[r.reason]}.</p>
                  </li>
                ))}
              </ul>
            </section>
          )}

          {myWaitlist.length > 0 && (
            <section className="mt-10">
              <h2 className="mb-3 text-lg">On the waitlist</h2>
              <ul className="space-y-3">
                {myWaitlist.map((w) => (
                  <li key={w.shiftId} className="card flex flex-wrap items-center justify-between gap-3 px-5 py-4">
                    <div>
                      <p className="font-medium">{w.title} <span className="ml-2 text-sm text-ink-secondary">Position {w.position}</span></p>
                      <p className="text-sm text-ink-secondary tabular-nums">{dateWithYear(w.shiftDate)} · {shiftWindow(w.startTime, w.endTime)}</p>
                    </div>
                    <button type="button" disabled={busy} onClick={() => run((t) => api.leaveWaitlist(w.shiftId, t), "We couldn’t remove you from the waitlist.")} className="min-h-touch rounded border border-hairline px-4 text-sm hover:bg-sunken disabled:opacity-60">
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
