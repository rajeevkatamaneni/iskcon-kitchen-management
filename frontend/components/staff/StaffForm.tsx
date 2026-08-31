"use client";

import { useMemo, useState } from "react";
import { GROUP_LABELS, EMPLOYMENT_TYPES } from "./labels";
import type {
  HireStaffInput,
  JobTitle,
  JobTitleGroup,
  JobTitleOption,
  StaffPayView,
  StaffProfileView,
  SystemAccess,
  UserSummary,
} from "@/lib/api";

/**
 * The one form behind hiring somebody and updating their record (E6-S8).
 *
 * <p>One component and not two, because they are the same record: every field an update may change
 * is a field a hire has to set, and a second form would be free to grow a field the other never
 * learns about. What the two do differently is decided by the screen around it — {@code /staff/new}
 * runs the cross-temple check on what comes out of here, {@code /staff/[id]/edit} deliberately does
 * not, because correcting a phone number is not a hire.
 *
 * <p>It carries no heading and no buttons of its own. Both belong to the {@link FocusScreen} it sits
 * in: the task is the heading, and there is one place to commit — the header. The submit button up
 * there reaches this form through the {@code form} attribute and the id below.
 *
 * <p>A <b>job title</b> and <b>system access</b> are two fields that are easy to confuse and must not
 * be merged: the title is what somebody is called and grants nothing, access is what they may do.
 * The title only pre-selects the access, so the common case is one choice and the unusual one is
 * still possible — a head cook who is also an administrator, a driver with no login at all.
 */

/** What the header's submit button points at. */
export const STAFF_FORM_ID = "staff-form";

const FIELD = "min-h-touch rounded border border-hairline bg-canvas px-3";

export function StaffForm({
  staff,
  pay,
  options,
  devotees,
  revealedPan,
  onRevealPan,
  onSubmit,
}: {
  /** The record being changed, or null while hiring. */
  staff: StaffProfileView | null;
  /** Null while hiring, and null for a moment on an edit until the pay request lands. */
  pay: StaffPayView | null;
  options: JobTitleOption[];
  devotees: UserSummary[];
  /** The PAN in clear, once somebody has asked for it. Null until then, and never fetched eagerly. */
  revealedPan?: string | null;
  onRevealPan?: (staff: StaffProfileView) => void;
  onSubmit: (e: React.FormEvent<HTMLFormElement>) => void;
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
    <form
      id={STAFF_FORM_ID}
      className="grid grid-cols-2 gap-4"
      aria-label={staff ? "Edit a staff member" : "Hire a staff member"}
      onSubmit={onSubmit}
    >
      <p className="col-span-2 text-sm text-ink-secondary">
        A job title is what somebody is called. Access is what they may do.
      </p>

      {!staff && devotees.length > 0 && (
        <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Already registered here?</span>
          <select name="existingUserId" defaultValue="" className={FIELD}>
            <option value="">No — this person is new to the temple</option>
            {devotees.map((d) => (
              <option key={d.id} value={d.id}>
                {d.fullName} · {d.email}
              </option>
            ))}
          </select>
          <span className="pl-field-inset text-xs text-ink-muted">
            Their seva history stays with them.
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
          {/*
            The temple's storekeeper is a Kitchen Manager — this system has no Storekeeper role and
            deliberately does not add one (E10 design D4). Which means this option is what makes
            approving and issuing ingredients reachable by anybody other than the admin. E6-S12's own
            D5 said the hire form would offer it; it never did, and E10 is what made the omission
            bite.
          */}
          <option value="KITCHEN_MANAGER">Kitchen manager</option>
          <option value="TEMPLE_ADMIN">Temple admin</option>
        </select>
        {access !== "" && (
          <span className="pl-field-inset text-xs text-ink-muted">
            Needs both an email and a phone number.
          </span>
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
          Leave it blank if no pay has been agreed.
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
        {/* One of the four texts exempt from the twelve-word rule: why a tax number is asked for and
            what becomes of it. Tightened only where tightening was free — the semicolon became a
            full stop, and nothing else moved. */}
        <span className="pl-field-inset text-xs text-ink-muted">
          {staff?.panLast4
            ? "Stored and hidden. Leave blank to keep it as it is."
            : "Encrypted before it is stored. Reading it later is recorded."}
        </span>
        {/* Reading the stored PAN moved here from the register on 2026-08-20. It belongs on one
            person’s own screen rather than in a column beside everybody’s: the same audited act,
            asked for on purpose instead of sitting an inch from every row. */}
        {staff?.panLast4 && (
          <span className="mt-1 flex items-center gap-2 text-sm">
            <span className="tabular-nums text-ink">{revealedPan ?? `••••••${staff.panLast4}`}</span>
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
    </form>
  );
}

// ---------------------------------------------------------------------------

/** Everything the form holds, as the API wants it. */
export function readStaffForm(f: FormData): HireStaffInput {
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
export function stripHireOnly(input: HireStaffInput) {
  const { existingUserId: _ignored, ...rest } = input;
  return rest;
}

export function emptyToNull(s: string): string | null {
  const t = s.trim();
  return t === "" ? null : t;
}
