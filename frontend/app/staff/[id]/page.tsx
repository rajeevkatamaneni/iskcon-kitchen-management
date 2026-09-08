"use client";

import { useCallback, useState, type ReactNode } from "react";
import { useParams } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Loading } from "@/components/Loading";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { Card } from "@/components/ds/Card";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { BanRecord } from "@/components/staff/Ban";
import { ConductNotes } from "@/components/staff/ConductNotes";
import { StaffNotFound } from "@/components/staff/StaffNotFound";
import { ACCESS_LABELS, STATUS_LABELS, dayMonthYear, employmentTypeLabel, whoLine } from "@/components/staff/labels";
import { useStaffRecord } from "@/components/staff/use-staff-record";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { money, shortDate } from "@/lib/format";
import {
  api,
  toApiError,
  type ApiError,
  type BanCategory,
  type StaffProfileView,
  type SystemAccess,
} from "@/lib/api";

/** The control shape the ban panel on this same screen uses, so the two read as one screen. */
const FIELD = "min-h-touch rounded-control border border-hairline px-3";

/**
 * One person's whole record, read (E6-S8, B9).
 *
 * <p>The way into a former employee, who has no editable form and would otherwise have no way in at
 * all. Current staff reach their record through Update, which is the same record in a form — that is
 * why they get no `View` of their own (Q6).
 *
 * <p>Its top-right action is <b>Close</b> and not Cancel. Two words, because they are two different
 * acts: Cancel says what happens to what you typed, and nothing here has been typed.
 *
 * <p>It is also the one screen from which somebody can be taken back on (T-014), and it has to be:
 * ending an employment locks the record against editing as well as ending it, so a former employee
 * has no form anywhere and a misclick on the termination screen had no way back. The panel is drawn
 * only for somebody who has actually left, and is replaced by a plain refusal where this temple has
 * a record standing against them — the server answers that with KMS-400136 and means it, and the way
 * through is the retraction further down this same screen.
 *
 * <p>If a ban was raised at the dismissal it is on this screen, whole — the category, the words that
 * were written, when it was raised, when it fades, and the two remedies. <b>Only</b> here: the list
 * at `/staff/bans` is an audit of what this temple has ever recorded and is read-only, so that one
 * record can never be changed from two places (Q5).
 */
export default function StaffRecordPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <StaffRecordScreen />
    </RequireRole>
  );
}

function StaffRecordScreen() {
  const id = useParams<{ id: string }>().id;
  const { getToken } = useAuth();

  const { staff, banned, pay, loading, error, reload } = useStaffRecord(id);
  const bans = useAuthedQuery(useCallback((t: string | undefined) => api.templeBans(t), []));
  const categories = useAuthedQuery(useCallback((t: string | undefined) => api.banCategories(t), []));

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [revealedPan, setRevealedPan] = useState<string | null>(null);

  // The reinstatement panel (T-014). Closed until asked for: this screen is a record to read, and
  // bringing somebody back is a deliberate act rather than something to fall into while reading.
  const [takingBack, setTakingBack] = useState(false);
  const [rejoinedOn, setRejoinedOn] = useState("");
  // "" is no login at all, which is an ordinary answer for a cook and is the one this starts on. A
  // value nobody chose must not be an access level somebody did not mean to grant.
  const [comingBackAs, setComingBackAs] = useState<SystemAccess | "">("");
  const [takeBackReason, setTakeBackReason] = useState("");

  // Everything this temple recorded about this person. Almost always none or one; a second can only
  // exist where the first was taken back, and both belong on the record rather than the newer one
  // quietly standing for the pair.
  const theirBans = (bans.data ?? []).filter((b) => b.staffProfileId === id);

  async function run(mutation: (t: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    setActionError(null);
    try {
      await mutation(await getToken());
      bans.reload();
      // The record too, not only the ban list. A reinstatement changes the employment on it, and a
      // retraction changes whether one is offered at all — leaving either on screen would show an
      // answer the server has stopped giving.
      reload();
    } catch (e) {
      setActionError(toApiError(e, failure));
    } finally {
      setBusy(false);
    }
  }

  async function revealPan(member: StaffProfileView) {
    setActionError(null);
    try {
      const { pan } = await api.revealStaffPan(member.id, await getToken());
      if (pan) setRevealedPan(pan);
    } catch (e) {
      setActionError(toApiError(e, "We couldn’t read that PAN."));
    }
  }

  const settlements = (pay?.payments ?? []).filter((p) => p.purpose === "SETTLEMENT" && !p.voidedAt);

  return (
    <FocusScreen
      task="Staff record"
      who={staff ? whoLine(staff) : undefined}
      activeHref="/staff"
      actions={
        <ButtonLink href="/staff" variant="secondary">
          Close
        </ButtonLink>
      }
    >
      {actionError && <ErrorNotice error={actionError} />}

      {loading ? (
        <Loading label="Loading the record…" />
      ) : error ? (
        <ErrorNotice error={error} />
      ) : !staff ? (
        <StaffNotFound />
      ) : (
        <>
          <Card title="Employment">
            <dl className="grid grid-cols-3 gap-4 text-sm">
              <Fact label="Job">{staff.jobTitleLabel}</Fact>
              <Fact label="Employment">{employmentTypeLabel(staff.employmentType)}</Fact>
              <Fact label="Joined">{dayMonthYear(staff.dateOfJoining)}</Fact>
              <Fact label="App access">
                {staff.systemAccess ? ACCESS_LABELS[staff.systemAccess] : <Absent>No login</Absent>}
              </Fact>
              {staff.employmentStatus !== "ACTIVE" && (
                <>
                  <Fact label="How it ended">{STATUS_LABELS[staff.employmentStatus]}</Fact>
                  <Fact label="Last working day">
                    {staff.lastWorkingDay ? dayMonthYear(staff.lastWorkingDay) : <Absent>Not recorded</Absent>}
                  </Fact>
                  <Fact label="Why">{staff.endReason ?? <Absent>Not recorded</Absent>}</Fact>
                </>
              )}
              {staff.notes && <Fact label="Notes">{staff.notes}</Fact>}
            </dl>
          </Card>

          {/* Taking them back on (T-014), and only where there is something to take back — a
              current member of staff has not left, and the server says so with KMS-400135 rather
              than quietly succeeding. Offered here because a former employee has no editable form:
              ending an employment also locks the record, so until this panel existed a misclick on
              the termination screen could not be corrected from anywhere at all.

              A live record against them is drawn as a refusal rather than as a form that will be
              refused. The server returns KMS-400136 and means it, and the way through — taking the
              record back — is the panel further down this same screen, so the reader is told where
              to go rather than being let press something that cannot work. */}
          {staff.employmentStatus !== "ACTIVE" && (
            <Card title="Take them back on">
              {banned ? (
                <InlineNotice tone="warning">
                  <p>There is a record against this person from when they left.</p>
                  <p>Take that record back first, below, if they are to be taken back on.</p>
                </InlineNotice>
              ) : !takingBack ? (
                <div className="flex flex-wrap items-center gap-3">
                  <p className="text-sm text-ink-secondary">
                    They left on {staff.lastWorkingDay ? dayMonthYear(staff.lastWorkingDay) : "a day nobody recorded"}.
                  </p>
                  <Button variant="secondary" size="sm" disabled={busy} onClick={() => setTakingBack(true)}>
                    Take them back on
                  </Button>
                </div>
              ) : (
                <div className="grid gap-3" role="group" aria-label="Take them back on">
                  <InlineNotice tone="info">
                    <p>Their record can be edited again, and the ending comes off it.</p>
                    <p>What they can do in the app is set here, because nothing remembers what it was.</p>
                  </InlineNotice>

                  <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                    <span className="pl-field-inset font-medium text-ink">What day did they come back?</span>
                    <input
                      type="date"
                      name="dateOfRejoining"
                      required
                      value={rejoinedOn}
                      onChange={(e) => setRejoinedOn(e.target.value)}
                      className={FIELD}
                    />
                  </label>

                  <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                    <span className="pl-field-inset font-medium text-ink">What can they do in the app?</span>
                    <select
                      name="systemAccess"
                      value={comingBackAs}
                      onChange={(e) => setComingBackAs(e.target.value as SystemAccess | "")}
                      className={FIELD}
                    >
                      <option value="">No login</option>
                      {(Object.keys(ACCESS_LABELS) as SystemAccess[]).map((a) => (
                        <option key={a} value={a}>
                          {ACCESS_LABELS[a]}
                        </option>
                      ))}
                    </select>
                  </label>

                  <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                    <span className="pl-field-inset font-medium text-ink">Why are they coming back?</span>
                    <input
                      name="reason"
                      value={takeBackReason}
                      onChange={(e) => setTakeBackReason(e.target.value)}
                      className={FIELD}
                    />
                  </label>

                  <div className="flex flex-wrap items-center gap-2">
                    <Button
                      disabled={busy || rejoinedOn === ""}
                      onClick={() =>
                        void run(
                          (t) =>
                            api.reinstateStaff(
                              staff.id,
                              {
                                dateOfRejoining: rejoinedOn,
                                systemAccess: comingBackAs === "" ? null : comingBackAs,
                                reason: takeBackReason.trim() === "" ? null : takeBackReason.trim(),
                              },
                              t
                            ),
                          "We couldn’t take them back on."
                        )
                      }
                    >
                      Take them back on
                    </Button>
                    <Button variant="ghost" size="sm" disabled={busy} onClick={() => setTakingBack(false)}>
                      Leave it
                    </Button>
                  </div>
                </div>
              )}
            </Card>
          )}

          <Card title="Contact">
            <dl className="grid grid-cols-3 gap-4 text-sm">
              <Fact label="Phone">
                {staff.phone ? <span className="tabular-nums">{staff.phone}</span> : <Absent>Not recorded</Absent>}
              </Fact>
              <Fact label="Email">{staff.email ?? <Absent>Not recorded</Absent>}</Fact>
              <Fact label="Date of birth">
                {staff.dateOfBirth ? dayMonthYear(staff.dateOfBirth) : <Absent>Not recorded</Absent>}
              </Fact>
              <Fact label="Address">{staff.address ?? <Absent>Not recorded</Absent>}</Fact>
              <Fact label="In an emergency">
                {staff.emergencyContactName ? (
                  <>
                    {staff.emergencyContactName}
                    {staff.emergencyContactRelationship ? ` · ${staff.emergencyContactRelationship}` : ""}
                    {staff.emergencyContactPhone ? (
                      <span className="block tabular-nums">{staff.emergencyContactPhone}</span>
                    ) : null}
                  </>
                ) : (
                  <Absent>Not recorded</Absent>
                )}
              </Fact>
              {/* The whole PAN is a separate, audited request, so it is asked for here rather than
                  sitting in the record for anybody who opens it. */}
              <Fact label="PAN">
                {!staff.panLast4 ? (
                  <Absent>Not recorded</Absent>
                ) : (
                  <span className="flex items-center gap-2">
                    <span className="tabular-nums">{revealedPan ?? `••••••${staff.panLast4}`}</span>
                    {!revealedPan && (
                      <button
                        type="button"
                        onClick={() => revealPan(staff)}
                        className="text-accent-text hover:underline"
                        title="Reading a PAN is recorded on the audit log"
                      >
                        Reveal
                      </button>
                    )}
                  </span>
                )}
              </Fact>
            </dl>
          </Card>

          <Card title="Pay">
            <dl className="grid grid-cols-3 gap-4 text-sm">
              <Fact label="Monthly salary">
                {pay?.monthlySalary != null ? (
                  <span className="tabular-nums">{money(pay.monthlySalary, pay.currency)}</span>
                ) : (
                  <Absent>No salary recorded</Absent>
                )}
              </Fact>
              <Fact label="Cash advances outstanding">
                {pay ? (
                  <span className="tabular-nums">{money(pay.advanceBalance, pay.currency)}</span>
                ) : (
                  <Absent>Not recorded</Absent>
                )}
              </Fact>
              <Fact label="Settlement paid">
                {settlements.length === 0 ? (
                  <Absent>None recorded</Absent>
                ) : (
                  settlements.map((s) => (
                    <span key={s.id} className="block tabular-nums">
                      {money(s.net, pay!.currency)} on {shortDate(s.paidOn)}
                    </span>
                  ))
                )}
              </Fact>
            </dl>
          </Card>

          {/* Behind its own permission, and it draws nothing for anybody without it. The panel is
              on this screen and on Update, because between them they are the record: a former
              employee has no form, and a current one has no View (Q6). Neither is a second door to
              the same room — nothing here can be changed from either. */}
          <ConductNotes staffId={staff.id} />

          {theirBans.map((ban) => (
            <Card key={ban.id} title="The record we raised">
              <BanRecord
                ban={ban}
                categories={categories.data ?? []}
                busy={busy}
                onSubmitAmend={(event) => {
                  event.preventDefault();
                  const f = new FormData(event.currentTarget);
                  void run(
                    (t) =>
                      api.amendBan(
                        ban.id,
                        {
                          category: String(f.get("category")) as BanCategory,
                          account: String(f.get("account") ?? "").trim(),
                        },
                        t
                      ),
                    "We couldn’t save that correction."
                  );
                }}
                onSubmitRetract={(event) => {
                  event.preventDefault();
                  const reason = String(new FormData(event.currentTarget).get("reason") ?? "").trim();
                  void run(
                    (t) => api.retractBan(ban.id, reason === "" ? null : reason, t),
                    "We couldn’t take that record back."
                  );
                }}
              />
            </Card>
          ))}
        </>
      )}
    </FocusScreen>
  );
}

/** One labelled fact. The label above the value, on the same line as everything beside it. */
function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt className="text-ink-secondary">{label}</dt>
      <dd className="mt-1 text-ink">{children}</dd>
    </div>
  );
}

/** Something the record does not hold. Said, rather than left as a gap somebody has to interpret. */
function Absent({ children }: { children: ReactNode }) {
  return <span className="text-ink-muted">{children}</span>;
}
