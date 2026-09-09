"use client";

import Link from "next/link";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { useParams } from "next/navigation";
import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type RosterSignup } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";
import { Button } from "@/components/ds/Button";
import { TABLE, THEAD, TR, TH_TEXT, TH_ACTIONS, TD_TEXT, TD_ACTIONS, ACTIONS_ROW, WRAP } from "@/components/ds/table";
import { dateWithYear, hhmm, moment, templeDay, templeZone, todayIso } from "@/lib/format";

/**
 * One shift's roster, coordinator side (E6-S4+, and B7).
 *
 * <p>B7 added the two things a coordinator could not do here at all: say who turned up, and take
 * somebody off the roster. Both are this screen's, not the volunteer's — the volunteer's own
 * release is on My Shifts and acts on their own spot and nobody else's.
 *
 * <p>T-079 added the third: changing a mark. The roster is still marked once, in one press, but a
 * wrong tick — or a name the marking left out, which used to be unmarkable for ever after — is a
 * button on the row it belongs to.
 */
export default function ShiftRosterPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <ShiftRosterView />
    </RequireRole>
  );
}

function ShiftRosterView() {
  const id = useParams<{ id: string }>().id;
  const { getToken, appUser } = useAuth();
  const { data, error, loading, reload } = useAuthedQuery(
    useCallback((t: string | undefined) => api.shiftRoster(id, t), [id])
  );

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [showBroadcast, setShowBroadcast] = useState(false);

  async function broadcast(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const f = new FormData(form);
    setBusy(true);
    setActionError(null);
    setNotice(null);
    try {
      const res = await api.broadcastShift(
        id,
        { message: String(f.get("message") ?? "").trim(), includeWaitlist: f.get("includeWaitlist") === "on" },
        await getToken()
      );
      form.reset();
      setShowBroadcast(false);
      setNotice(`Update sent to ${res.recipients} volunteer(s).`);
      reload();
    } catch (e) {
      setActionError(toApiError(e, "We couldn’t send that update."));
    } finally {
      setBusy(false);
    }
  }

  async function recordAttendance(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const came = new Set(new FormData(event.currentTarget).getAll("attended").map(String));
    setBusy(true);
    setActionError(null);
    setNotice(null);
    try {
      // Everyone actively on the roster is marked, present or absent. Leaving the unticked out of
      // the payload would leave them unmarked, and unmarked means "nobody has said" — which is
      // exactly what this act is saying is no longer true of them.
      await api.recordShiftAttendance(
        id,
        { marks: activeSignups.map((s) => ({ userId: s.userId, attended: came.has(s.userId) })) },
        await getToken()
      );
      setNotice("Attendance recorded.");
      reload();
    } catch (e) {
      setActionError(toApiError(e, "We couldn’t record that attendance."));
    } finally {
      setBusy(false);
    }
  }

  /**
   * Changes one person's mark after the shift has been marked (T-079).
   *
   * <p>One press, one person, no form: the button a coordinator presses names the answer it will
   * set, so there is nothing to fill in and nothing to submit. That is deliberate on a screen whose
   * other attendance control is a whole roster committed at once — the two acts should not look
   * alike, because the blanket one is still once per shift and this one is not.
   */
  async function correctAttendance(userId: string, fullName: string, attended: boolean) {
    setBusy(true);
    setActionError(null);
    setNotice(null);
    try {
      await api.correctShiftAttendance(id, userId, attended, await getToken());
      setNotice(`${fullName} is now marked as ${attended ? "having come" : "not having come"}.`);
      reload();
    } catch (e) {
      setActionError(toApiError(e, "We couldn’t change that attendance mark."));
    } finally {
      setBusy(false);
    }
  }

  async function removeVolunteer(userId: string, fullName: string) {
    setBusy(true);
    setActionError(null);
    setNotice(null);
    try {
      await api.releaseVolunteerFromShift(id, userId, await getToken());
      setNotice(`${fullName} was taken off this shift.`);
      reload();
    } catch (e) {
      setActionError(toApiError(e, "We couldn’t take that volunteer off this shift."));
    } finally {
      setBusy(false);
    }
  }

  const roster = data;
  const shift = roster?.shift;
  const signups = roster?.signups ?? [];
  const activeSignups = signups.filter((s) => !s.releasedAt);
  const released = signups.filter((s) => s.releasedAt);
  // Attendance is marked once for the whole shift, so one signup carrying a time settles it for the
  // screen: the columns stop being checkboxes and start being the answer. A signup that arrived
  // after the marking stays null, and reads as "Not marked" rather than as an absence — the whole
  // reason `attended` is nullable.
  const attendanceRecordedAt =
    (roster?.signups ?? []).map((s) => s.attendanceRecordedAt).find((t) => t !== null) ?? null;
  // A shift that has not started yet cannot be marked, and the screen must say so before the
  // coordinator presses anything (T-085). Every tick starts ticked, marking is once per shift and
  // nothing in the product changes a mark afterwards, so opening tomorrow's roster and saving used
  // to record the whole crew as having come to a shift that had not happened — permanently. The
  // server refuses it now with KMS-400144; this is the same rule said before the click rather than
  // after it.
  //
  // Compared in the temple's own clock, not the reader's, for the reason `todayIso` gives: a
  // coordinator looking from further west would otherwise see the control appear a day early. Both
  // sides are "YYYY-MM-DD HH:MM" with fixed-width fields, so a string comparison is a chronological
  // one, and `startTime` is sliced because the API sends "08:00:00" where the formatter gives
  // "08:00". Read at render: a shift that starts while the page sits open needs a reload, which is
  // what the coordinator does anyway when they arrive to mark it.
  const templeNowHhmm = new Intl.DateTimeFormat("en-GB", {
    timeZone: templeZone(),
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).format(new Date());
  const shiftHasStarted = shift
    ? `${shift.shiftDate} ${(shift.startTime ?? "").slice(0, 5)}` <= `${todayIso()} ${templeNowHhmm}`
    : false;
  const canMarkAttendance =
    attendanceRecordedAt === null && shift?.status === "OPEN" && activeSignups.length > 0 && shiftHasStarted;
  // Once the shift has been marked, every row on it can be changed (T-079). Two cases arrive here
  // as one, on purpose: the wrong answer put right, and the FIRST answer for somebody the marking
  // left out — a partial `marks` list leaves the omitted unmarked, and any mark at all makes a
  // second blanket marking KMS-400139, so before this existed they could never be marked at all.
  //
  // Read off `attendanceRecordedAt` rather than off each row's own mark, so an unmarked row on a
  // marked shift is reachable; `shiftHasStarted` because the server refuses a future shift through
  // this door too, and a shift whose date was edited forwards after it was marked would otherwise
  // offer a press that can only fail.
  //
  // And who is asking (T-106). Correcting a mark is no longer the same permission as making one:
  // marking stays on `MANAGE_VOLUNTEER_SHIFTS`, which every cook holds, while changing a mark is
  // `CORRECT_RECORDED_ATTENDANCE` — the Temple Admin's and the Kitchen Manager's, because the person
  // running the shift knows who turned up and a cook should not be able to change a record about a
  // colleague they work alongside.
  //
  // The API is the boundary and enforces that permission on every request; this line only decides
  // whether a cook is shown a button that would refuse them, which is worse than not seeing it at
  // all. There is no permission list on the client to test against — `appUser` carries a role and
  // nothing finer — so this reads the two roles the grant was given to, exactly as MealServices does
  // for `CORRECT_RECORDED_MEAL`, and if that grant is ever widened this line has to widen with it.
  // Said out loud because a role test standing in for a permission test is the kind of duplication
  // that drifts silently.
  const mayCorrectAttendance =
    appUser?.role === "TEMPLE_ADMIN" || appUser?.role === "KITCHEN_MANAGER";
  // Split in two so the sentence under the table can tell a cook that a correction is possible and
  // who makes it, without offering them the press. "This shift can still be corrected" and "you may
  // correct it" are different facts and the screen needs both.
  const attendanceIsCorrectable =
    attendanceRecordedAt !== null && shift?.status === "OPEN" && shiftHasStarted;
  const canCorrectAttendance = mayCorrectAttendance && attendanceIsCorrectable;

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/volunteers" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <Link href="/volunteers" className="text-sm text-accent-text hover:underline">← All shifts</Link>

          {loading ? (
            <Loading label="Loading roster…" />
          ) : error ? (
            <div className="mt-6"><ErrorNotice error={error} /></div>
          ) : !shift ? null : (
            <>
              <header className="mb-6 mt-3 flex flex-wrap items-start justify-between gap-4">
                <div>
                  <h1>{shift.title}</h1>
                  <p className="mt-1 text-ink-secondary tabular-nums">
                    {dateWithYear(shift.shiftDate)} · {hhmm(shift.startTime)}–{hhmm(shift.endTime)}{shift.location ? ` · ${shift.location}` : ""}
                  </p>
                  <p className="mt-1 text-sm text-ink-muted tabular-nums">
                    {activeSignups.length}/{shift.capacity} filled{roster!.waitlist.length > 0 ? ` · ${roster!.waitlist.length} waiting` : ""}
                  </p>
                </div>
                {shift.status === "OPEN" && (
                  <button type="button" onClick={() => setShowBroadcast((s) => !s)} className="btn btn-primary min-h-touch px-5 transition-colors duration-state">
                    Send update to all
                  </button>
                )}
              </header>

              {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}
              {notice && <div className="mb-6"><InlineNotice tone="success" autoDismiss>{notice}</InlineNotice></div>}
              {shift.status === "CANCELLED" && (
                <div className="mb-6 rounded border border-hairline bg-warning-bg px-4 py-3 text-sm text-warning">
                  This shift was cancelled{shift.cancelReason ? `: ${shift.cancelReason}` : ""}.
                </div>
              )}

              {showBroadcast && (
                <section className="card mb-8 px-6 py-5">
                  <h2 className="text-lg">Send an update</h2>
                  <form className="mt-3" aria-label="Send an update" onSubmit={broadcast}>
                    <textarea name="message" required maxLength={1000} rows={3} placeholder="e.g. Gate B today, not A"
                      className="w-full rounded-control border border-hairline px-3 py-2" />
                    <label className="mt-2 flex items-center gap-2 text-sm text-ink-secondary">
                      <input type="checkbox" name="includeWaitlist" 
                className="accent-accent"
              /> Also send to the waitlist
                    </label>
                    <button type="submit" disabled={busy} className="btn btn-primary mt-3 min-h-touch px-5 transition-colors duration-state disabled:opacity-60">Send now</button>
                  </form>
                </section>
              )}

              <section className="mb-8">
                <h2 className="mb-3 text-lg">Signed up ({activeSignups.length})</h2>
                {activeSignups.length === 0 ? (
                  <p className="text-sm text-ink-secondary">No one signed up yet.</p>
                ) : (
                  <form aria-label="Attendance" onSubmit={recordAttendance}>
                    <div className="table-wrap overflow-x-auto">
                      <table className={TABLE}>
                        <thead className={THEAD}>
                          <tr>
                            <th className={`${TH_TEXT} ${WRAP}`}>Volunteer</th>
                            <th className={TH_TEXT}>Attendance</th>
                            <th className={TH_TEXT}>Reminders</th>
                            {shift.status === "OPEN" && <th className={TH_ACTIONS}>Actions</th>}
                          </tr>
                        </thead>
                        <tbody>
                          {activeSignups.map((s) => (
                            <tr key={s.userId} className={TR}>
                              {/* The name is the unbounded value here and so it is the one that
                                  wraps. The reminders beside it are a fixed vocabulary — an offset,
                                  a status and a channel — and no length a person can type reaches
                                  them, so squeezing a name to keep a row of "24h: sent (email)"
                                  on one line was the exception put on the wrong column. */}
                              <td className={`${TD_TEXT} ${WRAP}`}>
                                {s.fullName}
                                {s.source === "PROMOTION" && <span className="ml-2 rounded-sm bg-accent-bg px-2 py-0.5 text-xs text-accent-text font-semibold">promoted</span>}
                              </td>
                              <td className={TD_TEXT}>
                                {canMarkAttendance ? (
                                  // Ticked to start, and the tick is what a person unticks for the
                                  // one or two who did not come. The other way round — an empty
                                  // list the coordinator ticks their way down — makes a distracted
                                  // save record a shift of no-shows, and a no-show is the mark that
                                  // costs somebody something.
                                  <input
                                    type="checkbox"
                                    name="attended"
                                    value={s.userId}
                                    defaultChecked
                                    aria-label={`${s.fullName} came`}
                                    className="accent-accent"
                                  />
                                ) : (
                                  <span className="flex flex-wrap items-center gap-2">
                                    <span>
                                      {s.attended === true ? (
                                        <span>Came</span>
                                      ) : s.attended === false ? (
                                        <span className="text-warning">Did not come</span>
                                      ) : (
                                        // Null, and never rendered as an absence. A signup made after
                                        // the marking, or a shift nobody has marked at all, is a shift
                                        // nobody has spoken about — not a roster of no-shows.
                                        <span className="text-sm text-ink-muted">Not marked</span>
                                      )}
                                      {/* Three states, not two (T-099, finishing T-079): never
                                          marked (above), marked once and never changed (silence,
                                          same as today), and changed — which alone earns this line.
                                          `attendanceCorrectedAt` is null on a first answer given late
                                          through the correction door, on purpose (V110's own
                                          comment): that row is being answered for the first time, not
                                          changed, so it stays silent too. House idiom for "who did
                                          what when" (MealServices' "Corrected by X on Y.") rather than
                                          a new pattern — there is no prior *value* to show beside it,
                                          unlike a corrected meal, because the roster does not carry
                                          one; the audit trail is where that lives. */}
                                      {s.attendanceCorrectedAt && (
                                        <span className="block text-xs text-ink-muted">
                                          Corrected{s.attendanceCorrectedByName ? ` by ${s.attendanceCorrectedByName}` : ""} on{" "}
                                          {templeDay(s.attendanceCorrectedAt)}.
                                        </span>
                                      )}
                                    </span>
                                    {/* One button per answer this row does not currently hold
                                        (T-079). A marked row gets the one opposite answer, so the
                                        press is unambiguous and pressing what it already says is
                                        not on offer; an unmarked row gets both, because "nobody has
                                        said" has two ways out and neither of them is the default.
                                        The label says the answer it will set rather than "Change",
                                        so nothing turns on reading the cell first. */}
                                    {canCorrectAttendance &&
                                      [true, false]
                                        .filter((answer) => s.attended !== answer)
                                        .map((answer) => (
                                          <Button
                                            key={String(answer)}
                                            type="button"
                                            variant="ghost"
                                            size="sm"
                                            disabled={busy}
                                            onClick={() => correctAttendance(s.userId, s.fullName, answer)}
                                          >
                                            {answer ? "Mark as came" : "Mark as did not come"}
                                          </Button>
                                        ))}
                                  </span>
                                )}
                              </td>
                              <td className={`${TD_TEXT} text-sm text-ink-secondary`}>
                                <span className="block">
                                  {s.reminders.length === 0 ? "—" : s.reminders.map((r, i) => (
                                    <span key={i} className="me-2 inline-block tabular-nums">{r.offsetMinutes / 60}h: {(r.status ?? "").toLowerCase()}{r.channel ? ` (${r.channel.toLowerCase()})` : ""}</span>
                                  ))}
                                </span>
                              </td>
                              {shift.status === "OPEN" && (
                                <td className={TD_ACTIONS}>
                                  <div className={ACTIONS_ROW}>
                                    <Button
                                      type="button"
                                      variant="danger"
                                      size="sm"
                                      disabled={busy}
                                      onClick={() => removeVolunteer(s.userId, s.fullName)}
                                    >
                                      Remove
                                    </Button>
                                  </div>
                                </td>
                              )}
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                    {canMarkAttendance ? (
                      <div className="mt-3 flex flex-wrap items-center gap-3">
                        <Button type="submit" variant="secondary" size="sm" disabled={busy}>
                          Save attendance
                        </Button>
                        <span className="text-sm text-ink-muted">
                          {/* Who can undo this differs by reader since T-106, and the sentence has to
                              say so before the press rather than after it: marking is everyone's,
                              changing a mark is the Temple Admin's and the Kitchen Manager's. Telling
                              a cook they "can change a mark afterwards" and then not offering the
                              button is the same defect as offering a button that refuses them. */}
                          Untick anyone who did not come. The whole roster is saved in one go, and{" "}
                          {mayCorrectAttendance
                            ? "you can change a mark afterwards."
                            : "a kitchen manager can change a mark afterwards."}
                        </span>
                      </div>
                    ) : attendanceRecordedAt ? (
                      <p className="mt-3 text-sm text-ink-muted">
                        Attendance recorded {moment(attendanceRecordedAt)}.
                        {canCorrectAttendance
                          ? " Change any mark that is wrong."
                          : attendanceIsCorrectable
                            ? " Ask a kitchen manager if a mark is wrong."
                            : ""}
                      </p>
                    ) : !shiftHasStarted && shift.status === "OPEN" ? (
                      <p className="mt-3 text-sm text-ink-muted">
                        Attendance can be marked once this shift has started.
                      </p>
                    ) : null}
                  </form>
                )}
              </section>

              {roster!.waitlist.length > 0 && (
                <section className="mb-8">
                  <h2 className="mb-3 text-lg">Waitlist</h2>
                  <ol className="space-y-2">
                    {roster!.waitlist.map((w) => (
                      <li key={w.userId} className="card px-5 py-3 text-sm">
                        <span className="tabular-nums text-ink-muted">{w.position}.</span> {w.fullName}
                      </li>
                    ))}
                  </ol>
                </section>
              )}

              {released.length > 0 && (
                <section className="mb-8">
                  <h2 className="mb-3 text-lg">Released</h2>
                  <ul className="space-y-1 text-sm text-ink-secondary">
                    {released.map((s) => (
                      <li key={s.userId}>
                        {s.fullName} — released{s.releasedAt ? ` ${moment(s.releasedAt)}` : ""}
                      </li>
                    ))}
                  </ul>
                </section>
              )}

              {roster!.broadcasts.length > 0 && (
                <section>
                  <h2 className="mb-3 text-lg">Updates sent</h2>
                  <ul className="space-y-3">
                    {roster!.broadcasts.map((b, i) => (
                      <li key={i} className="card px-5 py-3">
                        <p className="text-sm">{b.message}</p>
                        <p className="mt-1 text-xs text-ink-muted">
                          {b.sentByName ?? "Someone"} · {moment(b.createdAt)} · {b.recipients.length} recipient(s)
                        </p>
                      </li>
                    ))}
                  </ul>
                </section>
              )}
            </>
          )}
        </div>
      </main>
    </div>
  );
}
