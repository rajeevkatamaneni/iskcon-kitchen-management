"use client";

/*
 * THROWAWAY MOCK for T-247 — delete before any deploy.
 *
 * Three ways to build "Create an invoice" (A to C), and the existing invoice's own page with its
 * items and a "Pay this invoice" form (D), so Rajeev can choose by using them rather than by reading
 * about them. It follows his direction of 2026-09-19: the copy of the bill is an upload and is
 * required, the invoice carries item lines (pulled from the purchase order when there is one), the
 * line's Amount is what is typed and the Rate is worked out from it, and the items, GST, charges and
 * discount must add up to the total printed on the bill before anything can be saved.
 *
 * Everything is sample data held in component state: no API call, no session, nothing saved, and an
 * uploaded file never leaves the browser (it is shown from an object URL and forgotten on reload).
 * It is built from the app's own parts (Sidebar, PageHeader, Button, Badge, InlineNotice, Form, the
 * table constants) so the table fitter and the phone card layout in globals.css apply to it exactly
 * as they would on a real screen. No shared component, token or stylesheet was changed for it.
 */

import { useRef, useState, type ReactNode } from "react";
import { Sidebar } from "@/components/Sidebar";
import { Button, BUTTON_CLASSES } from "@/components/ds/Button";
import { Badge } from "@/components/ds/Badge";
import { Form } from "@/components/ds/Form";
import { required } from "@/components/ds/formMessages";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { PageHeader } from "@/components/ds/PageHeader";
import {
  RULED_TABLE,
  TABLE,
  ENTRY_GRID,
  THEAD,
  TR,
  TH_PRIMARY,
  TD_PRIMARY,
  TH_SECOND,
  TD_SECOND,
  TH_FIXED,
  TD_FIXED,
  TD_FIXED_NUM,
  TH_TEXT,
  TH_NUM,
  TD_TEXT,
  TD_NUM,
} from "@/components/ds/table";
import {
  FOOD_UNITS,
  dateWithYear,
  money,
  quantity,
  unitLabel,
  unitLabelFor,
} from "@/lib/format";

// ---------------------------------------------------------------------------------------------
// Sample data. Plausible Bengaluru mandi prices for September. Not real quotes, not a real bill.
// ---------------------------------------------------------------------------------------------

const TODAY = "2026-09-19";
const VENDOR = "Kalasipalya Vegetable Mandi";
const PO = "PO-2026-0044";
const INVOICE_NO = "KVM/2026/0917";
const INVOICE_DATE = "2026-09-17";
const DUE_DATE = "2026-10-01";
const SAMPLE_FILE = "KVM-0917.jpg";

interface OrderLine {
  id: string;
  item: string;
  unit: string;
  ordered: number;
  delivered: number;
  /** The purchase order's own price, which is what "received, at the order's prices" is worked from. */
  orderRate: number;
}

const ORDER: OrderLine[] = [
  {
    id: "t",
    item: "Tomato, ripe",
    unit: "KG",
    ordered: 10,
    delivered: 9,
    orderRate: 32,
  },
  {
    id: "c",
    item: "Curry leaves",
    unit: "KG",
    ordered: 3,
    delivered: 3,
    orderRate: 60,
  },
  {
    id: "n",
    item: "Coconut, fresh grated",
    unit: "PIECES",
    ordered: 10,
    delivered: 10,
    orderRate: 35,
  },
  {
    id: "g",
    item: "Green chilli, slit",
    unit: "KG",
    ordered: 2,
    delivered: 1.5,
    orderRate: 70,
  },
];

/** What the sample bill says for each line. Green chilli is billed at 2 Kg, of which 1.5 Kg came. */
const BILLED: Record<string, { qty: string; amount: string }> = {
  t: { qty: "9", amount: "288" },
  c: { qty: "3", amount: "180" },
  n: { qty: "10", amount: "350" },
  g: { qty: "2", amount: "140" },
};

interface Totals {
  gst: string;
  charges: string;
  discount: string;
  billTotal: string;
}

/** GST at 5% on the grated coconut and the slit chilli (₹490), delivery ₹50, ₹2.50 rounded off. */
const START_TOTALS: Totals = {
  gst: "24.50",
  charges: "50",
  discount: "2.50",
  billTotal: "1030",
};

const VENDORS = [VENDOR, "Heritage Fresh Dairy", "Sri Balaji Traders"];

const METHODS = [
  { value: "UPI", label: "UPI" },
  { value: "BANK", label: "Bank transfer" },
  { value: "CHEQUE", label: "Cheque" },
  { value: "CASH", label: "Cash" },
];

// ---------------------------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------------------------

const rs = (n: number) => money(n, "INR");
const round2 = (n: number) => Math.round(n * 100) / 100;

/** A box's figure, or null when it is empty or not a number. */
function num(s: string): number | null {
  if (s.trim() === "") return null;
  const n = Number(s);
  return Number.isFinite(n) ? n : null;
}

/** "₹32 / Kg", worked out from the line's amount and the quantity billed. A dash until both exist. */
function rateText(qty: string, amount: string, unit: string): string {
  const q = num(qty);
  const a = num(amount);
  if (q == null || a == null || q <= 0) return "—";
  return `${rs(round2(a / q))} / ${unitLabelFor(1, unit)}`;
}

/** The amber note for a line billed at more than came: "Billed 2 Kg, 1.5 Kg delivered". */
function overBilled(line: OrderLine, qty: string): string | null {
  const q = num(qty);
  if (q == null || q <= line.delivered) return null;
  return `Billed ${quantity(q, line.unit)}, ${quantity(line.delivered, line.unit)} delivered`;
}

/** The one input look on this page: the 44px touch height, the control corner. */
const FIELD =
  "min-h-touch rounded-control border border-hairline bg-canvas px-3 tabular-nums";
const LABEL = "flex flex-col gap-1 text-sm text-ink-secondary";
const LABEL_TEXT = "pl-field-inset font-medium text-ink";

let keySeq = 0;
const newKey = () => `k${++keySeq}`;

// ---------------------------------------------------------------------------------------------
// Shared boxes
// ---------------------------------------------------------------------------------------------

function MoneyBox({
  value,
  onChange,
  label,
  invalid,
  placeholder,
  className = "",
}: {
  value: string;
  onChange: (v: string) => void;
  label?: string;
  invalid?: boolean;
  placeholder?: string;
  className?: string;
}) {
  return (
    <span className="flex items-center gap-2">
      <input
        type="number"
        inputMode="decimal"
        min="0"
        step="any"
        value={value}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
        aria-label={label}
        aria-invalid={invalid || undefined}
        className={`${FIELD} min-w-24 placeholder:text-ink-secondary ${invalid ? "border-danger" : ""} ${className}`}
      />
    </span>
  );
}

function QtyBox({
  value,
  onChange,
  unit,
  label,
  className = "",
}: {
  value: string;
  onChange: (v: string) => void;
  unit: string;
  label: string;
  className?: string;
}) {
  return (
    <span className="flex items-center gap-2">
      <input
        type="number"
        inputMode="decimal"
        min="0"
        step="any"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-label={label}
        className={`${FIELD} min-w-20 ${className}`}
      />
      {/* A fixed width, so the boxes after it line up from one line to the next. */}
      <span className="min-w-12 text-ink-secondary">{unitLabel(unit)}</span>
    </span>
  );
}

/** A figure that sits in a row of boxes and must line up with them, so it takes a box's height. */
function Readout({
  children,
  muted,
  inTable,
}: {
  children: ReactNode;
  muted?: boolean;
  /** In a table row it sits level with the boxes. On a phone card it sits under its own label, so
   * the box height would only add empty space. */
  inTable?: boolean;
}) {
  return (
    <span
      className={`inline-flex ${inTable ? "lg:min-h-touch" : "min-h-touch"} items-center tabular-nums ${muted ? "text-ink-secondary" : "text-ink"}`}
    >
      {children}
    </span>
  );
}

// ---------------------------------------------------------------------------------------------
// The copy of the bill: an upload, and required.
// ---------------------------------------------------------------------------------------------

interface BillFile {
  name: string;
  kind: "image" | "pdf" | "sample" | "receipt" | "note" | "idcard";
  /** For the drawn sample receipt and cash note only: what it says was paid, and when. */
  paid?: { amount: number; when: string; ref: string };
  /** For the drawn sample cash note and ID card only: who received the cash. */
  person?: string;
  url?: string;
}

const SAMPLE_BILL: BillFile = { name: SAMPLE_FILE, kind: "sample" };
const SAMPLE_RECEIPT = (
  amount: number,
  when: string,
  ref: string,
): BillFile => ({
  name: `UPI-${ref.replace(/ /g, "-")}.png`,
  kind: "receipt",
  paid: { amount, when, ref },
});

const SAMPLE_NOTE = (amount: number, person: string): BillFile => ({
  name: "cash-note.jpg",
  kind: "note",
  paid: { amount, when: "", ref: "" },
  person,
});
const SAMPLE_ID = (person: string): BillFile => ({
  name: "person-photo.jpg",
  kind: "idcard",
  person,
});

function pickFile(e: React.ChangeEvent<HTMLInputElement>): BillFile | null {
  const f = e.target.files?.[0];
  e.target.value = "";
  if (!f) return null;
  const pdf = f.type === "application/pdf" || /\.pdf$/i.test(f.name);
  return {
    name: f.name,
    kind: pdf ? "pdf" : "image",
    url: URL.createObjectURL(f),
  };
}

/**
 * The file chooser. `accept` names images and PDFs, so a phone offers the camera, the photo library
 * and its files in one sheet. `capture` is left off on purpose: it forces the camera and takes away
 * the PDF the vendor sent on WhatsApp.
 */
function ChooseFile({
  onPick,
  children,
  id,
}: {
  onPick: (f: BillFile) => void;
  children: ReactNode;
  id?: string;
}) {
  return (
    <label className={BUTTON_CLASSES({ variant: "ghost" })} id={id}>
      <input
        type="file"
        accept="image/*,application/pdf"
        className="sr-only"
        onChange={(e) => {
          const f = pickFile(e);
          if (f) onPick(f);
        }}
      />
      <i className="ti ti-camera text-lg" aria-hidden="true" />
      <span>{children}</span>
    </label>
  );
}

/**
 * A required upload: the copy of the bill on an invoice, and the proof on a payment. The same box
 * for both, so a person who has done one knows the other.
 */
function UploadBill({
  bill,
  setBill,
  invalid,
  title = "Copy of the bill",
  prompt = "Upload a copy of the bill — a photo, PDF or scan.",
  missing = "Upload a copy of the bill to save this invoice.",
  sample = SAMPLE_BILL,
  sampleLabel = "Use the sample bill",
  hint,
}: {
  bill: BillFile | null;
  setBill: (b: BillFile | null) => void;
  invalid?: boolean;
  title?: string;
  prompt?: string;
  missing?: string;
  sample?: BillFile;
  sampleLabel?: string;
  /** A small line under the box, for what the prompt inside it has no room to say. */
  hint?: string;
}) {
  const hintLine = hint ? (
    <span className="pl-field-inset text-sm text-ink-secondary">{hint}</span>
  ) : null;
  if (bill) {
    return (
      <div className="grid gap-1">
        <span className={LABEL_TEXT + " text-sm"}>{title}</span>
        <div className="flex flex-wrap items-center gap-x-4 gap-y-3 rounded-control border border-hairline bg-canvas px-4 py-3">
          <BillThumb bill={bill} />
          <div className="grid min-w-0 grow basis-40 gap-0.5">
            <span className="font-medium text-ink [overflow-wrap:anywhere]">
              {bill.name}
            </span>
            <span className="text-sm text-ink-secondary">
              {bill.kind === "pdf" ? "PDF" : "Photo"}
            </span>
          </div>
          <div className="flex flex-wrap gap-3">
            <ChooseFile onPick={setBill}>Replace</ChooseFile>
            <Button variant="ghost" onClick={() => setBill(null)}>
              Remove
            </Button>
          </div>
        </div>
        {hintLine}
      </div>
    );
  }
  return (
    <div className="grid gap-1">
      <span className={LABEL_TEXT + " text-sm"}>{title}</span>
      <div
        className={`flex flex-wrap items-center gap-x-6 gap-y-3 rounded-control border border-dashed px-4 py-4 ${
          invalid ? "border-danger bg-danger-bg" : "border-ink-muted bg-canvas"
        }`}
      >
        <div className="flex min-w-0 grow basis-60 items-center gap-3">
          <i
            className="ti ti-file-upload text-2xl text-ink-secondary"
            aria-hidden="true"
          />
          <span className="text-ink">{prompt}</span>
        </div>
        <div className="flex flex-wrap gap-3">
          <ChooseFile onPick={setBill}>Choose a file</ChooseFile>
          {/* Mock only, so the rest can be tried without a real bill to hand. */}
          <Button variant="secondary" onClick={() => setBill(sample)}>
            {sampleLabel}
          </Button>
        </div>
      </div>
      {invalid && (
        <span className="pl-field-inset text-sm text-danger">{missing}</span>
      )}
      {hintLine}
    </div>
  );
}

function BillThumb({
  bill,
  onOpen,
  small,
}: {
  bill: BillFile;
  onOpen?: () => void;
  small?: boolean;
}) {
  const inner =
    bill.kind === "image" && bill.url ? (
      // eslint-disable-next-line @next/next/no-img-element
      <img src={bill.url} alt="" className="h-full w-full object-cover" />
    ) : bill.kind === "pdf" ? (
      <span className="flex h-full w-full items-center justify-center bg-sunken">
        <i
          className="ti ti-file-type-pdf text-3xl text-ink-secondary"
          aria-hidden="true"
        />
      </span>
    ) : (
      <SamplePicture bill={bill} />
    );
  const box = `${small ? "inline-block h-11 w-8 align-middle" : "block h-20 w-14"} flex-none overflow-hidden rounded-control border border-hairline`;
  if (!onOpen) return <span className={box}>{inner}</span>;
  return (
    <button
      type="button"
      onClick={onOpen}
      className={`${box} hover:shadow-lift`}
      aria-label={`Open ${bill.name}`}
    >
      {inner}
    </button>
  );
}

/** The bill full size: the sample drawing, the photo, or the PDF in the browser's own viewer. */
function BillView({ bill }: { bill: BillFile }) {
  if (bill.kind === "image" && bill.url) {
    // eslint-disable-next-line @next/next/no-img-element
    return (
      <img
        src={bill.url}
        alt={`The bill, ${bill.name}`}
        className="w-full rounded-control border border-hairline"
      />
    );
  }
  if (bill.kind === "pdf" && bill.url) {
    return (
      <iframe
        src={bill.url}
        title={`The bill, ${bill.name}`}
        className="h-[36rem] w-full rounded-control border border-hairline"
      />
    );
  }
  return (
    <div className="rounded-control border border-hairline">
      <SamplePicture bill={bill} />
    </div>
  );
}

/** Whichever neutral drawing stands in for a sample file. */
function SamplePicture({ bill }: { bill: BillFile }) {
  if (bill.kind === "receipt") return <ReceiptPicture paid={bill.paid} />;
  if (bill.kind === "note") return <NotePicture bill={bill} />;
  if (bill.kind === "idcard") return <IdPicture person={bill.person ?? ""} />;
  return <BillPicture />;
}

/** A neutral drawing of a handwritten note acknowledging cash, signed. */
function NotePicture({ bill }: { bill: BillFile }) {
  const lines = [
    `Received ${rs(bill.paid?.amount ?? 0)} in cash`,
    "from ISKCON South Bengaluru",
    "against bill " + INVOICE_NO,
    "19/09/2026",
  ];
  return (
    <svg viewBox="0 0 360 500" className="block h-auto w-full" role="img" aria-label="Sample signed cash note">
      <rect x="0" y="0" width="360" height="500" className="fill-raised" />
      {[0, 1, 2, 3, 4, 5, 6, 7, 8, 9].map((i) => (
        <line key={i} x1="24" x2="336" y1={120 + i * 34} y2={120 + i * 34} className="stroke-hairline" />
      ))}
      <g fontFamily="ui-serif, Georgia, serif" fontStyle="italic" fontSize="19" className="fill-ink">
        {lines.map((t, i) => (
          <text key={t} x="30" y={112 + i * 34}>
            {t}
          </text>
        ))}
      </g>
      <path d="M190 380 c14 -26 26 12 40 -6 s18 -20 30 2 s16 8 32 -12" fill="none" strokeWidth="2" className="stroke-ink" />
      <text x="330" y="420" textAnchor="end" fontSize="15" fontFamily="ui-serif, Georgia, serif" className="fill-ink-secondary">
        {bill.person ?? ""}
      </text>
      <rect x="0.5" y="0.5" width="359" height="499" fill="none" className="stroke-hairline" />
    </svg>
  );
}

/** A neutral drawing of a photo ID card: a portrait box and a few ruled lines, no real number. */
function IdPicture({ person }: { person: string }) {
  return (
    <svg viewBox="0 0 360 500" className="block h-auto w-full" role="img" aria-label="Sample photo ID card">
      <rect x="0" y="0" width="360" height="500" className="fill-sunken" />
      <rect x="20" y="150" width="320" height="200" rx="14" className="fill-raised stroke-hairline" />
      <rect x="40" y="180" width="90" height="110" rx="6" className="fill-sunken stroke-hairline" />
      <circle cx="85" cy="222" r="20" className="fill-hairline" />
      <path d="M55 290 c0 -30 60 -30 60 0" className="fill-hairline" />
      <g fontFamily="ui-sans-serif, system-ui" fontSize="15">
        <text x="150" y="200" fontWeight="700" className="fill-ink">
          {person}
        </text>
        {["Sample ID card", "Number hidden", "Valid to 2031"].map((t, i) => (
          <text key={t} x="150" y={228 + i * 24} className="fill-ink-secondary">
            {t}
          </text>
        ))}
      </g>
    </svg>
  );
}

/** A neutral drawing of a UPI confirmation, for the sample proof of payment. */
function ReceiptPicture({ paid }: { paid?: BillFile["paid"] }) {
  const p = paid ?? { amount: 0, when: "", ref: "" };
  const lines: [string, number, string][] = [
    ["Payment successful", 150, "fill-ink"],
    [rs(p.amount), 196, "fill-ink"],
    ["To " + VENDOR, 236, "fill-ink-secondary"],
    [p.when, 262, "fill-ink-secondary"],
    ["UPI ref " + p.ref, 288, "fill-ink-secondary"],
  ];
  return (
    <svg
      viewBox="0 0 360 500"
      className="block h-auto w-full"
      role="img"
      aria-label="Sample payment confirmation"
    >
      <rect x="0" y="0" width="360" height="500" className="fill-raised" />
      <circle cx="180" cy="90" r="30" className="fill-sunken stroke-hairline" />
      <path
        d="M166 90 l10 10 l18 -20"
        fill="none"
        strokeWidth="4"
        className="stroke-ink-secondary"
      />
      <g fontFamily="ui-sans-serif, system-ui" textAnchor="middle">
        {lines.map(([t, y, c], i) => (
          <text
            key={t}
            x="180"
            y={y}
            fontSize={i === 1 ? 34 : 15}
            fontWeight={i < 2 ? 700 : 400}
            className={c}
          >
            {t}
          </text>
        ))}
      </g>
      <rect
        x="0.5"
        y="0.5"
        width="359"
        height="499"
        fill="none"
        className="stroke-hairline"
      />
    </svg>
  );
}

// ---------------------------------------------------------------------------------------------
// A neutral drawing of a paper bill, so the side-by-side and the thumbnail have something to show
// without an external image. The same figures as the sample data.
// ---------------------------------------------------------------------------------------------

const BILL_ROWS = [
  ["Tomato, ripe", "9 kg", "32.00", "288.00"],
  ["Curry leaves", "3 kg", "60.00", "180.00"],
  ["Coconut grated *", "10 no", "35.00", "350.00"],
  ["Green chilli slit *", "2 kg", "70.00", "140.00"],
];

const BILL_SUMS = [
  ["Sub total", "958.00"],
  ["GST 5% on *", "24.50"],
  ["Delivery", "50.00"],
  ["Less discount", "-2.50"],
];

function BillPicture() {
  const W = 360;
  const t = "fill-ink-secondary";
  return (
    <svg
      viewBox="0 0 360 500"
      className="block h-auto w-full"
      role="img"
      aria-label="Sample bill from the vendor"
    >
      <rect x="0" y="0" width={W} height="500" className="fill-raised" />
      <g fontFamily="ui-monospace, monospace" fontSize="11">
        <text
          x={W / 2}
          y="36"
          textAnchor="middle"
          fontSize="15"
          fontWeight="700"
          className="fill-ink"
        >
          {VENDOR}
        </text>
        <text x={W / 2} y="54" textAnchor="middle" className={t}>
          {"K.R. Market Road, Bengaluru 560002"}
        </text>
        <text x={W / 2} y="70" textAnchor="middle" className={t}>
          {"Cash / credit bill · sample"}
        </text>
        <line x1="20" x2={W - 20} y1="82" y2="82" className="stroke-hairline" />
        <text
          x="20"
          y="102"
          className="fill-ink"
        >{`Bill no: ${INVOICE_NO}`}</text>
        <text x={W - 20} y="102" textAnchor="end" className="fill-ink">
          {"17/09/2026"}
        </text>
        <text x="20" y="120" className={t}>{`Your ref: ${PO}`}</text>
        <line
          x1="20"
          x2={W - 20}
          y1="134"
          y2="134"
          className="stroke-hairline"
        />
        {["Item", "Qty", "Rate", "Amount"].map((h, i) => (
          <text
            key={h}
            x={[20, 190, 262, W - 20][i]}
            y="152"
            textAnchor={i === 0 ? "start" : "end"}
            fontWeight="700"
            className="fill-ink"
          >
            {h}
          </text>
        ))}
        {BILL_ROWS.map((r, row) => (
          <g key={r[0]}>
            {r.map((cell, i) => (
              <text
                key={i}
                x={[20, 190, 262, W - 20][i]}
                y={176 + row * 24}
                textAnchor={i === 0 ? "start" : "end"}
                className="fill-ink"
              >
                {cell}
              </text>
            ))}
          </g>
        ))}
        <line
          x1="20"
          x2={W - 20}
          y1="276"
          y2="276"
          className="stroke-hairline"
        />
        {BILL_SUMS.map(([k, v], i) => (
          <g key={k}>
            <text x="170" y={296 + i * 20} className={t}>
              {k}
            </text>
            <text
              x={W - 20}
              y={296 + i * 20}
              textAnchor="end"
              className="fill-ink"
            >
              {v}
            </text>
          </g>
        ))}
        <line
          x1="170"
          x2={W - 20}
          y1="372"
          y2="372"
          className="stroke-hairline"
        />
        <text x="170" y="392" fontWeight="700" className="fill-ink">
          {"Total Rs."}
        </text>
        <text
          x={W - 20}
          y="392"
          textAnchor="end"
          fontWeight="700"
          className="fill-ink"
        >
          {"1,030.00"}
        </text>
        <text x="20" y="440" className={t}>
          {"Goods received in good condition"}
        </text>
        <path
          d="M230 462 c10 -18 18 10 28 -4 s14 -14 22 2 s12 6 22 -8"
          fill="none"
          strokeWidth="1.5"
          className="stroke-ink-secondary"
        />
        <text x={W - 20} y="484" textAnchor="end" className={t}>
          {"for the vendor"}
        </text>
      </g>
      <rect
        x="0.5"
        y="0.5"
        width={W - 1}
        height="499"
        fill="none"
        className="stroke-hairline"
      />
    </svg>
  );
}

// ---------------------------------------------------------------------------------------------
// The totals: what the lines add up to, the three figures typed from the foot of the bill, and the
// check that they come to the total printed on it.
// ---------------------------------------------------------------------------------------------

function sums(subtotal: number, t: Totals) {
  const computed = round2(
    subtotal +
      (num(t.gst) ?? 0) +
      (num(t.charges) ?? 0) -
      (num(t.discount) ?? 0),
  );
  const bill = num(t.billTotal);
  const matches = bill != null && Math.abs(bill - computed) < 0.005;
  return { computed, bill, matches };
}

/**
 * Whether the figures add up to the grand total, said as little as possible (Rajeev, 2026-09-19:
 * the sentence spelling out the sum was "too much"). When they agree, a small green tick beside the
 * Grand total and nothing else. Green because it confirms the figures the person typed, which is
 * the one use of green the colour rule allows.
 */
function AddsUp() {
  // Its own tip rather than the shared Tooltip, which centres itself under the thing it explains:
  // the tick sits at the right edge of the form, and a centred tip ran 12px past the edge of a 1470px
  // window (measured), more on a phone. This one hangs from the tick's right edge and opens leftward.
  // Changing the shared component is outside this mock's reach, so this is noted for the real build.
  const [open, setOpen] = useState(false);
  const words = "Adds up to the grand total";
  return (
    <span
      className="relative inline-flex"
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
      onFocus={() => setOpen(true)}
      onBlur={() => setOpen(false)}
      onTouchStart={() => setOpen((was) => !was)}
    >
      <span
        role="img"
        aria-label={words}
        tabIndex={0}
        className="inline-flex h-6 w-6 items-center justify-center rounded-control text-success"
      >
        <i className="ti ti-circle-check text-xl" aria-hidden="true" />
      </span>
      {open && (
        <span
          role="tooltip"
          aria-hidden="true"
          className="absolute right-0 top-full z-10 mt-2 w-max max-w-xs rounded-control bg-ink px-3 py-2 text-sm font-normal text-ink-inverse shadow-overlay"
        >
          {words}
        </span>
      )}
    </span>
  );
}

/**
 * The foot of the bill, laid out the way a printed bill lays it out: on the right, a label down the
 * left of the block and its figure down the right, every figure ending on the same edge. Rajeev
 * chose this for A on 2026-09-19 from design B.
 *
 * <p>It is not a table, so the table rule (everything left-aligned, DESIGN_SYSTEM §5) does not
 * reach it: it is a sum, and a sum is read by lining the figures up on their last digit. The typed
 * boxes are right-aligned for the same reason, and the read-only Sub total sits in a box-shaped
 * space with the same padding so its last digit ends where theirs do.
 */
function TotalsFields({
  subtotal,
  totals,
  setTotals,
  tried,
}: {
  subtotal: number;
  totals: Totals;
  setTotals: (t: Totals) => void;
  tried: boolean;
}) {
  const { computed, bill, matches } = sums(subtotal, totals);
  const set = (k: keyof Totals) => (v: string) =>
    setTotals({ ...totals, [k]: v });
  const wrong = bill != null && !matches;
  const missing = tried && bill == null;
  const row = "contents";
  const name = "text-ink-secondary";
  const box = "w-32 text-right";
  return (
    <div className="grid w-full gap-3 sm:ms-auto sm:max-w-sm">
      {/* Three columns: the label, the figure, and a narrow slot after the figure that only the
          Grand total's tick uses, so the tick never pushes that box out of line with the others. */}
      <div className="grid grid-cols-[1fr_auto_1.5rem] items-center gap-x-3 gap-y-3">
        {/* The column's unit said once over the figures, as a printed bill does, rather than a
            rupee sign beside every box. */}
        <span />
        <span className="px-3 text-right text-xs text-ink-muted">Amount (₹)</span>
        <span />
        <div className={row}>
          <span className={name}>Sub total</span>
          <span className="inline-flex min-h-touch w-32 items-center justify-end border border-transparent px-3 tabular-nums text-ink">
            {rs(subtotal).replace("₹", "")}
          </span>
          <span />
        </div>
        <label className={row}>
          <span className={name}>GST</span>
          <MoneyBox value={totals.gst} onChange={set("gst")} className={box} />
          <span />
        </label>
        <label className={row}>
          <span className={name}>Other charges</span>
          <MoneyBox
            value={totals.charges}
            onChange={set("charges")}
            className={box}
          />
          <span />
        </label>
        <label className={row}>
          <span className={name}>Discount</span>
          <MoneyBox
            value={totals.discount}
            onChange={set("discount")}
            className={box}
          />
          <span />
        </label>
        <div className="col-span-3 border-t border-hairline" />
        <label className={row}>
          <span className="font-semibold text-ink">Grand total</span>
          <MoneyBox
            value={totals.billTotal}
            onChange={set("billTotal")}
            invalid={wrong || missing}
            className={`${box} font-semibold`}
          />
          {matches ? <AddsUp /> : <span />}
        </label>
        {/* Short, under the Grand total only, and it blocks saving. */}
        {wrong && (
          <p
            role="alert"
            className="col-span-2 px-3 text-right text-sm text-danger tabular-nums"
          >
            {`Adds up to ${rs(computed)}, not ${rs(bill ?? 0)}`}
          </p>
        )}
      </div>
    </div>
  );
}


// ---------------------------------------------------------------------------------------------
// The frame each design sits in: the focus screen's header with its Cancel and Save, then the body.
// ---------------------------------------------------------------------------------------------

function MockScreen({
  onCancel,
  onSave,
  problems,
  saved,
  children,
}: {
  onCancel: () => void;
  onSave: () => void;
  problems: string[] | null;
  saved: boolean;
  children: ReactNode;
}) {
  return (
    <div className="rounded-card border border-hairline bg-canvas">
      <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2 rounded-t-card border-b border-hairline bg-sunken px-4 py-2 sm:px-8 sm:py-4">
        <p className="text-lg font-semibold leading-6 text-ink sm:text-xl">
          Create an invoice
        </p>
        <div className="flex flex-wrap gap-2 max-sm:[&_.btn]:px-3 max-sm:[&_.btn]:text-sm">
          <Button variant="ghost" onClick={onCancel}>
            Cancel
          </Button>
          <Button onClick={onSave}>Save invoice</Button>
        </div>
      </div>
      <div className="grid gap-6 px-4 pb-8 pt-6 sm:px-8">
        {problems && problems.length > 0 && (
          <InlineNotice tone="danger" title="This invoice can’t be saved yet.">
            <ul className="list-disc ps-5">
              {problems.map((p) => (
                <li key={p}>{p}</li>
              ))}
            </ul>
          </InlineNotice>
        )}
        {saved && (
          <InlineNotice tone="success" autoDismiss>
            Invoice {INVOICE_NO} saved. This is a mock, so nothing was kept.
          </InlineNotice>
        )}
        {children}
      </div>
    </div>
  );
}

function Design({
  letter,
  title,
  about,
  children,
  chosen,
}: {
  letter: string;
  title: string;
  chosen?: boolean;
  about: ReactNode;
  children: ReactNode;
}) {
  return (
    <section
      className="grid gap-4 border-t border-hairline pt-10"
      aria-labelledby={`design-${letter}-title`}
      id={`design-${letter}`}
    >
      <div className="grid gap-1">
        <h2
          id={`design-${letter}-title`}
          className="flex flex-wrap items-center gap-3 text-xl font-semibold text-ink"
        >
          {letter}. {title}
          {chosen && <Badge tone="accent">Chosen</Badge>}
        </h2>
        <p className="max-w-prose text-ink-secondary">{about}</p>
      </div>
      {children}
    </section>
  );
}

/** Vendor, order, number and the two dates: the fields every invoice asks for. */
function HeaderFields({
  direct,
  column,
}: {
  direct: boolean;
  column?: boolean;
}) {
  // Wide: vendor and order share the first row, the number and the two dates the second, so no cell
  // is left empty. In a half-width column (design C) the vendor, order and number take the full
  // width, because a vendor's name and an order's label were cut off in a half-width box.
  const wide = column ? "col-span-2" : "sm:col-span-3";
  const third = column ? "col-span-2" : "sm:col-span-2";
  const date = column ? "" : "sm:col-span-2";
  return (
    <>
      <label className={`${LABEL} ${wide}`}>
        <span className={LABEL_TEXT}>Vendor</span>
        <select defaultValue={VENDOR} className={FIELD}>
          {VENDORS.map((v) => (
            <option key={v}>{v}</option>
          ))}
        </select>
      </label>
      {direct ? (
        <label className={`${LABEL} ${wide}`}>
          <span className={LABEL_TEXT}>Description</span>
          <input placeholder="Cash market vegetables" className={FIELD} />
        </label>
      ) : (
        <label className={`${LABEL} ${wide}`}>
          <span className={LABEL_TEXT}>Purchase order</span>
          <select defaultValue={PO} className={FIELD}>
            <option value={PO}>{`${PO} · Delivered · needed 17 Sept`}</option>
          </select>
        </label>
      )}
      <label className={`${LABEL} ${third}`}>
        <span className={LABEL_TEXT}>Invoice number</span>
        <input defaultValue={INVOICE_NO} className={FIELD} />
      </label>
      <label className={`${LABEL} ${date}`}>
        <span className={LABEL_TEXT}>Invoice date</span>
        <input type="date" defaultValue={INVOICE_DATE} className={FIELD} />
      </label>
      <label className={`${LABEL} ${date}`}>
        <span className={LABEL_TEXT}>Due date</span>
        <input type="date" defaultValue={DUE_DATE} className={FIELD} />
      </label>
    </>
  );
}

type Billed = Record<string, { qty: string; amount: string }>;
const startBilled = (): Billed => JSON.parse(JSON.stringify(BILLED));

function orderSubtotal(b: Billed): number {
  return round2(ORDER.reduce((s, l) => s + (num(b[l.id].amount) ?? 0), 0));
}

/** Why Save refuses, in the order a person would fix them. Empty when it can go. */
function savingProblems({
  bill,
  amountsMissing,
  subtotal,
  totals,
}: {
  bill: BillFile | null;
  amountsMissing: boolean;
  subtotal: number;
  totals: Totals;
}): string[] {
  const out: string[] = [];
  if (!bill) out.push("Upload a copy of the bill.");
  if (amountsMissing) out.push("Type the amount billed for every item.");
  const { bill: total, matches } = sums(subtotal, totals);
  if (total == null) out.push("Type the grand total from the bill.");
  else if (!matches)
    out.push(
      "The sub total, GST, other charges and discount don’t add up to the grand total on the bill.",
    );
  return out;
}

// ---------------------------------------------------------------------------------------------
// A. Classic
// ---------------------------------------------------------------------------------------------

interface DirectLine {
  key: string;
  item: string;
  unit: string;
  qty: string;
  amount: string;
}

const blankDirect = (): DirectLine => ({
  key: newKey(),
  item: "",
  unit: "KG",
  qty: "",
  amount: "",
});

function DesignA() {
  const [direct, setDirect] = useState(false);
  const [billed, setBilled] = useState<Billed>(startBilled);
  const [directLines, setDirectLines] = useState<DirectLine[]>(() => [
    blankDirect(),
  ]);
  const [totals, setTotals] = useState<Totals>(START_TOTALS);
  const [bill, setBill] = useState<BillFile | null>(null);
  const [tried, setTried] = useState(false);
  const [problems, setProblems] = useState<string[] | null>(null);
  const [saved, setSaved] = useState(false);

  const setLine = (
    id: string,
    patch: Partial<{ qty: string; amount: string }>,
  ) => setBilled((b) => ({ ...b, [id]: { ...b[id], ...patch } }));
  const setDirectLine = (key: string, patch: Partial<DirectLine>) =>
    setDirectLines((ls) =>
      ls.map((l) => (l.key === key ? { ...l, ...patch } : l)),
    );

  const subtotal = direct
    ? round2(directLines.reduce((s, l) => s + (num(l.amount) ?? 0), 0))
    : orderSubtotal(billed);
  const amountsMissing = direct
    ? directLines.some((l) => l.item.trim() !== "" && num(l.amount) == null) ||
      directLines.every((l) => l.item.trim() === "")
    : ORDER.some(
        (l) =>
          (num(billed[l.id].qty) ?? 0) > 0 && num(billed[l.id].amount) == null,
      );

  function save() {
    setTried(true);
    const p = savingProblems({ bill, amountsMissing, subtotal, totals });
    setProblems(p);
    setSaved(p.length === 0);
  }

  function reset() {
    setDirect(false);
    setBilled(startBilled());
    setDirectLines([blankDirect()]);
    setTotals(START_TOTALS);
    setBill(null);
    setTried(false);
    setProblems(null);
    setSaved(false);
  }

  return (
    <MockScreen
      onCancel={reset}
      onSave={save}
      problems={problems}
      saved={saved}
    >
      <label className="flex min-h-touch items-center gap-2 text-sm text-ink-secondary">
        <input
          type="checkbox"
          checked={direct}
          onChange={(e) => setDirect(e.target.checked)}
          className="accent-accent"
        />
        <span>Direct, with no purchase order</span>
      </label>

      <div className="grid gap-4 sm:grid-cols-6">
        <HeaderFields direct={direct} />
      </div>
      <UploadBill bill={bill} setBill={setBill} invalid={tried && !bill} />

      <div className="grid gap-2">
        <h3 className="text-base font-semibold text-ink">Items</h3>
        <p className="text-sm text-ink-secondary">
          {direct
            ? "Add each item on the bill. Type the amount printed for the line, and the rate is worked out."
            : `Pulled in from ${PO}. Type the quantity and the amount printed on the bill for each line.`}
        </p>
      </div>

      {direct ? (
        <div className="grid gap-3">
          <table
            className={`${TABLE} ${ENTRY_GRID} text-sm max-lg:[&_tbody_td]:border-0`}
          >
            <thead className={THEAD}>
              <tr>
                <th className={TH_TEXT}>Item</th>
                <th className={TH_NUM}>Billed qty</th>
                <th className={TH_NUM}>Amount (₹)</th>
                <th className={TH_NUM}>Rate</th>
                <th className={TH_TEXT}>
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {directLines.map((l, i) => (
                <tr key={l.key} className={TR}>
                  <td className={TD_TEXT}>
                    <input
                      value={l.item}
                      onChange={(e) =>
                        setDirectLine(l.key, { item: e.target.value })
                      }
                      placeholder="Item"
                      aria-label={`Item ${i + 1}`}
                      className={`${FIELD} w-full min-w-48`}
                    />
                  </td>
                  <td className={`${TD_NUM} max-lg:col-span-2`} data-label="Billed qty">
                    <span className="flex items-center gap-2">
                      <input
                        type="number"
                        inputMode="decimal"
                        min="0"
                        step="any"
                        value={l.qty}
                        onChange={(e) =>
                          setDirectLine(l.key, { qty: e.target.value })
                        }
                        aria-label={`Item ${i + 1} billed quantity`}
                        className={`${FIELD} min-w-20`}
                      />
                      <select
                        value={l.unit}
                        onChange={(e) =>
                          setDirectLine(l.key, { unit: e.target.value })
                        }
                        aria-label={`Item ${i + 1} unit`}
                        className={FIELD}
                      >
                        {FOOD_UNITS.map((u) => (
                          <option key={u} value={u}>
                            {unitLabel(u)}
                          </option>
                        ))}
                      </select>
                    </span>
                  </td>
                  <td className={TD_NUM} data-label="Amount (₹)">
                    <MoneyBox
                      value={l.amount}
                      onChange={(v) => setDirectLine(l.key, { amount: v })}
                      label={`Item ${i + 1} amount`}
                      invalid={
                        tried && l.item.trim() !== "" && num(l.amount) == null
                      }
                    />
                  </td>
                  <td className={TD_NUM} data-label="Rate">
                    <Readout inTable muted>{rateText(l.qty, l.amount, l.unit)}</Readout>
                  </td>
                  <td className={TD_TEXT}>
                    {directLines.length > 1 && (
                      <Button
                        variant="ghost"
                        icon="trash"
                        aria-label={`Remove item ${i + 1}`}
                        onClick={() =>
                          setDirectLines((ls) =>
                            ls.filter((x) => x.key !== l.key),
                          )
                        }
                      />
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div>
            <Button
              variant="ghost"
              icon="plus"
              onClick={() => setDirectLines((ls) => [...ls, blankDirect()])}
            >
              Add an item
            </Button>
          </div>
        </div>
      ) : (
        <table
          className={`${TABLE} ${ENTRY_GRID} text-sm max-lg:[&_tbody_td]:border-0`}
        >
          <thead className={THEAD}>
            <tr>
              <th className={TH_TEXT}>Item</th>
              <th className={TH_NUM}>Ordered</th>
              <th className={TH_NUM}>Delivered</th>
              <th className={TH_NUM}>Billed qty</th>
              <th className={TH_NUM}>Amount (₹)</th>
              <th className={TH_NUM}>Rate</th>
            </tr>
          </thead>
          <tbody>
            {ORDER.map((l) => {
              const b = billed[l.id];
              const warn = overBilled(l, b.qty);
              const missing =
                tried && (num(b.qty) ?? 0) > 0 && num(b.amount) == null;
              return (
                <tr key={l.id} className={TR}>
                  <td className={TD_TEXT}>
                    <span className="inline-flex items-center font-medium text-ink lg:min-h-touch">
                      {l.item}
                    </span>
                    {warn && (
                      <span className="flex items-center gap-1 text-xs text-warning">
                        <i
                          className="ti ti-alert-triangle"
                          aria-hidden="true"
                        />
                        {warn}
                      </span>
                    )}
                  </td>
                  <td className={TD_NUM} data-label="Ordered">
                    <Readout inTable>{quantity(l.ordered, l.unit)}</Readout>
                  </td>
                  <td className={TD_NUM} data-label="Delivered">
                    <Readout inTable>{quantity(l.delivered, l.unit)}</Readout>
                  </td>
                  <td className={TD_NUM} data-label="Billed qty">
                    <QtyBox
                      value={b.qty}
                      onChange={(v) => setLine(l.id, { qty: v })}
                      unit={l.unit}
                      label={`${l.item} billed quantity`}
                    />
                  </td>
                  <td className={TD_NUM} data-label="Amount (₹)">
                    <MoneyBox
                      value={b.amount}
                      onChange={(v) => setLine(l.id, { amount: v })}
                      label={`${l.item} amount`}
                      invalid={missing}
                    />
                  </td>
                  <td className={TD_NUM} data-label="Rate">
                    <Readout inTable muted>{rateText(b.qty, b.amount, l.unit)}</Readout>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}

      <div className="grid border-t border-hairline pt-5">
        <TotalsFields
          subtotal={subtotal}
          totals={totals}
          setTotals={setTotals}
          tried={tried}
        />
      </div>
    </MockScreen>
  );
}

// ---------------------------------------------------------------------------------------------
// B. Bill-shaped
// ---------------------------------------------------------------------------------------------

/**
 * A line on the paper. Rate and Amount are each either typed or worked out from the other. Typing
 * into a worked-out box makes it typed, and clearing a box makes it worked out again. When both are
 * typed they have to agree with the quantity, or the line is refused.
 */
interface PaperLine {
  qty: string;
  rate: string;
  amount: string;
}

// Coconut starts with both typed and disagreeing, so the refusal is on the page from the start.
const START_PAPER: Record<string, PaperLine> = {
  t: { qty: "9", rate: "", amount: "288" },
  c: { qty: "3", rate: "60", amount: "" },
  n: { qty: "10", rate: "40", amount: "350" },
  g: { qty: "2", rate: "", amount: "140" },
};

function paperAmount(p: PaperLine): number | null {
  const a = num(p.amount);
  if (a != null) return a;
  const q = num(p.qty);
  const r = num(p.rate);
  return q != null && r != null ? round2(q * r) : null;
}

function paperRate(p: PaperLine): number | null {
  const r = num(p.rate);
  if (r != null) return r;
  const q = num(p.qty);
  const a = num(p.amount);
  return q != null && a != null && q > 0 ? round2(a / q) : null;
}

function paperConflict(line: OrderLine, p: PaperLine): string | null {
  const q = num(p.qty);
  const r = num(p.rate);
  const a = num(p.amount);
  if (q == null || r == null || a == null) return null;
  const product = round2(q * r);
  if (Math.abs(product - a) < 0.005) return null;
  return `${quantity(q, line.unit)} × ${rs(r)} = ${rs(product)}, not ${rs(a)}. Check the bill, or clear one box to have it worked out from the other.`;
}

function DesignB() {
  const [paper, setPaper] = useState<Record<string, PaperLine>>(() =>
    JSON.parse(JSON.stringify(START_PAPER)),
  );
  const [totals, setTotals] = useState<Totals>(START_TOTALS);
  const [bill, setBill] = useState<BillFile | null>(null);
  const [tried, setTried] = useState(false);
  const [problems, setProblems] = useState<string[] | null>(null);
  const [saved, setSaved] = useState(false);

  const set = (id: string, patch: Partial<PaperLine>) =>
    setPaper((p) => ({ ...p, [id]: { ...p[id], ...patch } }));
  const subtotal = round2(
    ORDER.reduce((s, l) => s + (paperAmount(paper[l.id]) ?? 0), 0),
  );
  const conflicts = ORDER.filter((l) => paperConflict(l, paper[l.id]) != null);

  function save() {
    setTried(true);
    const amountsMissing = ORDER.some(
      (l) =>
        (num(paper[l.id].qty) ?? 0) > 0 && paperAmount(paper[l.id]) == null,
    );
    const p = savingProblems({ bill, amountsMissing, subtotal, totals });
    if (conflicts.length > 0) {
      p.splice(
        1,
        0,
        `The rate and the amount disagree on ${conflicts.map((l) => l.item).join(", ")}.`,
      );
    }
    setProblems(p);
    setSaved(p.length === 0);
  }

  function reset() {
    setPaper(JSON.parse(JSON.stringify(START_PAPER)));
    setTotals(START_TOTALS);
    setBill(null);
    setTried(false);
    setProblems(null);
    setSaved(false);
  }

  return (
    <MockScreen
      onCancel={reset}
      onSave={save}
      problems={problems}
      saved={saved}
    >
      <div className="mx-auto grid w-full max-w-3xl gap-6 rounded-card border border-hairline bg-raised px-4 py-6 shadow-card sm:px-8">
        <div className="grid gap-1 text-center">
          <p className="text-lg font-semibold text-ink">{VENDOR}</p>
          <p className="text-sm text-ink-secondary">Bill against {PO}</p>
        </div>

        <div className="flex flex-wrap gap-x-6 gap-y-3">
          <label className={LABEL}>
            <span className={LABEL_TEXT}>Bill number</span>
            <input defaultValue={INVOICE_NO} className={FIELD} />
          </label>
          <label className={LABEL}>
            <span className={LABEL_TEXT}>Bill date</span>
            <input type="date" defaultValue={INVOICE_DATE} className={FIELD} />
          </label>
          <label className={LABEL}>
            <span className={LABEL_TEXT}>Due date</span>
            <input type="date" defaultValue={DUE_DATE} className={FIELD} />
          </label>
        </div>

        <p className="text-sm text-ink-secondary">
          Type the rate or the amount, whichever the bill shows. The other is
          worked out and shown in grey.
        </p>

        <table
          className={`${TABLE} ${ENTRY_GRID} text-sm max-lg:[&_tbody_td]:border-0`}
        >
          <thead className={THEAD}>
            <tr>
              <th className={TH_TEXT}>Item</th>
              <th className={TH_NUM}>Qty</th>
              <th className={TH_NUM}>Rate (₹)</th>
              <th className={TH_NUM}>Amount (₹)</th>
            </tr>
          </thead>
          {ORDER.map((l) => {
            const p = paper[l.id];
            const r = paperRate(p);
            const a = paperAmount(p);
            const conflict = paperConflict(l, p);
            const warn = overBilled(l, p.qty);
            return (
              <tbody key={l.id}>
                <tr className={TR}>
                  <td className={TD_TEXT}>
                    <span className="block pt-2 font-medium text-ink">
                      {l.item}
                    </span>
                    <span className="block text-xs text-ink-muted">
                      {`Delivered ${quantity(l.delivered, l.unit)}`}
                    </span>
                    {warn && (
                      <span className="flex items-center gap-1 text-xs text-warning">
                        <i
                          className="ti ti-alert-triangle"
                          aria-hidden="true"
                        />
                        {warn}
                      </span>
                    )}
                  </td>
                  <td className={TD_NUM} data-label="Qty">
                    <QtyBox
                      value={p.qty}
                      onChange={(v) => set(l.id, { qty: v })}
                      unit={l.unit}
                      label={`${l.item} quantity`}
                    />
                  </td>
                  <td className={TD_NUM} data-label="Rate (₹)">
                    <span className="flex items-center gap-2">
                      <span className="text-ink-muted" aria-hidden="true">
                        ×
                      </span>
                      <MoneyBox
                        value={p.rate}
                        onChange={(v) => set(l.id, { rate: v })}
                        placeholder={
                          num(p.rate) == null && r != null
                            ? String(r)
                            : undefined
                        }
                        label={`${l.item} rate per ${unitLabelFor(1, l.unit)}`}
                        invalid={conflict != null}
                      />
                      <span className="text-ink-secondary">
                        / {unitLabelFor(1, l.unit)}
                      </span>
                    </span>
                  </td>
                  <td className={TD_NUM} data-label="Amount (₹)">
                    <span className="flex items-center gap-2">
                      <span className="text-ink-muted" aria-hidden="true">
                        =
                      </span>
                      <MoneyBox
                        value={p.amount}
                        onChange={(v) => set(l.id, { amount: v })}
                        placeholder={
                          num(p.amount) == null && a != null
                            ? String(a)
                            : undefined
                        }
                        label={`${l.item} amount`}
                        invalid={conflict != null}
                      />
                    </span>
                  </td>
                </tr>
                {conflict && (
                  <tr className={`${TR} border-t-0 hover:bg-transparent`}>
                    <td
                      colSpan={4}
                      className={`${TD_TEXT} !whitespace-normal pt-0`}
                    >
                      <span
                        className="flex items-start gap-2 rounded-control bg-danger-bg px-3 py-2 text-sm text-danger"
                        role="alert"
                      >
                        <i
                          className="ti ti-alert-circle mt-0.5 text-base"
                          aria-hidden="true"
                        />
                        <span>{conflict}</span>
                      </span>
                    </td>
                  </tr>
                )}
              </tbody>
            );
          })}
        </table>

        {/* The foot of a bill: the same block as A's. */}
        <div className="border-t border-hairline pt-4">
          <TotalsFields
            subtotal={subtotal}
            totals={totals}
            setTotals={setTotals}
            tried={tried}
          />
        </div>
      </div>

      {/* Held to the paper's width, so the two share their edges. */}
      <div className="mx-auto w-full max-w-3xl">
        <UploadBill bill={bill} setBill={setBill} invalid={tried && !bill} />
      </div>
    </MockScreen>
  );
}

// ---------------------------------------------------------------------------------------------
// C. Side by side with the bill
// ---------------------------------------------------------------------------------------------

function DesignC() {
  const [billed, setBilled] = useState<Billed>(startBilled);
  const [totals, setTotals] = useState<Totals>(START_TOTALS);
  const [bill, setBill] = useState<BillFile | null>(SAMPLE_BILL);
  const [tried, setTried] = useState(false);
  const [problems, setProblems] = useState<string[] | null>(null);
  const [saved, setSaved] = useState(false);

  const setLine = (
    id: string,
    patch: Partial<{ qty: string; amount: string }>,
  ) => setBilled((b) => ({ ...b, [id]: { ...b[id], ...patch } }));
  const subtotal = orderSubtotal(billed);

  function save() {
    setTried(true);
    const amountsMissing = ORDER.some(
      (l) =>
        (num(billed[l.id].qty) ?? 0) > 0 && num(billed[l.id].amount) == null,
    );
    const p = savingProblems({ bill, amountsMissing, subtotal, totals });
    setProblems(p);
    setSaved(p.length === 0);
  }

  function reset() {
    setBilled(startBilled());
    setTotals(START_TOTALS);
    setBill(SAMPLE_BILL);
    setTried(false);
    setProblems(null);
    setSaved(false);
  }

  return (
    <MockScreen
      onCancel={reset}
      onSave={save}
      problems={problems}
      saved={saved}
    >
      <div className="grid gap-6 lg:grid-cols-2 lg:items-start">
        {/* The bill stays in view while the form beside it scrolls. */}
        <div className="grid gap-3 lg:sticky lg:top-4">
          {bill ? (
            <>
              <BillView bill={bill} />
              <div className="flex flex-wrap items-center justify-between gap-3">
                <span className="min-w-0 text-sm text-ink-secondary [overflow-wrap:anywhere]">
                  {bill.name}
                </span>
                <div className="flex flex-wrap gap-3">
                  <ChooseFile onPick={setBill}>Replace</ChooseFile>
                  <Button variant="ghost" onClick={() => setBill(null)}>
                    Remove
                  </Button>
                </div>
              </div>
            </>
          ) : (
            <UploadBill bill={bill} setBill={setBill} invalid={tried} />
          )}
        </div>

        <div className="grid gap-6">
          <div className="grid grid-cols-2 gap-4">
            <HeaderFields direct={false} column />
          </div>

          <div className="grid gap-2">
            <h3 className="text-base font-semibold text-ink">Items</h3>
            <p className="text-sm text-ink-secondary">
              From {PO}. Copy each line from the bill beside you.
            </p>
            <ul className="grid border-t border-hairline">
              {ORDER.map((l) => {
                const b = billed[l.id];
                const warn = overBilled(l, b.qty);
                const missing =
                  tried && (num(b.qty) ?? 0) > 0 && num(b.amount) == null;
                return (
                  <li
                    key={l.id}
                    className="grid gap-2 border-b border-hairline py-3"
                  >
                    <div className="grid gap-0.5">
                      <span className="font-medium text-ink">{l.item}</span>
                      <span className="text-sm text-ink-secondary">
                        {`Ordered ${quantity(l.ordered, l.unit)} · Delivered ${quantity(l.delivered, l.unit)}`}
                      </span>
                      {warn && (
                        <span className="flex items-center gap-1 text-sm text-warning">
                          <i
                            className="ti ti-alert-triangle"
                            aria-hidden="true"
                          />
                          {warn}
                        </span>
                      )}
                    </div>
                    <div className="flex flex-wrap gap-x-4 gap-y-3">
                      <label className={LABEL}>
                        <span className={LABEL_TEXT}>Billed qty</span>
                        <QtyBox
                          value={b.qty}
                          onChange={(v) => setLine(l.id, { qty: v })}
                          unit={l.unit}
                          label={`${l.item} billed quantity`}
                          className="w-24"
                        />
                      </label>
                      <label className={LABEL}>
                        <span className={LABEL_TEXT}>Amount (₹)</span>
                        <MoneyBox
                          value={b.amount}
                          onChange={(v) => setLine(l.id, { amount: v })}
                          invalid={missing}
                          className="w-28"
                        />
                      </label>
                      <div className={LABEL}>
                        <span className={LABEL_TEXT}>Rate</span>
                        <span className="pl-field-inset">
                          <Readout muted>
                            {rateText(b.qty, b.amount, l.unit)}
                          </Readout>
                        </span>
                      </div>
                    </div>
                  </li>
                );
              })}
            </ul>
          </div>

          <div className="grid">
            <TotalsFields
              subtotal={subtotal}
              totals={totals}
              setTotals={setTotals}
              tried={tried}
            />
          </div>
        </div>
      </div>
    </MockScreen>
  );
}

// ---------------------------------------------------------------------------------------------
// D. The invoice once it exists: its items, the difference from what came, and paying it
// ---------------------------------------------------------------------------------------------

interface Payment {
  id: string;
  paidOn: string;
  amount: number;
  method: string;
  reference: string;
  by: string;
  /** The receipt, UPI screenshot or bank confirmation. Required, like the copy of the bill. */
  proof?: BillFile;
  /**
   * A cash payment has no bank record behind it, so it is proved differently (Rajeev,
   * 2026-09-19): a note signed by whoever took the money, a photo of them (their ID card or of
   * them), and their name. No ID type and no Aadhaar rules: Rajeev simplified it the same day to
   * "a photo so we can recognise who took the money".
   */
  cash?: { note: BillFile; id: BillFile; receivedBy: string };
}

const START_PAYMENTS: Payment[] = [
  {
    id: "p1",
    paidOn: "2026-09-17",
    amount: 300,
    method: "UPI",
    reference: "UPI 4261 8837 0192",
    by: "Govinda Das",
    proof: SAMPLE_RECEIPT(300, "17 Sept 2026, 16:05", "4261 8837 0192"),
  },
  {
    id: "p2",
    paidOn: "2026-09-18",
    amount: 200,
    method: "CASH",
    reference: "",
    by: "Govinda Das",
    cash: {
      note: SAMPLE_NOTE(200, "Manjunath K."),
      id: SAMPLE_ID("Manjunath K."),
      receivedBy: "Manjunath K.",
    },
  },
];

function Detail({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt className="text-ink-secondary">{label}</dt>
      <dd className="mt-1">{children}</dd>
    </div>
  );
}

function DesignD() {
  const invoiceTotal = sums(orderSubtotal(BILLED), START_TOTALS).computed;
  const [payments, setPayments] = useState<Payment[]>(START_PAYMENTS);
  const [paying, setPaying] = useState(false);
  // What is open in the viewer: the bill, or one payment's proof.
  const [viewing, setViewing] = useState<BillFile | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const paid = payments.reduce((s, p) => s + p.amount, 0);
  const owed = round2(invoiceTotal - paid);
  const payRef = useRef<HTMLElement>(null);

  const billedTotal = orderSubtotal(BILLED);
  const receivedValue = round2(
    ORDER.reduce((s, l) => s + l.delivered * l.orderRate, 0),
  );
  const difference = round2(billedTotal - receivedValue);
  const overLines = ORDER.map(
    (l) =>
      overBilled(l, BILLED[l.id].qty) &&
      `${l.item}: ${overBilled(l, BILLED[l.id].qty)?.replace("Billed", "billed")}`,
  ).filter((x): x is string => !!x);

  function openPay() {
    setPaying(true);
    setDone(null);
    // The form opens in the Payments section, so the page is taken there with the cursor in Amount.
    setTimeout(() => {
      payRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
      payRef.current?.querySelector("input")?.focus({ preventScroll: true });
    }, 0);
  }

  return (
    <div className="grid gap-6 rounded-card border border-hairline bg-canvas px-4 pb-8 pt-6 sm:px-8">
      <PageHeader
        title={INVOICE_NO}
        subtitle={
          <span className="flex flex-wrap items-center gap-2">
            <span className="text-accent-text">{VENDOR}</span>
            {owed > 0 ? (
              <Badge tone="accent">Pending</Badge>
            ) : (
              <Badge>Paid</Badge>
            )}
          </span>
        }
        actions={
          owed > 0 && !paying ? (
            <Button icon="cash" onClick={openPay}>
              Pay this invoice
            </Button>
          ) : undefined
        }
      />

      {done && (
        <InlineNotice tone="success" autoDismiss>
          {done}
        </InlineNotice>
      )}

      <section className="card px-6 py-5" aria-labelledby="d-summary">
        <h3 id="d-summary" className="text-lg font-semibold text-ink">
          The invoice
        </h3>
        <div className="mt-4 flex flex-wrap items-start gap-x-8 gap-y-4">
          <dl className="grid min-w-0 grow basis-72 grid-cols-2 gap-x-8 gap-y-4 text-sm">
            <Detail label="Grand total">
              <span className="tabular-nums">{rs(invoiceTotal)}</span>
            </Detail>
            <Detail label="Against">
              <span className="tabular-nums text-accent-text">{PO}</span>
            </Detail>
            <Detail label="Invoice date">
              <span className="tabular-nums">{dateWithYear(INVOICE_DATE)}</span>
            </Detail>
            <Detail label="Due">
              <span className="tabular-nums">{dateWithYear(DUE_DATE)}</span>
            </Detail>
          </dl>
          <div className="grid gap-1 text-sm">
            <span className="text-ink-secondary">Copy of the bill</span>
            <span className="flex items-center gap-3">
              <BillThumb bill={SAMPLE_BILL} onOpen={() => setViewing(SAMPLE_BILL)} />
              <button
                type="button"
                onClick={() => setViewing(SAMPLE_BILL)}
                className="min-h-touch text-accent-text hover:underline"
              >
                {SAMPLE_FILE}
              </button>
            </span>
          </div>
        </div>
      </section>

      <section className="card overflow-hidden" aria-labelledby="d-items">
        <h3 id="d-items" className="px-6 pt-5 text-lg font-semibold text-ink">
          Items
        </h3>
        <table className={`${RULED_TABLE} mt-3`}>
          <thead className={THEAD}>
            <tr>
              <th className={TH_PRIMARY}>Item</th>
              <th className={TH_FIXED}>Ordered</th>
              <th className={TH_FIXED}>Delivered</th>
              <th className={TH_FIXED}>Billed</th>
              <th className={TH_FIXED}>Amount</th>
              <th className={TH_FIXED}>Rate</th>
            </tr>
          </thead>
          <tbody>
            {ORDER.map((l) => {
              const b = BILLED[l.id];
              const warn = overBilled(l, b.qty);
              return (
                <tr key={l.id} className={TR}>
                  <td className={TD_PRIMARY}>
                    {l.item}
                    {warn && (
                      <span className="flex items-center gap-1 text-sm font-normal text-warning">
                        <i
                          className="ti ti-alert-triangle"
                          aria-hidden="true"
                        />
                        {warn}
                      </span>
                    )}
                  </td>
                  <td className={TD_FIXED_NUM} data-label="Ordered">
                    {quantity(l.ordered, l.unit)}
                  </td>
                  <td className={TD_FIXED_NUM} data-label="Delivered">
                    {quantity(l.delivered, l.unit)}
                  </td>
                  <td className={TD_FIXED_NUM} data-label="Billed">
                    {quantity(Number(b.qty), l.unit)}
                  </td>
                  <td className={TD_FIXED_NUM} data-label="Amount">
                    {rs(Number(b.amount))}
                  </td>
                  <td
                    className={`${TD_FIXED_NUM} text-ink-secondary`}
                    data-label="Rate"
                  >
                    {rateText(b.qty, b.amount, l.unit)}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {/* The bill's foot, on the right as on the paper, figures ending on one edge. Not a table,
            so the table rule's left alignment does not apply (see TotalsFields). */}
        <div className="flex border-t border-hairline px-6 py-4">
          <dl className="ms-auto grid grid-cols-[auto_auto_1.5rem] items-center gap-x-3 gap-y-2 text-sm [&_dd]:text-right">
            <dt className="pe-5 text-ink-secondary">Sub total</dt>
            <dd className="tabular-nums">{rs(billedTotal)}</dd>
            <dd aria-hidden="true" />
            <dt className="pe-5 text-ink-secondary">GST</dt>
            <dd className="tabular-nums">{rs(Number(START_TOTALS.gst))}</dd>
            <dd aria-hidden="true" />
            <dt className="pe-5 text-ink-secondary">Other charges</dt>
            <dd className="tabular-nums">{rs(Number(START_TOTALS.charges))}</dd>
            <dd aria-hidden="true" />
            <dt className="pe-5 text-ink-secondary">Discount</dt>
            <dd className="tabular-nums">{`− ${rs(Number(START_TOTALS.discount))}`}</dd>
            <dd aria-hidden="true" />
            <dt className="pe-5 font-semibold text-ink">Grand total</dt>
            <dd className="font-semibold tabular-nums">{rs(invoiceTotal)}</dd>
            <dd>
              <AddsUp />
            </dd>
          </dl>
        </div>
      </section>

      <section className="card px-6 py-5" aria-labelledby="d-variance">
        <h3 id="d-variance" className="text-lg font-semibold text-ink">
          Invoiced against received
        </h3>
        <p className="mt-1 max-w-prose text-sm text-ink-secondary">
          The items billed, against what was delivered at the order’s own
          prices. A difference is worth a question, not a refusal.
        </p>
        <dl className="mt-4 flex flex-wrap gap-x-10 gap-y-4 text-sm">
          <Detail label="Items billed">
            <span className="tabular-nums">{rs(billedTotal)}</span>
          </Detail>
          <Detail label="Delivered, at the order’s prices">
            <span className="tabular-nums">{rs(receivedValue)}</span>
          </Detail>
          <Detail label="Difference">
            <span
              className={`tabular-nums ${difference ? "text-warning" : ""}`}
            >
              {difference === 0
                ? "None"
                : `${rs(Math.abs(difference))} ${difference > 0 ? "more" : "less"} than received`}
            </span>
          </Detail>
        </dl>
        {overLines.length > 0 && (
          <ul className="mt-3 grid gap-1 text-sm text-warning">
            {overLines.map((w) => (
              <li key={w} className="flex items-center gap-1">
                <i className="ti ti-alert-triangle" aria-hidden="true" />
                {w}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section
        className="card scroll-mt-4 px-6 py-5"
        aria-labelledby="d-payments"
        ref={payRef}
      >
        <h3 id="d-payments" className="text-lg font-semibold text-ink">
          Payments
        </h3>
        <p className="mt-1 max-w-prose text-sm text-ink-secondary">
          Payments are made at the bank and recorded here. This app never pays
          anybody.
        </p>
        <dl className="mt-4 flex flex-wrap gap-x-10 gap-y-4 text-sm">
          <Detail label="Paid to date">
            <span className="tabular-nums">{rs(paid)}</span>
          </Detail>
          <Detail label="Still owed">
            <span className="tabular-nums">{rs(owed)}</span>
          </Detail>
        </dl>

        {paying && (
          <PayForm
            owed={owed}
            onCancel={() => setPaying(false)}
            onPaid={(p) => {
              setPayments((ps) => [...ps, p]);
              setPaying(false);
              const left = round2(owed - p.amount);
              setDone(
                `Payment of ${rs(p.amount)} recorded. ` +
                  (left > 0
                    ? `${rs(left)} is still owed on this invoice.`
                    : "This invoice is now paid in full."),
              );
            }}
          />
        )}

        <div className="-mx-6 mt-4">
          <table className={RULED_TABLE}>
            <thead className={THEAD}>
              <tr>
                <th className={TH_FIXED}>Paid on</th>
                <th className={TH_FIXED}>Amount</th>
                <th className={TH_FIXED}>Method</th>
                <th className={TH_FIXED}>Reference</th>
                <th className={TH_SECOND}>Paid by</th>
                <th className={TH_FIXED}>Proof</th>
              </tr>
            </thead>
            <tbody>
              {payments.map((p) => (
                <tr key={p.id} className={TR}>
                  <td className={TD_FIXED}>{dateWithYear(p.paidOn)}</td>
                  <td className={TD_FIXED_NUM}>{rs(p.amount)}</td>
                  <td className={TD_FIXED}>
                    {METHODS.find((m) => m.value === p.method)?.label}
                  </td>
                  <td
                    className={`${TD_FIXED} text-ink-secondary`}
                    data-label="Reference"
                  >
                    {p.cash
                      ? `Received by ${p.cash.receivedBy}`
                      : p.reference || "—"}
                  </td>
                  <td
                    className={`${TD_SECOND} text-ink-secondary`}
                    data-label="Paid by"
                  >
                    {p.by}
                  </td>
                  <td className={TD_FIXED} data-label="Proof">
                    {/* Cash carries two: the signed note, then the ID. */}
                    <span className="inline-flex gap-2 align-middle">
                      {(p.cash ? [p.cash.note, p.cash.id] : p.proof ? [p.proof] : []).map((file) => (
                        <BillThumb
                          key={file.name}
                          bill={file}
                          small
                          onOpen={() => setViewing(file)}
                        />
                      ))}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      {viewing && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 px-4 py-6"
          role="dialog"
          aria-modal="true"
          aria-label={viewing.name}
          onClick={() => setViewing(null)}
        >
          <div
            className="modal grid max-h-full w-full max-w-md gap-3 overflow-y-auto px-5 py-5"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between gap-3">
              <span className="min-w-0 font-semibold text-ink [overflow-wrap:anywhere]">
                {viewing.name}
              </span>
              <Button variant="ghost" onClick={() => setViewing(null)}>
                Close
              </Button>
            </div>
            <BillView bill={viewing} />
          </div>
        </div>
      )}
    </div>
  );
}

function PayForm({
  owed,
  onCancel,
  onPaid,
}: {
  owed: number;
  onCancel: () => void;
  onPaid: (p: Payment) => void;
}) {
  const [amount, setAmount] = useState(String(owed));
  const [paidOn, setPaidOn] = useState(TODAY);
  const [method, setMethod] = useState("UPI");
  const [reference, setReference] = useState("");
  const [proof, setProof] = useState<BillFile | null>(null);
  const [note, setNote] = useState<BillFile | null>(null);
  const [idCard, setIdCard] = useState<BillFile | null>(null);
  const [receivedBy, setReceivedBy] = useState("");
  const [tried, setTried] = useState(false);
  const a = num(amount);
  const tooMuch = a != null && a > owed + 0.005;
  const cash = method === "CASH";
  const proved = cash ? note != null && idCard != null : proof != null;

  function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setTried(true);
    if (a == null || a <= 0 || tooMuch || !proved) return;
    onPaid({
      id: newKey(),
      paidOn,
      amount: a,
      method,
      reference: cash ? "" : reference.trim(),
      by: "Govinda Das",
      ...(cash
        ? { cash: { note: note!, id: idCard!, receivedBy: receivedBy.trim() } }
        : { proof: proof! }),
    });
  }

  return (
    <Form
      onSubmit={submit}
      aria-label="Pay this invoice"
      className="mt-4 grid gap-4 rounded-control border border-hairline bg-sunken px-4 py-4"
    >
      <p className="font-semibold text-ink">Record a payment</p>
      <div className="flex flex-wrap gap-x-4 gap-y-3">
        <label className={LABEL}>
          <span className={LABEL_TEXT}>Amount (₹)</span>
          <MoneyBox
            value={amount}
            onChange={setAmount}
            invalid={tooMuch}
            className="w-32"
          />
          {tooMuch && (
            <span className="pl-field-inset text-sm text-danger">{`That’s more than the ${rs(owed)} still owed.`}</span>
          )}
        </label>
        <label className={LABEL}>
          <span className={LABEL_TEXT}>Paid on</span>
          <input
            type="date"
            required
            value={paidOn}
            max={TODAY}
            onChange={(e) => setPaidOn(e.target.value)}
            className={FIELD}
          />
        </label>
        <label className={LABEL}>
          <span className={LABEL_TEXT}>Method</span>
          <select
            value={method}
            onChange={(e) => setMethod(e.target.value)}
            className={FIELD}
          >
            {METHODS.map((m) => (
              <option key={m.value} value={m.value}>
                {m.label}
              </option>
            ))}
          </select>
        </label>
        {/* Cash has no reference number. The person who took it is what identifies it, so the
            same place in the row asks for their name instead. */}
        {cash ? (
          <label className={`${LABEL} grow basis-48`}>
            <span className={LABEL_TEXT}>Received by</span>
            <input
              required
              value={receivedBy}
              onChange={(e) => setReceivedBy(e.target.value)}
              placeholder="Their full name"
              className={FIELD}
            />
          </label>
        ) : (
          <label className={`${LABEL} grow basis-48`}>
            <span className={LABEL_TEXT}>Reference</span>
            <input
              value={reference}
              onChange={(e) => setReference(e.target.value)}
              placeholder="Bank, UPI or cheque number"
              className={FIELD}
            />
          </label>
        )}
      </div>
      {/* Required, as the copy of the bill is on the invoice. The file input is not a form
          control Form can check (it is emptied after each pick so the same file can be chosen
          again), so the refusal is said here, in Form's own words. */}
      {cash ? (
        <>
          <UploadBill
            bill={note}
            setBill={setNote}
            invalid={tried && !note}
            title="Signed note"
            prompt="A note signed by the person who received the cash, e.g. ‘Received ₹1,030 in cash from ISKCON South Bengaluru’"
            missing={required("Signed note")}
            sample={SAMPLE_NOTE(a ?? 0, receivedBy.trim() || "Manjunath K.")}
            sampleLabel="Use a sample note"
          />
          <UploadBill
            bill={idCard}
            setBill={setIdCard}
            invalid={tried && !idCard}
            title="Photo of the person who took the cash"
            prompt="Their ID card, or a photo of them, so we can recognise who took the money."
            missing={required("Photo of the person who took the cash")}
            sample={SAMPLE_ID(receivedBy.trim() || "Manjunath K.")}
            sampleLabel="Use a sample photo"
          />
        </>
      ) : (
        <UploadBill
          bill={proof}
          setBill={setProof}
          invalid={tried && !proof}
          title="Proof of payment"
          prompt="Upload proof of payment — a receipt, UPI screenshot or bank confirmation."
          missing={required("Proof of payment")}
          sample={SAMPLE_RECEIPT(a ?? 0, "19 Sept 2026, 11:42", "4261 8837 0417")}
          sampleLabel="Use a sample receipt"
        />
      )}
      <div className="flex flex-wrap justify-end gap-3">
        <Button variant="ghost" onClick={onCancel} className="max-sm:flex-1">
          Cancel
        </Button>
        {/* Marks the attempt before Form's own checks run, so a missing upload is named in the
            same press as a blank name rather than one press later. */}
        <Button type="submit" className="max-sm:flex-1" onClick={() => setTried(true)}>
          Record payment
        </Button>
      </div>
    </Form>
  );
}

// ---------------------------------------------------------------------------------------------
// The page
// ---------------------------------------------------------------------------------------------

export default function InvoiceMockPage() {
  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/invoices" />
      <main className="min-w-0 flex-1">
        <div className="mx-auto grid max-w-content gap-10 px-4 pb-24 pt-8 sm:px-8">
          <PageHeader
            title="Invoices: design A, finished"
            subtitle="A is the design you chose, now finished. B and C stay for comparison. Every figure is sample data and nothing is saved."
            actions={
              <Button
                icon="plus"
                onClick={() =>
                  document
                    .getElementById("design-A")
                    ?.scrollIntoView({ behavior: "smooth", block: "start" })
                }
              >
                Create an invoice
              </Button>
            }
          />
          <p className="-mt-6 max-w-prose text-sm text-ink-secondary">
            On the Invoices list the button now says Create an invoice and opens
            the form straight away. Here it takes you to design A.
          </p>

          <Design
            letter="A"
            title="Classic"
            chosen
            about="The invoice’s details and the copy of the bill first, then the order’s items with what was billed, then the bill’s totals on the right. Tick Direct to add items by hand instead."
          >
            <DesignA />
          </Design>

          <Design
            letter="B"
            title="Shaped like the bill"
            about="Laid out like the paper: quantity × rate = amount on each line, and the sums at the bottom right. Type the rate or the amount, and the other is worked out."
          >
            <DesignB />
          </Design>

          <Design
            letter="C"
            title="Side by side with the bill"
            about="The uploaded bill on the left and the form on the right, so you can copy the figures without turning away from them. On a phone the bill sits above the form."
          >
            <DesignC />
          </Design>

          <Design
            letter="D"
            title="An existing invoice"
            about="What the invoice looks like once saved: its items, how the bill compares with what came, and its payments. Press Pay this invoice to record a payment here."
          >
            <DesignD />
          </Design>
        </div>
      </main>
    </div>
  );
}
