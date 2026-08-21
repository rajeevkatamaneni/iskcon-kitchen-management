"use client";

import { useCallback, useState, type ReactNode } from "react";
import { useParams } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Loading } from "@/components/Loading";
import { RequireRole } from "@/components/RequireRole";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { Card } from "@/components/ds/Card";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { BanRecord } from "@/components/staff/Ban";
import { StaffNotFound } from "@/components/staff/StaffNotFound";
import { ACCESS_LABELS, STATUS_LABELS, dayMonthYear, employmentTypeLabel, whoLine } from "@/components/staff/labels";
import { useStaffRecord } from "@/components/staff/use-staff-record";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { money, shortDate } from "@/lib/format";
import { api, toApiError, type ApiError, type BanCategory, type StaffProfileView } from "@/lib/api";

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

  const { staff, pay, loading, error } = useStaffRecord(id);
  const bans = useAuthedQuery(useCallback((t: string | undefined) => api.templeBans(t), []));
  const categories = useAuthedQuery(useCallback((t: string | undefined) => api.banCategories(t), []));

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [revealedPan, setRevealedPan] = useState<string | null>(null);

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
