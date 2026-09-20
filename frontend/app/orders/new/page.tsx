"use client";

import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Loading } from "@/components/Loading";
import { RequireRole } from "@/components/RequireRole";
import { ItemCombobox, type ComboChoice, type ComboItem } from "@/components/ItemCombobox";
import { revealNotice } from "@/components/PurchaseOrderEditor";
import { Button } from "@/components/ds/Button";
import { Form } from "@/components/ds/Form";
import { countedBox, PACKS } from "@/components/ds/formMessages";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import {
  RULED_TABLE,
  THEAD,
  TR,
  TH_PRIMARY,
  TD_PRIMARY,
  TH_FIXED,
  TD_FIXED,
  TD_FIXED_NUM,
  TH_ACTIONS_FIXED,
  TD_ACTIONS_FIXED,
} from "@/components/ds/table";
import {
  api,
  toApiError,
  type ApiError,
  type IngredientView,
  type PoLineInput,
  type VendorDetailView,
  type VendorSupplyView,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import {
  FOOD_UNITS,
  convertQuantity,
  money,
  pricePer,
  quantity as sayQuantity,
  readableRate,
  stepForUnit,
  todayIso,
  unitLabel,
  unitLabelFor,
} from "@/lib/format";

/**
 * Create a purchase order — one screen, straight to the form (R-PO-1, R-PO-2, R-PO-3; T-263).
 *
 * <p><b>What changed, and why the old reasoning no longer holds.</b> Until T-263 this was step one
 * of two: a screen asking only which vendor, with "Add a vendor" beside it, and the lines on
 * `/orders/new/lines`. D-7 chose that shape so leaving to add a vendor could lose nothing. Rajeev
 * removed it in the procurement redesign (2026-09-19, `docs/work/PROCUREMENT-REQUIREMENTS.md` R-PO-1):
 * the button goes "straight to the create form", and the vendor sits beside "Needed by" on one row.
 * `/orders/new/lines` is kept only as a redirect here, carrying `?vendor=`, so an old bookmark lands
 * on the form rather than a 404.
 *
 * <p><b>This is design A of the `dev-po` mock, built as specified</b> (`docs/work/mocks/dev-po.page.tsx`,
 * `DesignA`): the header fields, then "Items", a one-line hint, and an inline table whose last row
 * is always an empty type-ahead. Choosing in it adds a row above and moves focus to that row's
 * quantity, so the empty row is always there to fill next. The type-ahead is the shared
 * {@link ItemCombobox}, which the invoice form will reuse (R-INV-3).
 *
 * <p><b>The vendor still cannot be changed once the order exists</b> — `orders/[id]` refuses it and
 * says to cancel and create another. It can be changed freely here, before anything is saved.
 * Lines already added keep the price they were given: a price somebody may have typed over is
 * theirs, and quietly re-pricing rows under their hands is worse than leaving a figure to check.
 *
 * <p><b>Readable units and packs (T-288, VERIFY-B D-2, D-3, D-8).</b> A catalogue line is typed in
 * its readable unit — Kg for curry leaves kept in grams — and priced per that unit, so "3" orders
 * 3 Kg at "₹60 / Kg" rather than 3 gm at ₹0.06. An ingredient with pack sizes gets a unit select
 * offering them, and starts in the vendor's own pack where the vendor sells it in one: "4" beside
 * "× Bag (25 Kg)" at ₹1,500 a bag. Under each price box the rate is said in words by the shared
 * `readableRate` ("₹1,500 / bag · ₹60 / Kg"). Lines go to the server exactly as the shopping list
 * sends them (see toLineInput), so a purchase-order line has one convention.
 */

/** Named so the header's primary button can submit the form in the body. */
const FORM = "create-purchase-order";
const FIELD = "min-h-touch rounded-control border border-hairline px-3";
// `min-w-28`, where the mock has `w-28`: the table rule (DESIGN_SYSTEM v1.13 §5, enforced by
// table-rule.test.ts) says a number box in a table only sets a minimum, so a long figure — a price
// per gram to four places — widens it rather than being cut off. The one deviation from the mock,
// and the document's rule is what makes it.
const NUM_BOX = "min-h-touch min-w-28 rounded-control border border-hairline px-3 tabular-nums";
// The quantity box is `min-w-20` (80px), not the mock's 112 (T-288). Measured on /orders/new at
// 1024, where the page has 680px for the table: with the mock's 112 on both boxes, a line in packs
// ("× Bag (25 Kg)" in the Unit column) made the table 688 wide, so its right edge was cut off. A
// quantity is at most a few digits ("12.5", "4" bags) and 80px holds six; the price box keeps 112
// because a price per gram runs to four places. The lesser evil against the mock's width, recorded
// in docs/work/proof/T-288.md; the box still grows for a longer figure, since it only sets a minimum.
const QTY_BOX = "min-h-touch min-w-20 rounded-control border border-hairline px-3 tabular-nums";
const LABEL = "flex flex-col gap-1 text-sm text-ink-secondary";
const LABEL_TEXT = "pl-field-inset font-medium text-ink";

/**
 * The vendors this order may be created against: the active ones, and only those.
 *
 * <p><b>`true` means active-only, and the flag really is the other way round.</b> The endpoint asks
 * `includeInactive` and `listVendors` inverts it. `api.ts`'s own comment records the day that
 * inversion was a bug. The narrowing is this screen's job because the server will not do it:
 * `requireVendor` checks only that the vendor row exists, so the picker not offering a dropped
 * supplier is the whole of the guard.
 *
 * <p>Module scope, so it is the same function object on every render: `useAuthedQuery` lists its
 * fetcher in the effect's dependencies, and an arrow written inline re-fetches for ever.
 */
const activeVendors = (token: string | undefined) => api.listVendors(true, token);
const allIngredients = (token: string | undefined) => api.listIngredients(token);

/**
 * A line as it is being built. Quantity and price are the text in their boxes, so somebody can
 * clear one and retype it; they become numbers once, on submit.
 *
 * <p>`key` is a React key and nothing else. The order does not exist yet, so a line has no id, and
 * `ingredientId` is null on every one-off line — two of those keyed alike is undefined
 * reconciliation on a form somebody is typing into (the defect `orders/[id]` was fixed for in T-024).
 */
interface DraftLine {
  key: string;
  /** Null on a one-off line, which travels as `description` instead. */
  ingredientId: string | null;
  name: string;
  /**
   * The unit the quantity is typed in and the price is per, when the line is not in a pack: the
   * READABLE unit, Kg for an ingredient kept in grams and L for one kept in millilitres (T-288,
   * R-SL-1). See {@link readableUnit}. A one-off line's is whatever its select says.
   */
  unit: string;
  /** The ingredient's own unit — what stock is counted in, and what a pack's size is given in. */
  stockUnit: string;
  /** The ingredient's pack sizes (R-ING-1), as this line can be ordered in them. Empty for a one-off. */
  packs: PackChoice[];
  /**
   * The pack this line is ordered in, or null for a plain amount. When set, `quantity` is a count of
   * packs ("4") and `price` is the price of ONE pack ("1500"), which is how a vendor quotes a bag.
   */
  packId: string | null;
  quantity: string;
  price: string;
  oneOff: boolean;
}

/** One pack size a line may be ordered in: "Bag (25 Kg)", holding 25 of the line's stock unit. */
interface PackChoice {
  id: string;
  /** The order form of the pack, as the server writes it on a PO line: "Bag (25 Kg)", "500 gm". */
  label: string;
  /** One pack's size in the ingredient's own unit — 25 for a 25 Kg bag of rice kept in Kg. */
  perPackQty: number;
}

/** What the type-ahead offers: an ingredient, with the unit a picked line is counted in and its price. */
interface Offer extends ComboItem {
  unit: string;
  /** The vendor's list price per `unit`, or null — for "Other ingredients", always null. */
  price: number | null;
  /** The pack this vendor sells it in ("Sells it as", R-VEN-1), or null. */
  vendorPackId: string | null;
  /** What the vendor charges for one of that pack, or null. */
  pricePerPack: number | null;
}

let nextKey = 1;
const newKey = () => `line-${nextKey++}`;

function num(text: string): number {
  const n = Number(text);
  return Number.isFinite(n) && n > 0 ? n : 0;
}

/**
 * Quantity × expected price, or null until both are above zero — the cell then stays blank.
 *
 * <p>Rounded to paise, which the mock does not do, because a price per gram makes it matter: 300 gm
 * at ₹0.07 is 21.000000000000004 in floating point (checked in node), and `money` shows paise on
 * anything that is not a whole number, so the cell would read "₹21.00" beside a line reading "₹288".
 */
function lineTotal(l: { quantity: string; price: string }): number | null {
  const q = num(l.quantity);
  const p = num(l.price);
  return q > 0 && p > 0 ? paise(q * p) : null;
}

function paise(amount: number): number {
  return Math.round(amount * 100) / 100;
}

/**
 * The unit a list price is *said* in: per Kg or per L for a price kept per gram or millilitre, as
 * the vendor page says it ("₹68/Kg", not "₹0.07/gm", which rounds two different prices to one).
 * The same rule as the vendor page's `sayPriceIn`, converted with the shared `convertQuantity`
 * rather than a second table of what a kilo is (§1: add no new conversion tables).
 */
function sayPriceIn(unit: string): string {
  const u = unit.toUpperCase();
  return u === "GM" ? "KG" : u === "ML" ? "L" : u;
}

/**
 * "₹32/Kg": the list price as R-PO-3 writes it, with no spaces round the slash (the document and the
 * mock agree; the vendor page's "₹32 / Kg" is its own screen). `unitLabelFor(1, …)` so a count says
 * "₹4/piece", not "₹4/pieces".
 */
function listPriceText(price: number, unit: string): string {
  const said = sayPriceIn(unit);
  // Rounded to paise for the same floating-point reason as `lineTotal`.
  const perSaid = paise(price * (convertQuantity(1, said, unit) ?? 1));
  return `${money(perSaid, "INR")}/${unitLabelFor(1, said)}`;
}

/**
 * The vendor's own items, first in the list, alphabetical. Their detail is the list price, or with
 * no list price, what a picked line will be counted in ({@link startsIn}).
 *
 * <p>`ingredients` is the catalogue, which arrives on its own: the ingredient's own unit and packs
 * are what {@link catalogueLine} builds the line from, so the option has to be worked out from the
 * same two things or it can promise one unit and create another (VERIFY2-B N-1: "Curry leaves ·
 * gm", picked, became a Kg line).
 */
function vendorOffers(supplies: VendorSupplyView[], ingredients: IngredientView[]): Offer[] {
  return [...supplies]
    .sort((a, b) => a.ingredientName.localeCompare(b.ingredientName))
    .map((s) => {
      const offer = {
        id: s.ingredientId,
        name: s.ingredientName,
        unit: s.unit,
        price: s.lastPrice,
        vendorPackId: s.packSizeId,
        pricePerPack: s.pricePerPack,
      };
      const ingredient = ingredients.find((i) => i.id === s.ingredientId);
      return {
        ...offer,
        detail: s.lastPrice === null ? startsIn(offer, ingredient) : listPriceText(s.lastPrice, s.unit),
      };
    });
}

/**
 * What a line picked from this option is counted in, as the option says it: the readable unit
 * ("Kg" for curry leaves kept in grams), or the vendor's pack where the line starts in one ("Bag
 * (25 Kg)"). Worked out with {@link catalogueLine}'s own rules — the ingredient's unit made
 * readable, and the vendor's pack only where the ingredient has that pack — so the option and the
 * line it creates cannot disagree (VERIFY2-B N-1).
 *
 * <p><b>Why the pack is named, not only "Kg".</b> Such a line starts at "× Bag (25 Kg)", and the
 * option is the promise of what the line will be. The requirements settle only a priced option
 * (R-PO-3, "₹32/Kg"); the mock's unpriced option shows the unit alone and predates packs, and R-SL-3
 * words the line "4 × Bag (25 Kg)" but not the dropdown. The clarifier left it to the builder
 * (2026-09-19, T-298); the pack is what the line is in, so the pack is what the option says.
 *
 * <p>A priced option keeps its list price per Kg ("₹60/Kg") even when the line starts in a pack:
 * that is R-PO-3's wording, and the rate under the line's price box says the same "₹60 / Kg" beside
 * "₹1,500 / bag".
 */
function startsIn(offer: Omit<Offer, "detail" | "name">, ingredient: IngredientView | undefined): string {
  const pack = packChoices(ingredient).find((p) => p.id === offer.vendorPackId);
  return pack ? pack.label : unitLabel(readableUnit(ingredient?.unit ?? offer.unit));
}

/**
 * The unit a catalogue line's quantity is typed in: Kg for an ingredient kept in grams, L for one
 * kept in millilitres, and the ingredient's own unit otherwise (T-288, VERIFY-B D-2).
 *
 * <p>Until T-288 the box took the ingredient's own unit, so typing 3 for curry leaves ordered 3 gm
 * while the type-ahead beside it said "₹60/Kg", and the price box was prefilled 0.06. The mock
 * orders vegetables in Kg, and R-SL-1 says what is typed is read in the unit shown beside the box.
 * The same rule as {@link sayPriceIn}, so the unit the list price is quoted in and the unit the
 * amount is typed in are one answer: a 250 gm order is typed "0.25" beside "Kg", at "₹60 / Kg".
 *
 * <p>The line goes to the server in this unit, exactly as the shopping list sends a plain line
 * (T-264: 3 KG at ₹60, not 3000 GM at ₹0.06). The server takes any unit of the ingredient's own
 * family (it refuses only a different family, KMS-400013) and stock receives 3,000 gm either way;
 * sending the readable unit is what keeps "3 Kg" on the order when it is edited later. The
 * conductor's ruling, 2026-09-19.
 */
function readableUnit(unit: string): string {
  return sayPriceIn(unit);
}

/**
 * An ingredient's pack sizes in the form a purchase-order line is ordered in (R-ING-1, R-SL-3).
 *
 * <p>The label is built the way the server writes a PO line's `packLabel` ("Bag (25 Kg)", a plain
 * "500 gm" with no name; `PurchaseOrderService.packLabel`) rather than the ingredient page's chip
 * ("Bag = 25 Kg"), because the order is what it names, and the sheet the vendor is handed says it
 * that way. `baseQuantity` is the pack in the ingredient's own unit, which is what a pack line's
 * stock amount is counted in.
 */
function packChoices(ingredient: IngredientView | undefined): PackChoice[] {
  return (ingredient?.packSizes ?? []).map((p) => {
    const size = sayQuantity(p.quantity, p.unit);
    return { id: p.id, label: p.name ? `${p.name} (${size})` : size, perPackQty: p.baseQuantity };
  });
}

/**
 * A line's price restated when its unit select moves between the plain unit and a pack, so the
 * figure somebody may have typed keeps its meaning: ₹60 a Kg becomes ₹1,500 a 25 Kg bag, and back.
 * Nothing is re-read from the vendor — the price in the box is theirs (see the file comment).
 */
function repriced(line: DraftLine, toPackId: string | null): string {
  const typed = Number(line.price);
  if (line.price.trim() === "" || !Number.isFinite(typed)) return line.price;
  const perStock = perStockUnit(line, typed);
  if (perStock === null) return line.price;
  const pack = line.packs.find((p) => p.id === toPackId);
  const next = pack ? perStock * pack.perPackQty : pricePer(perStock, line.stockUnit, line.unit);
  return next === null ? line.price : String(Number(next.toFixed(pack ? 2 : 4)));
}

/**
 * The price typed on a line, per ONE of the ingredient's own unit — what a pack line sends
 * (T-260 reads a pack line's price per its `unit`, and the shopping list sends pack lines in the
 * stock unit, so this form does too). Four places, which is what the column holds (V146).
 */
function perStockUnit(line: DraftLine, typed: number): number | null {
  const pack = line.packs.find((p) => p.id === line.packId);
  if (pack) return Number((typed / pack.perPackQty).toFixed(4));
  return pricePer(typed, line.unit, line.stockUnit);
}

export default function NewPurchaseOrderPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      {/* useSearchParams — a vendor chosen before arriving, `?vendor=` — needs a boundary. */}
      <Suspense>
        <CreatePurchaseOrderView />
      </Suspense>
    </RequireRole>
  );
}

function CreatePurchaseOrderView() {
  const { getToken } = useAuth();
  const router = useRouter();
  const askedVendor = useSearchParams().get("vendor") ?? "";

  const { data: vendorData, error: vendorsError, loading: loadingVendors } = useAuthedQuery(activeVendors);
  const vendors = vendorData ?? [];
  const { data: ingredientData } = useAuthedQuery(allIngredients);

  const [vendorId, setVendorId] = useState("");
  // A vendor named in the address is chosen once the active list has arrived — and only if it is on
  // that list, for the same reason the picker offers nothing else. Once, so a person who then picks
  // another vendor is not overruled on the next render.
  const preselected = useRef(false);
  useEffect(() => {
    if (preselected.current || loadingVendors) return;
    preselected.current = true;
    if (askedVendor !== "" && vendors.some((v) => v.id === askedVendor)) setVendorId(askedVendor);
  }, [askedVendor, loadingVendors, vendors]);

  // The chosen vendor's own items and list prices, for the top of the type-ahead.
  const fetchVendor = useCallback(
    (token: string | undefined): Promise<VendorDetailView | null> =>
      vendorId === "" ? Promise.resolve(null) : api.getVendor(vendorId, token),
    [vendorId]
  );
  const { data: vendorDetail, error: vendorError } = useAuthedQuery(fetchVendor);
  const vendor = vendors.find((v) => v.id === vendorId);
  const theirs =
    vendorDetail && vendorDetail.vendor.id === vendorId ? vendorOffers(vendorDetail.supplies, ingredientData ?? []) : [];
  // Not the vendor's, so never in the vendor's pack: the line starts plain, in the readable unit,
  // and the option says that unit — "Jeera · Kg" for jeera kept in grams, not "· gm" (VERIFY2-B N-1).
  const everyone: Offer[] = [...(ingredientData ?? [])]
    .sort((a, b) => a.name.localeCompare(b.name))
    .map((i) => ({
      id: i.id,
      name: i.name,
      unit: i.unit,
      price: null,
      vendorPackId: null,
      pricePerPack: null,
      detail: unitLabel(readableUnit(i.unit)),
    }));

  const [note, setNote] = useState("");
  const [lines, setLines] = useState<DraftLine[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  /**
   * A refusal this screen makes itself, before anything is sent: no items, or a needed-by date in
   * the past. Kept apart from `error` because it is not a failure of the server or the connection.
   *
   * <p>It used to go through `toApiError(null, …)`, which exists for a request that never reached
   * the server, so the notice added that request's next step and code under the refusal: "An order
   * needs at least one item…" / "Check your connection and try again." / "If you need help, quote
   * KMS-0000" (VERIFY2-B N-2, found first by T-288). Nothing was wrong with the connection, and
   * there is nothing for anyone helping to look up: the sentence already says what to do. So it is
   * the one sentence, drawn as ErrorNotice draws a refusal, and nothing under it. `about` is what
   * clears it: the items one goes the moment an item is added.
   */
  const [refusal, setRefusal] = useState<{ about: "items" | "date"; message: string } | null>(null);

  // Create order sits in the sticky header, and both notices are drawn at the top of the form, so
  // somebody who has scrolled down to the items and pressed it saw nothing happen: at 390 the
  // refusal was above the viewport (T-304). Each new refusal or failure is brought into view and
  // given focus, as the edit form does; revealNotice says why that rather than a notice beside the
  // button. Keyed on the notice objects, so pressing Create order twice on the same refusal (a new
  // object each time) shows it again. Clearing one (null) does nothing.
  useEffect(() => {
    if (refusal || error) revealNotice();
  }, [refusal, error]);

  // Focus moves to the quantity of a line the moment it is added: the next thing to type.
  const qtyRefs = useRef(new Map<string, HTMLInputElement>());
  const [focusKey, setFocusKey] = useState<string | null>(null);
  useEffect(() => {
    if (focusKey) qtyRefs.current.get(focusKey)?.focus();
  }, [focusKey]);

  const update = (key: string, patch: Partial<DraftLine>) =>
    setLines((cur) => cur.map((l) => (l.key === key ? { ...l, ...patch } : l)));

  function choose(c: ComboChoice<Offer>) {
    const key = newKey();
    const line: DraftLine =
      c.kind === "item"
        ? catalogueLine(key, c.item, (ingredientData ?? []).find((i) => i.id === c.item.id))
        : // A one-off is counted in pieces until somebody says otherwise, as the mock has it: the
          // things nobody catalogues — a stool, a cylinder — are mostly counted, not weighed.
          {
            key,
            ingredientId: null,
            name: c.text,
            unit: "PIECES",
            stockUnit: "PIECES",
            packs: [],
            packId: null,
            quantity: "",
            price: "",
            oneOff: true,
          };
    setLines((cur) => [...cur, line]);
    setFocusKey(key);
    // "An order needs at least one item" is answered now; a date refusal still stands.
    setRefusal((r) => (r?.about === "items" ? null : r));
  }

  /** The unit select moved: between the plain unit and a pack, or from one pack to another. */
  function changeUnit(l: DraftLine, value: string) {
    const packId = value === PLAIN ? null : value;
    update(l.key, { packId, price: repriced(l, packId) });
  }

  async function create(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!vendor) return;

    // A new attempt: whatever was refused last time is judged again below.
    setRefusal(null);
    if (lines.length === 0) {
      setError(null);
      setRefusal({ about: "items", message: "An order needs at least one item. Type one into the Items table." });
      return;
    }
    // The server refuses a needed-by date earlier than today with KMS-400014. The box carries a
    // `min`, which `Form` names beside it; this is the same refusal for anything the box lets through.
    const f = new FormData(event.currentTarget);
    const neededBy = String(f.get("neededBy") ?? "");
    if (neededBy !== "" && neededBy < todayIso()) {
      setError(null);
      setRefusal({ about: "date", message: "That date has already passed. Choose today or a day after it." });
      return;
    }

    setBusy(true);
    setError(null);
    try {
      const { id } = await api.createPurchaseOrder(
        {
          vendorId: vendor.id,
          neededBy: neededBy === "" ? null : neededBy,
          // "Deliver to" is gone from the form (R-PO-2). Sent as null rather than left out, so the
          // body says plainly that no place was given.
          deliveryLocation: null,
          notes: note.trim() === "" ? null : note.trim(),
          // Each line in the shopping list's shape: see toLineInput.
          lines: lines.map(toLineInput),
        },
        await getToken()
      );
      // Back to the list, with the confirmation waiting there and a link to the new draft.
      router.push(`/orders?added=${encodeURIComponent(vendor.name)}&po=${encodeURIComponent(id)}`);
    } catch (e) {
      setError(toApiError(e, "We couldn’t create that order."));
      setBusy(false);
    }
  }

  const total = paise(lines.reduce((a, l) => a + (lineTotal(l) ?? 0), 0));

  return (
    <FocusScreen
      // The shared h1 inherits the base h1's -0.02em tracking (text-xl sets only size and line
      // height), and the approved mock draws this title untracked. The span puts the mock's normal
      // spacing back without giving up the h1, so the heading stays a heading (VERIFY-B D-6).
      task={<span className="tracking-normal">Create a purchase order</span>}
      activeHref="/orders"
      actions={
        <>
          <ButtonLink href="/orders" variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={FORM} disabled={busy || loadingVendors}>
            Create order
          </Button>
        </>
      }
    >
      {refusal && (
        // ErrorNotice's box, less its next-step line and reference code: see `refusal`.
        <div role="alert" className="rounded border border-danger bg-danger-bg p-4 text-danger">
          <p className="font-medium">{refusal.message}</p>
        </div>
      )}
      {error && <ErrorNotice error={error} />}
      {vendorsError && <ErrorNotice error={vendorsError} />}
      {vendorError && <ErrorNotice error={vendorError} />}

      {loadingVendors ? (
        <Loading label="Loading vendors…" />
      ) : (
        <Form id={FORM} aria-label="Create a purchase order" onSubmit={create} className="grid gap-6">
          <div className="grid gap-4">
            {/* Vendor beside Needed by, on one row (R-PO-2); stacked only on a phone, where the two
                side by side would each be too narrow for a vendor's name. */}
            <div className="grid gap-4 sm:grid-cols-2">
              <label className={LABEL}>
                <span className={LABEL_TEXT}>Vendor</span>
                <select
                  name="vendorId"
                  required
                  value={vendorId}
                  onChange={(e) => setVendorId(e.target.value)}
                  className={FIELD}
                >
                  <option value="">Choose a vendor…</option>
                  {vendors.map((v) => (
                    <option key={v.id} value={v.id}>
                      {v.name}
                    </option>
                  ))}
                </select>
              </label>
              {/* `min` is what refuses yesterday in the browser. The temple's today, not the
                  device's: the server measures the date against the same clock. */}
              <label className={LABEL}>
                <span className={LABEL_TEXT}>Needed by</span>
                <input name="neededBy" type="date" min={todayIso()} className={FIELD} />
              </label>
            </div>
            <label className={LABEL}>
              <span className={LABEL_TEXT}>Note for the vendor</span>
              <GrowingNote value={note} onChange={setNote} />
            </label>
          </div>

          <div>
            <h2 className="text-base font-semibold text-ink">Items</h2>
            <p className="mt-1 text-sm text-ink-secondary">
              Start typing an ingredient, or type anything to add a one-off item.
            </p>
            <table className={`${RULED_TABLE} mt-2`}>
              <thead className={THEAD}>
                <tr>
                  <th className={TH_PRIMARY}>Item</th>
                  <th className={TH_FIXED}>Quantity</th>
                  <th className={TH_FIXED}>Unit</th>
                  <th className={TH_FIXED}>Expected price (₹)</th>
                  <th className={TH_FIXED}>Line total</th>
                  <th className={TH_ACTIONS_FIXED}>
                    <span className="sr-only">Remove</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {lines.map((l) => (
                  <tr key={l.key} className={`${TR} !align-middle`}>
                    <td className={TD_PRIMARY}>
                      {l.name}
                      {l.oneOff && <span className="block text-sm text-ink-secondary">One-off item</span>}
                    </td>
                    <td className={TD_FIXED_NUM} data-label="Quantity">
                      <input
                        ref={(el) => {
                          if (el) qtyRefs.current.set(l.key, el);
                          else qtyRefs.current.delete(l.key);
                        }}
                        type="number"
                        required
                        min="0"
                        data-more-than="0"
                        // A vendor sells whole bags: a pack line's box counts packs, and `Form`
                        // says so beside the box. And a line in a counted unit counts the things
                        // themselves, pack or no pack — an order for 3.6 brooms is the T-424 defect.
                        step={l.packId ? "1" : stepForUnit(l.unit)}
                        // The two reasons are different and the sentence says which one applies
                        // (T-431): "Rice is ordered in whole packs" for a pack line, "Broom is
                        // counted in whole pieces" for a line in the thing's own unit. Claiming
                        // pieces on a pack of rice would simply be false.
                        {...countedBox(l.name, l.packId ? PACKS : l.unit)}
                        aria-label={`Quantity of ${l.name}`}
                        value={l.quantity}
                        onChange={(e) => update(l.key, { quantity: e.target.value })}
                        className={QTY_BOX}
                      />
                    </td>
                    {l.packs.length > 0 ? (
                      // Sold in packs (R-ING-1): the line is ordered either as a plain amount in the
                      // readable unit, or as a count of one of the ingredient's packs — "4 × Bag
                      // (25 Kg)" (R-SL-3, T-288 / VERIFY-B D-3). The same choice the shopping list
                      // makes for a line, offered here by hand.
                      <td className={TD_FIXED}>
                        <select
                          aria-label={`Unit for ${l.name}`}
                          value={l.packId ?? PLAIN}
                          onChange={(e) => changeUnit(l, e.target.value)}
                          className={FIELD}
                        >
                          <option value={PLAIN}>{unitLabel(l.unit)}</option>
                          {l.packs.map((p) => (
                            <option key={p.id} value={p.id}>
                              × {p.label}
                            </option>
                          ))}
                        </select>
                      </td>
                    ) : l.oneOff ? (
                      <td className={TD_FIXED}>
                        <select
                          aria-label={`Unit for ${l.name}`}
                          value={l.unit}
                          onChange={(e) => update(l.key, { unit: e.target.value })}
                          className={FIELD}
                        >
                          {FOOD_UNITS.map((u) => (
                            <option key={u} value={u}>
                              {unitLabel(u)}
                            </option>
                          ))}
                        </select>
                      </td>
                    ) : (
                      // A catalogue ingredient with no packs is counted in its readable unit —
                      // Kg for one kept in grams (T-288). The server refuses a different family
                      // (BL-9), and there is no reason to offer the mistake.
                      <td className={`${TD_FIXED} text-ink-secondary`}>{unitLabel(l.unit)}</td>
                    )}
                    <td className={TD_FIXED_NUM} data-label="Expected price (₹)">
                      <input
                        type="number"
                        min="0"
                        step="any"
                        aria-label={`Expected price of ${l.name}`}
                        value={l.price}
                        onChange={(e) => update(l.key, { price: e.target.value })}
                        className={NUM_BOX}
                      />
                      <RateNote line={l} />
                    </td>
                    <TotalCell line={l} />
                    <td className={TD_ACTIONS_FIXED}>
                      <Button
                        type="button"
                        variant="ghost"
                        size="sm"
                        aria-label={`Remove ${l.name}`}
                        onClick={() => setLines((cur) => cur.filter((x) => x.key !== l.key))}
                      >
                        Remove
                      </Button>
                    </td>
                  </tr>
                ))}
                {/* Always one empty row at the bottom; choosing in it adds a line above it.

                    The type-ahead spans Item, Quantity and Unit (T-288). It is at least 256px wide
                    (its list has to be), and in the Item cell alone that minimum set the whole Item
                    column: measured on /orders/new at 1024, where the page has 680px, one plain line
                    made the table 775 wide and a line in packs 891, so the Remove buttons and the
                    order's right edge were cut off. The cells beside it were always empty, so
                    spanning them changes nothing anyone reads, and item names now set the Item
                    column's width, as the table rule intends (DESIGN_SYSTEM §5). */}
                <tr className={`${TR} !align-middle`}>
                  <td className={TD_PRIMARY} colSpan={3}>
                    {/* Held to the width it had in the Item column (the mock's), so spanning the
                        empty cells does not stretch the box across three columns on a wide screen. */}
                    <div className="max-w-xs">
                      <ItemCombobox<Offer>
                        label="Add an item"
                        firstItems={theirs}
                        firstGroupLabel={vendor ? `Sold by ${vendor.name}` : "Sold by this vendor"}
                        otherItems={everyone}
                        exclude={new Set(lines.flatMap((l) => (l.ingredientId ? [l.ingredientId] : [])))}
                        onChoose={choose}
                      />
                    </div>
                  </td>
                  <td className={TD_FIXED_NUM} />
                  <td className={TD_FIXED_NUM} />
                  <td className={TD_ACTIONS_FIXED} />
                </tr>
              </tbody>
            </table>
            <p className="mt-4 flex flex-wrap items-baseline gap-x-3 pl-5 text-ink-secondary">
              <span>Order total</span>
              <span className="text-lg font-semibold tabular-nums text-ink" data-testid="order-total">
                {money(total, "INR")}
              </span>
              <span className="text-sm">
                {lines.length} {lines.length === 1 ? "item" : "items"}
              </span>
            </p>
          </div>
        </Form>
      )}
    </FocusScreen>
  );
}

/** The unit select's value for "not in a pack". Not a uuid, so it cannot collide with a pack's id. */
const PLAIN = "plain";

/**
 * A line for an ingredient picked in the type-ahead (T-263, T-288).
 *
 * <p><b>Readable unit (D-2).</b> The quantity is typed in {@link readableUnit} — Kg for curry leaves
 * kept in grams — and the list price is restated per that unit, so the box is prefilled "60" beside
 * "Kg", never "0.06" beside "gm".
 *
 * <p><b>The vendor's pack (D-3, R-SL-3).</b> Where this vendor sells the ingredient in one of its
 * packs ("Sells it as" Bag = 25 Kg), the line starts in that pack, as the shopping list suggests it,
 * and the price is the vendor's own price per pack. Where the vendor quotes no pack price, it is the
 * list price times the pack, as the shopping list works it out (T-264). The select beside it can
 * move the line to a plain amount or another pack at any time.
 */
function catalogueLine(key: string, item: Offer, ingredient: IngredientView | undefined): DraftLine {
  const stockUnit = ingredient?.unit ?? item.unit;
  const unit = readableUnit(stockUnit);
  const packs = packChoices(ingredient);
  const vendorPack = packs.find((p) => p.id === item.vendorPackId) ?? null;
  // The list price per ONE of the ingredient's own unit, converted rather than assumed: the
  // vendor's supply row states it per its own unit.
  const perStock = pricePer(item.price, item.unit, stockUnit);
  let price: number | null;
  if (vendorPack) {
    price = item.pricePerPack ?? (perStock === null ? null : Number((perStock * vendorPack.perPackQty).toFixed(2)));
  } else {
    price = pricePer(item.price, item.unit, unit);
  }
  return {
    key,
    ingredientId: item.id,
    name: item.name,
    unit,
    stockUnit,
    packs,
    packId: vendorPack?.id ?? null,
    quantity: "",
    price: price === null ? "" : String(price),
    oneOff: false,
  };
}

/**
 * A draft line as the server takes it — in the same shape the shopping list sends (T-264), so a
 * purchase-order line has one convention whichever screen made it:
 *
 * <ul>
 * <li><b>A plain line</b> goes in the unit it was typed in, with the price per that unit: "3" beside
 * Kg is `{quantity: 3, unit: "KG", expectedPrice: 60}`. Stock receives 3,000 gm.</li>
 * <li><b>A pack line</b> goes in the ingredient's own unit, with `packSizeId` and `packCount`:
 * 4 × Bag (25 Kg) is `{quantity: 100, unit: "KG", expectedPrice: 60, packSizeId, packCount: 4}`.
 * The price is sent per that unit because that is how the server reads a pack line's price
 * (T-260); the server takes the pack as the truth for the amount and stores packs × size.</li>
 * </ul>
 *
 * <p>Both halves of a line's subject travel on every line, always: `description` is
 * required-and-nullable on `PoLineInput` so an omitted one cannot arrive as undefined and be refused
 * with KMS-400128. A blank price goes as null, and the server fills a catalogue line's from the
 * vendor's list price (T-134). A price typed on a plain line is sent exactly as typed.
 */
function toLineInput(l: DraftLine): PoLineInput {
  const blank = l.price.trim() === "";
  const pack = l.packs.find((p) => p.id === l.packId);
  if (pack) {
    const count = Number(l.quantity);
    return {
      ingredientId: l.ingredientId,
      description: null,
      quantity: Number((count * pack.perPackQty).toFixed(6)),
      unit: l.stockUnit,
      expectedPrice: blank ? null : perStockUnit(l, Number(l.price)),
      packSizeId: pack.id,
      packCount: count,
    };
  }
  return {
    ingredientId: l.ingredientId,
    description: l.oneOff ? l.name : null,
    quantity: Number(l.quantity),
    unit: l.unit,
    expectedPrice: blank ? null : Number(l.price),
  };
}

/**
 * What the price in the box is per, in words, under it: "₹60 / Kg", or for a pack line "₹1,500 /
 * bag · ₹60 / Kg" — the shared {@link readableRate}, so it reads as the sheet and the vendor page
 * do (T-288, D-8). A one-off line says nothing: the box beside its unit select already says it all,
 * and it has no catalogue unit to restate a price in.
 */
function RateNote({ line }: { line: DraftLine }) {
  if (line.oneOff || line.price.trim() === "") return null;
  const typed = Number(line.price);
  if (!Number.isFinite(typed) || typed <= 0) return null;
  const pack = line.packs.find((p) => p.id === line.packId);
  const text = pack
    ? readableRate(typed / pack.perPackQty, line.stockUnit, { label: pack.label, quantity: pack.perPackQty })
    : readableRate(typed, line.unit);
  // Each half keeps its own words together ("₹1,500 / bag", "₹60 / Kg") but the two may part at
  // the dot. Measured at 1024, one unbroken note set the price column's width and squeezed the item
  // names beside it; a rate is never split mid-figure, which is what would make it misread.
  return text ? (
    <span className="mt-1 block text-xs text-ink-muted" data-testid="rate-note">
      {text.split(" · ").map((half, i) => (
        <span key={half}>
          {i > 0 && " · "}
          <span className="whitespace-nowrap">{half}</span>
        </span>
      ))}
    </span>
  ) : null;
}

function TotalCell({ line }: { line: DraftLine }) {
  const t = lineTotal(line);
  return (
    <td className={TD_FIXED_NUM} data-label={t == null ? undefined : "Line total"}>
      {t == null ? "" : money(t, "INR")}
    </td>
  );
}

/** "Note for the vendor": one line to start with, growing with what is typed (R-PO-2). */
function GrowingNote({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const ref = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${el.scrollHeight + 2}px`;
  }, [value]);
  return (
    <textarea
      ref={ref}
      name="notes"
      rows={1}
      value={value}
      maxLength={1000}
      onChange={(e) => onChange(e.target.value)}
      className="min-h-touch w-full resize-none overflow-hidden rounded-control border border-hairline px-3 py-2.5 leading-6"
    />
  );
}
