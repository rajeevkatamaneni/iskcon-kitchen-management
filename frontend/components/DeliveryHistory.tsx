"use client";

import { useId, useState } from "react";
import type { DeliveryPartView, RejectReason } from "@/lib/api";
import { quantity, shortDate, todayIso } from "@/lib/format";
import { Badge } from "@/components/ds/Badge";

/**
 * Every delivery of one order line, folded away under a quiet "▸ 2 deliveries" (R-DEL-4).
 *
 * <p>Built once for three places: the Deliveries screen's Partly delivered and Received tabs, and
 * the purchase order's merged items table (R-PO-4). The requirement is that the breakdown reads
 * identically wherever it is opened, and the only way to keep three screens saying the same words is
 * for there to be one copy of them. So this takes plain props and fetches nothing: each screen
 * already holds a `DeliveryLineView`, and handing this its `parts`, `unit`, `orderedQty` and
 * `completedOn` is the whole integration.
 *
 * <p>`completedOn` is taken from the server rather than worked out here, although the approved mock
 * derived it from the parts. The backend already computes it for `DeliveryLineView` (the date of
 * the part that brought the kept total up to the order), and a second copy of that rule in the
 * browser is a second place for "complete" to mean something different. The received total, on the
 * other hand, is summed from the parts shown, so the closing line always adds up to the lines above
 * it on the screen.
 *
 * <p>Closed by default: most of the time the row is enough, and six of these open on a vegetable
 * delivery would bury the table. It is a real `<button>` with `aria-expanded` and `aria-controls`,
 * so Tab reaches it and Enter or Space opens it with no key handling of our own. Its hit area is the
 * 44px the design system asks of every target, pulled back into the line with negative margins so
 * it takes a text line's room in a table cell.
 *
 * <p>Secondary text throughout, and no status colour: nothing in a history needs acting on, and a
 * completion date is a settled fact, which the colour rule keeps neutral. The one exception is the
 * blue "Today" pill beside a date that is today, which R-DEL-5 asks of every date on the Deliveries
 * screen; the date itself is always written, and the pill only sits beside it.
 *
 * <p>**No order number** appears anywhere in here (R-DEL-4). On the Deliveries tabs the order is
 * already shown twice in the same row, and on the purchase order page it is the page.
 */
export function DeliveryHistory({
  parts,
  unit,
  orderedQty,
  completedOn,
  itemName,
  today,
}: {
  /** The line's deliveries. Sorted here by date, oldest first, whatever order they arrive in. */
  parts: DeliveryPartView[];
  /** The line's unit as the API sends it ("KG", "GM"); quantities are shown in the readable one. */
  unit: string;
  orderedQty: number;
  /** The date the line was completed, or null while anything is still to come. */
  completedOn: string | null;
  /**
   * The item's name, spoken to a screen reader after the count ("2 deliveries of Rice") and never
   * shown. A table of these would otherwise be a column of identical "2 deliveries" buttons.
   */
  itemName?: string;
  /**
   * The temple's today, for the Today pill. The Deliveries screen passes the server's
   * (`DeliveriesView.today`, VERIFY-C minor 6), so the history and the rows around it agree on which
   * day is today even on a tablet with the wrong date. Left out, it is the browser's date, which is
   * what the purchase order page, the one other caller, has always had.
   */
  today?: string;
}) {
  const [open, setOpen] = useState(false);
  const id = useId();
  if (parts.length === 0) return null;
  return (
    <div>
      <DeliveryHistoryToggle
        count={parts.length}
        open={open}
        onToggle={() => setOpen((o) => !o)}
        controls={id}
        itemName={itemName}
      />
      {open && (
        <DeliveryHistoryList
          id={id}
          parts={parts}
          unit={unit}
          orderedQty={orderedQty}
          completedOn={completedOn}
          today={today}
          className="mt-1"
        />
      )}
    </div>
  );
}

/**
 * The "▸ 2 deliveries" button on its own, for a table that shows the opened history somewhere
 * other than directly under it (T-343).
 *
 * <p>The Deliveries screen's two tables put the button in the Item cell, where it belongs to the
 * item, and the opened history in a row of its own under the item's row, spanning the whole table,
 * as the purchase order page already did (T-265) and the mock draws it. Inside the Item cell, at
 * 1024, the history was held to that column's 115px while the five columns beside it held one line
 * each: measured on VERIFY-B Rice, a 413px row with about 560px of it empty (VERIFY3, F-1). The
 * table owns the open state, so the two halves are one control: `controls` is the list's id.
 */
export function DeliveryHistoryToggle({
  count,
  open,
  onToggle,
  controls,
  itemName,
}: {
  count: number;
  open: boolean;
  onToggle: () => void;
  /** The id {@link DeliveryHistoryList} is given, for `aria-controls`. */
  controls: string;
  itemName?: string;
}) {
  return (
    <button
      type="button"
      aria-expanded={open}
      aria-controls={controls}
      onClick={onToggle}
      className="-my-2.5 flex min-h-touch w-fit items-center gap-1 whitespace-nowrap rounded-control text-start text-sm font-normal text-ink-secondary hover:text-ink"
    >
      <span aria-hidden="true" className="inline-block w-3">
        {open ? "▾" : "▸"}
      </span>
      {count === 1 ? "1 delivery" : `${count} deliveries`}
      {itemName ? <span className="sr-only"> of {itemName}</span> : null}
    </button>
  );
}

/** The opened history: one line per delivery, then the received total (see {@link DeliveryHistory}). */
export function DeliveryHistoryList({
  id,
  parts,
  unit,
  orderedQty,
  completedOn,
  today,
  className = "",
}: {
  id: string;
  parts: DeliveryPartView[];
  unit: string;
  orderedQty: number;
  completedOn: string | null;
  today?: string;
  className?: string;
}) {
  const todayIs = today ?? todayIso();
  // Stable sort by the ISO date string: two parts on one day keep the order the server gave them.
  const inOrder = [...parts].sort((a, b) => a.receivedOn.localeCompare(b.receivedOn));
  // toFixed mops up float dust: 0.1 + 0.2 must read 0.3, not 0.30000000000000004.
  const got = Number(inOrder.reduce((sum, p) => sum + p.receivedQty, 0).toFixed(3));
  return (
    <ol id={id} className={`grid gap-1 ps-4 text-sm font-normal text-ink-secondary ${className}`}>
      {inOrder.map((p, i) => (
        <li key={`${p.receiptId}-${i}`}>
          <Day iso={p.receivedOn} today={todayIs} />
          {partPieces(p, unit)
            // Each piece kept whole, at every width, so a wrap falls between pieces and never inside
            // a name: the mock found "Received by: Govinda Das" splitting before "Das".
            //
            // T-299 let the pieces wrap from 1024 up, because the history then sat in a fitted
            // table's Item column and a piece kept whole was that column's floor: it broke
            // "PO-2026-0054" in the Order column instead. Every caller now gives the opened history
            // the whole width of the table or card (T-343), and a cell spanning the row is left out
            // of the fitter's measuring, so a whole piece holds no column open any more.
            .map((bit) => (
              <span key={bit}>
                {" · "}
                <span className="whitespace-nowrap">{bit}</span>
              </span>
            ))}
        </li>
      ))}
      <li className="font-medium">
        Received {ofTotal(got, orderedQty, unit)} ordered
        {completedOn ? (
          <>
            {" · complete "}
            <Day iso={completedOn} today={todayIs} />
          </>
        ) : null}
      </li>
    </ol>
  );
}

/** The words for why goods were refused, lowercase because they sit inside a sentence's brackets. */
const REASON: Record<RejectReason, string> = {
  DAMAGED: "damaged",
  SPOILED: "spoiled",
  WRONG_ITEM: "wrong item",
  OTHER: "other",
};

/**
 * The pieces after the date: "30 Kg received", "2 Kg rejected (spoiled)", "Received by: …".
 *
 * <p>The rejection is left out when nothing was rejected, and so is the receiver when the server has
 * no name for them: "Received by:" with nothing after it tells the reader less than silence does.
 */
function partPieces(p: DeliveryPartView, unit: string): string[] {
  const out = [`${quantity(p.receivedQty, unit)} received`];
  if (p.rejectedQty > 0) {
    const why = p.rejectReason ? ` (${REASON[p.rejectReason]})` : "";
    out.push(`${quantity(p.rejectedQty, unit)} rejected${why}`);
  }
  // Rajeev, 2026-09-19 (quoted in the mock): "Received by:" with its label.
  if (p.receivedByName) out.push(`Received by: ${p.receivedByName}`);
  return out;
}

/**
 * "30 of 50 Kg" when both halves land in the same unit, "500 gm of 1 Kg" when they do not, as the
 * mock has it. Both halves go through `quantity`, so a gram line of 1,000 or more reads in Kg.
 */
function ofTotal(got: number, ordered: number, unit: string): string {
  const a = quantity(got, unit);
  const b = quantity(ordered, unit);
  const unitA = a.slice(a.lastIndexOf(" ") + 1);
  const unitB = b.slice(b.lastIndexOf(" ") + 1);
  return unitA === unitB ? `${a.slice(0, a.lastIndexOf(" "))} of ${b}` : `${a} of ${b}`;
}

/** "12 Sept", with the blue "Today" pill beside it when it is the temple's today (R-DEL-5). */
function Day({ iso, today }: { iso: string; today: string }) {
  if (iso !== today) return <>{shortDate(iso)}</>;
  return (
    <span className="inline-flex flex-wrap items-center gap-x-2 gap-y-1 align-baseline">
      {shortDate(iso)}
      <Badge tone="info">Today</Badge>
    </span>
  );
}
