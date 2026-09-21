"use client";

import { ErrorNotice } from "@/components/ErrorNotice";
import { Form } from "@/components/ds/Form";
import { NOT_BOUGHT_LABEL } from "@/components/ingredient/IngredientFacts";
import { FOOD_UNITS, unitLabel } from "@/lib/format";
import type { ApiError, CreateIngredientInput, IngredientView } from "@/lib/api";

const FIELD = "min-h-touch rounded-control border border-hairline px-3";

/**
 * The ingredient form (E10-S12). Presentational: it collects the fields and hands them up, and the
 * screen around it owns the API call, the navigation and the error.
 *
 * <p>One form for both halves of the catalogue since T-089 — `/ingredients/new` and
 * `/supplies/new` — because they are the same six fields against the same endpoint, and the only
 * thing that differs is the answer to a question the form no longer asks. See {@code kind}.
 *
 * <p>Well over `DESIGN_SYSTEM.md`'s four-field threshold, so it is a screen rather than a panel over
 * the list. It has no button of its own, for the same reason {@link RecipeForm} has none: the one
 * place to commit is the focus screen's sticky header, which reaches this form by name with
 * `form={formId}`.
 */
export function IngredientForm({
  formId,
  kind = "FOOD",
  ingredient,
  isAdmin = false,
  canMarkNotBought = false,
  busy,
  error,
  onSubmit,
}: {
  /** The id the screen's own commit button points at with `form={formId}`. */
  formId: string;
  /**
   * The row being changed, on `/ingredients/[id]/edit`; absent when something is being added (T-441).
   *
   * <p>One form for adding and for changing, and the reason is Rajeev's instruction of 2026-09-20 —
   * the editing row on the list is gone, "the same pattern should be applied" everywhere, and the
   * fields the row used to offer (aliases, unit, the Ekadashi flag, category) have to be somewhere.
   * Two forms would have been two sets of labels asking the same four questions, and they would have
   * drifted the first time one was reworded.
   *
   * <p>Defaults rather than controlled values, which is what lets one component do both: every box
   * here is uncontrolled and read out of the `FormData` on submit, so seeding it is a `defaultValue`
   * and nothing else about the component changes. The screen above still owns the call, the error and
   * where Save goes.
   */
  ingredient?: IngredientView;
  /**
   * Which half of the catalogue this form is adding to (T-089).
   *
   * <p>It used to be a checkbox on the form — "This is a supply, not food" — and Rajeev's ruling
   * of 2026-09-08 replaced the checkbox with two menu items: a person who wants to catalogue a
   * cylinder of LPG goes to Supplies and adds it there. So the answer is settled by the screen the
   * person chose before they started typing, and the form states it rather than asking it.
   *
   * <p>The value still travels on the payload explicitly in both directions, which is the part that
   * matters and the part that was got wrong once already: `CreateIngredientRequest.supply` is a
   * primitive `boolean` on the server, so a key left off the JSON silently deserialises to `false`
   * — food — and food is what reaches the recipe picker.
   */
  kind?: "FOOD" | "SUPPLY";
  /** Only an administrator may declare an ingredient Ekadashi-prohibited. */
  isAdmin?: boolean;
  /**
   * Whether this role holds `MANAGE_BUYING_POLICY` and may say the temple never buys the thing
   * (T-402). A separate prop from `isAdmin` beside it although both resolve to the Temple Admin
   * today, because they are separate grants in `RolePermissions.java` and either may move: a screen
   * that read one permission off the other would go on hiding the right control for the wrong
   * reason. `/ingredients/new` reads it from `components/ingredient/access.tsx`; `/supplies/new`
   * never passes it, because a supply is always bought.
   */
  canMarkNotBought?: boolean;
  busy: boolean;
  error: ApiError | null;
  onSubmit: (input: CreateIngredientInput) => void;
}) {
  const supply = kind === "SUPPLY";
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
      // Same reasoning, and a worse failure if it is skipped: `false` here means food, and food is
      // the thing that reaches the recipe picker. A leaf plate whose key never left the form would
      // be offered as an ingredient of a dish (D-1). Read from the prop rather than from a box
      // since T-089 — see `kind` above — and still written out on every submission either way.
      supply,
      // Same reasoning again, and the failure is the one Rajeev asked this flag to prevent: the
      // server field is a primitive, so a missing key reads as `false` — the temple buys it — and
      // water is back on the order with nobody told. Read from the box where there is one, and
      // stated as `false` where there is not, rather than left off the payload.
      notBought: f.get("notBought") === "on",
      aliases: splitAliases(String(f.get("aliases") ?? "")),
    });
  }

  return (
    <>
      {error && <ErrorNotice error={error} />}

      <Form
        id={formId}
        className="grid grid-cols-2 gap-4"
        /*
          Named for what pressing the button will do, which is the one thing that differs between
          the two uses of this form. Tests and screen readers both reach it by this name.
        */
        aria-label={
          ingredient
            ? supply
              ? "Edit supply"
              : "Edit ingredient"
            : supply
              ? "Add a supply"
              : "Add an ingredient"
        }
        aria-busy={busy}
        onSubmit={submit}
      >
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Name</span>
          <input name="name" required defaultValue={ingredient?.name} className={FIELD} />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Category</span>
          {/*
            Free text on both halves, and the placeholder is the only thing that changes. The
            category column has been free text since V10 on purpose — "temples add their own, and a
            constraint would make each a migration" — and that argument is stronger for supplies,
            where one temple's Fuel is another's Gas.
          */}
          <input
            name="category"
            required
            defaultValue={ingredient?.category}
            placeholder={supply ? "Fuel, Cleaning, Disposables…" : "Grains, Pulses, Spices…"}
            className={FIELD}
          />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Unit</span>
          <select name="unit" defaultValue={ingredient?.unit} className={FIELD}>
            {FOOD_UNITS.map((u) => (
              <option key={u} value={u}>
                {unitLabel(u)}
              </option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Aliases (comma-separated)</span>
          {/* The stored list back as the comma-separated line it was typed as. `splitAliases` below
              is the exact inverse, so opening this form and saving it unchanged is a no-op. */}
          <input
            name="aliases"
            defaultValue={ingredient?.aliases.join(", ")}
            placeholder="Arhar Dal"
            className={FIELD}
          />
        </label>

        {/*
          The only observance flag on this form since D-18 removed the one it was built as the
          twin of (T-045). It is a fasting rule rather than a preference, so it is stated as a
          prohibition and names the staples it covers, which is how the rule reads to a cook.

          It matters more now than it did: D-18 also deleted the provisioning seed, so nothing
          arrives pre-flagged and this box — or the toggle on the list — is the only way any
          ingredient is ever marked. That is what the warning on `/recipes` is warning about.
        */}
        {/*
          There was a checkbox here — "This is a supply, not food (LPG, leaf plates, soap…)" — and
          T-089 removed it rather than moving it. Two screens now ask the question by existing, so
          asking it again on the form would let a person standing on /supplies/new add a bag of rice
          that then never appears on the screen they added it from.

          Still no separate permission either way, and D-1's reason holds: anyone who may add an
          ingredient may add a supply, because saying a thing is a mop is a fact about the thing
          rather than the religious ruling MANAGE_DIETARY_POLICY guards below.

          What a supply gets instead is a line saying where the line is drawn, because the rule is
          not obvious and it is Rajeev's rather than anybody's intuition: a broom is used up by
          sweeping and a stool is not, so the broom is here and the stool is under Equipment,
          however cheap and however breakable it is.
        */}
        {/* Only while something is being added. On a supply that was catalogued months ago the rule
            has already been applied, and repeating it over an edit reads as a challenge to a
            decision somebody already made. */}
        {supply && !ingredient && (
          <p className="col-span-2 text-sm text-ink-secondary">
            Anything the temple uses up that is not food. Things it keeps and re-uses — stools,
            ladders, extension boxes — belong under Equipment.
          </p>
        )}

        {/*
          Absent on a supply, and absent rather than disabled. Ekadashi says what a fasting rule
          makes of a food, and there is no fasting rule about dishwashing liquid — a greyed-out box
          would imply there is one and that this account may not reach it.
        */}
        {!supply && isAdmin && (
          <label className="col-span-2 flex items-center gap-2 text-sm">
            {/* Seeded from the row when one is being changed, or Save would quietly un-prohibit
                every prohibited ingredient anybody opened. The list's editing row had the same
                hazard and solved it the same way. */}
            <input
              name="ekadashiProhibited"
              type="checkbox"
              defaultChecked={ingredient?.ekadashiProhibited}
              className="h-5 w-5 rounded-sm border-hairline-strong accent-accent"
            />
            <span>Ekadashi-prohibited (rice, wheat, dal, chickpeas…)</span>
          </label>
        )}

        {/*
          T-402. Absent for a supply and absent for anyone without MANAGE_BUYING_POLICY, both times
          absent rather than disabled — the same call the Ekadashi box above makes, for the same two
          reasons. A supply is bought by definition (LPG and leaf plates come from a vendor, which is
          the whole of D-1), so a greyed box would imply the question applies here and this account
          may not answer it; and a control nobody on this screen can use is a control to leave out.

          Sits under the Ekadashi box because the two are the same kind of thing — a standing fact
          about the ingredient rather than a word describing it — and because a person adding water
          reads down the form and meets the buying question last, after they have said what it is.
        */}
        {!supply && canMarkNotBought && (
          <label className="col-span-2 flex items-center gap-2 text-sm">
            {/* Seeded for the same reason the box above it is, although the edit screen does not
                offer this one: `UpdateIngredientInput` has no `notBought` key at all, and the flag
                is set on the ingredient's own page through a route of its own that is audited. */}
            <input
              name="notBought"
              type="checkbox"
              defaultChecked={ingredient?.notBought}
              className="h-5 w-5 rounded-sm border-hairline-strong accent-accent"
            />
            <span>{NOT_BOUGHT_LABEL}</span>
          </label>
        )}
      </Form>
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
