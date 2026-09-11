"use client";

import { useState } from "react";
import { HintedField, InfoHint } from "@/components/ds/InfoHint";
import { ErrorNotice } from "@/components/ErrorNotice";
import {
  CONDITION_LABEL,
  INTERVAL_UNITS,
  SOURCE_LABEL,
  intervalUnitLabel,
} from "@/components/EquipmentWords";
import type {
  ApiError,
  EquipmentCondition,
  EquipmentSource,
  ServiceIntervalUnit,
} from "@/lib/api";

const FIELD = "min-h-touch rounded-control border border-hairline px-3";

/**
 * The number box beside the unit picker.
 *
 * <p>A fixed width rather than `flex-1`, which stretched a box that holds at most three digits
 * across half the row and made an interval look like a field somebody had left blank. The select
 * takes the rest.
 */
// "3" and "months" are a phrase, not a row: the count is as wide as three digits and the unit
// as wide as its longest word. Letting the unit take the rest of the line put a tiny box
// beside a huge one, which is the shape Rajeev objected to on 2026-09-04.
const COUNT_FIELD = "w-20 shrink-0";
const UNIT_FIELD = "w-32 shrink-0";

const CONDITIONS: EquipmentCondition[] = ["GOOD", "NEEDS_REPAIR", "IN_REPAIR", "SCRAPPED"];
const SOURCES: EquipmentSource[] = ["PURCHASED", "DONATED"];

/** What the screen needs in order to register a machine and, for an administrator, schedule it. */
export interface NewEquipment {
  name: string;
  storageLocation: string | null;
  condition: EquipmentCondition;
  acquisitionDate: string | null;
  source: EquipmentSource | null;
  notes: string | null;
  serialNumber: string | null;
  purchaseCostInr: number | null;
  warrantyExpiry: string | null;
  /** Null together where no schedule is being set — nobody has decided yet, or it never needs one. */
  intervalCount: number | null;
  intervalUnit: ServiceIntervalUnit | null;
  /**
   * Whether this is a thing nobody will ever service (T-120, T-143).
   *
   * <p>Not the same fact as a null interval, which is the whole of T-120: an empty interval means
   * nobody has decided, and this means there is nothing to decide. The two can never both be true,
   * and the database refuses the pairing outright, so a ticked box sends no interval at all.
   */
  neverNeedsServicing: boolean;
  serviceCompany: string | null;
  serviceCompanyPhone: string | null;
}

/**
 * Registering a piece of equipment (E3-S11).
 *
 * <p>Presentational: the screen around it owns the two API calls, the navigation and the error.
 *
 * <p><strong>Two blocks, because they are two permissions.</strong> The register itself — what it
 * is, where it lives, what it cost, the number on the plate on the back — is kitchen staff's, and
 * whoever unpacks the machine is the person holding the invoice. Deciding that it needs looking at
 * every six months and naming the firm that will do it commits the temple to money and to a diary,
 * and is the administrator's alone (E3-S10 D10). Kitchen staff are shown the first block and not
 * the second, which is the same split the API enforces on two different endpoints.
 *
 * <p>The servicing block sits <em>outside</em> the form element on purpose: it is the second
 * permission, and keeping it out of the form means nothing in it can commit the register by
 * accident. Its values are held here and read on submit like everything else.
 */
export function EquipmentForm({
  formId,
  isAdmin,
  busy,
  error,
  onSubmit,
}: {
  /** The id the screen's own commit button points at with `form={formId}`. */
  formId: string;
  /** Whether this person holds MANAGE_EQUIPMENT_SERVICING — the servicing half is theirs alone. */
  isAdmin: boolean;
  busy: boolean;
  error: ApiError | null;
  onSubmit: (input: NewEquipment) => void;
}) {
  const [intervalCount, setIntervalCount] = useState("");
  const [intervalUnit, setIntervalUnit] = useState<ServiceIntervalUnit>("MONTHS");
  const [never, setNever] = useState(false);
  const [company, setCompany] = useState("");
  const [companyPhone, setCompanyPhone] = useState("");

  // Ticking clears the count in the form as well as in what is sent, because Rajeev's ruling says
  // the box is cleared out and not merely ignored: a number left sitting greyed out in a disabled
  // field reads as a value that is still in force. The same function, for the same reason, as the
  // one on the item's own page — this is one behaviour built once and shown in two places.
  function toggleNever(ticked: boolean) {
    setNever(ticked);
    if (ticked) setIntervalCount("");
  }

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    const cost = String(f.get("purchaseCostInr") ?? "").trim();
    const count = intervalCount.trim();

    onSubmit({
      name: String(f.get("name") ?? "").trim(),
      storageLocation: emptyToNull(String(f.get("storageLocation") ?? "")),
      condition: String(f.get("condition") ?? "GOOD") as EquipmentCondition,
      acquisitionDate: emptyToNull(String(f.get("acquisitionDate") ?? "")),
      source: (emptyToNull(String(f.get("source") ?? "")) as EquipmentSource | null) ?? null,
      notes: emptyToNull(String(f.get("notes") ?? "")),
      serialNumber: emptyToNull(String(f.get("serialNumber") ?? "")),
      purchaseCostInr: cost === "" ? null : Number(cost),
      warrantyExpiry: emptyToNull(String(f.get("warrantyExpiry") ?? "")),
      // Both halves together or neither: a day count with no unit cannot be shown back in the words
      // it was entered in, and a unit with no count is not an interval.
      //
      // Ticked sends no interval at all, and not because the box happens to have been cleared: the
      // server refuses "never needs servicing, every six months" outright, so the screen must never
      // post a request it already knows will be turned away.
      intervalCount: isAdmin && !never && count !== "" ? Number(count) : null,
      intervalUnit: isAdmin && !never && count !== "" ? intervalUnit : null,
      neverNeedsServicing: isAdmin && never,
      serviceCompany: isAdmin ? emptyToNull(company) : null,
      serviceCompanyPhone: isAdmin ? emptyToNull(companyPhone) : null,
    });
  }

  return (
    <>
      {error && <ErrorNotice error={error} />}

      <form
        id={formId}
        className="grid grid-cols-2 gap-4"
        aria-label="Register equipment"
        aria-busy={busy}
        onSubmit={submit}
      >
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Name</span>
          <input name="name" required maxLength={200} placeholder="Wet grinder 10L" className={FIELD} />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Where it lives</span>
          <input name="storageLocation" maxLength={120} placeholder="Main kitchen" className={FIELD} />
        </label>

        <HintedField
          label="Condition"
          hint="After this it moves only through a recorded change, with a reason."
        >
          {(id) => (
            <select id={id} name="condition" defaultValue="GOOD" className={FIELD}>
              {CONDITIONS.map((c) => (
                <option key={c} value={c}>
                  {CONDITION_LABEL[c]}
                </option>
              ))}
            </select>
          )}
        </HintedField>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">How it came to the temple</span>
          <select name="source" defaultValue="" className={FIELD}>
            <option value="">Not recorded</option>
            {SOURCES.map((s) => (
              <option key={s} value={s}>
                {SOURCE_LABEL[s]}
              </option>
            ))}
          </select>
        </label>

        <HintedField
          label="Acquired on"
          hint="Where nothing has ever been serviced, this is what the next service is counted from."
        >
          {(id) => <input id={id} name="acquisitionDate" type="date" className={FIELD} />}
        </HintedField>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">What it cost (₹)</span>
          <input
            name="purchaseCostInr"
            type="number"
            min="0"
            step="0.01"
            className={FIELD}
          />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Warranty runs to</span>
          <input name="warrantyExpiry" type="date" className={FIELD} />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Serial number</span>
          <input name="serialNumber" maxLength={120} className={FIELD} />
        </label>

        {/* Last, and across both columns. Everything above it is a word or a number in a box;
            this is the one field somebody writes a sentence in, and a single line the width of a
            date picker invites three words and no more. */}
        <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Notes</span>
          <textarea
            name="notes"
            rows={4}
            maxLength={1000}
            className="min-h-touch rounded-control border border-hairline px-3 py-2"
          />
        </label>
      </form>

      {isAdmin && (
        <section className="card px-6 py-5" aria-label="Servicing">
          <h2 className="text-lg">Servicing</h2>
          <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
            How often this has to be looked at, and by whom. Leave it empty if nobody has decided
            yet, and tick the box below for something that will never need it at all.
          </p>

          <div className="mt-4 grid grid-cols-2 gap-4">
            {/*
              The same tick box as the item's own page, here as well, because a temple registering
              sixty stools should be able to say so as it registers them (T-143). It was only on the
              servicing form until now, which made declaring sixty things un-serviced sixty visits
              to sixty pages.

              The behaviour is Rajeev's ruling of 2026-09-10 and is deliberately identical to the
              one T-120 built there — "a check box for equipment that don't need service like a
              ladder. When checked, the Service interval box is cleared out and uneditable" — down
              to the words, because an approved behaviour built twice is a behaviour that drifts.
            */}
            <div className="col-span-2">
              <label className="flex items-start gap-2 text-sm text-ink">
                <input
                  type="checkbox"
                  name="neverNeedsServicing"
                  checked={never}
                  onChange={(e) => toggleNever(e.target.checked)}
                  className="mt-1 h-4 w-4 shrink-0 accent-accent"
                />
                <span>This never needs servicing</span>
              </label>
              <p className="mt-1 max-w-[60ch] pl-6 text-sm text-ink-secondary">
                A ladder, a trestle table, a stack of stools — the temple owns it and nobody will
                ever service it. Leave it un-ticked for anything that just has not been scheduled
                yet.
              </p>
            </div>

            <div className="flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset flex items-center gap-1.5 font-medium text-ink">
                {/* Not a HintedField: this label sits over a pair of controls, a count and a unit,
                    each already carrying its own aria-label. There is no single input for an
                    htmlFor to point at. */}
                <span>Service it every</span>
                <InfoHint
                  text="A month is thirty days and a year is three hundred and sixty-five, which is what a service contract means."
                  label="Service it every"
                />
              </span>
              <div className="flex gap-2">
                <input
                  aria-label="How often"
                  type="number"
                  min="1"
                  max="100"
                  value={intervalCount}
                  disabled={never}
                  onChange={(e) => setIntervalCount(e.target.value)}
                  className={`${FIELD} ${COUNT_FIELD} disabled:bg-sunken disabled:text-ink-muted`}
                />
                <select
                  aria-label="Interval unit"
                  value={intervalUnit}
                  disabled={never}
                  onChange={(e) => setIntervalUnit(e.target.value as ServiceIntervalUnit)}
                  className={`${FIELD} ${UNIT_FIELD} disabled:bg-sunken disabled:text-ink-muted`}
                >
                  {INTERVAL_UNITS.map((u) => (
                    <option key={u} value={u}>
                      {intervalUnitLabel(u)}
                    </option>
                  ))}
                </select>
              </div>
              {never && (
                /* A disabled empty box on its own explains nothing, and the reader is entitled to
                   know why they cannot type in it. The field stays visible rather than vanishing,
                   which would make them wonder what they had just broken. */
                <span className="max-w-[60ch] text-sm text-ink-secondary">
                  This equipment does not need servicing, so there is no interval to set.
                </span>
              )}
            </div>

            {/* Two text boxes, and nothing behind them. There was a managed list here with an
                *Add a service company* button until 2026-09-04, when Rajeev removed it: "A Text
                box serves the purpose JUST FINE" (E3-S10 D7). */}
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">Service company</span>
              <input
                value={company}
                onChange={(e) => setCompany(e.target.value)}
                maxLength={200}
                placeholder="Bengaluru Kitchen Engineering"
                className={FIELD}
              />
            </label>

            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">Their phone number</span>
              <input
                value={companyPhone}
                onChange={(e) => setCompanyPhone(e.target.value)}
                maxLength={40}
                placeholder="+91 98450 12345"
                className={FIELD}
              />
            </label>
          </div>
        </section>
      )}
    </>
  );
}

function emptyToNull(value: string): string | null {
  const trimmed = value.trim();
  return trimmed === "" ? null : trimmed;
}
