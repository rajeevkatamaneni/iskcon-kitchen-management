"use client";

import { useRef, useState } from "react";
import { api, toApiError, type ApiError, type DeliveryLineView, type RecordDeliveryLineInput, type RejectReason } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { quantity, repeatsPack, shortDate, stepForUnit, unitLabel } from "@/lib/format";
import { wholeNumberProblem } from "@/components/ds/formMessages";
import { Badge } from "@/components/ds/Badge";
import { Button } from "@/components/ds/Button";
import { ErrorNotice } from "@/components/ErrorNotice";
import { TABLE, ENTRY_GRID, THEAD, TR, TH_TEXT, TH_NUM, TD_TEXT, TD_NUM, WRAP } from "@/components/ds/table";

/**
 * "Record a delivery" (R-DEL-3): one vendor's van, every line that vendor still owes.
 *
 * <p>The person at the gate thinks "the van from Kalasipalya is here", not "open PO-0044", so this
 * takes a vendor's open lines across all of their orders and records them in one save. The server
 * splits that save into one goods receipt per order through the existing ReceivingService, so there
 * is still one code path that moves stock; this panel only gathers what came off the van.
 *
 * <p>Its own component, not a part of the Deliveries page, because it has two callers in waiting:
 * the Deliveries screen, and possibly a per-item button on the purchase order page (Q-8 is open).
 * The PO page's case is why `orderId` exists. With it the panel lists only that order's lines, and
 * without it every line the vendor owes. The completion message still counts across everything
 * the vendor owes, which is why `lines` is always the vendor's full set and the filter is a
 * separate prop rather than something the caller applies first.
 *
 * <p>**No prices.** R-DEL-5 removed "Price paid" from delivery recording: a price belongs to the
 * invoice, and the person counting sacks at the gate should not have to look past one.
 *
 * <p>**Packs.** A line ordered as "4 × Bag (25 Kg)" is counted at the gate in bags, so its boxes
 * take bags and "Still to come" is said in bags. What is sent is the stock-unit amount (bags ×
 * the pack's size in the line's unit), because stock, batches and costing all run in the stock unit
 * (R-ING-1) and the API takes every quantity in the line's `unit`.
 */

/** What is typed into one line, kept as the strings in the boxes so a half-typed "2." survives. */
interface Entry {
  received: string;
  rejected: string;
  reason: RejectReason | "";
  expiry: string;
}

const BLANK: Entry = { received: "", rejected: "", reason: "", expiry: "" };

/** The mock's four reasons, in its order. The values are the API's `RejectReason`. */
const REASONS: { value: RejectReason; label: string }[] = [
  { value: "DAMAGED", label: "Damaged" },
  { value: "SPOILED", label: "Spoiled" },
  { value: "WRONG_ITEM", label: "Wrong item" },
  { value: "OTHER", label: "Other" },
];

/** The input box every entry on this screen uses: the 44px touch height, the control corner. */
const FIELD = "min-h-touch rounded-control border border-hairline bg-canvas px-3 tabular-nums";

/*
 * The entry grid is the mock's, with nothing of this panel's own on it (T-285, VERIFY-C defect 1).
 * T-266 gave it a spacing of its own (12px cells, 20px row ends, and pulled out over the panel's
 * padding to the card's edges), saying the mock's grid ran past its card. The verifier measured the
 * mock at 0px past, and since T-278 every entry grid gets §5 rule 4's edges from the shared
 * stylesheet (`.kms-entry-grid` in globals.css), the mock included. So the panel now uses the
 * mock's classes as they are, inside the panel's own 20px, and the spacing is whatever the shared
 * rule says for every entry grid. Measured at 1280 against the mock rendered on the same tree:
 * the same cell padding, the same heading widths, and nothing past the card (the proof has the
 * numbers).
 */

/**
 * The entry grid as cards when the panel is too narrow for it as a table, whatever the screen width
 * (T-285, VERIFY-C defect 3; §5's head rule: follow the logic, pick the lesser evil).
 *
 * <p>Below 1024px the shared stylesheet already turns every entry grid into cards. The gap is just
 * above it: from 1024px the menu is a fixed 280px column, so the card this panel sits in is 680px
 * wide and the grid has 638px. Its six columns need about 700px however they are squeezed: the
 * boxes (96px), the date box (146px) and the Reason dropdown never shrink, and the item and its
 * order number may not break inside a word. At 1024 it ran 40px past the card, which clips, so
 * "Everything arrived", "Save delivery" and the Expiry boxes were cut off and the names broke
 * mid-word. The approved mock does the same, worse: measured on the same tree at 1024 its grid is
 * 792px in 638px, 133px past the card, with the same two buttons cut off. Both first fit at 1100,
 * where the grid has 714px (mock and real both measured 714 in 714, nothing broken).
 *
 * <p>So the choice was between a table that hides things and cards a little wider than a phone's,
 * and hidden buttons are the greater evil. Below 714px of panel the rows become the same cards the
 * phone gets, from the same rules as `.kms-entry-grid` below 1024px in globals.css, restated here
 * against the panel's own width (a container query) because that stylesheet is keyed to the screen.
 * From 714px up it is the mock's table, untouched.
 *
 * <p>The limit, stated plainly: 714px is what the lines measured here need. A van with a much
 * longer single word in an item name could need more, and between 714px and that width the shared
 * fitter's last resort would apply as it does on every other table.
 */
// Written out whole, not built from a shared prefix: Tailwind finds a class only by reading it
// in the source as one string.
const CARDS_WHEN_NARROW = [
  "[@container(max-width:713.98px)]:block",
  "[@container(max-width:713.98px)]:[&>tbody]:block",
  "[@container(max-width:713.98px)]:[&>thead]:sr-only",
  "[@container(max-width:713.98px)]:[&>tbody>tr]:grid",
  "[@container(max-width:713.98px)]:[&>tbody>tr]:grid-cols-[repeat(auto-fill,minmax(9rem,1fr))]",
  "[@container(max-width:713.98px)]:[&>tbody>tr]:gap-x-4",
  "[@container(max-width:713.98px)]:[&>tbody>tr]:gap-y-3",
  "[@container(max-width:713.98px)]:[&>tbody>tr]:py-4",
  "[@container(max-width:713.98px)]:[&>tbody>tr>td]:block",
  "[@container(max-width:713.98px)]:[&>tbody>tr>td]:min-w-0",
  "[@container(max-width:713.98px)]:[&>tbody>tr>td]:!p-0",
  "[@container(max-width:713.98px)]:[&>tbody>tr>td]:border-0",
  "[@container(max-width:713.98px)]:[&>tbody>tr>td]:!whitespace-normal",
  "[@container(max-width:713.98px)]:[&>tbody>tr>td:first-child]:col-span-full",
  "[@container(max-width:713.98px)]:[&_[data-label]]:before:block",
  "[@container(max-width:713.98px)]:[&_[data-label]]:before:mb-1",
  "[@container(max-width:713.98px)]:[&_[data-label]]:before:text-xs",
  "[@container(max-width:713.98px)]:[&_[data-label]]:before:font-normal",
  "[@container(max-width:713.98px)]:[&_[data-label]]:before:text-ink-muted",
  "[@container(max-width:713.98px)]:[&_[data-label]]:before:content-[attr(data-label)]",
  "[@container(max-width:713.98px)]:[&_:is(input,select)]:max-w-full",
].join(" ");

/** toFixed mops up float dust: 2.8 × 25 must be 70, not 70.00000000000001. */
const clean = (n: number, places = 6) => Number(n.toFixed(places));

/** A line counted in packs, when it was ordered in one. Null means it is counted in its own unit. */
function packOf(l: DeliveryLineView): { label: string; size: number } | null {
  return l.packLabel && l.packQuantity && l.packQuantity > 0 ? { label: l.packLabel, size: l.packQuantity } : null;
}

/**
 * The `step` both of a line's boxes carry (T-424).
 *
 * <p><strong>A pack line is deliberately left free.</strong> Ordering is where a vendor sells whole
 * bags; receiving is not. Part of a bag really does come off a van, and this screen has supported it
 * on purpose since T-266 — "2.8 bags (70 Kg)" still to come, tested in `record-delivery-panel`. A
 * `step` of 1 on the pack box would have made the gate unable to write down what actually arrived,
 * which is a worse defect than the one T-424 is fixing.
 *
 * <p>What must be whole for a counted ingredient is therefore not the figure in the box but the
 * amount it becomes in stock — 2.8 boxes of a dozen aprons is 33.6 aprons, and that is the number
 * with no meaning. {@link RecordDeliveryPanel}'s `problems` checks exactly that, against the stock
 * amount, so both shapes of line are covered and the box stays typeable.
 */
function stepOf(l: DeliveryLineView): "1" | "any" {
  return packOf(l) ? "any" : stepForUnit(l.unit);
}

/** `"1"` when this line's ingredient is counted, whatever its boxes are counted in. */
function stockStepOf(l: DeliveryLineView): "1" | "any" {
  return stepForUnit(l.unit);
}

/** How many of the unit the boxes take one stock unit is: 1, or 1 ÷ the pack's size. */
function toEntry(l: DeliveryLineView, stockQty: number): number {
  const pack = packOf(l);
  return pack ? clean(stockQty / pack.size, 3) : stockQty;
}

/** What a typed figure is in the line's own unit, the amount the API takes. */
function toStock(l: DeliveryLineView, typed: number): number {
  const pack = packOf(l);
  return pack ? clean(typed * pack.size) : typed;
}

/** A count of packs as a person writes it: 4, 2.8, 1,200. */
function count(n: number): string {
  return new Intl.NumberFormat("en-IN", { maximumFractionDigits: 3 }).format(n);
}

/**
 * The pack's own word, "bag" for "Bag (25 Kg)", as the order document takes it
 * (`DocumentGenerationService.packWord`). Null for a pack with no name, whose label is just its size
 * ("500 gm") and has no word to count in.
 */
function packWord(label: string): string | null {
  const at = label.indexOf(" (");
  return at > 0 ? label.slice(0, at).toLowerCase() : null;
}

/** "1 bag", "2.8 bags", "3 boxes". */
function plural(word: string, n: number): string {
  if (n === 1) return word;
  return /(s|x|z|ch|sh)$/.test(word) ? `${word}es` : `${word}s`;
}

/** The unit word a pack line counts in, for `n` of them: "bags", or "× 500 gm" for an unnamed pack. */
function packUnit(label: string, n: number): string {
  const word = packWord(label);
  return word ? plural(word, n) : `× ${label}`;
}

/**
 * An amount of this line in the unit it was ordered in. The conductor's ruling (2026-09-19): a pack
 * line reads "2.8 bags (70 Kg)", the count typed at the gate and the stock it becomes; an unnamed
 * pack reads "2 × 500 gm (1 Kg)". Any other line is its plain quantity, "25 Kg".
 */
export function inLineUnit(l: DeliveryLineView, stockQty: number): string {
  const pack = packOf(l);
  if (!pack) return quantity(stockQty, l.unit);
  const n = toEntry(l, stockQty);
  const word = packWord(pack.label);
  const counted = word ? `${count(n)} ${plural(word, n)}` : `${count(n)} × ${pack.label}`;
  const stock = quantity(stockQty, l.unit);
  // One unnamed pack is its own size: "1 × 500 gm (500 gm)" says the amount twice (VERIFY2-C, T-299).
  // `n` is already rounded to the 3 places it is written to. The rule is lib/format's (T-303).
  return repeatsPack(pack.label, n) ? counted : `${counted} (${stock})`;
}

/** What goes beside a box: the pack's word for what is typed ("bags"), or the unit ("Kg"). */
function boxUnit(l: DeliveryLineView, typed: string): string {
  const pack = packOf(l);
  return pack ? packUnit(pack.label, Number(typed || 0)) : unitLabel(l.unit);
}

/**
 * A date with the blue "Today" pill beside it when it is the temple's today (R-DEL-5). `today` comes
 * from the server's `DeliveriesView.today`, never the browser's clock.
 */
export function DeliveryDay({ iso, today, late = false }: { iso: string; today: string; late?: boolean }) {
  const lateBy = daysBetween(iso, today);
  const isToday = iso === today;
  const lateNow = late && lateBy > 0;
  // The date is kept whole: "12 Sept" broke into "12" and "Sept" in a narrow column at 1280.
  if (!isToday && !lateNow) return <span className="whitespace-nowrap">{shortDate(iso)}</span>;
  return (
    <span className="inline-flex flex-wrap items-center gap-x-2 gap-y-1 align-baseline">
      <span className="whitespace-nowrap">{shortDate(iso)}</span>
      {isToday && <Badge tone="info">Today</Badge>}
      {lateNow && <Badge tone="warning">{days(lateBy)} late</Badge>}
    </span>
  );
}

/** Calendar days from one ISO date to another, counted in UTC so no time zone can shift one. */
export function daysBetween(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000);
}

export function days(n: number): string {
  return n === 1 ? "1 day" : `${n} days`;
}

/** "30 of 50 Kg" when both halves are in the same unit, "500 gm of 1 Kg" when they are not. */
export function ofTotal(got: number, ordered: number, unit: string): string {
  const a = quantity(got, unit);
  const b = quantity(ordered, unit);
  const unitA = a.slice(a.lastIndexOf(" ") + 1);
  const unitB = b.slice(b.lastIndexOf(" ") + 1);
  return unitA === unitB ? `${a.slice(0, a.lastIndexOf(" "))} of ${b}` : `${a} of ${b}`;
}

/**
 * A name that may wrap between its words and never inside one. A hyphen is a break the browser
 * takes on its own ("VERIFY-" above "C Milk"), so each word goes in a piece of its own that does
 * not wrap, with ordinary spaces between them.
 */
function WholeWords({ text }: { text: string }) {
  const words = text.split(" ");
  return (
    <>
      {words.map((w, i) => (
        <span key={i}>
          <span className="whitespace-nowrap">{w}</span>
          {i < words.length - 1 ? " " : null}
        </span>
      ))}
    </>
  );
}

export function RecordDeliveryPanel({
  vendorId,
  vendorName,
  lines,
  orderId = null,
  today,
  onCancel,
  onSaved,
}: {
  vendorId: string;
  vendorName: string;
  /** Every line this vendor still owes, across all their open orders. */
  lines: DeliveryLineView[];
  /** List only this order's lines (the `?order=` link from the purchase order page). */
  orderId?: string | null;
  /** The temple's today, from `DeliveriesView.today`. */
  today: string;
  onCancel: () => void;
  /** Called once the server has recorded it, with the confirmation to show. */
  onSaved: (confirmation: string) => void;
}) {
  const { getToken } = useAuth();
  const shown = orderId ? lines.filter((l) => l.poId === orderId) : lines;
  const owed = (l: DeliveryLineView) => toEntry(l, l.stillToCome);

  // Blank on both tabs, the conductor's ruling (2026-09-19), where the mock started the Partly
  // delivered panel filled in. R-DEL-3 makes "Everything arrived" the way to fill Received, and
  // says the rest of a partial delivery is recorded "the same way. Nothing new to learn", so the
  // panel behaves the same wherever it is opened.
  const [entries, setEntries] = useState<Record<string, Entry>>({});
  const [tried, setTried] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<ApiError | null>(null);

  // One idempotency key per attempt. Pressing Save again after a failure, with nothing changed, is
  // a retry of the same attempt and must carry the same key, so that a save the server did record
  // (and whose answer was lost on the way back) is not recorded twice. Changing any box makes it a
  // new attempt with a new key.
  const attempt = useRef<{ key: string; body: string } | null>(null);

  const get = (id: string) => entries[id] ?? BLANK;
  const set = (id: string, patch: Partial<Entry>) =>
    setEntries((e) => ({ ...e, [id]: { ...(e[id] ?? BLANK), ...patch } }));

  function everythingArrived() {
    setEntries((e) => {
      const next = { ...e };
      for (const l of shown) next[l.poLineId] = { ...(e[l.poLineId] ?? BLANK), received: String(owed(l)) };
      return next;
    });
  }

  /** What is wrong with a line, if anything, in the words that go under its box. */
  function problems(l: DeliveryLineView): { received?: string; rejected?: string; reason?: string } {
    const e = get(l.poLineId);
    const out: { received?: string; rejected?: string; reason?: string } = {};
    const received = Number(e.received || 0);
    const rejected = Number(e.rejected || 0);
    const still = owed(l);
    /*
     * A fraction of a counted thing is said before anything about how much is owed (T-424).
     * "2.4 mops is more than is still to come" answers the wrong question about a figure that is
     * not a number of mops at all, so the whole-number sentence takes the slot and the arithmetic
     * below is not reached for that box. The reason rule still runs: it is about the select, not
     * about either figure.
     *
     * Judged on the STOCK amount and not on what is typed, because a pack line's box counts bags
     * and part of a bag is a real delivery — see `stepOf` above. 2.8 bags of a dozen aprons is
     * 33.6 aprons, and it is the aprons that cannot be fractional. For a line counted in its own
     * unit the two figures are the same number, so this is one rule and not two.
     *
     * Named from the box's own accessible name up to its comma. The full name ends ", in Kg",
     * which cannot take "must be a whole number" after it and still be a sentence. Since T-431 the
     * name is only the fallback: with the item and its unit the sentence is "Apron is counted in
     * whole pieces", which needs no name at all and is the same words on every other screen. The
     * unit is the ITEM's, never the pack's — 2.8 bags is refused because 33.6 aprons is not a
     * number of aprons, and `PACKS` would say a bag cannot be split, which here it can.
     */
    const step = stockStepOf(l);
    const counted = { subject: l.itemName, unit: l.unit };
    const wholeReceived = wholeNumberProblem(`${l.itemName} received now`, step, toStock(l, received), counted);
    const wholeRejected = wholeNumberProblem(
      `${l.itemName} rejected on delivery`,
      step,
      toStock(l, rejected),
      counted
    );
    if (wholeRejected) out.rejected = wholeRejected;
    if (wholeReceived || wholeRejected) {
      if (wholeReceived) out.received = wholeReceived;
      if (rejected > 0 && !e.reason) out.reason = "Choose why it was rejected";
      return out;
    }
    // What came off the van is what was kept plus what was refused, and that cannot be more than
    // is owed. The usual way to hit this: press Everything arrived, then refuse 2 Kg of curd
    // without taking the 2 Kg off Received.
    if (received > still + 1e-9) out.received = `Only ${inLineUnit(l, l.stillToCome)} is still to come`;
    else if (received + rejected > still + 1e-9)
      out.received = `Received and rejected add up to more than the ${inLineUnit(l, l.stillToCome)} still to come`;
    if (rejected > 0 && !e.reason) out.reason = "Choose why it was rejected";
    return out;
  }

  const anything = shown.some((l) => Number(get(l.poLineId).received || 0) > 0 || Number(get(l.poLineId).rejected || 0) > 0);
  const hasProblems = shown.some((l) => Object.keys(problems(l)).length > 0);

  async function save() {
    setTried(true);
    if (!anything || hasProblems || busy) return;
    const input: RecordDeliveryLineInput[] = [];
    for (const l of shown) {
      const e = get(l.poLineId);
      const received = Number(e.received || 0);
      const rejected = Number(e.rejected || 0);
      if (received <= 0 && rejected <= 0) continue;
      input.push({
        poLineId: l.poLineId,
        receivedQty: toStock(l, received),
        rejectedQty: toStock(l, rejected),
        rejectReason: rejected > 0 ? (e.reason as RejectReason) : null,
        expiryDate: e.expiry || null,
      });
    }
    const body = JSON.stringify(input);
    if (!attempt.current || attempt.current.body !== body) attempt.current = { key: crypto.randomUUID(), body };

    setBusy(true);
    setFailure(null);
    try {
      await api.recordDelivery({ vendorId, idempotencyKey: attempt.current.key, lines: input }, await getToken());
    } catch (caught) {
      setFailure(toApiError(caught, "We couldn’t record that delivery."));
      setBusy(false);
      return;
    }
    setBusy(false);
    onSaved(confirmation(input));
  }

  /**
   * The mock's confirmation: what went into stock, what this delivery finished, and what is still to
   * come from this vendor. Worked out from what was sent and the lines as last loaded, so it can be
   * said before the reload lands.
   */
  function confirmation(input: RecordDeliveryLineInput[]): string {
    const byId = new Map(input.map((i) => [i.poLineId, i]));
    const after = (l: DeliveryLineView) => clean(l.stillToCome - (byId.get(l.poLineId)?.receivedQty ?? 0), 3);
    // Name what this delivery finished, so a completed part delivery is seen to complete.
    const finished = shown
      .filter((l) => byId.has(l.poLineId) && l.receivedQty > 0 && after(l) <= 0)
      .map((l) => `${l.itemName} is complete: ${ofTotal(clean(l.receivedQty + byId.get(l.poLineId)!.receivedQty, 3), l.orderedQty, l.unit)}.`);
    const still = lines.filter((l) => after(l) > 0).length;
    const n = input.filter((i) => i.receivedQty > 0).length;
    return [
      `Delivery from ${vendorName} recorded. ${n} ${n === 1 ? "item" : "items"} went into stock.`,
      ...finished,
      still > 0 ? `${still} ${still === 1 ? "line is" : "lines are"} still to come from them.` : "Nothing more is owed by them.",
    ].join(" ");
  }

  return (
    // 16px sides on a phone rather than 20px: at 390 the 20px version left the entry grid 301px,
    // 3px short of two 144px boxes side by side, and every box fell onto a row of its own.
    // `[container-type:inline-size]` makes the panel the box the grid's container query measures,
    // and also stops the grid's own width from pushing the panel wider than its card.
    <div className="grid gap-4 border-t border-hairline bg-raised px-4 py-5 [container-type:inline-size] lg:px-5">
      <div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3">
        <div className="grid min-w-0 grow basis-60 gap-1">
          <h3 className="font-semibold text-ink">Record a delivery from {vendorName}</h3>
          <p className="text-sm text-ink-secondary">
            Type what came off the van. Leave a line blank if it did not come. Rejected goods stay owed and never enter stock.
          </p>
        </div>
        <Button variant="ghost" icon="checks" onClick={everythingArrived}>
          Everything arrived
        </Button>
      </div>

      {/* The entry grid: spaced like every other table from 1024px up, and below that each line
          becomes a card with a small label over every box (the `data-label` on each cell).
          `max-lg:[&_tbody_td]:border-0`: on a phone card the base table style would still draw a
          rule above every labelled box, which read as six separate rows rather than one item. */}
      <table className={`${TABLE} ${ENTRY_GRID} ${CARDS_WHEN_NARROW} text-sm max-lg:[&_tbody_td]:border-0`}>
        <thead className={THEAD}>
          <tr>
            {/* WRAP marks the columns that may give way when the card is short of room: the
                headings and notes wrap between words, and the boxes never shrink. */}
            <th className={`${TH_TEXT} ${WRAP}`}>Item</th>
            <th className={`${TH_NUM} ${WRAP}`}>Still to come</th>
            <th className={`${TH_NUM} ${WRAP}`}>Received now</th>
            <th className={`${TH_NUM} ${WRAP}`}>Rejected on delivery</th>
            <th className={`${TH_TEXT} ${WRAP}`}>Reason</th>
            {/* On every line and optional. The mock asked for it on perishables only, and nothing
                in the data says which items are perishable (the clarifier: NOT SETTLED), so this
                keeps what the purchase order page does today until that is decided. */}
            <th className={TH_TEXT}>Expiry</th>
          </tr>
        </thead>
        <tbody>
          {shown.map((l) => {
            const e = get(l.poLineId);
            const p = tried ? problems(l) : {};
            const refused = l.parts.filter((x) => x.rejectedQty > 0);
            return (
              <tr key={l.poLineId} className={TR}>
                <td className={`${TD_TEXT} ${WRAP}`}>
                  {/* Words are kept whole, a hyphenated one included, and so is the order number:
                      at 1024 "VERIFY-C Milk" broke after its hyphen and "PO-2026-0049" into three
                      (VERIFY-C defect 3). The fitter measures a nowrap piece as one word, so the
                      column is never given less than the longest of them. */}
                  <span className="block font-medium text-ink">
                    <WholeWords text={l.itemName} />
                  </span>
                  <span className="block whitespace-nowrap text-xs font-normal text-ink-muted">{l.poNumber}</span>
                </td>
                <td className={`${TD_NUM} ${WRAP}`} data-label="Still to come">
                  <span className="inline-flex min-h-touch items-center">{inLineUnit(l, l.stillToCome)}</span>
                  {/* Why a vendor who "sent 32 of 50" still owes 20: say so where the 20 is. */}
                  {refused.map((r) => (
                    <span key={r.receiptId} className="block text-xs text-ink-muted">
                      includes {quantity(r.rejectedQty, l.unit)} rejected <DeliveryDay iso={r.receivedOn} today={today} />
                    </span>
                  ))}
                </td>
                <td className={`${TD_NUM} ${WRAP}`} data-label="Received now">
                  {/* The box and its unit stay together. The stock amount beside them may drop under
                      them when there is no room, rather than run past the card: box, "bags" and
                      "(70 Kg)" need 186px, and a phone card's cell at 390 has 154. */}
                  <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
                    <span className="flex items-center gap-2">
                      <input
                        type="number"
                        inputMode={stepOf(l) === "1" ? "numeric" : "decimal"}
                        min="0"
                        step={stepOf(l)}
                        value={e.received}
                        onChange={(ev) => set(l.poLineId, { received: ev.target.value })}
                        aria-label={`${l.itemName} received now, in ${packOf(l)?.label ?? unitLabel(l.unit)}`}
                        aria-invalid={p.received ? true : undefined}
                        aria-describedby={p.received ? `recv-err-${l.poLineId}` : undefined}
                        className={`${FIELD} min-w-24 ${p.received ? "border-danger" : ""}`}
                      />
                      <span className="whitespace-nowrap text-ink-secondary">{boxUnit(l, e.received)}</span>
                    </span>
                    {/* A pack line says what the typed bags become in stock, beside them, so "4"
                        is seen to mean 100 Kg before it is saved (the conductor's ruling). */}
                    {/* Not when it would repeat the unit beside the box: "1" beside "× 500 gm" is
                        500 gm, and "(500 gm)" after it says so twice (VERIFY2-C, T-299). */}
                    {packOf(l) && Number(e.received) > 0 && !repeatsPack(packOf(l)!.label, Number(e.received)) && (
                      <span className="whitespace-nowrap text-ink-muted">
                        ({quantity(toStock(l, Number(e.received)), l.unit)})
                      </span>
                    )}
                  </span>
                  {p.received && (
                    <span id={`recv-err-${l.poLineId}`} className="mt-1 block text-xs text-danger">
                      {p.received}
                    </span>
                  )}
                </td>
                <td className={`${TD_NUM} ${WRAP}`} data-label="Rejected on delivery">
                  <span className="flex items-center gap-2">
                    <input
                      type="number"
                      inputMode={stepOf(l) === "1" ? "numeric" : "decimal"}
                      min="0"
                      step={stepOf(l)}
                      value={e.rejected}
                      onChange={(ev) => set(l.poLineId, { rejected: ev.target.value })}
                      aria-label={`${l.itemName} rejected on delivery, in ${packOf(l)?.label ?? unitLabel(l.unit)}`}
                      aria-invalid={p.rejected ? true : undefined}
                      aria-describedby={p.rejected ? `rej-err-${l.poLineId}` : undefined}
                      className={`${FIELD} min-w-24 ${p.rejected ? "border-danger" : ""}`}
                    />
                    <span className="whitespace-nowrap text-ink-secondary">{boxUnit(l, e.rejected)}</span>
                  </span>
                  {/* This box had no sentence of its own until T-424, because nothing it could
                      hold was refused on its own account; a fraction of a counted thing is. */}
                  {p.rejected && (
                    <span id={`rej-err-${l.poLineId}`} className="mt-1 block text-xs text-danger">
                      {p.rejected}
                    </span>
                  )}
                </td>
                <td className={`${TD_TEXT} ${WRAP}`} data-label="Reason">
                  <select
                    value={e.reason}
                    onChange={(ev) => set(l.poLineId, { reason: ev.target.value as RejectReason | "" })}
                    disabled={!(Number(e.rejected || 0) > 0)}
                    aria-label={`Why ${l.itemName} was rejected`}
                    aria-invalid={p.reason ? true : undefined}
                    aria-describedby={p.reason ? `reason-err-${l.poLineId}` : undefined}
                    className={`${FIELD} disabled:opacity-45 ${p.reason ? "border-danger" : ""}`}
                  >
                    <option value="">Choose</option>
                    {REASONS.map((r) => (
                      <option key={r.value} value={r.value}>
                        {r.label}
                      </option>
                    ))}
                  </select>
                  {p.reason && (
                    <span id={`reason-err-${l.poLineId}`} className="mt-1 block text-xs text-danger">
                      {p.reason}
                    </span>
                  )}
                </td>
                <td className={TD_TEXT} data-label="Expiry">
                  <input
                    type="date"
                    value={e.expiry}
                    onChange={(ev) => set(l.poLineId, { expiry: ev.target.value })}
                    aria-label={`${l.itemName} expiry date`}
                    className={FIELD}
                  />
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>

      {tried && !anything && (
        <p className="text-sm text-danger">Type what arrived on at least one line, or press Everything arrived.</p>
      )}

      {failure && <ErrorNotice error={failure} />}

      <div className="flex flex-wrap justify-end gap-3">
        <Button variant="ghost" onClick={onCancel} className="max-sm:flex-1">
          Cancel
        </Button>
        <Button onClick={save} busy={busy} className="max-sm:flex-1">
          Save delivery
        </Button>
      </div>
    </div>
  );
}
