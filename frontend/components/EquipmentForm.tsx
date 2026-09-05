"use client";

import { useState } from "react";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Button } from "@/components/ds/Button";
import {
  CATEGORY_LABEL,
  CONDITION_LABEL,
  INTERVAL_UNITS,
  SOURCE_LABEL,
  intervalUnitLabel,
} from "@/components/EquipmentWords";
import type {
  ApiError,
  EquipmentCategory,
  EquipmentCondition,
  EquipmentSource,
  ServiceIntervalUnit,
  ServiceProviderView,
} from "@/lib/api";

const FIELD = "min-h-touch rounded border border-hairline bg-canvas px-3";

const CATEGORIES: EquipmentCategory[] = ["MACHINE", "TOOL", "FURNITURE"];
const CONDITIONS: EquipmentCondition[] = ["GOOD", "NEEDS_REPAIR", "IN_REPAIR", "SCRAPPED"];
const SOURCES: EquipmentSource[] = ["PURCHASED", "DONATED"];

/** What the screen needs in order to register a machine and, for an administrator, schedule it. */
export interface NewEquipment {
  name: string;
  category: EquipmentCategory;
  storageLocation: string | null;
  condition: EquipmentCondition;
  acquisitionDate: string | null;
  source: EquipmentSource | null;
  notes: string | null;
  serialNumber: string | null;
  purchaseCostInr: number | null;
  warrantyExpiry: string | null;
  /** Null together where no schedule is being set — a trestle table needs no servicing. */
  intervalCount: number | null;
  intervalUnit: ServiceIntervalUnit | null;
  serviceProviderId: string | null;
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
 * <p>The servicing block sits <em>outside</em> the form element on purpose. It carries a control
 * that adds a service company without leaving the screen, and a button inside a form that is not
 * the form's own commit is how somebody registers a machine by pressing Enter in a phone number.
 * Its values are held here and read on submit like everything else.
 */
export function EquipmentForm({
  formId,
  isAdmin,
  providers,
  busy,
  error,
  onSubmit,
  onAddProvider,
}: {
  /** The id the screen's own commit button points at with `form={formId}`. */
  formId: string;
  /** Whether this person holds MANAGE_EQUIPMENT_SERVICING — the servicing half is theirs alone. */
  isAdmin: boolean;
  /** The firms this temple already knows. Empty for anybody who may not read the list. */
  providers: ServiceProviderView[];
  busy: boolean;
  error: ApiError | null;
  onSubmit: (input: NewEquipment) => void;
  /** Adds a company and hands back its id, so the picker can select what was just typed. */
  onAddProvider: (input: { name: string; phone: string | null }) => Promise<string | null>;
}) {
  const [intervalCount, setIntervalCount] = useState("");
  const [intervalUnit, setIntervalUnit] = useState<ServiceIntervalUnit>("MONTHS");
  const [providerId, setProviderId] = useState("");

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    const cost = String(f.get("purchaseCostInr") ?? "").trim();
    const count = intervalCount.trim();

    onSubmit({
      name: String(f.get("name") ?? "").trim(),
      category: String(f.get("category") ?? "MACHINE") as EquipmentCategory,
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
      intervalCount: isAdmin && count !== "" ? Number(count) : null,
      intervalUnit: isAdmin && count !== "" ? intervalUnit : null,
      serviceProviderId: isAdmin ? emptyToNull(providerId) : null,
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
          <span className="pl-field-inset font-medium text-ink">Kind</span>
          <select name="category" defaultValue="MACHINE" className={FIELD}>
            {CATEGORIES.map((c) => (
              <option key={c} value={c}>
                {CATEGORY_LABEL[c]}
              </option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Where it lives</span>
          <input name="storageLocation" maxLength={120} placeholder="Main kitchen" className={FIELD} />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Condition</span>
          <select name="condition" defaultValue="GOOD" className={FIELD}>
            {CONDITIONS.map((c) => (
              <option key={c} value={c}>
                {CONDITION_LABEL[c]}
              </option>
            ))}
          </select>
          <span className="pl-field-inset text-sm text-ink-secondary">
            After this it moves only through a recorded change, with a reason.
          </span>
        </label>

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

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Acquired on</span>
          <input name="acquisitionDate" type="date" className={FIELD} />
          <span className="pl-field-inset text-sm text-ink-secondary">
            Where nothing has ever been serviced, this is what the next service is counted from.
          </span>
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">What it cost (₹)</span>
          <input
            name="purchaseCostInr"
            type="number"
            min="0"
            step="0.01"
            className={FIELD}
          />
          <span className="pl-field-inset text-sm text-ink-secondary">
            Leave it blank for a gift, or where nobody knows.
          </span>
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Warranty runs to</span>
          <input name="warrantyExpiry" type="date" className={FIELD} />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Serial number</span>
          <input name="serialNumber" maxLength={120} className={FIELD} />
          <span className="pl-field-inset text-sm text-ink-secondary">
            Off the plate on the back. Furniture has none, and that is fine.
          </span>
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Notes</span>
          <input name="notes" maxLength={1000} className={FIELD} />
        </label>
      </form>

      {isAdmin && (
        <section className="rounded-lg bg-raised px-6 py-5" aria-label="Servicing">
          <h2 className="text-lg">Servicing</h2>
          <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
            How often this has to be looked at, and by whom. Leave it empty for something that needs
            no servicing — a trestle table is not overdue, it is not scheduled.
          </p>

          <div className="mt-4 grid grid-cols-2 gap-4">
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">Service it every</span>
              <div className="flex gap-2">
                <input
                  aria-label="How often"
                  type="number"
                  min="1"
                  max="100"
                  value={intervalCount}
                  onChange={(e) => setIntervalCount(e.target.value)}
                  className={`${FIELD} min-w-0 flex-1`}
                />
                <select
                  aria-label="Interval unit"
                  value={intervalUnit}
                  onChange={(e) => setIntervalUnit(e.target.value as ServiceIntervalUnit)}
                  className={FIELD}
                >
                  {INTERVAL_UNITS.map((u) => (
                    <option key={u} value={u}>
                      {intervalUnitLabel(u)}
                    </option>
                  ))}
                </select>
              </div>
              <span className="pl-field-inset text-sm text-ink-secondary">
                A month is thirty days and a year is three hundred and sixty-five, which is what a
                service contract means.
              </span>
            </label>

            <ServiceCompanyPicker
              providers={providers}
              value={providerId}
              onChange={setProviderId}
              onAdd={onAddProvider}
            />
          </div>
        </section>
      )}
    </>
  );
}

/**
 * Pick the firm that services this, or add one without leaving the screen (E3-S11).
 *
 * <p>A settings page for four fields would be a trip nobody makes, and a temple with one annual
 * maintenance contract covering six machines types the phone number once and picks it five times
 * after that (E3-S10 D7).
 *
 * <p>Name and phone only, here. The provider record also carries an email and a note, and neither
 * is anything the person registering a grinder has to hand — they can be filled in later against a
 * firm that exists, and a form that asks for them now is a form somebody abandons.
 */
export function ServiceCompanyPicker({
  providers,
  value,
  onChange,
  onAdd,
}: {
  providers: ServiceProviderView[];
  value: string;
  onChange: (id: string) => void;
  onAdd: (input: { name: string; phone: string | null }) => Promise<string | null>;
}) {
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState("");
  const [phone, setPhone] = useState("");
  const [busy, setBusy] = useState(false);

  async function add() {
    if (name.trim() === "") return;
    setBusy(true);
    try {
      const id = await onAdd({ name: name.trim(), phone: emptyToNull(phone) });
      if (id) {
        onChange(id);
        setAdding(false);
        setName("");
        setPhone("");
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col gap-1 text-sm text-ink-secondary">
      <label className="flex flex-col gap-1">
        <span className="pl-field-inset font-medium text-ink">Service company</span>
        <select value={value} onChange={(e) => onChange(e.target.value)} className={FIELD}>
          <option value="">Nobody yet</option>
          {providers.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
              {p.phone ? ` — ${p.phone}` : ""}
            </option>
          ))}
        </select>
      </label>

      {adding ? (
        <div className="mt-2 grid gap-2 rounded border border-hairline px-3 py-3">
          <label className="flex flex-col gap-1">
            <span className="pl-field-inset font-medium text-ink">Company name</span>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              maxLength={200}
              className={FIELD}
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="pl-field-inset font-medium text-ink">Phone</span>
            <input
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              maxLength={40}
              className={FIELD}
            />
          </label>
          <div className="flex gap-3">
            <Button type="button" size="sm" disabled={busy || name.trim() === ""} onClick={add}>
              Add the company
            </Button>
            <Button type="button" variant="ghost" size="sm" onClick={() => setAdding(false)}>
              Cancel
            </Button>
          </div>
        </div>
      ) : (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="self-start"
          onClick={() => setAdding(true)}
        >
          Add a service company
        </Button>
      )}
    </div>
  );
}

function emptyToNull(value: string): string | null {
  const trimmed = value.trim();
  return trimmed === "" ? null : trimmed;
}
