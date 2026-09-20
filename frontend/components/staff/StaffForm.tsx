"use client";

import { useMemo, useState } from "react";
import { Form } from "@/components/ds/Form";
import { HintedField, InfoHint } from "@/components/ds/InfoHint";
import { GROUP_LABELS, EMPLOYMENT_TYPES, maskedPan } from "./labels";
import { PreviousEmploymentFields, readPreviousEmployment } from "./PreviousEmploymentFields";
import { RevealBox } from "./RevealBox";
import { normalizePhone } from "@/lib/phone";
import type {
  HireStaffInput,
  JobTitle,
  JobTitleGroup,
  JobTitleOption,
  Kitchen,
  PreviousEmploymentView,
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
 *
 * <p><b>Kitchen</b> (Epic 12). Rajeev, 2026-09-19: "every staff member belongs to exactly one kitchen
 * — required on add and edit." So it is a required select on both, refused in red under the box by
 * {@link Form} like every other required field, and by the server as `KMS-400184` if it is ever sent
 * without one. It offers active kitchens only, in the order Settings lists them. A temple with one
 * kitchen has nothing to choose, so that kitchen is already chosen; with more, a hire starts on
 * "Choose a kitchen" rather than on whichever kitchen happens to be first, because a default there
 * is a guess that looks like an answer. An edit starts on the person's saved kitchen.
 */

/** What the header's submit button points at. */
export const STAFF_FORM_ID = "staff-form";

const FIELD = "min-h-touch rounded-control border border-hairline px-3";

export function StaffForm({
  staff,
  pay,
  options,
  kitchens,
  devotees,
  previousEmployment = [],
  revealedPan,
  onRevealPan,
  onHidePan,
  onSubmit,
}: {
  /** The record being changed, or null while hiring. */
  staff: StaffProfileView | null;
  /** Null while hiring, and null for a moment on an edit until the pay request lands. */
  pay: StaffPayView | null;
  options: JobTitleOption[];
  /** The temple's kitchens as `listKitchens(false)` returns them. Archived ones are never offered. */
  kitchens: Kitchen[];
  devotees: UserSummary[];
  /** Where they worked before (T-428). Empty while hiring — it is asked for on the record's own edit. */
  previousEmployment?: PreviousEmploymentView[];
  /** The PAN in clear, once somebody has asked for it. Null until then, and never fetched eagerly. */
  revealedPan?: string | null;
  onRevealPan?: (staff: StaffProfileView) => void;
  /** Throws the revealed PAN away, so showing it again is a second recorded read. */
  onHidePan?: () => void;
  onSubmit: (e: React.FormEvent<HTMLFormElement>) => void;
}) {
  // Held in state only so the two dependent fields react: "Other" reveals its text box, and a title
  // pre-selects the access it usually needs without ever locking it.
  const [jobTitle, setJobTitle] = useState<JobTitle>(staff?.jobTitle ?? "COOK");
  const [access, setAccess] = useState<SystemAccess | "">(staff?.systemAccess ?? "");
  const [accessTouched, setAccessTouched] = useState(false);

  // Null until somebody picks one. The starting value is derived on every render instead of copied
  // into state once, because the kitchens arrive after the form first draws: copied on mount, a
  // one-kitchen temple would start on the placeholder and stay there.
  const [kitchenChoice, setKitchenChoice] = useState<string | null>(null);
  const activeKitchens = useMemo(() => kitchens.filter((k) => k.status === "ACTIVE"), [kitchens]);
  const startingKitchen = staff?.kitchenId
    ? staff.kitchenId
    : activeKitchens.length === 1
      ? activeKitchens[0].id
      : "";
  const kitchenId = kitchenChoice ?? startingKitchen;
  // The saved kitchen is offered even when it is not among the active ones — archived since, or the
  // list not yet arrived — so the select shows where the person really is rather than falling back to
  // whichever option happens to be first. The server refuses a save into an archived kitchen
  // (`KMS-400109`), and says so; a guess here would have hidden the question.
  const savedElsewhere =
    !!staff?.kitchenId && kitchenId === staff.kitchenId && !activeKitchens.some((k) => k.id === staff.kitchenId);

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
    <Form
      id={STAFF_FORM_ID}
      className="grid grid-cols-1 gap-4 sm:grid-cols-2"
      aria-label={staff ? "Edit a staff member" : "Hire a staff member"}
      onSubmit={onSubmit}
    >
      <p className="col-span-full text-sm text-ink-secondary">
        A job title is what somebody is called. Access is what they may do.
      </p>

      {/* The wrapper carries the grid span: HintedField owns the field's own layout and takes no
          class of its own, deliberately, so that every hinted field on every screen is spaced the
          same. */}
      {!staff && devotees.length > 0 && (
        <div className="col-span-full">
          <HintedField
            label="Already registered here?"
            hint="Their seva history stays with them."
          >
            {(id) => (
              <select id={id} name="existingUserId" defaultValue="" className={FIELD}>
                <option value="">No — this person is new to the temple</option>
                {devotees.map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.fullName} · {d.email}
                  </option>
                ))}
              </select>
            )}
          </HintedField>
        </div>
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
        <label className="col-span-full flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">What does your temple call this job?</span>
          <input
            name="jobTitleOther"
            required
            defaultValue={staff?.jobTitleOther ?? ""}
            className={FIELD}
          />
        </label>
      )}

      {/* Beside the job title's row rather than at the end: where somebody works is read with what
          they are called. It also evens the half-width fields to ten, so no box sits alone on a row
          with an empty space beside it. */}
      <label className="flex flex-col gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Kitchen</span>
        <select
          name="kitchenId"
          required
          value={kitchenId}
          onChange={(e) => setKitchenChoice(e.target.value)}
          className={FIELD}
        >
          {/* Only while there is something to choose between. Not disabled: an empty value on a
              required select is what makes the form say "Kitchen is required". */}
          {kitchenId === "" && <option value="">Choose a kitchen</option>}
          {savedElsewhere && staff && <option value={staff.kitchenId}>{staff.kitchenName}</option>}
          {activeKitchens.map((k) => (
            <option key={k.id} value={k.id}>
              {k.name}
            </option>
          ))}
        </select>
      </label>

      <label className="flex flex-col gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Phone</span>
        <input name="phone" placeholder="+919876543210" defaultValue={staff?.phone ?? ""} className={FIELD} />
      </label>

      <label className="flex flex-col gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Email</span>
        <input name="email" type="email" defaultValue={staff?.email ?? ""} className={FIELD} />
      </label>

      {/* The hint appears only once a login has actually been chosen. With "No login" selected there
          is no rule to state, and a sentence about needing an email would read as a demand rather
          than a condition — which is why the hint is conditional and the field is not. */}
      <HintedField
        label="App access"
        hint={access !== "" ? "Needs both an email and a phone number." : undefined}
      >
        {(id) => (
          <select
            id={id}
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
              approving and issuing ingredients reachable by anybody other than the admin. E6-S12's
              own D5 said the hire form would offer it; it never did, and E10 is what made the
              omission bite.
            */}
            <option value="KITCHEN_MANAGER">Kitchen manager</option>
            <option value="TEMPLE_ADMIN">Temple admin</option>
          </select>
        )}
      </HintedField>

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

      <HintedField label="Monthly salary" hint="Leave it blank if no pay has been agreed.">
        {(id) => (
          /* Keyed on the loaded figure so an edit fills the box once the pay request lands; an
             uncontrolled input keeps whatever it was first rendered with otherwise. */
          <input
            id={id}
            key={pay ? "salary-loaded" : "salary-loading"}
            name="monthlySalary"
            type="number"
            min="1"
            step="0.01"
            inputMode="decimal"
            defaultValue={pay?.monthlySalary ?? ""}
            className={FIELD}
          />
        )}
      </HintedField>

      <label className="col-span-full flex flex-col gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Address</span>
        <input name="address" defaultValue={staff?.address ?? ""} className={FIELD} />
      </label>

      <fieldset className="col-span-full grid grid-cols-1 gap-4 rounded border border-hairline px-4 py-3 sm:grid-cols-3">
        {/* Not a HintedField: this legend names three controls, so there is no single id for an
            htmlFor to point at. The tail that used to sit after the dash says what the number is
            for, which is the icon's job. */}
        <legend className="flex items-center gap-1.5 px-1 text-sm text-ink-secondary">
          <span>Emergency contact</span>
          <InfoHint
            text="Who to call if something happens at the stove."
            label="Emergency contact"
          />
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

      {/* A stored PAN is read in its own box, with the eye inside it (T-428). What replaced the
          masked line that used to float under the input with a text link called Reveal beside it:
          Rajeev, 2026-09-20, asked for the PAN "in the same text box with an ‘Eye’ Icon". The eye
          makes the audited request when it is pressed — see {@link RevealBox}, which is emphatic
          about why it must not be fetched on load and hidden with CSS. */}
      {staff?.panLast4 ? (
        <div className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">PAN</span>
          <RevealBox
            masked={maskedPan(staff.panLast4)}
            revealed={revealedPan ?? null}
            onReveal={() => onRevealPan?.(staff)}
            onHide={() => onHidePan?.()}
            what="PAN"
            note="Reading it is recorded."
          />
          <label className="mt-1 flex flex-col gap-1">
            <span className="pl-field-inset text-xs text-ink-muted">
              Leave this blank to keep the stored one.
            </span>
            <input name="pan" placeholder="ABCDE1234F" aria-label="Replace the PAN" className={FIELD} />
          </label>
        </div>
      ) : (
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">PAN</span>
          <input name="pan" placeholder="ABCDE1234F" className={FIELD} />
          {/* One of the four texts exempt from the twelve-word rule: why a tax number is asked for
              and what becomes of it. */}
          <span className="pl-field-inset text-xs text-ink-muted">
            Encrypted before it is stored. Reading it later is recorded.
          </span>
        </label>
      )}

      {/* Where they worked before (T-428). Only on an edit: hiring somebody is already ten fields,
          and Rajeev asked for this on "the record’s own Edit screen". */}
      {staff && <PreviousEmploymentFields jobs={previousEmployment} />}
    </Form>
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
    // Both numbers without their separators (T-157): what the API stores, and what it checks.
    phone: emptyToNull(normalizePhone(String(f.get("phone") ?? ""))),
    email: emptyToNull(String(f.get("email") ?? "")),
    jobTitle: String(f.get("jobTitle") ?? "COOK") as JobTitle,
    jobTitleOther: emptyToNull(String(f.get("jobTitleOther") ?? "")),
    employmentType: String(f.get("employmentType") ?? "FULL_TIME") as HireStaffInput["employmentType"],
    dateOfJoining: String(f.get("dateOfJoining") ?? ""),
    dateOfBirth: emptyToNull(String(f.get("dateOfBirth") ?? "")),
    address: emptyToNull(String(f.get("address") ?? "")),
    emergencyContactName: emptyToNull(String(f.get("emergencyContactName") ?? "")),
    emergencyContactRelationship: emptyToNull(String(f.get("emergencyContactRelationship") ?? "")),
    emergencyContactPhone: emptyToNull(normalizePhone(String(f.get("emergencyContactPhone") ?? ""))),
    // Blank means "leave the stored one alone", which is why it is omitted rather than sent as "".
    pan: pan === "" ? undefined : pan,
    systemAccess: access === "" ? null : (access as SystemAccess),
    // A blank box is null and not 0. The two mean different things all the way down: no salary
    // recorded is what the termination screen has to be able to say.
    monthlySalary: salary === "" ? null : Number(salary),
    // Sent on a hire and on every edit alike (Epic 12). The form refuses a blank one before it gets
    // here; the server's KMS-400184 is the twin guard.
    kitchenId: String(f.get("kitchenId") ?? ""),
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
