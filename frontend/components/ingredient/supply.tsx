"use client";

import {
  convertQuantity,
  FOOD_UNITS,
  money,
  ratePackWord,
  rateUnit,
  readablePackRate,
  readableRate,
  unitLabel,
  unitLabelFor,
} from "@/lib/format";
import type { IngredientView, PackSizeView, SetVendorSupplyInput, VendorSupplyView } from "@/lib/api";

/*
  The supply vocabulary the ingredient page shares with the vendor page (T-286).

  **Copied, not imported, and the copy is deliberate for now.** Every helper below is the vendor page's
  own (`app/vendors/[id]/page.tsx`), word for word in what it prints and what it sends: R-ING-2 says the
  ingredient page creates "the same vendor_supplies row as the vendor page", and the conductor ruled on
  2026-09-19 that it shows the same columns, the same labels and the same derived "₹1,500 / bag · ₹60 /
  Kg". They could not be imported because a `page.tsx` in the App Router may export nothing but its
  default (an extra export type-checks and then fails `next build`), and moving them out of that page
  was outside this task's files while another task had it open. The tests in
  `__tests__/ingredient-detail.test.tsx` pin the body and the words to the vendor page's, so the two
  copies cannot drift without a test going red. Folding the vendor page onto this file is a one-commit
  follow-up, flagged in docs/work/proof/T-286.md.

  T-293 did that for the price words only: the vendor page now imports `listPriceText` and
  `perUnitPrice` from here, and both are built on `lib/format.ts`. The rest is still a copy.
*/

/** Blank is null and zero is zero — see the vendor page's `numberOrNull` for the two ways to get it wrong. */
export function numberOrNull(raw: string): number | null {
  const t = raw.trim();
  return t === "" ? null : Number(t);
}

/** A stored value as a number box holds it — and `0` is "0", never "". */
export function boxValue(n: number | null): string {
  return n === null ? "" : String(n);
}

export function emptyToNull(s: string): string | null {
  const t = s.trim();
  return t === "" ? null : t;
}

/** The same words wherever a lead time is typed, because it is the same question (the vendor page's). */
export const LEAD_TIME_HINT =
  "How long this vendor takes to deliver this item once you ask. Leave it blank if you don’t know — we’ll assume two days until somebody records it. Put 0 for a shop you walk into and carry it back from.";

/** The "Sells it as" value for the stock unit itself: no pack. */
export const STOCK_UNIT = "";

/** The one box look on entry rows, as the mocks draw it: 44px, the control corner. */
export const BOX = "min-h-touch rounded-control border border-hairline bg-canvas px-3";

/**
 * What the server is sent for one supply — the vendor page's `supplyInput`, exactly. A price sold in a
 * pack goes as `pricePerPack` only with `lastPrice` null (the server refuses both, KMS-400163); without
 * a pack it is the per-unit list price and both pack keys go as null. All six keys, always: the server
 * writes the whole row, so a key left out is a column erased.
 */
export function supplyInput(
  ingredientId: string,
  e: { pack: string; price: string; lead: string; preferred: boolean }
): SetVendorSupplyInput {
  const price = numberOrNull(e.price);
  const packed = e.pack !== STOCK_UNIT;
  return {
    ingredientId,
    lastPrice: packed ? null : price,
    leadTimeDays: numberOrNull(e.lead),
    preferred: e.preferred,
    packSizeId: packed ? e.pack : null,
    pricePerPack: packed ? price : null,
  };
}

/*
  How a price is SAID lives in `lib/format.ts` (T-293): `readableRate` / `readablePackRate` for the
  words, `rateUnit` for the unit a price is said per, `ratePackWord` for a pack's word. This file used
  to carry its own `sayPriceIn`, `perUnitText` and `packWord`, copied from the vendor page; the merge
  screen, the invoice and the shopping list each had a third and fourth copy that had drifted
  ("₹0.30/gm" beside "₹300 / Kg" for one price, VERIFY-A defect 3). What stays here is only the
  arithmetic for the price BOX, which is typed per Kg and stored per gram.
*/

/** A price per stock unit, re-expressed per the unit it is said in ({@link rateUnit}): ₹0.06 a gram is 60. */
export function perUnitPrice(price: number, unit: string): number {
  return price * (convertQuantity(1, rateUnit(unit), unit) ?? 1);
}

/** The reverse: a price typed per the said unit, back into the ingredient's stock unit for the server. */
export function perStockUnit(typed: number, unit: string): number {
  return typed * (convertQuantity(1, unit, rateUnit(unit)) ?? 1);
}

/** The List price as the supplies table prints it: "₹1,500 / bag · ₹60 / Kg", or "₹60 / Kg", or "—". */
export function listPriceText(s: VendorSupplyView): string {
  const perPack = s.packLabel ? s.pricePerPack : null;
  return readablePackRate(perPack, s.packLabel ?? "", s.lastPrice, s.unit) ?? "—";
}

/**
 * The previous price's figure for the trend marker beside a supply's List price (VERIFY2-A, N2),
 * handed to {@link PriceTrend}'s `format`: the old price in the SAME unit as the price it sits beside.
 *
 * <p>Before this, the tip was always the old price per Kg. Beside "₹1,600 / bag · ₹64 / Kg" it read
 * "₹60 on 19 Sept", and a bare ₹60 next to a price that leads with the bag reads as ₹60 a bag. So a
 * supply sold in a pack gives the old price per pack, "₹1,500 on 19 Sept", and one sold by the unit the
 * old price per Kg (or per L), "₹60 on 19 Sept", as the unit price is said in the row.
 *
 * <p>The words stay "₹<amount> on <date>", with no unit, because R-VEN-3's acceptance check fixes
 * them that way; the conductor ruled on 2026-09-19 that N2 is about the amount's unit, not the words.
 *
 * <p>The history keeps the old price per stock unit only (`previousPrice`), so the old pack price is
 * that times the pack the supply is sold in NOW. That is the fair comparison for the arrow, which is
 * about the price per unit: a vendor who moved a 25 Kg bag to a 30 Kg bag is shown what the old rate
 * comes to on today's bag. It is rounded to paise, as `readableRate` rounds, so float dust never prints
 * as "₹1,499.99". Where the pack is not among `packs` (not loaded yet), the tip falls back to the old
 * price per Kg, the second figure in the row.
 */
export function previousPriceText(
  s: VendorSupplyView,
  packs: PackSizeView[] | undefined
): (previousPerUnit: number) => string {
  const pack = s.packSizeId && s.packLabel ? packs?.find((p) => p.id === s.packSizeId) : undefined;
  return (previous) => {
    const amount = pack ? previous * pack.baseQuantity : perUnitPrice(previous, s.unit);
    return money(Math.round(amount * 100) / 100, "INR");
  };
}

/** The units a new pack of this ingredient can be measured in: its own family only (R-ING-1). */
export function sameFamilyUnits(unit: string): string[] {
  return FOOD_UNITS.filter((u) => convertQuantity(1, u, unit) !== null);
}

/**
 * "Preferred (replaces Anand Stores)" (R-VEN-2), neutral and wrapping, as on the vendor page: moving a
 * preference is a normal thing to do, so it is information rather than amber, and a long vendor name
 * wraps rather than widening the row past a phone.
 */
export function ReplacesText({ vendorName, id }: { vendorName: string; id?: string }) {
  return (
    <span id={id} className="min-w-0 max-w-56 whitespace-normal text-sm text-ink-secondary">
      Preferred (replaces {vendorName})
    </span>
  );
}

/** "Sells it as": the stock unit, then each of the ingredient's pack sizes. */
export function SellsAsSelect({
  ingredient,
  value,
  onChange,
  label,
  id,
}: {
  ingredient: IngredientView;
  value: string;
  onChange: (pack: string) => void;
  label?: string;
  id?: string;
}) {
  return (
    <select id={id} aria-label={label} value={value} onChange={(e) => onChange(e.target.value)} className={BOX}>
      <option value={STOCK_UNIT}>{unitLabel(ingredient.unit)}</option>
      {ingredient.packSizes.map((p) => <option key={p.id} value={p.id}>{p.label}</option>)}
    </select>
  );
}

/**
 * The List price box, the unit it is typed per beside it ("/ bag", "/ Kg"), and — when typed per pack —
 * what that comes to per stock unit underneath ("= ₹60 / Kg"). Never a second box (R-VEN-1).
 */
export function PriceBox({
  value,
  onChange,
  unit,
  pack,
  label,
  id,
}: {
  value: string;
  onChange: (v: string) => void;
  unit: string;
  pack: PackSizeView | undefined;
  label?: string;
  id?: string;
}) {
  const price = numberOrNull(value);
  const derived = pack && price !== null && pack.baseQuantity > 0 ? readableRate(price / pack.baseQuantity, unit) : null;
  return (
    <span className="flex flex-col gap-1">
      <span className="flex items-center gap-2">
        <input
          id={id}
          aria-label={label}
          type="number"
          inputMode="decimal"
          min="0"
          step="any"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className={`${BOX} w-28 min-w-24 tabular-nums`}
        />
        <span className="whitespace-nowrap text-ink-secondary">/ {pack ? ratePackWord(pack.label) : unitLabelFor(1, unit)}</span>
      </span>
      {derived && <span className="pl-field-inset text-xs tabular-nums text-ink-secondary">= {derived}</span>}
    </span>
  );
}
