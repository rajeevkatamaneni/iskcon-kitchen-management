"use client";

import Link from "next/link";
import { useCallback } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Loading } from "@/components/Loading";
import { Badge } from "@/components/ds/Badge";
import {
  api,
  type ScheduleDay,
  type ScheduleExceptionView,
  type ScheduleLeaveDay,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { dateWithYear, hhmm, longDay, todayIso } from "@/lib/format";

/**
 * The days the signed-in person is rostered to work (E6-S1, T-006).
 *
 * <p>The endpoint behind it — `GET /api/v1/staff/schedule/me`, behind `VIEW_OWN_SHIFTS` — has been
 * serving this since E6-S1 and had no caller at all: the roster was readable only by whoever could
 * open the manager's grid, so the people it is *about* were the one group who could not see it.
 * Only the screen was missing.
 *
 * <p><strong>Who this is for, and who it deliberately is not.</strong> A Temple Admin, a Kitchen
 * Manager and a cook all hold `VIEW_OWN_SHIFTS` and all have working days, so all three are admitted
 * — the hole was never only the cook's. A volunteer holds the same permission and is excluded, per
 * D-10: they have no staff profile for this endpoint to read, and their seva is a different thing on
 * a different screen. <em>My shifts</em> means seva; <em>My schedule</em> means your work. The role
 * set here and the one on the `/my-schedule` row in `nav.ts` are the same set, on purpose.
 *
 * <p><strong>Leave is resolved on the server, not here (T-032).</strong> The payload names the dates
 * approved leave covers, whether each is a half day, and the window it was resolved across — all of
 * it out of `ScheduleResolver`, which is the one place the order "approved leave, then the per-date
 * override, then the template" is written down. This screen maps no spans onto dates and keeps no
 * copy of the leave vocabulary. If it did, the grid a manager reads and the list a cook reads would
 * be two answers about one Thursday, and they would disagree the first time that order moved.
 *
 * <p>A missing window means *not resolved*, never *no leave*, so it is said out loud rather than
 * drawn as a clear fortnight.
 *
 * <p><strong>Nothing from the employment record is drawn.</strong> The payload is the whole
 * `StaffProfileDetailView` — the same shape the manager's template page reads — which carries date of
 * birth, address and the last four of a PAN. It is the reader's own record and the server is right to
 * send it, but this screen answers one question and renders only the two fields that answer it: the
 * weekly template and the per-date overrides.
 *
 * <p><strong>Every date is the temple's own day.</strong> The horizon starts at {@link todayIso},
 * not at the device's date, for the reason that helper gives: the kitchen's day is the operational
 * day, and a cook opening this from a train in another zone must not be shown a different Thursday
 * from the one their manager rostered. This is `/staff-schedule`'s convention, not a second one.
 */

/** Mon-first, matching the template's own 1–7 and the week grid's column order. */
const DAY_LABELS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

/**
 * How far ahead "next" reaches.
 *
 * <p>Two weeks, because the question a rostered person asks this screen is <em>when am I next in,
 * and has anything moved?</em> — and both answers live within a fortnight. A month would turn a list
 * that is read at a glance into one that is scrolled, and the pattern underneath it is on the same
 * screen for anyone asking about a Thursday further out than that.
 */
const HORIZON_DAYS = 14;

/**
 * What the server says when the signed-in person has no staff profile at all.
 *
 * <p>`/schedule/me` answers `RESOURCE_NOT_FOUND` in that case, and it is not an error the reader
 * caused: a Temple Admin who is not themselves on the payroll is the ordinary example. So it is
 * caught here and answered with a sentence, rather than shown as a failure with a code to quote.
 * Matched on the code and never on the 404, per the note on `ApiError.status` — a screen branching on
 * a status number is a screen drifting away from the error contract.
 */
const NO_STAFF_RECORD = "KMS-400030";

export default function MySchedulePage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <MyScheduleView />
    </RequireRole>
  );
}

/** One day of the horizon, after the template and the overrides have been reconciled. */
interface RosteredDay {
  date: string;
  working: boolean;
  startTime: string | null;
  endTime: string | null;
  /** An override decided this day rather than the pattern, so it is shown as changed. */
  fromException: boolean;
  note: string | null;
  /** Approved leave the server resolved onto this date, or null. Never worked out here. */
  leave: ScheduleLeaveDay | null;
}

function MyScheduleView() {
  const { appUser } = useAuth();
  const { data, error, loading } = useAuthedQuery(
    useCallback((t: string | undefined) => api.myStaffSchedule(t), [])
  );

  const today = todayIso();
  const template = data?.template ?? [];
  const leaveFrom = data?.leaveFrom ?? null;
  const leaveTo = data?.leaveTo ?? null;
  const days = rosteredDays(
    template,
    data?.exceptions ?? [],
    leaveByDate(data?.leaveDays ?? [], leaveFrom, leaveTo),
    today,
    HORIZON_DAYS
  );
  const caveat = leaveCaveat(days, leaveFrom, leaveTo);

  const noRecord = error?.code === NO_STAFF_RECORD;

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/my-schedule" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6">
            <h1>My schedule</h1>
            <p className="mt-1 text-ink-secondary">The days you’re rostered to work.</p>
          </header>

          {loading ? (
            <Loading label="Loading your schedule…" />
          ) : noRecord ? (
            <div className="card px-6 py-14 text-center">
              <p className="text-lg">You’re not on the staff roster</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                <NextStep role={appUser?.role} />
              </p>
            </div>
          ) : error ? (
            <ErrorNotice error={error} />
          ) : (
            <>
              <section className="card px-6 py-5" aria-labelledby="next-heading">
                <h2 id="next-heading" className="text-lg">
                  Next {HORIZON_DAYS} days
                </h2>

                {days.length === 0 ? (
                  // No next step here on purpose: it is one sentence away, under the pattern this
                  // list is drawn from, and the same sentence twice on one screen reads as a screen
                  // that does not know what it already said.
                  <p className="mt-2 max-w-prose text-ink-secondary">
                    You have no days in the next {HORIZON_DAYS}.
                  </p>
                ) : (
                  <ul className="mt-3 grid gap-2">
                    {days.map((d) => {
                      // Full-day leave replaces the hours; a half day does not, because they are in
                      // for part of it and the hours shown are still the hours they work. Which of
                      // those it is was decided on the server — this only draws it.
                      const offAllDay = d.leave !== null && !d.leave.halfDayLeave;
                      return (
                        <li
                          key={d.date}
                          className="flex flex-wrap items-center gap-3 border-t border-hairline pt-2 first:border-0 first:pt-0"
                        >
                          <span className="w-56 text-ink">{longDay(d.date)}</span>
                          <span className="text-sm tabular-nums text-ink-secondary">
                            {offAllDay
                              ? d.leave?.leaveLabel
                              : d.working
                                ? `${hhmm(d.startTime)}–${hhmm(d.endTime)}`
                                : "Off"}
                          </span>
                          {d.date === today && <span className="text-sm text-ink-muted">Today</span>}
                          {/* Worded as the week grid words it — "Time off, half day" — so the cook
                              and the manager who approved it are reading the same sentence. */}
                          {d.leave?.halfDayLeave && (
                            <Badge tone="neutral">{d.leave.leaveLabel}, half day</Badge>
                          )}
                          {/* Amber for an override, the same tone the week grid marks a changed day
                              with — the person reading this and the manager who changed it should not
                              be looking at two different colours for one Thursday. Both this and the
                              note describe the hours, so both go when the hours do. */}
                          {!offAllDay && d.fromException && <Badge tone="warning">Changed</Badge>}
                          {!offAllDay && d.note && (
                            <span className="text-sm text-ink-muted">{d.note}</span>
                          )}
                        </li>
                      );
                    })}
                  </ul>
                )}

                {/*
                  Only when the server has not answered about the whole list. Since T-032 it resolves
                  four weeks and this lists two, so in the running application this says nothing —
                  which is the point. It is what stops an unanswered payload being drawn as a clear
                  fortnight, and it says so rather than leaving the reader to find out on the day.
                */}
                {caveat && <p className="mt-4 text-sm text-ink-muted">{caveat}</p>}
              </section>

              <section className="card mt-6 px-6 py-5" aria-labelledby="usual-heading">
                <h2 id="usual-heading" className="text-lg">
                  Your usual week
                </h2>
                <p className="mt-1 max-w-prose text-sm text-ink-secondary">
                  <NextStep role={appUser?.role} />
                </p>
                <ul className="mt-3 grid gap-2">
                  {[1, 2, 3, 4, 5, 6, 7].map((dow) => {
                    const d = template.find((t) => t.dayOfWeek === dow);
                    return (
                      <li key={dow} className="flex items-center gap-3">
                        <span className="w-12 text-sm text-ink-secondary">{DAY_LABELS[dow - 1]}</span>
                        <span className="tabular-nums">
                          {d?.working ? `${hhmm(d.startTime)}–${hhmm(d.endTime)}` : "Off"}
                        </span>
                      </li>
                    );
                  })}
                </ul>
              </section>
            </>
          )}
        </div>
      </main>
    </div>
  );
}

/**
 * Who changes this, written for whoever is reading it.
 *
 * <p>The rule an empty state has to keep is that it never points somewhere the reader will be
 * refused — the defect this batch is fixing in four other places. `/staff-schedule` admits a Temple
 * Admin and a Kitchen Manager and nobody else, so a cook is sent to a person rather than to a page,
 * and the two people who can actually open the roster are given the link.
 */
function NextStep({ role }: { role: string | undefined }) {
  if (role === "TEMPLE_ADMIN" || role === "KITCHEN_MANAGER") {
    return (
      <>
        The roster is set on the{" "}
        <Link href="/staff-schedule" className="text-accent-text hover:underline">
          staff schedule
        </Link>
        .
      </>
    );
  }
  return <>Your kitchen manager sets who works when.</>;
}

/**
 * The pattern, the overrides and the resolved leave, gathered into the days ahead worth listing.
 *
 * <p>One rule, and it is the model E6-S1 describes: a per-date exception decides that date, and the
 * weekly template decides every other. Nothing else is inferred.
 *
 * <p>Days the person is not in are left out, with one deliberate exception — a day an override turned
 * *off*. That is the outbound half of a swap (E6-S11 left the table no other way to say "not
 * working"), and "you are no longer in that Tuesday" is exactly the kind of change this screen exists
 * to carry. Dropping it would make the swap visible from one end only.
 *
 * <p><strong>Leave is attached, never worked out.</strong> `leave` arrives already mapped onto dates
 * by `ScheduleResolver`, so this looks each date up and nothing more: no spans, no comparisons, no
 * second opinion about which half of a week a Thursday falls in. A date the server did not answer
 * about simply has no entry, which is why {@link leaveCaveat} exists to say so.
 *
 * <p>Leave changes how a listed day reads and never which days are listed. A day off by the pattern
 * stays out even when leave covers it: the reader was not in that day either way, and "Time off" on
 * a Sunday they never work is noise standing where an answer should be.
 */
function rosteredDays(
  template: ScheduleDay[],
  exceptions: ScheduleExceptionView[],
  leave: Map<string, ScheduleLeaveDay>,
  from: string,
  days: number
): RosteredDay[] {
  const byDayOfWeek = new Map(template.map((d) => [d.dayOfWeek, d]));
  const byDate = new Map(exceptions.map((e) => [e.exceptionDate, e]));

  const out: RosteredDay[] = [];
  for (let i = 0; i < days; i += 1) {
    const date = addDays(from, i);
    const override = byDate.get(date);
    const onLeave = leave.get(date) ?? null;

    if (override) {
      out.push({
        date,
        working: override.working,
        startTime: override.startTime,
        endTime: override.endTime,
        fromException: true,
        note: override.note,
        leave: onLeave,
      });
      continue;
    }

    const pattern = byDayOfWeek.get(isoDayOfWeek(date));
    if (pattern?.working) {
      out.push({
        date,
        working: true,
        startTime: pattern.startTime,
        endTime: pattern.endTime,
        fromException: false,
        note: null,
        leave: onLeave,
      });
    }
  }
  return out;
}

/**
 * Approved leave keyed by date, and only inside the window it was resolved across.
 *
 * <p>The server sends nothing outside that window, so the filter never removes anything today. It is
 * written down because the window is the whole safeguard: leave is drawn where it was answered for
 * and nowhere else, so a payload resolved for one week can never leave a mark on the second.
 */
function leaveByDate(
  leaveDays: ScheduleLeaveDay[],
  from: string | null,
  to: string | null
): Map<string, ScheduleLeaveDay> {
  if (!from || !to) return new Map();
  return new Map(
    leaveDays.filter((l) => l.date >= from && l.date <= to).map((l) => [l.date, l])
  );
}

/**
 * What to say when the server has not told us about leave across every day on the list.
 *
 * <p>`leaveDays` absent or null means the payload was never resolved for leave — the shape the
 * manager's `/staff/profiles/{id}` sends — and it must never be read as *no leave*. Silence about a
 * fortnight and a clear fortnight look identical on screen, and the difference is the one day the
 * reader cares about, so it is said instead of assumed.
 *
 * <p>`/schedule/me` resolves four weeks against a list of two, so this returns null in the running
 * application and the sentence never appears. It is here for the case where that stops being true.
 */
function leaveCaveat(days: RosteredDay[], from: string | null, to: string | null): string | null {
  if (days.length === 0) return null;
  if (!from || !to) return "Approved leave isn’t shown here.";
  // ISO dates compare correctly as strings, which is the reason the API speaks them.
  const answered = days.every((d) => d.date >= from && d.date <= to);
  return answered ? null : `Approved leave is only shown up to ${dateWithYear(to)}.`;
}

/** 1=Mon … 7=Sun, which is what the template is keyed by. `getDay()` is 0=Sun. */
function isoDayOfWeek(iso: string): number {
  const day = new Date(`${iso}T00:00:00`).getDay();
  return day === 0 ? 7 : day;
}

/**
 * Calendar days from an ISO date. Written the way `/staff-schedule` writes it — parsed at local
 * midnight and stepped by date rather than by milliseconds, so nothing here can be shifted by a zone
 * or by a daylight-saving hour.
 */
function addDays(iso: string, days: number): string {
  const d = new Date(`${iso}T00:00:00`);
  d.setDate(d.getDate() + days);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}
