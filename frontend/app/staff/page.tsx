"use client";

import { useCallback, useMemo, useState } from "react";
import Link from "next/link";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { RequireRole } from "@/components/RequireRole";
import { Loading } from "@/components/Loading";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import {
  api,
  toApiError,
  type ApiError,
  type EmploymentStatus,
  type HireStaffInput,
  type JobTitle,
  type JobTitleGroup,
  type JobTitleOption,
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

  const [panel, setPanel] = useState<Panel>({ mode: "closed" });
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [revealed, setRevealed] = useState<Record<string, string>>({});

  const current = register.data?.current ?? [];
  const former = register.data?.former ?? [];
  const options = useMemo(() => titles.data ?? [], [titles.data]);

  async function run(mutation: (t: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    setActionError(null);
    try {
      await mutation(await getToken());
      register.reload();
      devotees.reload();
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

    const ok =
      panel.mode === "edit"
        ? await run(
            (t) => api.updateStaffMember(panel.staff.id, stripHireOnly(input), t),
            "We couldn't save that change."
          )
        : await run((t) => api.hireStaff(input, t), "We couldn't add that staff member.");

    if (ok) {
      form.reset();
      setPanel({ mode: "closed" });
    }
  }

  async function submitEnd(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (panel.mode !== "end") return;
    const f = new FormData(event.currentTarget);
    const ok = await run(
      (t) =>
        api.endEmployment(
          panel.staff.id,
          {
            status: String(f.get("status")) as Exclude<EmploymentStatus, "ACTIVE">,
            lastWorkingDay: String(f.get("lastWorkingDay")),
            reason: emptyToNull(String(f.get("reason") ?? "")),
            revokeSignIn: f.get("revokeSignIn") === "on",
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

          {(panel.mode === "hire" || panel.mode === "edit") && (
            <StaffForm
              key={panel.mode === "edit" ? panel.staff.id : "hire"}
              staff={panel.mode === "edit" ? panel.staff : null}
              options={options}
              devotees={(devotees.data ?? []).filter((d) => d.status === "ACTIVE")}
              busy={busy}
              onSubmit={submitStaff}
              onCancel={() => setPanel({ mode: "closed" })}
            />
          )}

          {panel.mode === "end" && (
            <EndEmploymentForm
              staff={panel.staff}
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
                revealed={revealed}
                busy={busy}
                onEdit={(staff) => setPanel({ mode: "edit", staff })}
                onEnd={(staff) => setPanel({ mode: "end", staff })}
                onRevealPan={revealPan}
              />

              {former.length > 0 && (
                <div className="mt-10">
                  <StaffTable
                    heading="Former staff"
                    caption="Kept so you can answer who worked here, and when. These records can be read but not changed."
                    empty=""
                    rows={former}
                    revealed={revealed}
                    busy={busy}
                    onRevealPan={revealPan}
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
  revealed,
  busy,
  onEdit,
  onEnd,
  onRevealPan,
}: {
  heading: string;
  caption?: string;
  empty: string;
  rows: StaffProfileView[];
  revealed: Record<string, string>;
  busy: boolean;
  onEdit?: (staff: StaffProfileView) => void;
  onEnd?: (staff: StaffProfileView) => void;
  onRevealPan: (staff: StaffProfileView) => void;
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
                <th className="px-5 py-3 font-medium">Joined</th>
                <th className="px-5 py-3 font-medium">Access</th>
                <th className="px-5 py-3 font-medium">PAN</th>
                {onEdit && <th className="px-5 py-3 font-medium text-right">Actions</th>}
                {!onEdit && <th className="px-5 py-3 font-medium">Left</th>}
              </tr>
            </thead>
            <tbody>
              {rows.map((s) => (
                <tr key={s.id} className="border-t border-hairline align-middle">
                  <td className="px-5 py-4">
                    {s.fullName}
                    {s.jobTitle === "UNRECORDED" && (
                      <span className="ml-2 rounded bg-warning-bg px-2 py-0.5 text-xs text-warning">
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
                  <td className="px-5 py-4 text-ink-secondary tabular-nums">{s.dateOfJoining}</td>
                  <td className="px-5 py-4 text-sm">
                    {s.systemAccess ? ACCESS_LABELS[s.systemAccess] : <span className="text-ink-muted">No login</span>}
                  </td>
                  <td className="px-5 py-4 text-sm tabular-nums">
                    {revealed[s.id] ? (
                      <span>{revealed[s.id]}</span>
                    ) : s.panLast4 ? (
                      <button
                        type="button"
                        onClick={() => onRevealPan(s)}
                        className="text-accent-text hover:underline"
                        title="Reading a PAN is recorded on the audit log"
                      >
                        ••••••{s.panLast4}
                      </button>
                    ) : (
                      <span className="text-ink-muted">—</span>
                    )}
                  </td>
                  {onEdit ? (
                    <td className="px-5 py-4 text-right text-sm">
                      <button
                        type="button"
                        disabled={busy}
                        onClick={() => onEdit(s)}
                        className="text-accent-text hover:underline disabled:opacity-60"
                      >
                        Edit
                      </button>
                      {s.userId && (
                        <Link
                          href="/staff-schedule"
                          className="ml-3 text-accent-text hover:underline"
                        >
                          Schedule
                        </Link>
                      )}
                      {onEnd && (
                        <button
                          type="button"
                          disabled={busy}
                          onClick={() => onEnd(s)}
                          className="ml-3 text-danger hover:underline disabled:opacity-60"
                        >
                          End employment
                        </button>
                      )}
                    </td>
                  ) : (
                    <td className="px-5 py-4 text-sm text-ink-secondary">
                      <div className="tabular-nums">{s.lastWorkingDay}</div>
                      <div className="text-xs text-ink-muted">
                        {STATUS_LABELS[s.employmentStatus]}
                        {s.endReason ? ` — ${s.endReason}` : ""}
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
  options,
  devotees,
  busy,
  onSubmit,
  onCancel,
}: {
  staff: StaffProfileView | null;
  options: JobTitleOption[];
  devotees: UserSummary[];
  busy: boolean;
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
            Already registered here?
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
            <span className="text-xs text-ink-muted">
              Choosing a devotee promotes the account they already have, so their seva history stays
              with them.
            </span>
          </label>
        )}

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          Full name
          <input name="fullName" required defaultValue={staff?.fullName ?? ""} className={FIELD} />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          Job title
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
            What does your temple call this job?
            <input
              name="jobTitleOther"
              required
              defaultValue={staff?.jobTitleOther ?? ""}
              className={FIELD}
            />
          </label>
        )}

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          Phone
          <input name="phone" placeholder="+919876543210" defaultValue={staff?.phone ?? ""} className={FIELD} />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          Email
          <input name="email" type="email" defaultValue={staff?.email ?? ""} className={FIELD} />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          App access
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
            <span className="text-xs text-ink-muted">Needs both an email and a phone number.</span>
          )}
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          Employment
          <select name="employmentType" defaultValue={staff?.employmentType ?? "FULL_TIME"} className={FIELD}>
            {EMPLOYMENT_TYPES.map((t) => (
              <option key={t.value} value={t.value}>
                {t.label}
              </option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          Date of joining
          <input
            name="dateOfJoining"
            type="date"
            required
            defaultValue={staff?.dateOfJoining ?? ""}
            className={FIELD}
          />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          Date of birth
          <input name="dateOfBirth" type="date" defaultValue={staff?.dateOfBirth ?? ""} className={FIELD} />
        </label>

        <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
          Address
          <input name="address" defaultValue={staff?.address ?? ""} className={FIELD} />
        </label>

        <fieldset className="col-span-2 grid grid-cols-3 gap-4 rounded border border-hairline px-4 py-3">
          <legend className="px-1 text-sm text-ink-secondary">
            Emergency contact — who to call if something happens at the stove
          </legend>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            Name
            <input
              name="emergencyContactName"
              defaultValue={staff?.emergencyContactName ?? ""}
              className={FIELD}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            Relationship
            <input
              name="emergencyContactRelationship"
              defaultValue={staff?.emergencyContactRelationship ?? ""}
              className={FIELD}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            Phone
            <input
              name="emergencyContactPhone"
              placeholder="+919876543210"
              defaultValue={staff?.emergencyContactPhone ?? ""}
              className={FIELD}
            />
          </label>
        </fieldset>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          PAN
          <input name="pan" placeholder="ABCDE1234F" className={FIELD} />
          <span className="text-xs text-ink-muted">
            {staff?.panLast4
              ? "Stored and hidden. Leave blank to keep it as it is."
              : "Encrypted before it is stored; reading it later is recorded."}
          </span>
        </label>

        <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
          Notes
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
  busy,
  onSubmit,
  onCancel,
}: {
  staff: StaffProfileView;
  busy: boolean;
  onSubmit: (e: React.FormEvent<HTMLFormElement>) => void;
  onCancel: () => void;
}) {
  // A dismissal defaults to taking the sign-in away; a resignation does not. Someone who resigns is
  // still a devotee of this temple and should go on signing in as one.
  const [status, setStatus] = useState<Exclude<EmploymentStatus, "ACTIVE">>("RESIGNED");

  return (
    <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="end-heading">
      <h2 id="end-heading" className="text-lg">
        End {staff.fullName}&rsquo;s employment
      </h2>
      <div className="mt-3">
        <InlineNotice tone="info">
          Nothing is deleted. Their record moves to Former staff, and the shifts, adjustments and
          orders that name them stay exactly as they are.
        </InlineNotice>
      </div>

      <form className="mt-4 grid grid-cols-2 gap-4" aria-label="End employment" onSubmit={onSubmit}>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          How did it end?
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
          Last working day
          <input name="lastWorkingDay" type="date" required className={FIELD} />
        </label>

        <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
          Reason
          <input name="reason" className={FIELD} />
        </label>

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

        <div className="col-span-2 flex items-center gap-3">
          <button
            type="submit"
            disabled={busy}
            className="min-h-touch rounded bg-danger-bg px-5 text-danger transition-colors duration-state hover:brightness-95 disabled:opacity-60"
          >
            End employment
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

const FIELD = "min-h-touch rounded border border-hairline bg-canvas px-3";

function readStaffForm(f: FormData): HireStaffInput {
  const access = String(f.get("systemAccess") ?? "");
  const pan = String(f.get("pan") ?? "").trim();
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
