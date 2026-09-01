"use client";

import Link from "next/link";
import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type DayCoverage, type LeaveType, type ResolvedDay, type StaffWeek } from "@/lib/api";
import { longDay, shortDate, todayIso } from "@/lib/format";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";
import { Badge } from "@/components/ds/Badge";
import { Button } from "@/components/ds/Button";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { TABLE, THEAD, TR, TH_TEXT, TH_GRID, TD_TEXT, TD_GRID, WRAP } from "@/components/ds/table";

/**
 * The week grid (E6-S1, reworked by the 2026-08-20 brief §6): who works when, and edited here.
 *
 * <p>It used to carry its own "Add staff" form, which created a bare profile for a kitchen-staff
 * user. Since E6-S8 an employment record is how somebody comes to work here at all, so this screen
 * shows the people the register already holds. The link to the register itself has gone (A11): a
 * kitchen manager runs this grid and is deliberately not shown the screen salary and PAN live on.
 *
 * <p>Per-date changes are made by clicking a cell, not on the staff member's template page. That
 * page answers "what is this person's pattern?", and a swapped Thursday is not a pattern.
 *
 * <p><strong>It shows what was needed, not only who is in (E6-S15).</strong> The grid used to be
 * supply on its own — seven columns of hours under a foot that read <em>In that day · 4 staff, 2
 * volunteers</em> — while the figure it should have been measured against sat on the planner, where
 * a staffing gap was visible only to somebody who happened to open the right meal. Both halves now
 * sit on the same screen: every column says how many people short it is, and the days short in the
 * next thirty are listed under the grid so a gap three weeks out is seen without paging to it.
 *
 * <p><strong>Marking somebody off records approved leave.</strong> It is not a mark this grid keeps
 * for itself, because there must be exactly one answer to "why is this person not in on Thursday".
 * Leave already granted shows read-only, and the grid refuses to schedule over it — the manager
 * revokes it first if the person is in after all.
 */

const DAY_LABELS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

/**
 * How far ahead the shortfall list looks.
 *
 * <p>Thirty days and not a month grid. The question this screen answers is <em>where am I short?</em>,
 * and thirty columns of colour answers <em>what does the month look like?</em> instead — a wall in
 * which the three days that need a telephone call are no easier to find than they are today.
 */
const HORIZON_DAYS = 30;

const LEAVE_TYPES: { value: LeaveType; label: string }[] = [
  { value: "TIME_OFF", label: "Time off" },
  { value: "SICK", label: "Sick leave" },
  { value: "UNPAID", label: "Unpaid leave" },
];

export default function StaffSchedulePage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER"]}>
      <StaffScheduleView />
    </RequireRole>
  );
}

/** The cell the manager has opened, held as ids rather than as the object it came from. */
interface Selection {
  staffProfileId: string;
  fullName: string;
  date: string;
}

function StaffScheduleView() {
  const [weekStart, setWeekStart] = useState(mondayOfThisWeek());
  const { getToken } = useAuth();

  const week = useAuthedQuery(useCallback((t: string | undefined) => api.staffWeek(weekStart, t), [weekStart]));

  // The week on screen, and the thirty days ahead of today. Two ranges of one endpoint rather than
  // two endpoints: they are the same question, and the second is not a slice of the first — the
  // manager pages back into August while the gap they need to see is in September.
  const weekEnd = shiftWeek(weekStart, 6);
  const coverage = useAuthedQuery(
    useCallback((t: string | undefined) => api.crewCoverage(weekStart, weekEnd, t), [weekStart, weekEnd])
  );
  // Fixed at first render. Read afresh each render, it would build a new callback every time and the
  // query would refetch forever.
  const [horizonStart] = useState(todayIso);
  const horizonEnd = addDays(horizonStart, HORIZON_DAYS - 1);
  const ahead = useAuthedQuery(
    useCallback((t: string | undefined) => api.crewCoverage(horizonStart, horizonEnd, t), [horizonStart, horizonEnd])
  );

  const [selected, setSelected] = useState<Selection | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const rows = week.data?.staff ?? [];
  const counts = week.data?.counts ?? [];
  const columnDates = DAY_LABELS.map((_, i) => shiftWeek(weekStart, i));
  const coverageByDate = new Map((coverage.data ?? []).map((d) => [d.date, d]));

  const openDay: ResolvedDay | null = selected
    ? rows.find((r) => r.staffProfileId === selected.staffProfileId)?.days.find((d) => d.date === selected.date) ?? null
    : null;

  async function run(mutation: (t: string | undefined) => Promise<unknown>, ok: string, failure: string) {
    setBusy(true);
    setActionError(null);
    setNotice(null);
    try {
      await mutation(await getToken());
      week.reload();
      // A cook marked off changes what the day has, not what it needs — so the shortfall moves too,
      // and a footer left on the old figure would quietly contradict the row above it.
      coverage.reload();
      ahead.reload();
      setNotice(ok);
      setSelected(null);
    } catch (e) {
      setActionError(toApiError(e, failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/staff-schedule" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6">
            <h1>Staff schedule</h1>
            <p className="mt-1 text-ink-secondary">
              Click a day to change it.
            </p>
          </header>

          <div className="mb-4 flex items-center gap-3">
            <button type="button" onClick={() => setWeekStart(shiftWeek(weekStart, -7))} className="min-h-touch rounded border border-hairline px-3 hover:bg-sunken">← Prev</button>
            <span className="text-sm text-ink-secondary tabular-nums">Week of {weekStart}</span>
            <button type="button" onClick={() => setWeekStart(shiftWeek(weekStart, 7))} className="min-h-touch rounded border border-hairline px-3 hover:bg-sunken">Next →</button>
          </div>

          {actionError && <div className="mb-4"><ErrorNotice error={actionError} /></div>}
          {notice && <div className="mb-4"><InlineNotice tone="success" autoDismiss>{notice}</InlineNotice></div>}

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
            <>
              <div className="overflow-x-auto rounded-lg bg-raised">
                <table className={`${TABLE} text-sm`}>
                  <thead className={THEAD}>
                    <tr>
                      <th className={`${TH_TEXT} ${WRAP}`}>Staff</th>
                      {DAY_LABELS.map((d, i) => (
                        <th key={d} className={TH_GRID}>
                          {d}
                          <span className="block text-xs font-normal tabular-nums">{shortDate(columnDates[i])}</span>
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((r) => (
                      <tr key={r.staffProfileId} className={TR}>
                        <td className={`${TD_TEXT} ${WRAP}`}>
                          <Link href={`/staff-schedule/${r.staffProfileId}`} className="font-medium text-accent-text hover:underline">{r.fullName}</Link>
                          {r.jobTitleLabel && <div className="text-xs text-ink-muted">{r.jobTitleLabel}</div>}
                        </td>
                        {r.days.map((d) => (
                          <td key={d.date} className={TD_GRID}>
                            <DayCell
                              day={d}
                              person={r}
                              open={selected?.staffProfileId === r.staffProfileId && selected?.date === d.date}
                              onOpen={() => {
                                setActionError(null);
                                setNotice(null);
                                setSelected({ staffProfileId: r.staffProfileId, fullName: r.fullName, date: d.date });
                              }}
                            />
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                  {/*
                    Need above supply, in that order, because that is the order the question is asked
                    in: how many short, and then who is in. Both rows are computed on the server — the
                    head count by the same code that resolves the rows above, the shortfall by the same
                    code the planner warns with — so this grid, the Today tile and the meal composer
                    can never disagree about the same Thursday.
                  */}
                  <tfoot className="border-t border-hairline bg-sunken text-ink-secondary">
                    <tr>
                      <th scope="row" className={`${TD_TEXT} font-medium`}>Short of hands</th>
                      {columnDates.map((date) => (
                        <td key={date} className={TD_GRID}>
                          <CoverageCell day={coverageByDate.get(date) ?? null} loading={coverage.loading} />
                        </td>
                      ))}
                    </tr>
                    <tr>
                      <th scope="row" className={`${TD_TEXT} font-medium`}>In that day</th>
                      {DAY_LABELS.map((label, i) => {
                        const count = counts[i];
                        return (
                          <td key={label} className={TD_GRID}>
                            <span className="text-ink">{count ? count.staffIn : 0} staff</span>
                            <span className="block text-xs">{count ? count.volunteers : 0} volunteers</span>
                          </td>
                        );
                      })}
                    </tr>
                  </tfoot>
                </table>
              </div>

              <ShortAhead days={ahead.data ?? []} loading={ahead.loading} />

              {selected && openDay && (
                <DayEditor
                  selection={selected}
                  day={openDay}
                  busy={busy}
                  onClose={() => setSelected(null)}
                  onChangeHours={(startTime, endTime) =>
                    run(
                      (t) => api.setStaffException(selected.staffProfileId, {
                        exceptionDate: selected.date, working: true, startTime, endTime, note: null,
                      }, t),
                      "That day was changed. The staff member was told.",
                      "We couldn’t change that day."
                    )
                  }
                  onMarkOff={(leaveType, reason) =>
                    run(
                      (t) => api.recordLeave({
                        staffProfileId: selected.staffProfileId,
                        leaveType,
                        fromDate: selected.date,
                        toDate: selected.date,
                        halfDay: false,
                        reason,
                      }, t),
                      "Recorded as approved leave.",
                      "We couldn’t record that leave."
                    )
                  }
                  onSwap={(toDate) =>
                    run(
                      (t) => api.swapStaffShift(selected.staffProfileId, { fromDate: selected.date, toDate }, t),
                      "Both days were changed together.",
                      "We couldn’t swap those days."
                    )
                  }
                  onUndo={() =>
                    openDay.exceptionId
                      ? run(
                          (t) => api.deleteStaffException(selected.staffProfileId, openDay.exceptionId as string, t),
                          openDay.swapLinkId ? "The swap was undone — both days are back." : "That day is back to the usual pattern.",
                          "We couldn’t undo that change."
                        )
                      : Promise.resolve()
                  }
                  onRevokeLeave={() =>
                    openDay.leaveId
                      ? run(
                          (t) => api.decideLeave(openDay.leaveId as string, "revoke", null, t),
                          "The leave was revoked. The staff member was told.",
                          "We couldn’t revoke that leave."
                        )
                      : Promise.resolve()
                  }
                />
              )}
            </>
          )}
        </div>
      </main>
    </div>
  );
}

/**
 * What one column of the grid has to say about its own staffing (E6-S15).
 *
 * <p><strong>Coloured by shortfall, never by head count.</strong> A heatmap of how many people are in
 * would be decoration: a busy day with eight cooks and a quiet one with eight cooks would look
 * identical, and the design system's rule is that status colour is never decorative. Short of hands
 * is a status. Being well attended is not.
 *
 * <p><strong>Colour never carries it alone.</strong> Every cell says its shortfall in words, and
 * names the meal that is short — the colour only makes it findable across seven columns.
 *
 * <p>Two tones and no invented threshold between them. Any shortfall is a warning, which is what the
 * design system already reserves that colour for. Danger is kept for the one categorically different
 * case: a meal that named a number and has <em>nobody at all</em>. That is not a deeper shade of
 * short-staffed, it is a meal with no kitchen, and it is a fact rather than a cut-off somebody chose.
 */
function CoverageCell({ day, loading }: { day: DayCoverage | null; loading: boolean }) {
  if (loading && !day) {
    return <span className="block px-2 py-2 text-xs text-ink-muted">Checking…</span>;
  }
  const read = coverageReading(day);
  return (
    <div className={`rounded px-2 py-2 tabular-nums ${read.className}`}>
      <span className="block text-sm font-medium">{read.headline}</span>
      {read.detail && <span className="block text-xs">{read.detail}</span>}
    </div>
  );
}

/**
 * The days short of hands between today and thirty days out, and nothing else.
 *
 * <p>This is the list a manager acts on: a short day three weeks away needs a telephone call now,
 * and it was previously visible only to somebody who paged the grid forward to the right week and
 * then opened the right meal. Deliberately not a month grid — thirty columns of colour answers
 * "what does the month look like", and the question being asked is "where am I short".
 */
function ShortAhead({ days, loading }: { days: DayCoverage[]; loading: boolean }) {
  const short = days.filter((d) => d.state === "SHORT");
  const unplanned = days.filter((d) => d.state === "CREW_NOT_SET").length;

  return (
    <section className="mt-6 rounded-lg bg-raised px-6 py-5" aria-labelledby="short-ahead">
      <h2 id="short-ahead" className="text-lg">
        Short of hands in the next {HORIZON_DAYS} days
      </h2>

      {loading ? (
        <p className="mt-2 text-ink-secondary">Checking the next {HORIZON_DAYS} days…</p>
      ) : short.length === 0 ? (
        <p className="mt-2 text-ink-secondary">
          No day in the next {HORIZON_DAYS} is short of the crew its meals ask for.
        </p>
      ) : (
        <ul className="mt-3 grid gap-2">
          {short.map((d) => (
            <li key={d.date} className="flex flex-wrap items-center gap-3 border-t border-hairline pt-2 first:border-0 first:pt-0">
              <Badge tone={d.shortAtRostered === 0 ? "danger" : "warning"}>
                {d.shortBy} short
              </Badge>
              <span className="text-ink">{longDay(d.date)}</span>
              <span className="text-sm text-ink-secondary tabular-nums">
                {d.shortAt} — {d.shortAtRostered} of {d.shortAtRequired}
              </span>
            </li>
          ))}
        </ul>
      )}

      {/*
        Said out loud rather than left as a quiet green. A day nobody has crewed is not a day that
        needs nobody, and a list that stayed silent about them would read as an all-clear it has not
        earned.
      */}
      {!loading && unplanned > 0 && (
        <p className="mt-3 text-sm text-ink-muted">
          {unplanned === 1
            ? "One other day has meals planned with no crew figure set, so there is nothing to measure its roster against."
            : `${unplanned} other days have meals planned with no crew figure set, so there is nothing to measure their rosters against.`}
        </p>
      )}
    </section>
  );
}

/** The one place the four states become words and a tone, so no two readers of them can disagree. */
function coverageReading(day: DayCoverage | null): {
  headline: string;
  detail: string | null;
  className: string;
} {
  if (!day) {
    return { headline: "—", detail: null, className: "text-ink-muted" };
  }
  switch (day.state) {
    case "SHORT":
      return {
        headline: `${day.shortBy} short`,
        detail: `${day.shortAt} — ${day.shortAtRostered} of ${day.shortAtRequired}`,
        className: day.shortAtRostered === 0 ? "bg-danger-bg text-danger" : "bg-warning-bg text-warning",
      };
    case "COVERED":
      return { headline: "Covered", detail: null, className: "bg-success-bg text-success" };
    case "CREW_NOT_SET":
      // Not covered, and never drawn as though it were. Nobody has said what these meals take.
      return { headline: "Crew not set", detail: null, className: "text-ink-muted" };
    case "NOTHING_PLANNED":
    default:
      return { headline: "No meals", detail: null, className: "text-ink-muted" };
  }
}

/**
 * One day of one person. An adjusted day looks adjusted, and now says which kind of adjustment it
 * is: amber for an override the manager made, muted for leave, which is not the roster's doing.
 */
function DayCell({
  day,
  person,
  open,
  onOpen,
}: {
  day: ResolvedDay;
  person: StaffWeek;
  open: boolean;
  onOpen: () => void;
}) {
  const hours = `${(day.startTime ?? "").slice(0, 5)}–${(day.endTime ?? "").slice(0, 5)}`;
  const tone = day.leaveId ? "text-ink-muted" : day.fromException ? "text-warning" : "";

  return (
    <button
      type="button"
      onClick={onOpen}
      aria-label={`${person.fullName}, ${day.date}`}
      aria-expanded={open}
      className={`min-h-touch w-full rounded px-2 py-2 text-left tabular-nums transition-colors duration-state hover:bg-sunken ${open ? "bg-sunken ring-1 ring-accent-border" : ""} ${tone}`}
    >
      {day.leaveId && !day.halfDayLeave ? (
        <span>{day.leaveLabel}</span>
      ) : day.working ? (
        <span>{hours}</span>
      ) : (
        <span>Off</span>
      )}
      {day.leaveId && day.halfDayLeave && <span className="block text-xs">{day.leaveLabel}, half day</span>}
      {!day.leaveId && day.fromException && (
        <span className="block text-xs">{day.swapLinkId ? "Swapped" : "Changed"}</span>
      )}
    </button>
  );
}

/**
 * The four things a cell can do, and the one it refuses to.
 *
 * <p>Leave is shown, not edited: the manager revokes it and then schedules, which leaves a named
 * decision behind instead of a cell quietly overwritten.
 */
function DayEditor({
  selection,
  day,
  busy,
  onClose,
  onChangeHours,
  onMarkOff,
  onSwap,
  onUndo,
  onRevokeLeave,
}: {
  selection: Selection;
  day: ResolvedDay;
  busy: boolean;
  onClose: () => void;
  onChangeHours: (startTime: string, endTime: string) => void;
  onMarkOff: (leaveType: LeaveType, reason: string | null) => void;
  onSwap: (toDate: string) => void;
  onUndo: () => void;
  onRevokeLeave: () => void;
}) {
  return (
    <section className="mt-6 rounded-lg bg-raised px-6 py-5" aria-label={`Edit ${selection.fullName} on ${selection.date}`}>
      <header className="mb-4 flex items-baseline justify-between gap-4">
        <h2 className="text-lg">
          {selection.fullName} · {selection.date}
        </h2>
        <button type="button" onClick={onClose} className="text-sm text-ink-secondary hover:underline">Close</button>
      </header>

      {day.leaveId ? (
        <InlineNotice tone="warning" title={`On ${day.leaveLabel?.toLowerCase()}${day.halfDayLeave ? " for half the day" : ""}`}>
          <p>
            Revoke the leave first if they are in after all. They will be told.
          </p>
          <div className="mt-3">
            <Button variant="secondary" size="sm" disabled={busy} onClick={onRevokeLeave}>
              Revoke this leave
            </Button>
          </div>
        </InlineNotice>
      ) : (
        <div className="grid gap-5">
          <form
            className="flex flex-wrap items-end gap-3"
            aria-label={day.working ? "Change the hours" : "Add them on"}
            onSubmit={(e) => {
              e.preventDefault();
              const f = new FormData(e.currentTarget);
              onChangeHours(String(f.get("startTime") ?? ""), String(f.get("endTime") ?? ""));
            }}
          >
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">From</span>
              <input type="time" name="startTime" required defaultValue={(day.startTime ?? "09:00").slice(0, 5)} className="min-h-touch rounded border border-hairline bg-canvas px-2" />
            </label>
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">To</span>
              <input type="time" name="endTime" required defaultValue={(day.endTime ?? "17:00").slice(0, 5)} className="min-h-touch rounded border border-hairline bg-canvas px-2" />
            </label>
            <Button type="submit" size="sm" disabled={busy}>
              {day.working ? "Change the hours" : "Add them on"}
            </Button>
          </form>

          <form
            className="flex flex-wrap items-end gap-3"
            aria-label="Mark them off"
            onSubmit={(e) => {
              e.preventDefault();
              const f = new FormData(e.currentTarget);
              onMarkOff(String(f.get("leaveType") ?? "TIME_OFF") as LeaveType, emptyToNull(String(f.get("reason") ?? "")));
            }}
          >
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">Mark off as</span>
              <select name="leaveType" className="min-h-touch rounded border border-hairline bg-canvas px-2">
                {LEAVE_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
              </select>
            </label>
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">Note</span>
              <input name="reason" className="min-h-touch rounded border border-hairline bg-canvas px-2" />
            </label>
            <Button type="submit" variant="secondary" size="sm" disabled={busy}>Mark them off</Button>
          </form>
          <p className="-mt-3 max-w-prose text-xs text-ink-muted">
            Marking someone off records approved leave.
          </p>

          <form
            className="flex flex-wrap items-end gap-3"
            aria-label="Swap this day"
            onSubmit={(e) => {
              e.preventDefault();
              const f = new FormData(e.currentTarget);
              onSwap(String(f.get("toDate") ?? ""));
            }}
          >
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">Work this day instead</span>
              <input type="date" name="toDate" required className="min-h-touch rounded border border-hairline bg-canvas px-2" />
            </label>
            <Button type="submit" variant="secondary" size="sm" disabled={busy}>Swap</Button>
          </form>

          {day.fromException && (
            <div>
              <Button variant="ghost" size="sm" disabled={busy} onClick={onUndo}>
                {day.swapLinkId ? "Undo the swap (both days)" : "Back to the usual pattern"}
              </Button>
            </div>
          )}
        </div>
      )}
    </section>
  );
}

function emptyToNull(s: string): string | null {
  const t = s.trim();
  return t === "" ? null : t;
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

/** Calendar days from an ISO date, in the temple's own day rather than the device's. */
function addDays(iso: string, days: number): string {
  const d = new Date(`${iso}T00:00:00`);
  d.setDate(d.getDate() + days);
  return localIso(d);
}

function shiftWeek(weekStart: string, days: number): string {
  const d = new Date(weekStart + "T00:00:00");
  d.setDate(d.getDate() + days);
  return localIso(d);
}
