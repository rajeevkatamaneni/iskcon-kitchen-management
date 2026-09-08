"use client";

import { ErrorNotice } from "@/components/ErrorNotice";
import { FOOD_UNITS, unitLabel } from "@/lib/format";
import type { ApiError, CreateIngredientInput } from "@/lib/api";

const FIELD = "min-h-touch rounded-control border border-hairline px-3";

/**
 * The ingredient form (E10-S12). Presentational: it collects the fields and hands them up, and the
 * screen around it owns the API call, the navigation and the error.
 *
 * <p>Five fields with the observance flag, four without, so it is a screen rather than a panel
 * over the list — `DESIGN_SYSTEM.md`'s threshold is four. It has no button of its own, for the same
 * reason {@link RecipeForm} has none: the one place to commit is the focus screen's sticky header,
 * which reaches this form by name with `form={formId}`.
 */
export function IngredientForm({
  formId,
  isAdmin = false,
  busy,
  error,
  onSubmit,
}: {
  /** The id the screen's own commit button points at with `form={formId}`. */
  formId: string;
  /** Only an administrator may declare an ingredient Ekadashi-prohibited. */
  isAdmin?: boolean;
  busy: boolean;
  error: ApiError | null;
  onSubmit: (input: CreateIngredientInput) => void;
}) {
  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    onSubmit({
      name: String(f.get("name") ?? "").trim(),
      category: String(f.get("category") ?? "").trim(),
      unit: String(f.get("unit") ?? "KG"),
      // Stated explicitly even when the box is absent — an unticked checkbox puts no key in the
      // FormData at all (T-045). Leaving the field off the payload is how this flag came to be
      // unreachable in the first place: `CreateIngredientRequest` deserialises into a primitive
      // `boolean`, so a missing JSON key silently becomes `false`, the permissive answer, and
      // nothing anywhere says no. A grain would read as allowed on a fasting day.
      ekadashiProhibited: f.get("ekadashiProhibited") === "on",
      aliases: splitAliases(String(f.get("aliases") ?? "")),
    });
  }

  return (
    <>
      {error && <ErrorNotice error={error} />}

      <form
        id={formId}
        className="grid grid-cols-2 gap-4"
        aria-label="Add an ingredient"
        aria-busy={busy}
        onSubmit={submit}
      >
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Name</span>
          <input name="name" required className={FIELD} />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Category</span>
          <input name="category" required placeholder="Grains, Pulses, Spices…" className={FIELD} />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Unit</span>
          <select name="unit" className={FIELD}>
            {FOOD_UNITS.map((u) => (
              <option key={u} value={u}>
                {unitLabel(u)}
              </option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Aliases (comma-separated)</span>
          <input name="aliases" placeholder="Arhar Dal" className={FIELD} />
        </label>

        {/*
          The only observance flag on this form since D-18 removed the one it was built as the
          twin of (T-045). It is a fasting rule rather than a preference, so it is stated as a
          prohibition and names the staples it covers, which is how the rule reads to a cook.

          It matters more now than it did: D-18 also deleted the provisioning seed, so nothing
          arrives pre-flagged and this box — or the toggle on the list — is the only way any
          ingredient is ever marked. That is what the warning on `/recipes` is warning about.
        */}
        {isAdmin && (
          <label className="col-span-2 flex items-center gap-2 text-sm">
            <input name="ekadashiProhibited" type="checkbox" className="h-5 w-5 rounded-sm border-hairline-strong accent-accent" />
            <span>Ekadashi-prohibited (rice, wheat, dal, chickpeas…)</span>
          </label>
        )}
      </form>
    </>
  );
}

/** A comma-separated box as a list, with the empties dropped and the spaces trimmed. */
export function splitAliases(raw: string): string[] {
  return raw
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
}
