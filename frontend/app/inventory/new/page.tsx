"use client";

import { useCallback, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { api, toApiError, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * Track an item — four fields, which is exactly the threshold, so it is a screen.
 *
 * <p>Four is the line settled in Q1: at four fields a form becomes its own URL and at three it stays
 * inline. This form is on the line rather than over it, and it converts, because the rule is what
 * decides — not a judgement made form by form. The next field somebody adds here changes nothing.
 *
 * <p>Only ingredients that are not already tracked are offered. Tracking the same ingredient twice
 * would give the shelf two running totals for one thing, and the list on the other side of this
 * screen is where somebody would go to find out which of them was right.
 */

const FORM = "track-item";
const FIELD = "min-h-touch rounded border border-hairline bg-canvas px-3";

/** How a unit is written where a person reads it, rather than how it is stored. */
const UNIT_LABEL: Record<string, string> = { KG: "kg", GM: "gm", L: "L", ML: "ml", PIECES: "pieces" };

/**
 * The units a level may be typed in, per the unit the ingredient is actually kept in, and what one
 * of them is worth in that unit.
 *
 * <p>A store keeps rice in kilograms and asafoetida in grams, and the level somebody wants to be
 * warned at is naturally spoken in whichever of the two suits the quantity — "tell me at half a
 * kilo" for one, "at 50 grams" for the other. The figure is stored in the ingredient's own unit
 * either way, so this converts on the way in and nothing downstream has to know a choice was made.
 */
const ENTRY_UNITS: Record<string, { code: string; per: number }[]> = {
  KG: [{ code: "KG", per: 1 }, { code: "GM", per: 0.001 }],
  GM: [{ code: "GM", per: 1 }, { code: "KG", per: 1000 }],
  L: [{ code: "L", per: 1 }, { code: "ML", per: 0.001 }],
  ML: [{ code: "ML", per: 1 }, { code: "L", per: 1000 }],
  PIECES: [{ code: "PIECES", per: 1 }],
};

function entryUnits(unit: string) {
  return ENTRY_UNITS[unit] ?? [{ code: unit, per: 1 }];
}

export default function NewInventoryItemPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <NewInventoryItemView />
    </RequireRole>
  );
}

function NewInventoryItemView() {
  const { getToken } = useAuth();
  const router = useRouter();
  const { data: trackedData } = useAuthedQuery(useCallback((t: string | undefined) => api.listInventory({}, t), []));
  const { data: ingredientsData } = useAuthedQuery(api.listIngredients);

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const trackedIds = new Set((trackedData ?? []).map((i) => i.ingredientId));
  const untracked = (ingredientsData ?? []).filter((i) => !trackedIds.has(i.id));

  // Which ingredient is chosen, because the level below is meaningless without its unit: 100 of
  // rice is a sack or a handful depending on a word this form used not to say anywhere.
  const [ingredientId, setIngredientId] = useState("");
  const chosen = useMemo(
    () => untracked.find((i) => i.id === ingredientId),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [ingredientId, ingredientsData, trackedData]
  );
  const units = entryUnits(chosen?.unit ?? "KG");
  const [entryUnit, setEntryUnit] = useState<string | null>(null);
  const typedIn = units.find((u) => u.code === entryUnit) ?? units[0];

  async function add(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    const ingredientId = String(f.get("ingredientId") ?? "");
    const threshold = String(f.get("reorderThreshold") ?? "").trim();
    setBusy(true);
    setError(null);
    try {
      await api.createInventoryItem(
        {
          ingredientId,
          storageLocation: emptyToNull(String(f.get("storageLocation") ?? "")),
          // Stored in the ingredient's own unit, whichever one it was typed in.
          reorderThreshold: threshold === "" ? null : Number(threshold) * typedIn.per,
          notes: emptyToNull(String(f.get("notes") ?? "")),
        },
        await getToken()
      );
      const name = untracked.find((i) => i.id === ingredientId)?.name ?? "";
      router.push(`/inventory?tracking=${encodeURIComponent(name)}`);
    } catch (e) {
      setError(toApiError(e, "We couldn’t start tracking that item."));
      setBusy(false);
    }
  }

  return (
    <FocusScreen
      task="Track an item"
      who="A consumable this temple keeps on the shelf"
      activeHref="/inventory"
      actions={
        <>
          <ButtonLink href="/inventory" variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={FORM} disabled={busy || untracked.length === 0}>
            Start tracking
          </Button>
        </>
      }
    >
      {error && <ErrorNotice error={error} />}

      {untracked.length === 0 && <InlineNotice title="Every ingredient is already tracked." />}

      <form id={FORM} className="grid grid-cols-2 gap-4" aria-label="Track an item" onSubmit={add}>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Ingredient</span>
          <select
            name="ingredientId"
            required
            className={FIELD}
            value={ingredientId}
            onChange={(e) => {
              setIngredientId(e.target.value);
              // The unit choice belonged to the last ingredient; a new one brings its own.
              setEntryUnit(null);
            }}
          >
            <option value="">Choose an ingredient…</option>
            {untracked.map((i) => (
              <option key={i.id} value={i.id}>
                {i.name} — kept in {UNIT_LABEL[i.unit] ?? i.unit}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Storage location</span>
          <input name="storageLocation" placeholder="Main store, cold room…" className={FIELD} />
        </label>
        {/*
          "Reorder threshold" said nothing about which of the two numbers on this screen it was, and
          in a unit it never named: the first person to use it read it as how much they had on the
          shelf, typed 100, and got an item claiming 652 kg on hand with a warning level of 100. On
          hand is not something this form can be told — stock is the sum of the ledger, and the only
          thing a temple decides here is when it wants to hear about it. So the label says that, and
          the unit is on the field rather than in a sentence underneath it.
        */}
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Tell me when stock drops below</span>
          <div className="flex gap-2">
            <input
              name="reorderThreshold"
              type="number"
              min="0"
              step="any"
              placeholder="e.g. 5"
              className={`${FIELD} min-w-0 flex-1`}
            />
            {units.length > 1 ? (
              <select
                aria-label="Unit"
                className={FIELD}
                value={typedIn.code}
                onChange={(e) => setEntryUnit(e.target.value)}
              >
                {units.map((u) => (
                  <option key={u.code} value={u.code}>
                    {UNIT_LABEL[u.code] ?? u.code}
                  </option>
                ))}
              </select>
            ) : (
              <span className="flex min-h-touch items-center rounded border border-hairline bg-sunken px-3 text-ink-secondary">
                {UNIT_LABEL[typedIn.code] ?? typedIn.code}
              </span>
            )}
          </div>
          <span className="pl-field-inset text-sm text-ink-secondary">
            {chosen
              ? `We’ll flag ${chosen.name} as low below this. Leave it blank for no warning.`
              : "Leave it blank if you’d rather not be warned."}
          </span>
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Notes</span>
          <input name="notes" className={FIELD} />
        </label>
      </form>
    </FocusScreen>
  );
}

function emptyToNull(s: string): string | null {
  const t = s.trim();
  return t === "" ? null : t;
}
