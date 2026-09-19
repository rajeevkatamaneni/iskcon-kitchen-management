"use client";

import { useState } from "react";
import { Button } from "@/components/ds/Button";
import { Form } from "@/components/ds/Form";
import { InfoHint } from "@/components/ds/InfoHint";
import { ErrorNotice } from "@/components/ErrorNotice";
import { api, toApiError, type ApiError, type IngredientView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { unitLabel } from "@/lib/format";
import { BOX, emptyToNull, numberOrNull, sameFamilyUnits } from "@/components/ingredient/supply";

/** R-ING-1's hint, word for word. */
export const PACK_SIZES_HINT = "The pack sizes vendors sell this in. The shopping list suggests whole packs.";

/**
 * The ingredient's pack sizes (R-ING-1): chips, each removable, and a row of fields to add one.
 *
 * <p><strong>The chips print the server's own label</strong> ("250 gm", "Bag = 25 Kg"), not one worked
 * out here. `PackSizeService.label` writes it once for every screen — the vendor page's Sells it as, the
 * invoice's pack picker, the shopping list — so this page cannot say a pack differently from them.
 * They are read in the order the server sends, smallest first.
 *
 * <p><strong>The rules are the server's, and its words are shown.</strong> Positive, same family, no
 * duplicate, at most eight: `PackSizeService` enforces all four and answers each with a permanent code
 * (KMS-400157 duplicate, KMS-400013 other family, KMS-400158 a ninth, KMS-400159 still in use on removal).
 * The one check made here is that a size was typed and is more than nought, because that needs no
 * round trip. The unit list offers only the ingredient's own family, so the family refusal is reachable
 * only by a raw request — it is still shown if it comes.
 *
 * <p>Removing a pack that a vendor sells in, or that an order used, is refused by the server rather
 * than guessed at here; the refusal says why and appears under the chips.
 */
export function PackSizes({
  ingredient,
  canEdit,
  onChanged,
}: {
  ingredient: IngredientView;
  /** Holds MANAGE_RECIPES, which both pack-size endpoints declare. */
  canEdit: boolean;
  onChanged: () => void;
}) {
  const { getToken } = useAuth();
  const units = sameFamilyUnits(ingredient.unit);
  const [name, setName] = useState("");
  const [size, setSize] = useState("");
  const [unit, setUnit] = useState(ingredient.unit);
  const [busy, setBusy] = useState(false);
  const [removing, setRemoving] = useState<string | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  /*
    A refusal belongs to the entry that caused it (VERIFY2-A, N1). Before this, the server's refusal
    was cleared only once a press got past the size check, so a duplicate refused with KMS-400157 and
    then a Box of 0 showed "Size must be more than 0" with the duplicate's box still under it, as
    though both were true of a 0 that is no size at all. So, as on the vendor page's own pack row
    (T-292): any change to the three fields clears it, and every press of Add pack size clears it
    first. A successful add clears it by the same press. A refusal of the size is made by `Form`
    before that press reaches here (T-343), and it can only follow a change to the size box, which
    has already cleared the server's, so it too stands alone.
  */
  function edited() {
    setError(null);
  }

  async function add(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    const quantity = numberOrNull(size);
    // The twin guard only: `Form` has already refused an empty, nought or negative size in words
    // beside the box and not called this (see the Size box below).
    if (quantity === null || !(quantity > 0)) return;
    setBusy(true);
    try {
      await api.addPackSize(ingredient.id, { name: emptyToNull(name), quantity, unit }, await getToken());
      setName("");
      setSize("");
      onChanged();
    } catch (e) {
      setError(toApiError(e, "We couldn’t add that pack size."));
    } finally {
      setBusy(false);
    }
  }

  async function remove(packSizeId: string) {
    setRemoving(packSizeId);
    setError(null);
    try {
      await api.removePackSize(ingredient.id, packSizeId, await getToken());
      onChanged();
    } catch (e) {
      setError(toApiError(e, "We couldn’t remove that pack size."));
    } finally {
      setRemoving(null);
    }
  }

  const packs = ingredient.packSizes;
  return (
    <section className="card px-6 py-5" aria-labelledby="pack-sizes-heading">
      {/* The section is named by the words alone, not by the heading, which would take the hint
          button's "i" into its name ("Pack sizes i"). */}
      <h2 className="flex items-center gap-1.5 text-lg font-semibold text-ink">
        <span id="pack-sizes-heading">Pack sizes</span>
        <InfoHint text={PACK_SIZES_HINT} label="Pack sizes" />
      </h2>

      {packs.length === 0 ? (
        <p className="mt-3 text-sm text-ink-secondary">
          No pack sizes yet.
        </p>
      ) : (
        <ul className="mt-3 flex flex-wrap gap-2" aria-label="Pack sizes">
          {packs.map((p) => (
            <li
              key={p.id}
              className="inline-flex min-h-9 items-center gap-1 rounded-control border border-hairline bg-sunken pl-3 pr-1 text-sm tabular-nums text-ink"
            >
              <span className="whitespace-nowrap">{p.label}</span>
              {canEdit ? (
                <button
                  type="button"
                  aria-label={`Remove ${p.label}`}
                  disabled={removing !== null}
                  onClick={() => remove(p.id)}
                  // 28px drawn, 44px to touch through the `before` layer, as PriceTrend does it.
                  className="relative inline-flex h-7 w-7 items-center justify-center rounded-control text-ink-secondary transition-colors duration-state before:absolute before:-inset-2 before:content-[''] hover:bg-raised hover:text-ink disabled:opacity-60"
                >
                  <i className="ti ti-x text-base" aria-hidden="true" />
                </button>
              ) : (
                <span className="pr-2" />
              )}
            </li>
          ))}
        </ul>
      )}

      {canEdit && (
        <Form className="mt-5" aria-label="Add a pack size" onSubmit={add}>
          {/* Three fields and the button on one line from a tablet up; they wrap only on a phone. */}
          <div className="flex flex-wrap items-end gap-4">
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">Pack name (optional)</span>
              <input value={name} onChange={(e) => { setName(e.target.value); edited(); }} placeholder="Bag" className={`${BOX} w-40`} />
            </label>
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">Size</span>
              {/*
                One rule for the size, said once, by `Form` (VERIFY3, A3-1). This box used to carry
                `min="0"`, which `Form` reads as "Size must be at least 0", and the page kept a
                sentence of its own, "Size must be more than 0", under the row. Typing 0 and pressing
                Add showed the page's; typing -5 over it and pressing again showed `Form`'s as well,
                while the page's stayed: two rules for one box, contradicting each other. Now
                `data-more-than="0"` is the one floor, so 0 and -5 both read "Size must be more than
                0", an empty box reads "Size is required", and `Form` re-reads the box on every
                keystroke, clearing the sentence once the size is right or changing it when it is
                wrong in a new way. The same pairing as the credit on a bill (T-203).
              */}
              <input
                type="number"
                inputMode="decimal"
                required
                data-more-than="0"
                step="any"
                value={size}
                onChange={(e) => { setSize(e.target.value); edited(); }}
                className={`${BOX} w-28 tabular-nums`}
              />
            </label>
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">Unit</span>
              <select value={unit} onChange={(e) => { setUnit(e.target.value); edited(); }} className={BOX}>
                {units.map((u) => <option key={u} value={u}>{unitLabel(u)}</option>)}
              </select>
            </label>
            {/* Clears the server's refusal on the press itself, before `Form` checks anything: a
                press refused for its size never reaches `add`, and the refusal it followed (a
                removal refused with the Size box still empty) would otherwise stay on screen beside
                the new one. Enter in a box presses this button too, so it covers that as well. */}
            <Button type="submit" variant="secondary" busy={busy} onClick={edited}>Add pack size</Button>
          </div>
        </Form>
      )}

      {error && <div className="mt-4"><ErrorNotice error={error} /></div>}
    </section>
  );
}
