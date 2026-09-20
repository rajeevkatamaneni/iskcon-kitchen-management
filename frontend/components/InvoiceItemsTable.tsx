"use client";

import { Fragment, useState } from "react";

import { ErrorNotice } from "@/components/ErrorNotice";
import { ItemCombobox, type ComboChoice, type ComboItem } from "@/components/ItemCombobox";
import { Button } from "@/components/ds/Button";
import { TABLE, ENTRY_GRID, THEAD, TR, TH_TEXT, TH_NUM, TD_TEXT, TD_NUM, WRAP } from "@/components/ds/table";
import { api, toApiError, type ApiError, type InvoiceLineInput, type IngredientView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import {
  FOOD_UNITS,
  convertQuantity,
  entryQuantity,
  quantity,
  readablePackRate,
  readableRate,
  inputModeForUnit,
  isCountedUnit,
  repeatsPack,
  stepForUnit,
  unitLabel,
} from "@/lib/format";

/**
 * The items on a bill being typed in (R-INV-3, R-INV-4; design A of the `dev-invoices` mock; T-273).
 *
 * <p><b>Two shapes, as the mock has them.</b> Billing deliveries, the lines are pulled from those
 * deliveries and are locked: no row is added and none removed, because a bill for a delivery is a
 * bill for what came (R-INV-3). The columns are Item · Ordered · Delivered · Billed qty · Amount (₹)
 * · Rate (R-INV-4). A direct invoice has no delivery behind it, so it has no Ordered or Delivered:
 * Item · Billed qty · Amount (₹) · Rate, a Remove on each row, and last of all an empty row holding
 * the same type-ahead as a purchase order (R-PO-3's {@link ItemCombobox}), one-off items included.
 * The mock's direct table had a plain text box per row and an "Add an item" button; R-INV-3 asks for
 * the purchase order's combobox, and the purchase order's always-one-empty-row is how that works.
 *
 * <p><b>Billed qty defaults to what was delivered</b>, and an item not billed is set to 0 (R-INV-4).
 * Billed more than was delivered, the line says so in amber, "Billed 50 Kg, 45 Kg delivered": a
 * warning, not a refusal, because the vendor's bill is what the temple owes until somebody takes it
 * up with them.
 *
 * <p><b>Units and packs.</b> Every quantity the API takes is in the line's stock unit
 * (`InvoiceLineInput.billedQty`), and every quantity shown is readable, Kg from 1,000 gm up (§1).
 * The box beside Billed qty says what the figure in it is counted in, and it can be changed:
 * <ul>
 *   <li>the stock unit's family (Kg or gm, L or ml, pieces),</li>
 *   <li>each of the ingredient's pack sizes, "× Bag (25 Kg)", and the pack the order used, which is
 *       the default for a line ordered in packs (R-INV-4: "billed in the unit the order used"),</li>
 *   <li>and last, "Add a pack size…", which opens the vendor page's own small pack fields under the
 *       row. The pack is saved on the ingredient (`api.addPackSize`, R-ING-1) and then chosen. A bill
 *       in a unit the ingredient does not know is therefore never saved: the only way to bill in a
 *       new unit is to make it a pack of the ingredient first (conductor's ruling, 2026-09-19).</li>
 * </ul>
 * Switching the unit keeps the amount the box stands for, so 45 Kg becomes 1.8 × Bag (25 Kg), not 45
 * bags.
 *
 * <p><b>The rate is worked out, never typed</b>: Amount ÷ Billed qty, "₹100 / Kg", following the
 * unit the quantity is shown in (conductor's ruling for the purchase order, 2026-09-19, applied here
 * too). A pack line says it per pack and per stock unit, "₹1,500 / bag · ₹60 / Kg", the same words
 * the vendor page uses for a list price.
 */

/** A pack a line can be billed in, with its size said in the line's stock unit. */
export interface LinePack {
  id: string;
  /** "Bag (25 Kg)", or "500 gm" for a pack with no name. */
  label: string;
  /** How a rate is said per it: "bag", or the label itself for a pack with no name. */
  word: string;
  /** How much of the line's stock unit one pack holds. */
  size: number;
}

/** One line as it is being typed. Quantity and amount are the text in their boxes. */
export interface InvoiceLineDraft {
  key: string;
  /** Set on a line pulled from a delivery; such a line is locked. */
  goodsReceiptLineId: string | null;
  /** Null on a one-off, which travels as `description` instead. */
  ingredientId: string | null;
  name: string;
  oneOff: boolean;
  /** The unit the API is sent `billedQty` in: the ingredient's, or the one-off's chosen unit. */
  stockUnit: string;
  orderedQty: number | null;
  deliveredQty: number | null;
  /** The pack the order line was in, when it was in one. */
  orderPack: LinePack | null;
  /** What the Billed qty box counts in: a unit code ("KG"), or {@link PACK} + a pack's id. */
  billedIn: string;
  qty: string;
  amount: string;
}

/** Marks a `billedIn` that is a pack rather than a unit. */
export const PACK = "pack:";
const ADD_PACK = "__add_pack__";

/** A box's figure, or null when it is empty or not a number. */
export function num(text: string): number | null {
  if (text.trim() === "") return null;
  const n = Number(text);
  return Number.isFinite(n) ? n : null;
}

/** toFixed clears float dust: 1.8 × 25 must be 45, not 45.00000000000001. */
const clean = (n: number, places = 6) => Number(n.toFixed(places));
const count = (n: number) => new Intl.NumberFormat("en-IN", { maximumFractionDigits: 3 }).format(n);

/** An ingredient's pack sizes, sized in `stockUnit` and labelled the way the invoice says them. */
export function packsOf(ingredient: IngredientView | undefined, stockUnit: string): LinePack[] {
  return (ingredient?.packSizes ?? []).flatMap((p) => {
    const size = convertQuantity(p.quantity, p.unit, stockUnit);
    if (size == null || size <= 0) return [];
    const said = quantity(p.quantity, p.unit);
    return [{ id: p.id, label: p.name ? `${p.name} (${said})` : said, word: p.name ? p.name.toLowerCase() : said, size }];
  });
}

/** The pack the order used, from a delivery line's `packLabel` ("Bag (25 Kg)") and size. */
export function orderPackOf(id: string | null, label: string | null, size: number | null): LinePack | null {
  if (!id || !label || !size || size <= 0) return null;
  const at = label.indexOf(" (");
  return { id, label, word: at > 0 ? label.slice(0, at).toLowerCase() : label, size };
}

/** Every pack this line may be billed in: the ingredient's, and the order's if that one is not among them. */
export function linePacks(line: InvoiceLineDraft, ingredient: IngredientView | undefined): LinePack[] {
  const packs = packsOf(ingredient, line.stockUnit);
  if (line.orderPack && !packs.some((p) => p.id === line.orderPack!.id)) packs.unshift(line.orderPack);
  return packs;
}

function packIn(line: InvoiceLineDraft, packs: LinePack[]): LinePack | null {
  if (!line.billedIn.startsWith(PACK)) return null;
  const id = line.billedIn.slice(PACK.length);
  return packs.find((p) => p.id === id) ?? (line.orderPack?.id === id ? line.orderPack : null);
}

/** What the Billed qty box stands for in the line's stock unit, or null while it is empty. */
export function billedStock(line: InvoiceLineDraft, packs: LinePack[]): number | null {
  const q = num(line.qty);
  if (q == null) return null;
  const pack = packIn(line, packs);
  if (pack) return clean(q * pack.size);
  const c = convertQuantity(q, line.billedIn, line.stockUnit);
  return c == null ? q : clean(c);
}

/** The same stock amount, as a figure for the box when it counts in `billedIn`. */
function inBox(stock: number, billedIn: string, stockUnit: string, packs: LinePack[]): string {
  if (billedIn.startsWith(PACK)) {
    const pack = packs.find((p) => PACK + p.id === billedIn);
    return pack ? String(clean(stock / pack.size, 3)) : String(stock);
  }
  const c = convertQuantity(stock, stockUnit, billedIn);
  return String(c == null ? stock : clean(c));
}

/**
 * A line pulled from a delivery, before anything is typed: billed in the pack the order used, else in
 * the readable unit of what was delivered, and billed at what was delivered (R-INV-4).
 */
export function fromDelivery(
  key: string,
  l: {
    goodsReceiptLineId: string;
    ingredientId: string;
    itemName: string;
    orderedQty: number;
    deliveredQty: number;
    unit: string;
    packSizeId: string | null;
    packLabel: string | null;
    packQuantity: number | null;
  },
): InvoiceLineDraft {
  const orderPack = orderPackOf(l.packSizeId, l.packLabel, l.packQuantity);
  const shown = entryQuantity(l.deliveredQty, l.unit);
  return {
    key,
    goodsReceiptLineId: l.goodsReceiptLineId,
    ingredientId: l.ingredientId,
    name: l.itemName,
    oneOff: false,
    stockUnit: l.unit,
    orderedQty: l.orderedQty,
    deliveredQty: l.deliveredQty,
    orderPack,
    billedIn: orderPack ? PACK + orderPack.id : shown.unit,
    qty: orderPack ? String(clean(l.deliveredQty / orderPack.size, 3)) : String(shown.value),
    amount: "",
  };
}

/** The line as it is sent (`InvoiceLineInput`), every key present, nulls where it has nothing. */
export function lineInput(line: InvoiceLineDraft, packs: LinePack[]): InvoiceLineInput {
  const pack = packIn(line, packs);
  const stock = billedStock(line, packs) ?? 0;
  return {
    goodsReceiptLineId: line.goodsReceiptLineId,
    ingredientId: line.ingredientId,
    description: line.oneOff ? line.name : null,
    billedQty: stock,
    unit: line.stockUnit,
    packSizeId: pack ? pack.id : null,
    packCount: pack ? num(line.qty) ?? 0 : null,
    amount: num(line.amount) ?? 0,
  };
}

/**
 * The Rate cell: Amount ÷ Billed qty, said through the shared {@link readableRate} (T-293) so it
 * reads per Kg, per L or per piece whatever the box counts in: 500 gm at ₹250 is "₹500 / Kg", as the
 * invoice page, the vendor page and the order sheet say it — never "₹0.50 / gm". A pack line says
 * the pack first, "₹1,500 / bag · ₹60 / Kg", with the per-pack figure the amount over the count as
 * typed. A dash until both the quantity and the amount are there.
 */
export function rateText(line: InvoiceLineDraft, packs: LinePack[]): string {
  const q = num(line.qty);
  const a = num(line.amount);
  if (q == null || a == null || q <= 0) return "—";
  const pack = packIn(line, packs);
  const rate = pack
    ? readablePackRate(a / q, pack.word, a / clean(q * pack.size), line.stockUnit)
    : readableRate(a / q, line.billedIn);
  return rate ?? "—";
}

/** The amber note for a line billed at more than came: "Billed 50 Kg, 45 Kg delivered". */
export function overBilled(line: InvoiceLineDraft, packs: LinePack[]): string | null {
  const stock = billedStock(line, packs);
  if (line.deliveredQty == null || stock == null || stock <= line.deliveredQty + 1e-9) return null;
  return `Billed ${quantity(stock, line.stockUnit)}, ${quantity(line.deliveredQty, line.stockUnit)} delivered`;
}

/** The one input look on this table: the 44px touch height, the control corner. */
const FIELD = "min-h-touch rounded-control border border-hairline bg-canvas px-3 tabular-nums";

/** A figure that sits in a row of boxes and must line up with them, so it takes a box's height there. */
function Readout({ children, muted }: { children: React.ReactNode; muted?: boolean }) {
  return (
    <span className={`inline-flex items-center tabular-nums lg:min-h-touch ${muted ? "text-ink-secondary" : "text-ink"}`}>
      {children}
    </span>
  );
}

/**
 * A quantity in the order's own terms: "4 × Bag (25 Kg)" when the order was in a pack, else "45 Kg".
 * Exported because the delivery tick boxes name what came in the same words as the Delivered column
 * (T-290), so the two never disagree about the same delivery.
 */
export function packOrQuantity(stock: number, unit: string, pack: LinePack | null): string {
  return pack ? `${count(clean(stock / pack.size, 3))} × ${pack.label}` : quantity(stock, unit);
}

/** "4 × Bag (25 Kg)" over "100 Kg" for a pack line (conductor's ruling), else "45 Kg". */
function Amount({ stock, line }: { stock: number; line: InvoiceLineDraft }) {
  if (!line.orderPack) return <Readout>{quantity(stock, line.stockUnit)}</Readout>;
  // One pack with no name is its own size, so "500 gm" under "1 × 500 gm" is dropped (T-302). The
  // rule is `repeatsPack`, one copy for every view (T-303); it is given the count as
  // `packOrQuantity` writes it, to 3 places.
  const unnamedSingle = repeatsPack(line.orderPack.label, clean(stock / line.orderPack.size, 3));
  return (
    <span className="inline-flex flex-col justify-center lg:min-h-touch">
      <span className="tabular-nums text-ink">{packOrQuantity(stock, line.stockUnit, line.orderPack)}</span>
      {!unnamedSingle && <span className="text-sm tabular-nums text-ink-secondary">{quantity(stock, line.stockUnit)}</span>}
    </span>
  );
}

export interface InvoiceItemsTableProps {
  /** Lines pulled from deliveries (locked), or a direct invoice's lines (added and removed here). */
  mode: "delivery" | "direct";
  lines: InvoiceLineDraft[];
  onChange: (key: string, patch: Partial<InvoiceLineDraft>) => void;
  /** The ingredients, for their pack sizes. */
  ingredients: IngredientView[];
  /** Called after a pack size is saved on an ingredient, so the caller can fetch the packs again. */
  onPackAdded: () => void;
  /** Save was pressed: an empty amount on a billed line shows red. */
  tried?: boolean;
  // Direct invoices only.
  onRemove?: (key: string) => void;
  onChoose?: (choice: ComboChoice<ComboItem>) => void;
  firstItems?: ComboItem[];
  firstGroupLabel?: string;
  otherItems?: ComboItem[];
}

export function InvoiceItemsTable(props: InvoiceItemsTableProps) {
  const { mode, lines, onChange, ingredients, tried } = props;
  const direct = mode === "direct";
  const byId = new Map(ingredients.map((i) => [i.id, i]));
  const [addingFor, setAddingFor] = useState<string | null>(null);
  const columns = direct ? 5 : 6;

  function setUnit(line: InvoiceLineDraft, packs: LinePack[], next: string) {
    const stock = billedStock(line, packs);
    const patch: Partial<InvoiceLineDraft> = { billedIn: next };
    if (line.oneOff) patch.stockUnit = next;
    else if (stock != null) patch.qty = inBox(stock, next, line.stockUnit, packs);
    onChange(line.key, patch);
  }

  return (
    <table className={`${TABLE} ${ENTRY_GRID} text-sm max-lg:[&_tbody_td]:border-0`}>
      <thead className={THEAD}>
        <tr>
          {/* The one column that may wrap, and only when the table is short of room (§5 rule 2). The
              mock's Item did not wrap, and did not need to: measured at 1280, a pack line (its
              "× Bag (25 Kg)" unit and its two-part rate) made the table 1020px in 936, 52px past
              the window, until the item's name was allowed to give way. */}
          <th className={`${TH_TEXT} ${WRAP}`}>Item</th>
          {!direct && <th className={TH_NUM}>Ordered</th>}
          {!direct && <th className={TH_NUM}>Delivered</th>}
          <th className={TH_NUM}>Billed qty</th>
          <th className={TH_NUM}>Amount (₹)</th>
          <th className={TH_NUM}>Rate</th>
          {direct && (
            <th className={TH_TEXT}>
              <span className="sr-only">Actions</span>
            </th>
          )}
        </tr>
      </thead>
      <tbody>
        {lines.map((l) => {
          const ingredient = l.ingredientId ? byId.get(l.ingredientId) : undefined;
          const packs = l.oneOff ? [] : linePacks(l, ingredient);
          const warn = overBilled(l, packs);
          const billed = num(l.qty) ?? 0;
          const amountMissing = billed > 0 && num(l.amount) == null;
          const pack = packIn(l, packs);
          /*
           * A bill billed in a counted unit counts whole things (T-424).
           *
           * A bill billed in a PACK does not, and this is the trap: a bill restates a delivery, and
           * a delivery of part of a bag is a real thing this product supports on purpose ("2.8 bags
           * (70 Kg)", `RecordDeliveryPanel`). Stepping a pack line by 1 here would make a bill for
           * the delivery that actually happened impossible to type. What must be whole for a
           * counted ingredient is the stock amount, which the box in packs cannot express as a
           * step; the server's own refusal is the guard for that case.
           */
          const billedInCounted = isCountedUnit(l.billedIn);
          const stock = billedStock(l, packs);
          const units = l.oneOff
            ? FOOD_UNITS
            : FOOD_UNITS.filter((u) => convertQuantity(1, u, l.stockUnit) !== null);
          return (
            <Fragment key={l.key}>
              <tr className={TR}>
                <td className={`${TD_TEXT} ${WRAP}`}>
                  <span className="inline-flex items-center font-medium text-ink lg:min-h-touch">{l.name}</span>
                  {l.oneOff && <span className="block text-sm text-ink-secondary">One-off item</span>}
                  {warn && (
                    // The icon sits on the first line when the warning wraps, as it does when a pack
                    // line narrows the Item column; the mock's centred icon floated beside the middle line.
                    <span className="flex items-start gap-1 text-xs text-warning">
                      <i className="ti ti-alert-triangle mt-0.5" aria-hidden="true" />
                      {warn}
                    </span>
                  )}
                </td>
                {!direct && (
                  <td className={TD_NUM} data-label="Ordered">
                    <Amount stock={l.orderedQty ?? 0} line={l} />
                  </td>
                )}
                {!direct && (
                  <td className={TD_NUM} data-label="Delivered">
                    <Amount stock={l.deliveredQty ?? 0} line={l} />
                  </td>
                )}
                {/* One column of a phone card, beside Amount, as the mock's delivery table has it
                    (T-290). T-273 spanned both columns because the "× Bag (25 Kg)" list ran over the
                    Amount box; that made every card 24px taller with ~190px empty beside a plain
                    "Kg", against the layout rule (a new row only when things can't sit side by side).
                    Measured at 390, the half column is 171px: box 80 + gap 8 + "Kg" 61 = 149 fits,
                    "pieces" 83 makes 171 and fits, a "× Bag (25 Kg)" list (122) makes 210 and does
                    not. So only a line billed in a pack takes the card's full width, as before, and
                    Amount | Rate sit side by side under it. The other way was tried and measured:
                    letting the pack list wrap under its box inside the half column made that card
                    341px against 313, with 171 × 76px empty under the Amount box. The lesser evil
                    is the shorter card whose only spare room is to the right of one row of boxes.
                    The pair may still wrap below lg, as a safety net where the half column is
                    narrower than measured here (a 360px phone: "pieces" no longer fits in 156px).
                    On a wide screen it never wraps, so the column fitter measures it on one line.
                    A direct invoice keeps the mock's direct table, where Billed qty spans the card
                    and is followed by Amount | Rate. */}
                <td className={direct || pack ? `${TD_NUM} max-lg:col-span-2` : TD_NUM} data-label="Billed qty">
                  <span className="flex items-center gap-2 max-lg:flex-wrap">
                    <input
                      type="number"
                      // Billed in whatever the "Billed in" select beside it says — a pack, or a
                      // unit. Both can be counted, and a bill for 3.6 brooms is T-424.
                      inputMode={billedInCounted ? "numeric" : "decimal"}
                      min="0"
                      step={billedInCounted ? "1" : "any"}
                      required
                      value={l.qty}
                      onChange={(e) => onChange(l.key, { qty: e.target.value })}
                      aria-label={`${l.name} billed quantity`}
                      className={`${FIELD} min-w-20`}
                    />
                    <select
                      aria-label={`${l.name} billed in`}
                      value={l.billedIn}
                      onChange={(e) =>
                        e.target.value === ADD_PACK ? setAddingFor(l.key) : setUnit(l, packs, e.target.value)
                      }
                      // As wide as the unit chosen, not as the longest option. A select is otherwise as
                      // wide as "Add a pack size…", and measured at 1280 that 170px box for "Kg" is
                      // what squeezed the item names onto three lines. Where a browser does not
                      // support field-sizing it falls back to the ordinary width.
                      className={`${FIELD} [field-sizing:content]`}
                    >
                      {units.map((u) => (
                        <option key={u} value={u}>
                          {unitLabel(u)}
                        </option>
                      ))}
                      {packs.map((p) => (
                        <option key={p.id} value={PACK + p.id}>
                          {`× ${p.label}`}
                        </option>
                      ))}
                      {!l.oneOff && <option value={ADD_PACK}>Add a pack size…</option>}
                    </select>
                  </span>
                  {/* What the packs come to, in the stock unit every other figure is in. Not for one
                      pack with no name: "1" beside "× 500 gm" is 500 gm, and "500 gm" under them
                      says so twice (T-303, `repeatsPack`, given the figure in the box as typed). */}
                  {pack && stock != null && !repeatsPack(pack.label, billed) && (
                    <span className="mt-1 block pl-field-inset text-sm text-ink-secondary">
                      {quantity(stock, l.stockUnit)}
                    </span>
                  )}
                </td>
                <td className={TD_NUM} data-label="Amount (₹)">
                  <input
                    type="number"
                    inputMode="decimal"
                    min="0"
                    step="any"
                    // Required once anything is billed (R-INV-4); an item not billed goes as ₹0.
                    required={billed > 0}
                    value={l.amount}
                    onChange={(e) => onChange(l.key, { amount: e.target.value })}
                    aria-label={`${l.name} amount`}
                    aria-invalid={(tried && amountMissing) || undefined}
                    className={`${FIELD} min-w-24 ${tried && amountMissing ? "border-danger" : ""}`}
                  />
                </td>
                <td className={TD_NUM} data-label="Rate">
                  <Readout muted>{rateText(l, packs)}</Readout>
                </td>
                {direct && (
                  <td className={TD_TEXT}>
                    <Button
                      variant="ghost"
                      icon="trash"
                      aria-label={`Remove ${l.name}`}
                      onClick={() => props.onRemove?.(l.key)}
                    />
                  </td>
                )}
              </tr>
              {addingFor === l.key && ingredient && (
                <PackSizeRow
                  ingredient={ingredient}
                  colSpan={columns}
                  onCancel={() => setAddingFor(null)}
                  onSaved={(id, size) => {
                    setAddingFor(null);
                    props.onPackAdded();
                    // Chosen at once, keeping the amount the box stood for.
                    const s = billedStock(l, packs);
                    onChange(l.key, {
                      billedIn: PACK + id,
                      qty: s == null ? l.qty : String(clean(s / size, 3)),
                    });
                  }}
                />
              )}
            </Fragment>
          );
        })}
        {direct && (
          // Always one empty row at the bottom; choosing in it adds a line above it (R-PO-3's pattern).
          <tr className={TR}>
            <td className={TD_TEXT}>
              <ItemCombobox
                label="Add an item"
                firstItems={props.firstItems ?? []}
                firstGroupLabel={props.firstGroupLabel ?? "Sold by this vendor"}
                otherItems={props.otherItems ?? []}
                exclude={new Set(lines.flatMap((l) => (l.ingredientId ? [l.ingredientId] : [])))}
                onChoose={(c) => props.onChoose?.(c)}
              />
            </td>
            <td className={`${TD_NUM} max-lg:hidden`} />
            <td className={`${TD_NUM} max-lg:hidden`} />
            <td className={`${TD_NUM} max-lg:hidden`} />
            <td className={`${TD_TEXT} max-lg:hidden`} />
          </tr>
        )}
      </tbody>
    </table>
  );
}

/**
 * The pack size fields, opened from "Add a pack size…": an optional name, a size, and a unit of the
 * ingredient's own family. The vendor page's `PackSizeRow`, the same fields and words (conductor's
 * ruling, 2026-09-19), saved on the ingredient through the same endpoint, so a pack made here is the
 * ingredient's for every vendor and every later bill. A row of its own under the line, spanning the
 * table, so three fields and two buttons do not widen the Billed qty column for every row.
 */
function PackSizeRow({
  ingredient,
  colSpan,
  onSaved,
  onCancel,
}: {
  ingredient: IngredientView;
  colSpan: number;
  /** The new pack's id, and its size in the ingredient's unit. */
  onSaved: (packSizeId: string, size: number) => void;
  onCancel: () => void;
}) {
  const { getToken } = useAuth();
  const units = FOOD_UNITS.filter((u) => convertQuantity(1, u, ingredient.unit) !== null);
  const [name, setName] = useState("");
  const [size, setSize] = useState("");
  const [unit, setUnit] = useState(ingredient.unit);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [blank, setBlank] = useState(false);

  async function add() {
    const q = num(size);
    if (q == null || q <= 0) {
      setBlank(true);
      return;
    }
    setBlank(false);
    setBusy(true);
    setError(null);
    try {
      const trimmed = name.trim();
      const made = await api.addPackSize(
        ingredient.id,
        { name: trimmed === "" ? null : trimmed, quantity: q, unit },
        await getToken(),
      );
      onSaved(made.id, convertQuantity(q, unit, ingredient.unit) ?? q);
    } catch (e) {
      setError(toApiError(e, "We couldn’t add that pack size."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <tr className="bg-sunken">
      <td colSpan={colSpan} className="px-5 py-4">
        <div role="group" aria-label={`New pack size for ${ingredient.name}`} className="flex flex-wrap items-end gap-4">
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Pack name (optional)</span>
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Bag" className={`${FIELD} w-40`} />
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Size</span>
            <input
              type="number"
              // How much is in one pack, in the unit chosen beside it. A pack of 12.5 pieces is
              // not a pack of anything (T-424); a 2.5 Kg pack is ordinary.
              inputMode={inputModeForUnit(unit)}
              min="0"
              step={stepForUnit(unit)}
              value={size}
              onChange={(e) => setSize(e.target.value)}
              aria-invalid={blank || undefined}
              className={`${FIELD} min-w-28 ${blank ? "border-danger" : ""}`}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Unit</span>
            <select value={unit} onChange={(e) => setUnit(e.target.value)} className={FIELD}>
              {units.map((u) => (
                <option key={u} value={u}>
                  {unitLabel(u)}
                </option>
              ))}
            </select>
          </label>
          <div className="flex items-center gap-3">
            <Button variant="ghost" busy={busy} onClick={add}>
              Add pack size
            </Button>
            <Button variant="ghost" onClick={onCancel}>
              Cancel
            </Button>
          </div>
        </div>
        {blank && <p className="mt-2 pl-field-inset text-sm text-danger">Size must be more than 0</p>}
        {error && (
          <div className="mt-3">
            <ErrorNotice error={error} />
          </div>
        )}
      </td>
    </tr>
  );
}
