"use client";

import { useCallback } from "react";
import { Button } from "@/components/ds/Button";
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

/**
 * How many past shifts the server will return (T-429). Must stay equal to
 * `SignupService.PAST_SHIFTS_LIMIT`, which is where the reasoning for a row cap rather than a
 * months-back window is written down.
 *
 * Read here for one purpose only: to say so when the list has been cut. A volunteer of three years
 * shown her fifty most recent with no word about it has been told something untrue by omission.
 */
const HISTORY_CAP = 50;

/**
 * Whether she was recorded as having turned up — the volunteer's own copy of the mark a coordinator
 * makes on the roster (T-429).
 *
 * <p><strong>The same three words the coordinator sees</strong>, taken from the roster
 * (`app/volunteers/[id]/page.tsx`): "Came", "Did not come", "Not marked". One label per fact across
 * every view, so that a volunteer asking a coordinator about her record is reading the same page
 * they are.
 *
 * <p><strong>All three are neutral here, and the roster's amber is deliberately not carried
 * over.</strong> Colour on this project means something: amber warns, red says act now, green marks
 * the success of the reader's own action, and everything else is neutral. On the roster, amber on
 * "Did not come" is a real warning — it tells a coordinator a spot went uncovered and they may need
 * to act. On the page of the person it is about, it is none of those things. Nothing here asks her
 * to do anything, she cannot change the mark, and an amber flag beside her name on her own screen
 * reads as the app telling her off. The words carry the fact; the colour adds an accusation that
 * nobody decided to make.
 *
 * <p>`undefined` is not a case: `attended` is required-and-nullable on `MyPastShiftView`, so the
 * third branch is reached only by a real null — nobody has said — and never by a field that failed
 * to arrive.
 */
function attendanceLabel(attended: boolean | null): string {
  if (attended === true) return "Came";
  if (attended === false) return "Did not come";
  // Null, and never rendered as an absence. V107 made this column nullable precisely so that a
  // shift nobody got round to marking could not read as a shift she missed, and this screen is the
  // one place where that mistake would be read by the person it accuses.
  return "Not marked";
}

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
  // Fourth, and appended rather than inserted, for that same reason (T-429): a query added above an
  // existing one silently re-points every mocked list in that test at the wrong section.
  const history = useAuthedQuery(useCallback((t: string | undefined) => api.myPastShifts(t), []));

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
  const pastShifts = history.data ?? [];

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
            // The padding shrinks when there is history under it (T-429), and that is a layout
            // decision rather than a taste one. Measured at 1280 in the browser: at `py-14` this
            // card is 936×176 with 64px of text in it, so 112px of it is padding — which is right
            // when the card is the whole page and a volunteer has nothing at all, and is a heavy
            // empty block when it is only a header above nine rows of service she has served. Both
            // readings keep the same words, because both are true: she has nothing coming up, and
            // browsing is still what to do next.
            <div className={`card px-6 text-center ${pastShifts.length > 0 ? "py-8" : "py-14"}`}>
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
                      {s.source === "PROMOTION" && <span className="ml-2 rounded-control bg-accent-bg px-2 py-0.5 text-xs text-accent-text font-semibold">From waitlist</span>}
                    </p>
                    <p className="text-sm text-ink-secondary tabular-nums">{dateWithYear(s.shiftDate)} · {shiftWindow(s.startTime, s.endTime)}</p>
                    {s.location && <p className="text-sm text-ink-muted">{s.location}</p>}
                  </div>
                  <Button variant="secondary" disabled={busy} onClick={() => run((t) => api.releaseShift(s.shiftId, t), "We couldn’t release your spot.")}>
                    Release my spot
                  </Button>
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
                    <Button variant="secondary" disabled={busy} onClick={() => run((t) => api.leaveWaitlist(w.shiftId, t), "We couldn’t remove you from the waitlist.")}>
                      Leave waitlist
                    </Button>
                  </li>
                ))}
              </ul>
            </section>
          )}

          {/*
            What she has already done (T-429). Rajeev, 2026-09-20: "a volunteer cannot see anything
            she has already done" — the list above is only what is coming, so a devotee with six past
            services opened her own page to an empty box while the coordinator could see every one of
            them on the roster. Her attendance was recorded against her name the whole time and there
            was no screen that would show it to her.

            Last on the page, and that is the order of her questions: what am I committed to, what am
            I waiting for, what changed, what have I done. History is the only one of the four that
            never needs acting on.

            Absent when empty, like "Taken off" above, and that is what keeps acceptance 7 honest: a
            brand-new volunteer with nothing at all sees one empty box — "No upcoming shifts" — and
            not two stacked. A volunteer who has served but has nothing coming up sees that same box
            over her history, which is still exactly true and still points at the right next step.

            A failed load is shown rather than swallowed, for the reason the section above gives: a
            silent blank here is indistinguishable from "you have never served", which is the one
            wrong thing this section must not say to somebody.
          */}
          {history.error ? (
            <section className="mt-10">
              <h2 className="mb-3 text-lg">Past shifts</h2>
              <ErrorNotice error={history.error} />
            </section>
          ) : pastShifts.length > 0 && (
            <section className="mt-10">
              <h2 className="mb-3 text-lg">Past shifts</h2>
              {pastShifts.length >= HISTORY_CAP && (
                <p className="mb-3 text-sm text-ink-secondary">
                  Showing your {HISTORY_CAP} most recent shifts.
                </p>
              )}
              <ul className="space-y-3">
                {pastShifts.map((p) => (
                  // The same row as an upcoming shift — title, day, hours, place — because it is the
                  // same shift, read afterwards. The attendance mark takes the place the release
                  // button holds above it, so the right-hand edge of every row on this page lines up
                  // instead of leaving a column of empty space beside the history.
                  <li key={p.signupId} className="card flex flex-wrap items-center justify-between gap-3 px-5 py-4">
                    <div>
                      <p className="font-medium">
                        {p.title}
                        {p.source === "PROMOTION" && <span className="ml-2 rounded-control bg-accent-bg px-2 py-0.5 text-xs text-accent-text font-semibold">From waitlist</span>}
                      </p>
                      <p className="text-sm text-ink-secondary tabular-nums">{dateWithYear(p.shiftDate)} · {shiftWindow(p.startTime, p.endTime)}</p>
                      {p.location && <p className="text-sm text-ink-muted">{p.location}</p>}
                    </div>
                    {/* Neutral for all three answers; see `attendanceLabel` for why the roster's
                        amber does not come with them. */}
                    <span className="rounded-control bg-sunken px-2 py-0.5 text-xs font-semibold text-ink-secondary">
                      {attendanceLabel(p.attended)}
                    </span>
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
