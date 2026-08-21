"use client";

import { useCallback, useMemo, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { RequireRole } from "@/components/RequireRole";
import { Loading } from "@/components/Loading";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { money, shortDate } from "@/lib/format";
import Link from "next/link";
import { BanFindings, BanOnTermination } from "@/components/staff/Ban";
// The one list of ways money leaves a temple, kept where the pay screen defines it. The settlement
// on the termination form below is a payment like any other, and a second copy of the list here
// would be free to drift away from the one the pay page offers.
import { PAYMENT_MODES } from "@/components/staff/PayPanel";
import {
  api,
  toApiError,
  type ApiError,
  type BanCategory,
  type BanCategoryOption,
  type BanFinding,
  type EmploymentStatus,
  type HireStaffInput,
  type JobTitle,
  type JobTitleGroup,
  type JobTitleOption,
  type StaffPayView,
  type StaffPaymentMode,
  type StaffProfileView,
  type SystemAccess,
  type UserSummary,
} from "@/lib/api";

/**
 * The staff register (E6-S8): who works at this temple, and who used to.
 *
 * <p>This is the only door into a temple's own roles. Devotees register themselves and hold one role
 * by definition; being hired is how anyone comes to hold more, and employment ending is how they
 * stop. So the hire form carries two separate fields that are easy to confuse and must not be
 * merged: a <b>job title</b>, which is what someone is called and grants nothing, and <b>system
 * access</b>, which is what they may do. The title only pre-selects the access, so the common case
 * is one choice and the unusual one is still possible — a head cook who is also an administrator, a
 * driver with no login at all.
 *
 * <p>Former staff get their own section rather than a status column in one list. An admin looking
 * for them is asking a different question — who was the cook last Janmashtami, and why did they
 * leave — and mixing the two buries the answer under the people they see every day.
 */

const GROUP_LABELS: Record<JobTitleGroup, string> = {
  ADMINISTRATION: "Administration",
  KITCHEN: "Kitchen",
  STORE: "Store",
  SUPPORT: "Support",
  OTHER: "Other",
};

const ACCESS_LABELS: Record<SystemAccess, string> = {
  TEMPLE_ADMIN: "Temple admin",
  KITCHEN_STAFF: "Kitchen staff",
};

const STATUS_LABELS: Record<EmploymentStatus, string> = {
  ACTIVE: "Active",
  RESIGNED: "Resigned",
  TERMINATED: "Dismissed",
  CONTRACT_ENDED: "Contract ended",
};

const EMPLOYMENT_TYPES = [
  { value: "FULL_TIME", label: "Full-time" },
  { value: "PART_TIME", label: "Part-time" },
  { value: "CONTRACT", label: "Contract" },
] as const;

export default function StaffPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <StaffView />
    </RequireRole>
  );
}

type Panel =
  | { mode: "closed" }
  | { mode: "hire" }
  | { mode: "edit"; staff: StaffProfileView }
  | { mode: "end"; staff: StaffProfileView };

function StaffView() {
  const { getToken } = useAuth();
  const register = useAuthedQuery(useCallback((t: string | undefined) => api.staffRegister(t), []));
  const titles = useAuthedQuery(useCallback((t: string | undefined) => api.jobTitles(t), []));
  const devotees = useAuthedQuery(
    useCallback((t: string | undefined) => api.listUsers(t, "VOLUNTEER"), [])
  );

  // The ban categories (B9). Fetched with the page rather than when the option is ticked, so that
  // deciding to record something never waits on a request.
  const banCategories = useAuthedQuery(
    useCallback((t: string | undefined) => api.banCategories(t), [])
  );

  const [panel, setPanel] = useState<Panel>({ mode: "closed" });
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [revealed, setRevealed] = useState<Record<string, string>>({});

  // What the check at hire found, held here until the admin answers it (B9). Its presence means the
  // hire has not happened yet — not that it was refused. Nothing about a finding blocks anything.
  const [findings, setFindings] = useState<{
    checkId: string;
    findings: BanFinding[];
    input: HireStaffInput;
  } | null>(null);

  const current = register.data?.current ?? [];
  const former = register.data?.former ?? [];
  const options = useMemo(() => titles.data ?? [], [titles.data]);

  // Pay is fetched for whichever record the panel is open on, and never for the list. It is
  // deliberately absent from the staff row: that shape is shared with the roster and with a person's
  // own schedule, and pay belongs to this screen alone. Both remaining panels need it — what
  // somebody earns when you edit them, and what they owe when you terminate them — so one query
  // serves the two rather than one each. Paying somebody has its own page and fetches its own copy.
  const openStaff = panel.mode === "edit" || panel.mode === "end" ? panel.staff.id : null;
  const pay = useAuthedQuery(
    useCallback(
      (t: string | undefined) => (openStaff ? api.staffPay(openStaff, t) : Promise.resolve(null)),
      [openStaff]
    )
  );

  async function run(mutation: (t: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    setActionError(null);
    try {
      await mutation(await getToken());
      register.reload();
      devotees.reload();
      // A salary change, or a settlement recorded as part of ending an employment, moves the
      // figures the open panel is showing, so it is refreshed with the list rather than left
      // showing what was true a moment ago.
      pay.reload();
      return true;
    } catch (e) {
      setActionError(toApiError(e, failure));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function submitStaff(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (panel.mode !== "hire" && panel.mode !== "edit") return;
    const form = event.currentTarget;
    const input = readStaffForm(new FormData(form));

    // Editing an existing record runs no cross-temple check and must not: correcting somebody's
    // phone number is not a hire. Only the hire below is checked, and re-hiring is a hire.
    if (panel.mode === "edit") {
      const ok = await run(
        (t) => api.updateStaffMember(panel.staff.id, stripHireOnly(input), t),
        "We couldn't save that change."
      );
      if (ok) {
        form.reset();
        setPanel({ mode: "closed" });
      }
      return;
    }

    await attemptHire(input, form);
  }

  /**
   * Hiring, and the check that runs as part of it (B9).
   *
   * <p>Not routed through `run` because a hire has two successful outcomes, not one. Either the
   * person is taken on, or there is something another temple recorded that this admin should read
   * first — which is not an error and must not be rendered as one. Sending the same details again
   * with the check's id is the decision to go ahead, and that is what the second call here is.
   */
  async function attemptHire(input: HireStaffInput, form: HTMLFormElement | null) {
    setBusy(true);
    setActionError(null);
    try {
      const outcome = await api.hireStaff(input, await getToken());
      if (outcome.checkId && outcome.findings && outcome.findings.length > 0) {
        setFindings({ checkId: outcome.checkId, findings: outcome.findings, input });
        return;
      }
      setFindings(null);
      register.reload();
      devotees.reload();
      form?.reset();
      setPanel({ mode: "closed" });
    } catch (e) {
      setActionError(toApiError(e, "We couldn't add that staff member."));
    } finally {
      setBusy(false);
    }
  }

  /** The admin read the findings, and is taking the person on regardless. A legitimate answer. */
  async function hireAnyway() {
    if (!findings) return;
    await attemptHire({ ...findings.input, acknowledgedBanCheckId: findings.checkId }, null);
  }

  /** The admin read the findings and is not going ahead. Recorded, because walking away is a
   * decision and the log would otherwise show only the hires that happened. */
  async function doNotHire() {
    if (!findings) return;
    const checkId = findings.checkId;
    setFindings(null);
    setPanel({ mode: "closed" });
    await run((t) => api.abandonHireCheck(checkId, t), "We couldn't record that decision.");
  }

  async function submitEnd(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (panel.mode !== "end") return;
    const f = new FormData(event.currentTarget);
    const staffId = panel.staff.id;

    // The settlement goes in first, and the order is deliberate. A payment refused for a missing
    // cheque number would otherwise leave somebody already terminated with the money unrecorded,
    // whereas a settlement recorded against an employment that then fails to end is still a true
    // record of money that changed hands, and the termination can simply be done again.
    const settlement = Number(f.get("settlementAmount") ?? "");
    if (settlement > 0) {
      const recorded = await run(
        (t) =>
          api.recordStaffPayment(
            staffId,
            {
              paidOn: String(f.get("lastWorkingDay")),
              amount: settlement,
              mode: String(f.get("settlementMode") ?? "CASH") as StaffPaymentMode,
              reference: emptyToNull(String(f.get("settlementReference") ?? "")),
              purpose: "SETTLEMENT",
            },
            t
          ),
        "We couldn't record that settlement."
      );
      if (!recorded) return;
    }

    const ok = await run(
      (t) =>
        api.endEmployment(
          panel.staff.id,
          {
            status: String(f.get("status")) as Exclude<EmploymentStatus, "ACTIVE">,
            lastWorkingDay: String(f.get("lastWorkingDay")),
            reason: emptyToNull(String(f.get("reason") ?? "")),
            revokeSignIn: f.get("revokeSignIn") === "on",
            // Only when the admin deliberately ticked the option (B9). Absent otherwise, which is
            // the ordinary case: most dismissals warn nobody.
            ban:
              f.get("raiseBan") === "on"
                ? {
                    category: String(f.get("banCategory")) as BanCategory,
                    account: String(f.get("banAccount") ?? "").trim(),
                  }
                : null,
          },
          t
        ),
      "We couldn't end that employment."
    );
    if (ok) setPanel({ mode: "closed" });
  }

  async function revealPan(staff: StaffProfileView) {
    setActionError(null);
    try {
      const { pan } = await api.revealStaffPan(staff.id, await getToken());
      if (pan) setRevealed((r) => ({ ...r, [staff.id]: pan }));
    } catch (e) {
      setActionError(toApiError(e, "We couldn't read that PAN."));
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/staff" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Staff</h1>
              <p className="mt-1 text-ink-secondary">
                Everyone your temple employs. Hiring here is the only way anyone is given access to
                the app.
              </p>
              {/* Deliberately a quiet link and not a navigation item: raising a record about
                  somebody is not daily work and should take a moment to reach. */}
              <Link href="/staff/bans" className="mt-2 inline-block text-sm text-accent-text hover:underline">
                Records we have raised about former staff
              </Link>
            </div>
            <button
              type="button"
              onClick={() => setPanel((p) => (p.mode === "hire" ? { mode: "closed" } : { mode: "hire" }))}
              className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover"
            >
              Hire someone
            </button>
          </header>

          {actionError && (
            <div className="mb-6">
              <ErrorNotice error={actionError} />
            </div>
          )}

          {/* Above the form, because it has to be read before the button is pressed again (B9). */}
          {findings && (
            <BanFindings
              findings={findings.findings}
              busy={busy}
              onProceed={hireAnyway}
              onStop={doNotHire}
            />
          )}

          {(panel.mode === "hire" || panel.mode === "edit") && (
            <StaffForm
              key={panel.mode === "edit" ? panel.staff.id : "hire"}
              staff={panel.mode === "edit" ? panel.staff : null}
              pay={panel.mode === "edit" ? pay.data : null}
              options={options}
              devotees={(devotees.data ?? []).filter((d) => d.status === "ACTIVE")}
              busy={busy}
              revealedPan={panel.mode === "edit" ? revealed[panel.staff.id] ?? null : null}
              onRevealPan={revealPan}
              onSubmit={submitStaff}
              onCancel={() => setPanel({ mode: "closed" })}
            />
          )}

          {panel.mode === "end" && (
            <EndEmploymentForm
              staff={panel.staff}
              pay={pay.data}
              banCategories={banCategories.data ?? []}
              busy={busy}
              onSubmit={submitEnd}
              onCancel={() => setPanel({ mode: "closed" })}
            />
          )}

          {register.loading ? (
            <Loading label="Loading staff…" />
          ) : register.error ? (
            <ErrorNotice error={register.error} />
          ) : (
            <>
              <StaffTable
                heading="Current staff"
                empty="Nobody is employed here yet. Press “Hire someone” to add your first."
                rows={current}
                busy={busy}
                onEdit={(staff) => setPanel({ mode: "edit", staff })}
                onEnd={(staff) => setPanel({ mode: "end", staff })}
              />

              {former.length > 0 && (
                <div className="mt-10">
                  <StaffTable
                    heading="Former staff"
                    caption="Kept so you can answer who worked here, and when. These records can be read but not changed."
                    empty=""
                    rows={former}
                    busy={busy}
                  />
                </div>
              )}
            </>
          )}
        </div>
      </main>
    </div>
  );
}

// ---------------------------------------------------------------------------

function StaffTable({
  heading,
  caption,
  empty,
  rows,
  busy,
  onEdit,
  onEnd,
}: {
  heading: string;
  caption?: string;
  empty: string;
  rows: StaffProfileView[];
  busy: boolean;
  onEdit?: (staff: StaffProfileView) => void;
  onEnd?: (staff: StaffProfileView) => void;
}) {
  return (
    <section aria-labelledby={`${heading.replace(/\s+/g, "-").toLowerCase()}-heading`}>
      <h2 id={`${heading.replace(/\s+/g, "-").toLowerCase()}-heading`} className="mb-1 text-lg">
        {heading} <span className="text-sm text-ink-muted tabular-nums">({rows.length})</span>
      </h2>
      {caption && <p className="mb-3 text-sm text-ink-secondary">{caption}</p>}

      {rows.length === 0 ? (
        <div className="rounded-lg bg-raised px-6 py-14 text-center">
          <p className="mx-auto max-w-prose text-ink-secondary">{empty}</p>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-lg bg-raised">
          <table className="w-full text-left">
            <thead className="bg-sunken text-sm text-ink-secondary">
              <tr>
                <th className="px-5 py-3 font-medium">Name</th>
                <th className="px-5 py-3 font-medium">Job</th>
                <th className="px-5 py-3 font-medium">Contact</th>
                <th className="px-5 py-3 font-medium">Access</th>
                {/* Joined and PAN left this table on 2026-08-20. The joining date is on the record
                    and rarely the thing being scanned for, and a PAN is not something to have sitting
                    in a column at all — it is now read from the person's own Update panel, where it
                    is one deliberate act rather than an inch from every other row. The room they
                    freed goes to the actions. */}
                {onEdit && <th className="px-5 py-3 font-medium text-right">Actions</th>}
                {!onEdit && <th className="px-5 py-3 font-medium">Left</th>}
              </tr>
            </thead>
            <tbody>
              {rows.map((s) => (
                <tr key={s.id} className="border-t border-hairline align-middle hover:bg-raised/60">
                  <td className="px-5 py-4">
                    {s.fullName}
                    {s.jobTitle === "UNRECORDED" && (
                      <span className="ml-2 rounded bg-warning-bg px-2 py-0.5 text-xs text-warning font-semibold">
                        job not recorded
                      </span>
                    )}
                  </td>
                  <td className="px-5 py-4">
                    {s.jobTitleLabel}
                    <div className="text-xs text-ink-muted">
                      {EMPLOYMENT_TYPES.find((t) => t.value === s.employmentType)?.label}
                    </div>
                  </td>
                  <td className="px-5 py-4 text-sm text-ink-secondary">
                    <div className="tabular-nums">{s.phone ?? "—"}</div>
                    <div>{s.email ?? ""}</div>
                  </td>
                  <td className="px-5 py-4 text-sm">
                    {s.systemAccess ? ACCESS_LABELS[s.systemAccess] : <span className="text-ink-muted">No login</span>}
                  </td>
                  {onEdit ? (
                    <td className="px-5 py-4">
                      {/* One row, right-aligned, wrapping only on a narrow window. Terminate sits
                          last and apart: it is the one action here nobody takes twice. */}
                      <div className="flex flex-wrap items-center justify-end gap-2">
                        <Button variant="secondary" size="sm" className={ROW_ACTION} disabled={busy}
                                onClick={() => onEdit(s)}>
                          Update
                        </Button>
                        {/* A destination, not an action, since 2026-08-20: paying somebody is a
                            page of its own. It keeps the look of its neighbours so the row still
                            reads as three things you can do to this person. */}
                        <ButtonLink href={`/staff/${s.id}/pay`} variant="secondary" size="sm"
                                    className={ROW_ACTION}>
                          Pay
                        </ButtonLink>
                        {onEnd && (
                          <Button variant="danger" size="sm" className={`${ROW_ACTION} ml-1`} disabled={busy}
                                  onClick={() => onEnd(s)}>
                            Terminate
                          </Button>
                        )}
                      </div>
                    </td>
                  ) : (
                    <td className="px-5 py-4 text-sm text-ink-secondary">
                      <div className="tabular-nums">{s.lastWorkingDay}</div>
                      <div className="text-xs text-ink-muted">
                        {STATUS_LABELS[s.employmentStatus]}
                        {s.endReason ? ` — ${s.endReason}` : ""}
                      </div>
                      {/* Offered for former staff too: a final settlement is usually paid after the
                          last working day. It is the only thing that can still be done to one of
                          these records, so it is the same button as on a current row rather than a
                          word in the middle of a sentence about how they left. */}
                      <div className="mt-2">
                        <ButtonLink href={`/staff/${s.id}/pay`} variant="secondary" size="sm"
                                    className={ROW_ACTION}>
                          Pay
                        </ButtonLink>
                      </div>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function StaffForm({
  staff,
  pay,
  options,
  devotees,
  busy,
  revealedPan,
  onRevealPan,
  onSubmit,
  onCancel,
}: {
  staff: StaffProfileView | null;
  /** Null while hiring, and null for a moment on an edit until the pay request lands. */
  pay: StaffPayView | null;
  options: JobTitleOption[];
  devotees: UserSummary[];
  busy: boolean;
  /** The PAN in clear, once somebody has asked for it. Null until then, and never fetched eagerly. */
  revealedPan?: string | null;
  onRevealPan?: (staff: StaffProfileView) => void;
  onSubmit: (e: React.FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}) {
  // Held in state only so the two dependent fields react: "Other" reveals its text box, and a title
  // pre-selects the access it usually needs without ever locking it.
  const [jobTitle, setJobTitle] = useState<JobTitle>(staff?.jobTitle ?? "COOK");
  const [access, setAccess] = useState<SystemAccess | "">(staff?.systemAccess ?? "");
  const [accessTouched, setAccessTouched] = useState(false);

  const grouped = useMemo(() => {
    const by = new Map<JobTitleGroup, JobTitleOption[]>();
    for (const o of options) {
      if (!by.has(o.group)) by.set(o.group, []);
      by.get(o.group)!.push(o);
    }
    return [...by.entries()];
  }, [options]);

  function chooseTitle(value: JobTitle) {
    setJobTitle(value);
    if (accessTouched) return;
    const suggested = options.find((o) => o.value === value)?.suggestedAccess ?? "";
    setAccess(suggested ?? "");
  }

  return (
    <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="staff-form-heading">
      <h2 id="staff-form-heading" className="text-lg">
        {staff ? `Edit ${staff.fullName}` : "Hire someone"}
      </h2>
      <p className="mt-1 text-sm text-ink-secondary">
        A job title is what someone is called; access is what they may do in the app. Choosing a
        title suggests the access it usually needs — change it if this person is different.
      </p>

      <form
        className="mt-4 grid grid-cols-2 gap-4"
        aria-label={staff ? "Edit a staff member" : "Hire a staff member"}
        onSubmit={onSubmit}
      >
        {!staff && devotees.length > 0 && (
          <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Already registered here?</span>
            <select
              name="existingUserId"
              defaultValue=""
              className="min-h-touch rounded border border-hairline bg-canvas px-3"
            >
              <option value="">No — this person is new to the temple</option>
              {devotees.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.fullName} · {d.email}
                </option>
              ))}
            </select>
            <span className="pl-field-inset text-xs text-ink-muted">
              Choosing a devotee promotes the account they already have, so their seva history stays
              with them.
            </span>
          </label>
        )}

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Full name</span>
          <input name="fullName" required defaultValue={staff?.fullName ?? ""} className={FIELD} />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Job title</span>
          <select
            name="jobTitle"
            value={jobTitle}
            onChange={(e) => chooseTitle(e.target.value as JobTitle)}
            className={FIELD}
          >
            {grouped.map(([group, items]) => (
              <optgroup key={group} label={GROUP_LABELS[group]}>
                {items.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </optgroup>
            ))}
          </select>
        </label>

        {jobTitle === "OTHER" && (
          <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">What does your temple call this job?</span>
            <input
              name="jobTitleOther"
              required
              defaultValue={staff?.jobTitleOther ?? ""}
              className={FIELD}
            />
          </label>
        )}

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Phone</span>
          <input name="phone" placeholder="+919876543210" defaultValue={staff?.phone ?? ""} className={FIELD} />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Email</span>
          <input name="email" type="email" defaultValue={staff?.email ?? ""} className={FIELD} />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">App access</span>
          <select
            name="systemAccess"
            value={access}
            onChange={(e) => {
              setAccessTouched(true);
              setAccess(e.target.value as SystemAccess | "");
            }}
            className={FIELD}
          >
            <option value="">No login</option>
            <option value="KITCHEN_STAFF">Kitchen staff</option>
            <option value="TEMPLE_ADMIN">Temple admin</option>
          </select>
          {access !== "" && (
            <span className="pl-field-inset text-xs text-ink-muted">Needs both an email and a phone number.</span>
          )}
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Employment</span>
          <select name="employmentType" defaultValue={staff?.employmentType ?? "FULL_TIME"} className={FIELD}>
            {EMPLOYMENT_TYPES.map((t) => (
              <option key={t.value} value={t.value}>
                {t.label}
              </option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Date of joining</span>
          <input
            name="dateOfJoining"
            type="date"
            required
            defaultValue={staff?.dateOfJoining ?? ""}
            className={FIELD}
          />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Date of birth</span>
          <input name="dateOfBirth" type="date" defaultValue={staff?.dateOfBirth ?? ""} className={FIELD} />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Monthly salary</span>
          {/* Keyed on the loaded figure so an edit fills the box once the pay request lands; an
              uncontrolled input keeps whatever it was first rendered with otherwise. */}
          <input
            key={pay ? "salary-loaded" : "salary-loading"}
            name="monthlySalary"
            type="number"
            min="1"
            step="0.01"
            inputMode="decimal"
            defaultValue={pay?.monthlySalary ?? ""}
            className={FIELD}
          />
          <span className="pl-field-inset text-xs text-ink-muted">
            Leave it blank if no pay has been agreed. Blank means we say so, rather than showing
            zero.
          </span>
        </label>

        <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Address</span>
          <input name="address" defaultValue={staff?.address ?? ""} className={FIELD} />
        </label>

        <fieldset className="col-span-2 grid grid-cols-3 gap-4 rounded border border-hairline px-4 py-3">
          <legend className="px-1 text-sm text-ink-secondary">
            Emergency contact — who to call if something happens at the stove
          </legend>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Name</span>
            <input
              name="emergencyContactName"
              defaultValue={staff?.emergencyContactName ?? ""}
              className={FIELD}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Relationship</span>
            <input
              name="emergencyContactRelationship"
              defaultValue={staff?.emergencyContactRelationship ?? ""}
              className={FIELD}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Phone</span>
            <input
              name="emergencyContactPhone"
              placeholder="+919876543210"
              defaultValue={staff?.emergencyContactPhone ?? ""}
              className={FIELD}
            />
          </label>
        </fieldset>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">PAN</span>
          <input name="pan" placeholder="ABCDE1234F" className={FIELD} />
          <span className="pl-field-inset text-xs text-ink-muted">
            {staff?.panLast4
              ? "Stored and hidden. Leave blank to keep it as it is."
              : "Encrypted before it is stored; reading it later is recorded."}
          </span>
          {/* Reading the stored PAN moved here from the register on 2026-08-20. It belongs on one
              person's own panel rather than in a column beside everybody's: the same audited act,
              asked for on purpose instead of sitting an inch from every row. */}
          {staff?.panLast4 && (
            <span className="mt-1 flex items-center gap-2 text-sm">
              <span className="tabular-nums text-ink">
                {revealedPan ?? `••••••${staff.panLast4}`}
              </span>
              {!revealedPan && onRevealPan && (
                <button
                  type="button"
                  onClick={() => onRevealPan(staff)}
                  className="text-accent-text hover:underline"
                  title="Reading a PAN is recorded on the audit log"
                >
                  Reveal
                </button>
              )}
            </span>
          )}
        </label>

        <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Notes</span>
          <input name="notes" defaultValue={staff?.notes ?? ""} className={FIELD} />
        </label>

        <div className="col-span-2 flex items-center gap-3">
          <button
            type="submit"
            disabled={busy}
            className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60"
          >
            {staff ? "Save changes" : "Hire"}
          </button>
          <button
            type="button"
            onClick={onCancel}
            className="min-h-touch rounded border border-hairline px-4 hover:bg-sunken"
          >
            Cancel
          </button>
        </div>
      </form>
    </section>
  );
}

function EndEmploymentForm({
  staff,
  pay,
  banCategories,
  busy,
  onSubmit,
  onCancel,
}: {
  staff: StaffProfileView;
  /** Null for the moment before the pay request lands. */
  pay: StaffPayView | null;
  banCategories: BanCategoryOption[];
  busy: boolean;
  onSubmit: (e: React.FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}) {
  // A dismissal defaults to taking the sign-in away; a resignation does not. Someone who resigns is
  // still a devotee of this temple and should go on signing in as one.
  const [status, setStatus] = useState<Exclude<EmploymentStatus, "ACTIVE">>("RESIGNED");
  // Held so the sentence beneath the figures can name the day they are leaving beside the day they
  // were last paid. The gap between the two is the thing an admin is actually deciding about.
  const [lastDay, setLastDay] = useState("");

  const lastPaid = pay?.lastSalaryPayment ?? null;

  return (
    <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="end-heading">
      <h2 id="end-heading" className="text-lg">
        Terminate {staff.fullName}&rsquo;s employment
      </h2>
      <div className="mt-3">
        <InlineNotice tone="info">
          You are not removing anyone. {staff.fullName} moves to Former staff, and everything they
          worked on here — shifts, stock entries, orders — stays on the record exactly as it is.
        </InlineNotice>
      </div>

      {/* What we know about their money, and nothing beyond it. The advance balance is arithmetic
          and is stated flatly; what is owed in salary is not, so the last payment and the leaving
          date are put side by side and the conclusion is left to the person signing it off. */}
      <dl className="mt-4 grid grid-cols-3 gap-4 rounded border border-hairline px-4 py-3 text-sm">
        <div>
          <dt className="text-ink-secondary">Monthly salary</dt>
          <dd className="mt-1 tabular-nums">
            {/* "No salary recorded" is a statement about the record and must not be made before the
                record has arrived; until then this is simply blank. */}
            {!pay ? (
              "—"
            ) : pay.monthlySalary !== null ? (
              money(pay.monthlySalary, pay.currency)
            ) : (
              <span className="text-ink-muted">No salary recorded</span>
            )}
          </dd>
        </div>
        <div>
          <dt className="text-ink-secondary">Cash advances outstanding</dt>
          <dd className="mt-1 tabular-nums">{pay ? money(pay.advanceBalance, pay.currency) : "—"}</dd>
        </div>
        <div>
          <dt className="text-ink-secondary">Last salary payment</dt>
          <dd className="mt-1 tabular-nums">
            {lastPaid ? (
              `${money(lastPaid.net, pay!.currency)} on ${shortDate(lastPaid.paidOn)}`
            ) : (
              <span className="text-ink-muted">None recorded</span>
            )}
          </dd>
        </div>
        {lastPaid && lastDay && (
          <p className="col-span-3 text-ink-secondary">
            Last recorded payment {shortDate(lastPaid.paidOn)}; terminating {shortDate(lastDay)}.
            What is owed for the months between is yours to settle — we would only be guessing at it.
          </p>
        )}
      </dl>

      <form
        className="mt-4 grid grid-cols-2 gap-4"
        aria-label="Terminate employment"
        onSubmit={onSubmit}
      >
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">How did it end?</span>
          <select
            name="status"
            value={status}
            onChange={(e) => setStatus(e.target.value as Exclude<EmploymentStatus, "ACTIVE">)}
            className={FIELD}
          >
            <option value="RESIGNED">Resigned</option>
            <option value="CONTRACT_ENDED">Contract ended</option>
            <option value="TERMINATED">Dismissed</option>
          </select>
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Last working day</span>
          <input
            name="lastWorkingDay"
            type="date"
            required
            value={lastDay}
            onChange={(e) => setLastDay(e.target.value)}
            className={FIELD}
          />
        </label>

        <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Reason</span>
          <input name="reason" className={FIELD} />
        </label>

        {/* The settlement is typed, never worked out here. It is recorded as a payment on their last
            working day, so it lands in the same history as everything else they were paid. */}
        <fieldset className="col-span-2 grid grid-cols-3 gap-4 rounded border border-hairline px-4 py-3">
          <legend className="px-1 text-sm text-ink-secondary">
            Final settlement — leave blank if there is nothing to pay
          </legend>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Amount</span>
            <input
              name="settlementAmount"
              type="number"
              min="1"
              step="0.01"
              inputMode="decimal"
              className={FIELD}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Paid by</span>
            <select name="settlementMode" defaultValue="CASH" className={FIELD}>
              {PAYMENT_MODES.map((m) => (
                <option key={m.value} value={m.value}>
                  {m.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Reference</span>
            <input name="settlementReference" placeholder="Cheque number" className={FIELD} />
          </label>
        </fieldset>

        {staff.userId && (
          <label className="col-span-2 flex items-start gap-3 rounded border border-hairline px-4 py-3 text-sm">
            {/* Keyed so the default follows the reason rather than sticking at whatever loaded first. */}
            <input
              key={status}
              type="checkbox"
              name="revokeSignIn"
              defaultChecked={status === "TERMINATED"}
              className="mt-1"
            />
            <span>
              <span className="text-ink">Take their sign-in away entirely</span>
              <span className="block text-ink-secondary">
                Leave this unticked and they stay a devotee of your temple — they can still sign up
                for seva and give. Tick it and they cannot sign in at all.
              </span>
            </span>
          </label>
        )}

        {/* The gravest thing this screen offers (B9). Last, unticked, and deliberately not styled
            to invite a press — most dismissals raise nothing at all. */}
        <BanOnTermination categories={banCategories} />

        <div className="col-span-2 flex items-center gap-3">
          <button
            type="submit"
            disabled={busy}
            className="min-h-touch rounded bg-danger-bg px-5 text-danger transition-colors duration-state hover:brightness-95 disabled:opacity-60"
          >
            Terminate
          </button>
          <button
            type="button"
            onClick={onCancel}
            className="min-h-touch rounded border border-hairline px-4 hover:bg-sunken"
          >
            Cancel
          </button>
        </div>
      </form>
    </section>
  );
}

// ---------------------------------------------------------------------------

/**
 * The row actions, asked for on 2026-08-20: Update, Pay and Terminate should read as controls
 * rather than as underlined words in the middle of a table.
 *
 * <p>Everything that makes them look like buttons — the border, the hover fill, the focus ring, the
 * radius — comes from {@link Button} and {@link ButtonLink}. All this adds is a little more room at
 * each end, so three short words in a narrow column do not read as cramped.
 *
 * <p>They were briefly pill-shaped. They are not any more: §4 of the design system gives the pill
 * radius to status chips alone, so that a shape says whether something is a state or an action, and
 * a screen that spends it on buttons takes that distinction away everywhere.
 */
const ROW_ACTION = "px-4";

const FIELD = "min-h-touch rounded border border-hairline bg-canvas px-3";

function readStaffForm(f: FormData): HireStaffInput {
  const access = String(f.get("systemAccess") ?? "");
  const pan = String(f.get("pan") ?? "").trim();
  const salary = String(f.get("monthlySalary") ?? "").trim();
  return {
    existingUserId: emptyToNull(String(f.get("existingUserId") ?? "")),
    fullName: String(f.get("fullName") ?? "").trim(),
    phone: emptyToNull(String(f.get("phone") ?? "")),
    email: emptyToNull(String(f.get("email") ?? "")),
    jobTitle: String(f.get("jobTitle") ?? "COOK") as JobTitle,
    jobTitleOther: emptyToNull(String(f.get("jobTitleOther") ?? "")),
    employmentType: String(f.get("employmentType") ?? "FULL_TIME") as HireStaffInput["employmentType"],
    dateOfJoining: String(f.get("dateOfJoining") ?? ""),
    dateOfBirth: emptyToNull(String(f.get("dateOfBirth") ?? "")),
    address: emptyToNull(String(f.get("address") ?? "")),
    emergencyContactName: emptyToNull(String(f.get("emergencyContactName") ?? "")),
    emergencyContactRelationship: emptyToNull(String(f.get("emergencyContactRelationship") ?? "")),
    emergencyContactPhone: emptyToNull(String(f.get("emergencyContactPhone") ?? "")),
    // Blank means "leave the stored one alone", which is why it is omitted rather than sent as "".
    pan: pan === "" ? undefined : pan,
    systemAccess: access === "" ? null : (access as SystemAccess),
    // A blank box is null and not 0. The two mean different things all the way down: no salary
    // recorded is what the termination screen has to be able to say.
    monthlySalary: salary === "" ? null : Number(salary),
    notes: emptyToNull(String(f.get("notes") ?? "")),
  };
}

/** Which devotee's account this is cannot change after the hire, so an edit never sends it. */
function stripHireOnly(input: HireStaffInput) {
  const { existingUserId: _ignored, ...rest } = input;
  return rest;
}

function emptyToNull(s: string): string | null {
  const t = s.trim();
  return t === "" ? null : t;
}
