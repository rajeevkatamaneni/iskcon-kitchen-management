"use client";

import { useCallback, useState, type ReactNode } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Loading } from "@/components/Loading";
import { RequireRole } from "@/components/RequireRole";
import { Sidebar } from "@/components/Sidebar";
import { Badge } from "@/components/ds/Badge";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { Card } from "@/components/ds/Card";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { BanRecord } from "@/components/staff/Ban";
import { ConductNotes } from "@/components/staff/ConductNotes";
import { PreviousEmployment } from "@/components/staff/PreviousEmployment";
import { Reinstate } from "@/components/staff/Reinstate";
import { RevealBox } from "@/components/staff/RevealBox";
import { StaffDocuments } from "@/components/staff/StaffDocuments";
import { StaffNotFound } from "@/components/staff/StaffNotFound";
import { StaffPhoto } from "@/components/staff/StaffPhoto";
import {
  ACCESS_LABELS,
  STATUS_LABELS,
  dayMonthYear,
  employmentTypeLabel,
  maskedPan,
} from "@/components/staff/labels";
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
} from "@/lib/api";

/**
 * One person's whole record (E6-S8, B9, T-428).
 *
 * <h3>A record, opened by name</h3>
 *
 * <p>Rajeev, 2026-09-20: the person's name on the register should be a link that opens their record,
 * with Edit on that record, "and this is the pattern the rest of the app should follow". So the
 * register no longer has an Update button that goes straight to a form, and this screen is where
 * every staff member — current or former — is read.
 *
 * <p><b>It is no longer a `FocusScreen`.</b> That component's third rule makes the <em>task</em> the
 * `h1`, which is why this page used to be headed "Staff record" with the person's name as a quiet
 * line under it. Rajeev asked for the opposite — "their name is prominent" — and a focus screen
 * cannot give that without breaking its own rule. So it takes the shape `/equipment/[id]` already
 * has: the sidebar, a back-link, the record's own name as the heading, and the actions top right.
 * That shape is also what earns the back-link; a focus screen has a Close and no way back.
 *
 * <p>Their photograph sits at the top right beside Edit, as asked. Where there is none the tile says
 * so rather than guessing at initials.
 *
 * <h3>What is read here and what is done here</h3>
 *
 * <p>Read: employment, contact, pay, and where they worked before. Done, each on its own and saved
 * the moment it is pressed: taking somebody back on, the documents, a conduct note, and correcting
 * or retracting a record raised against them. None of those sits inside the Edit form, because none
 * of them is undone by a Cancel.
 *
 * <p>The whole PAN and the scans are the two things this screen will not fetch until asked. Reading
 * either is written to the audit log, so a page that fetched them to hide them would record a read
 * nobody made.
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

  const { staff, banned, documents, previousEmployment, pay, loading, error, reload } =
    useStaffRecord(id);
  const bans = useAuthedQuery(useCallback((t: string | undefined) => api.templeBans(t), []));
  const categories = useAuthedQuery(useCallback((t: string | undefined) => api.banCategories(t), []));

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [revealedPan, setRevealedPan] = useState<string | null>(null);

  // Everything this temple recorded about this person. Almost always none or one; a second can only
  // exist where the first was taken back, and both belong on the record rather than the newer one
  // quietly standing for the pair.
  const theirBans = (bans.data ?? []).filter((b) => b.staffProfileId === id);
  const photo = documents.find((d) => d.kind === "PHOTO") ?? null;

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
    <div className="flex min-h-screen">
      <Sidebar activeHref="/staff" />
      <main className="min-w-0 flex-1 px-4 py-10 sm:px-8">
        <div className="mx-auto grid max-w-content gap-6">
          <div>
            <Link href="/staff" className="text-sm link">
              ← Staff
            </Link>
          </div>

          {actionError && <ErrorNotice error={actionError} />}

          {loading ? (
            <Loading label="Loading the record…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : !staff ? (
            <StaffNotFound />
          ) : (
            <>
              {/* The name, then the photograph and Edit together on the right. Edit and the photo
                  sit side by side rather than on a row each: two items 80px and 90px wide have no
                  business taking two rows, and at 390 the whole right-hand group wraps under the
                  name as one piece. Edit comes first in the source so it is the first thing after
                  the heading for a keyboard and a screen reader; the photograph is the last thing
                  on the line, which is what "top right" means on the page. */}
              <header className="flex flex-wrap items-start justify-between gap-x-4 gap-y-3">
                {/* `basis-64` is what decides the phone. The right-hand group is about 150px wide
                    (Edit plus a 64px tile plus the gap), so on a 390px screen the two cannot both
                    have room — 358px of content less 150 leaves the name 208, which is not enough
                    for a name and a three-part line under it. Giving the left block a 256px basis
                    makes the pair overflow and the group wrap onto its own line, where `ml-auto`
                    keeps it at the right. At 1280 there is room for both and they sit side by side,
                    which is the rule: a new row only when they genuinely cannot. */}
                <div className="min-w-0 grow basis-64">
                  <h1 className={banned ? "text-danger" : undefined}>{staff.fullName}</h1>
                  <p className="mt-1 text-ink-secondary">
                    {staff.jobTitleLabel} · {staff.kitchenName} ·{" "}
                    {staff.employmentStatus === "ACTIVE"
                      ? `joined ${dayMonthYear(staff.dateOfJoining)}`
                      : staff.lastWorkingDay
                        ? `left ${dayMonthYear(staff.lastWorkingDay)}`
                        : STATUS_LABELS[staff.employmentStatus]}
                  </p>
                  {banned && (
                    <p className="mt-2">
                      <Badge tone="danger">Banned</Badge>
                    </p>
                  )}
                </div>
                <div className="ml-auto flex flex-none items-center gap-3">
                  {/* A former employee's record is locked against editing — ending an employment
                      does that — so the server would refuse the save. Offering the button anyway
                      would be a door into a room that is bricked up. */}
                  {staff.employmentStatus === "ACTIVE" && (
                    <ButtonLink href={`/staff/${staff.id}/edit`} variant="secondary">
                      Edit
                    </ButtonLink>
                  )}
                  <StaffPhoto
                    photo={photo}
                    name={staff.fullName}
                    load={async (p) => api.staffDocument(staff.id, p.id, await getToken())}
                  />
                </div>
              </header>

              <Card title="Employment">
                <dl className="grid grid-cols-2 gap-4 text-sm sm:grid-cols-3">
                  <Fact label="Job">{staff.jobTitleLabel}</Fact>
                  {/* Every staff record has exactly one (Epic 12), so there is no "Not recorded" here. */}
                  <Fact label="Kitchen">{staff.kitchenName}</Fact>
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
                  ) : (
                    // Its own component, as the ban correction and the conduct note are (T-172): this
                    // screen is a read-only record, and the form lives beside it rather than in it.
                    <Reinstate
                      lastWorkingDay={staff.lastWorkingDay}
                      busy={busy}
                      onReinstate={(input) =>
                        void run((t) => api.reinstateStaff(staff.id, input, t), "We couldn’t take them back on.")
                      }
                    />
                  )}
                </Card>
              )}

              <Card title="Contact">
                <dl className="grid grid-cols-2 gap-4 text-sm sm:grid-cols-3">
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
                  {/* The whole PAN is a separate, audited request, so the eye is what fetches it —
                      see RevealBox. Nothing here asks for it when the page opens. */}
                  {/* Across both columns on a phone. A bordered box with a 44px eye inside it,
                      squeezed into half of a 390px screen, leaves about 116px for the number; given
                      the whole width it is a field rather than a sliver. One of three columns at
                      the sm breakpoint, like everything else here. */}
                  <Fact label="PAN" span>
                    {!staff.panLast4 ? (
                      <Absent>Not recorded</Absent>
                    ) : (
                      <RevealBox
                        masked={maskedPan(staff.panLast4)}
                        revealed={revealedPan}
                        onReveal={() => revealPan(staff)}
                        onHide={() => setRevealedPan(null)}
                        what="PAN"
                        note="Reading it is recorded."
                      />
                    )}
                  </Fact>
                </dl>
              </Card>

              <Card title="Documents">
                <StaffDocuments staffId={staff.id} documents={documents} onChanged={reload} />
              </Card>

              <Card title="Previous employment">
                <PreviousEmployment jobs={previousEmployment} />
              </Card>

              <Card title="Pay">
                <dl className="grid grid-cols-2 gap-4 text-sm sm:grid-cols-3">
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

              {/* Behind its own permission, and it draws nothing for anybody without it. On this
                  screen alone now: since the register opens the record by name rather than the form,
                  this is where a person's record is read, and the Edit screen no longer needs a copy
                  of it. Nothing here can be changed from either place in any case. */}
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
        </div>
      </main>
    </div>
  );
}

/** One labelled fact. The label above the value, on the same line as everything beside it. */
function Fact({
  label,
  children,
  /** Both columns on a phone, for a value that needs the width. One column from `sm` as usual. */
  span = false,
}: {
  label: string;
  children: ReactNode;
  span?: boolean;
}) {
  return (
    <div className={span ? "col-span-2 sm:col-span-1" : undefined}>
      <dt className="text-ink-secondary">{label}</dt>
      <dd className="mt-1 text-ink [overflow-wrap:anywhere]">{children}</dd>
    </div>
  );
}

/** Something the record does not hold. Said, rather than left as a gap somebody has to interpret. */
function Absent({ children }: { children: ReactNode }) {
  return <span className="text-ink-muted">{children}</span>;
}
