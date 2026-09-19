"use client";

import { Tooltip } from "@/components/ds/Tooltip";
import { money } from "@/lib/format";

/**
 * An invoice's totals, laid out like the foot of a printed bill (R-INV-5; design A of the
 * `dev-invoices` mock, `TotalsFields` and `AddsUp`; T-273).
 *
 * <p><b>Two modes, the same labels.</b> "Create an invoice" types the figures in (`readOnly`
 * absent); the invoice's own page (R-INV-7) shows the saved ones (`readOnly`). Same labels, same right
 * edge, same tick and red line, so a person who has typed a bill in recognises it when it comes back.
 * R-INV-5: "These labels are used everywhere."
 *
 * <p><b>Why the read-only block is not the typed one with the boxes taken out</b> (T-277). It used to
 * be: every figure in a 44px box-shaped space, an "Amount (₹)" heading and bare figures, so that its
 * digits ended exactly where the typed boxes' do. That is right on the form, where there are boxes
 * to line up with, and wrong on the invoice's page, where there are none. Measured on the page, it
 * made the foot of the Items card 342px tall where mock design D's is 171 (T-274). Design D draws the
 * saved foot as a plain list: 20px rows 8px apart, a rupee sign on every figure, and the discount as
 * "− ₹2.50". R-INV-5 fixes the labels, the one right edge, the tick and the red line, and says
 * nothing about height, so the mock is the specification there and {@link ReadOnlyTotals} is built
 * to it. Both modes keep what R-INV-5 does fix.
 *
 * <p><b>Why it is not a table, and why its figures read right.</b> The table rule (DESIGN_SYSTEM §5,
 * everything left-aligned) is about tables. This is a sum, and a sum is read by lining the figures
 * up on their last digit, as a bill does: a label down the left of the block, its figure down the
 * right, every figure ending on one right edge. The typed boxes are right-aligned for the same
 * reason, and a figure that is only read (the Sub total, or every figure in read-only mode) sits in
 * a box-shaped space with the same padding and an invisible border, so its last digit ends exactly
 * where the boxes' do. That is the mock's own construction, kept.
 *
 * <p><b>Three columns</b>: the label, the figure, and a narrow slot after the figure that only the
 * Grand total's tick uses, so the tick never pushes that box out of line with the others.
 *
 * <p><b>The check</b>, Sub total + GST + Other charges − Discount = Grand total, to the paisa:
 * <ul>
 *   <li>When it holds: a green tick beside the Grand total, named and explained "Adds up to the
 *       grand total", and no sentence (Rajeev, 2026-09-19: the sentence spelling the sum out was "too
 *       much"). Green because it confirms figures the person typed, the one use of green the colour
 *       rule allows.</li>
 *   <li>When it does not: one short red line under the Grand total, "Adds up to ₹1,030, not
 *       ₹1,000". The page refuses to save ({@link totalsCheck} is what it asks), and the server
 *       refuses as well (KMS-400168), because the endpoint is reachable without this screen.</li>
 * </ul>
 *
 * <p><b>The tip uses the shared {@link Tooltip}.</b> The mock drew its own, because the shared one
 * was centred under its control and ran off the right edge beside the Grand total. R-INV-5 asked for
 * the shared component to be fixed rather than worked around; T-256 fixed it (it slides back inside
 * the window), and T-273 measured it here at 1280 and 390.
 */

/** What the person has typed from the foot of the bill: the text in each box, so "24." survives. */
export interface InvoiceTotalsDraft {
  gst: string;
  otherCharges: string;
  otherChargesNote: string;
  discount: string;
  grandTotal: string;
}

export const EMPTY_TOTALS: InvoiceTotalsDraft = {
  gst: "",
  otherCharges: "",
  otherChargesNote: "",
  discount: "",
  grandTotal: "",
};

/** Typed, from the form. `subTotal` is the sum of the line amounts, which this never asks for. */
export interface EditableInvoiceTotalsProps {
  readOnly?: false;
  subTotal: number;
  value: InvoiceTotalsDraft;
  onChange: (value: InvoiceTotalsDraft) => void;
  /** Save was pressed: an empty Grand total is shown as missing. */
  tried?: boolean;
}

/** Saved, for the invoice's own page. The figures as the server holds them. */
export interface ReadOnlyInvoiceTotalsProps {
  readOnly: true;
  subTotal: number;
  gstAmount: number;
  otherCharges: number;
  otherChargesNote: string | null;
  discount: number;
  grandTotal: number;
}

export type InvoiceTotalsProps = EditableInvoiceTotalsProps | ReadOnlyInvoiceTotalsProps;

const round2 = (n: number) => Math.round(n * 100) / 100;

/** A box's figure, or null when it is empty or not a number. */
export function figure(text: string): number | null {
  if (text.trim() === "") return null;
  const n = Number(text);
  return Number.isFinite(n) ? n : null;
}

/**
 * Whether the figures come to the grand total. `computed` is what they add up to, `grand` the total
 * typed from the bill (null until typed), and `matches` is true only when both exist and agree to
 * the paisa. The page saves only when `matches`.
 */
export function totalsCheck(
  subTotal: number,
  gst: number | null,
  otherCharges: number | null,
  discount: number | null,
  grand: number | null,
): { computed: number; grand: number | null; matches: boolean } {
  const computed = round2(subTotal + (gst ?? 0) + (otherCharges ?? 0) - (discount ?? 0));
  const matches = grand != null && Math.abs(grand - computed) < 0.005;
  return { computed, grand, matches };
}

/** The check, from what is typed. */
export function draftCheck(subTotal: number, d: InvoiceTotalsDraft) {
  return totalsCheck(subTotal, figure(d.gst), figure(d.otherCharges), figure(d.discount), figure(d.grandTotal));
}

/** The one red line under the Grand total when the figures disagree (R-INV-5's wording). */
export function addsUpMessage(computed: number, grand: number): string {
  return `Adds up to ${money(computed, "INR")}, not ${money(grand, "INR")}`;
}

/** The 44px box every typed figure sits in; right-aligned, because it is a sum. */
const BOX =
  "min-h-touch w-32 rounded-control border border-hairline bg-canvas px-3 text-right tabular-nums";
/** The same shape for the form's one figure that is only read (the Sub total), so its last digit ends
 *  where a box's does. */
const READ = "inline-flex min-h-touch w-32 items-center justify-end border border-transparent px-3 tabular-nums text-ink";
const NAME = "text-ink-secondary";
/** A rupee figure without its sign: the column says "Amount (₹)" once, as a printed bill does. */
const bare = (n: number) => money(n, "INR").replace("₹", "");
/** A label in the read-only list: design D's 20px gap before the figure column. */
const LABEL = "pe-5 text-ink-secondary";

/** Beside the Grand total when everything adds up: a green tick, named, with the same words as a tip. */
function AddsUp() {
  const words = "Adds up to the grand total";
  return (
    <Tooltip text={words}>
      <span
        role="img"
        aria-label={words}
        tabIndex={0}
        // `flex`, not the mock's `inline-flex`: the shared Tooltip wraps its control in a span, and an
        // inline tick in it sits on a line box that set it 1-2px off the mock's (measured, and
        // pixel-compared against the mock).
        className="flex h-6 w-6 items-center justify-center rounded-control text-success"
      >
        <i className="ti ti-circle-check text-xl" aria-hidden="true" />
      </span>
    </Tooltip>
  );
}

export function InvoiceTotals(props: InvoiceTotalsProps) {
  return props.readOnly ? <ReadOnlyTotals {...props} /> : <EditableTotals {...props} />;
}

/** The form's block: typed boxes, laid out as design A draws it (T-273, pixel-compared). */
function EditableTotals(props: EditableInvoiceTotalsProps) {
  const check = draftCheck(props.subTotal, props.value);
  const wrong = check.grand != null && !check.matches;
  const missing = !!props.tried && check.grand == null;

  // Each box is named by the <label> wrapping its row, so its accessible name is the row's label.
  const typed = (key: "gst" | "otherCharges" | "discount") => (
    <input
      type="number"
      inputMode="decimal"
      min="0"
      step="any"
      name={key}
      value={props.value[key]}
      onChange={(e) => props.onChange({ ...props.value, [key]: e.target.value })}
      className={BOX}
    />
  );

  return (
    <div className="grid w-full gap-3 sm:ms-auto sm:max-w-sm" data-testid="invoice-totals">
      <div className="grid grid-cols-[1fr_auto_1.5rem] items-center gap-x-3 gap-y-3">
        {/* The column's unit said once over the figures, rather than a rupee sign in every box. */}
        <span />
        <span className="px-3 text-right text-xs text-ink-muted">Amount (₹)</span>
        <span />

        <div className="contents">
          <span className={NAME}>Sub total</span>
          <span className={READ} data-testid="sub-total">
            {bare(props.subTotal)}
          </span>
          <span />
        </div>

        <label className="contents">
          <span className={NAME}>GST</span>
          {typed("gst")}
          <span />
        </label>
        <label className="contents">
          <span className={NAME}>Other charges</span>
          {typed("otherCharges")}
          <span />
        </label>
        {/* R-INV-5's "(plus a note)". The mock has no note: it spans the label and the figure
            columns because a note in a 128px box would be unreadable, and it sits straight under
            the charge it explains. Optional: a delivery charge often needs no explaining. */}
        <label className="col-span-2 grid gap-1">
          <span className="text-sm text-ink-secondary">What the other charges are for</span>
          <input
            name="otherChargesNote"
            maxLength={500}
            value={props.value.otherChargesNote}
            onChange={(e) => props.onChange({ ...props.value, otherChargesNote: e.target.value })}
            className="min-h-touch w-full rounded-control border border-hairline bg-canvas px-3"
          />
        </label>
        <span />
        <label className="contents">
          <span className={NAME}>Discount</span>
          {typed("discount")}
          <span />
        </label>

        <div className="col-span-3 border-t border-hairline" />

        <label className="contents">
          <span className="font-semibold text-ink">Grand total</span>
          <input
            type="number"
            inputMode="decimal"
            min="0"
            step="any"
            name="grandTotal"
            required
            value={props.value.grandTotal}
            onChange={(e) => props.onChange({ ...props.value, grandTotal: e.target.value })}
            aria-invalid={wrong || missing || undefined}
            className={`${BOX} font-semibold ${wrong || missing ? "border-danger" : ""}`}
          />
        </label>
        {check.matches ? <AddsUp /> : <span />}

        {/* Short, under the Grand total only. On the form it also blocks saving. */}
        {wrong && (
          <p role="alert" className="col-span-2 px-3 text-right text-sm tabular-nums text-danger">
            {addsUpMessage(check.computed, check.grand ?? 0)}
          </p>
        )}
      </div>
    </div>
  );
}

/**
 * The saved figures, as design D draws the foot of the invoice's page: a plain list, label on the
 * left, figure on the right with its rupee sign, every figure ending on one right edge (the list's
 * figure column is right-aligned), and the tick in a narrow third column so it never pushes the
 * Grand total out of line. The foot sits on the right of the card as on the paper; the page gives it
 * the `flex` row that `ms-auto` pushes against, exactly as the mock does.
 *
 * <p>Two things design D does not draw, both from R-INV-5:
 * <ul>
 *   <li>The note on Other charges ("plus a note"), under its figure and ending on the figures' edge.
 *       A short note stays on one line and widens the list a little (a 30-character note: 19px wider, measured). A
 *       long one (up to 500 characters) wraps at 15rem: at `max-w-xs` a long note stretched the
 *       list to 356px and its label column to 184px (measured, T-277), which parted every label
 *       from its figure; at 15rem the same note makes the list 276px.</li>
 *   <li>The red line when a saved invoice does not add up. The server refuses such a bill
 *       (KMS-400168), so only an invoice from before that check can show it.</li>
 * </ul>
 */
function ReadOnlyTotals(props: ReadOnlyInvoiceTotalsProps) {
  const check = totalsCheck(props.subTotal, props.gstAmount, props.otherCharges, props.discount, props.grandTotal);
  const rs = (n: number) => money(n, "INR");
  return (
    <dl
      className="ms-auto grid grid-cols-[auto_auto_1.5rem] items-center gap-x-3 gap-y-2 text-sm [&_dd]:text-right"
      data-testid="invoice-totals"
    >
      <dt className={LABEL}>Sub total</dt>
      <dd className="tabular-nums" data-testid="sub-total">
        {rs(props.subTotal)}
      </dd>
      <dd aria-hidden="true" />
      <dt className={LABEL}>GST</dt>
      <dd className="tabular-nums">{rs(props.gstAmount)}</dd>
      <dd aria-hidden="true" />
      <dt className={LABEL}>Other charges</dt>
      <dd className="tabular-nums">{rs(props.otherCharges)}</dd>
      <dd aria-hidden="true" />
      {props.otherChargesNote && (
        <>
          <dd className="col-span-2 max-w-[15rem] justify-self-end text-ink-secondary">{props.otherChargesNote}</dd>
          <dd aria-hidden="true" />
        </>
      )}
      <dt className={LABEL}>Discount</dt>
      {/* Taken off, so it says so, as design D does. A zero is just ₹0: "− ₹0" takes nothing off. */}
      <dd className="tabular-nums">{props.discount > 0 ? `− ${rs(props.discount)}` : rs(props.discount)}</dd>
      <dd aria-hidden="true" />
      <dt className="pe-5 font-semibold text-ink">Grand total</dt>
      <dd className="font-semibold tabular-nums" data-testid="grand-total">
        {rs(props.grandTotal)}
      </dd>
      <dd>{check.matches ? <AddsUp /> : null}</dd>
      {!check.matches && (
        <>
          <dd role="alert" className="col-span-2 tabular-nums text-danger">
            {addsUpMessage(check.computed, props.grandTotal)}
          </dd>
          <dd aria-hidden="true" />
        </>
      )}
    </dl>
  );
}
