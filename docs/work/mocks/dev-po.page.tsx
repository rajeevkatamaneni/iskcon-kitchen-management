"use client";

/*
 * THROWAWAY MOCK for T-245 — delete before any deploy.
 *
 * Four ways to build "Create a purchase order" by hand (A to D), and the merged order-and-deliveries
 * table for an order that already exists (E), so Rajeev can choose by using them rather than by
 * reading about them. Everything here is sample data held in component state: no API call, no
 * session needed, nothing saved.
 *
 * It is built from the app's own parts (Sidebar, Button, Badge, the ruled-table constants and the
 * field classes the order screens use) so it looks like the app, and so the table fitter and the
 * phone card layout in globals.css apply to it exactly as they would on the real screen. No shared
 * component, token or stylesheet was changed for it.
 */

import { Fragment, useEffect, useId, useRef, useState, type ReactNode } from "react";
import { Sidebar } from "@/components/Sidebar";
import { Button } from "@/components/ds/Button";
import { Badge } from "@/components/ds/Badge";
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
import { FOOD_UNITS, money, unitLabel, unitLabelFor } from "@/lib/format";

// ---------------------------------------------------------------------------------------------
// Sample data. Plausible Bengaluru wholesale prices, September; not real quotes.
// ---------------------------------------------------------------------------------------------

interface Product {
  name: string;
  unit: string;
  /** The vendor's list price, per unit. Null for an ingredient this vendor does not sell. */
  price: number | null;
  /** What the temple last paid this vendor, per unit. */
  lastPaid: number | null;
}

const KALASIPALYA = "Kalasipalya Vegetable Mandi";

const VENDOR_LISTS: Record<string, Product[]> = {
  [KALASIPALYA]: [
    { name: "Tomato, ripe", unit: "KG", price: 32, lastPaid: 30 },
    { name: "Coconut, fresh grated", unit: "KG", price: 120, lastPaid: 115 },
    { name: "Curry leaves", unit: "KG", price: 60, lastPaid: 55 },
    { name: "Green chilli, slit", unit: "KG", price: 70, lastPaid: 64 },
    { name: "Coriander leaves", unit: "KG", price: 80, lastPaid: 90 },
    { name: "Ginger", unit: "KG", price: 140, lastPaid: 150 },
    { name: "Potato", unit: "KG", price: 34, lastPaid: 32 },
    { name: "Onion, big", unit: "KG", price: 38, lastPaid: 36 },
    { name: "Beans", unit: "KG", price: 80, lastPaid: 76 },
    { name: "Carrot", unit: "KG", price: 56, lastPaid: 52 },
    { name: "Lemon", unit: "PIECES", price: 4, lastPaid: 4 },
    { name: "Banana leaf", unit: "PIECES", price: 3, lastPaid: 2.5 },
  ],
  "Sri Lakshmi Provisions": [
    { name: "Toor dal", unit: "KG", price: 148, lastPaid: 145 },
    { name: "Rice, Sona Masoori", unit: "KG", price: 58, lastPaid: 56 },
    { name: "Jaggery", unit: "KG", price: 62, lastPaid: 60 },
    { name: "Groundnut oil", unit: "L", price: 185, lastPaid: 180 },
    { name: "Rava, fine", unit: "KG", price: 46, lastPaid: 44 },
  ],
  "Nandini Milk Parlour, Chamrajpet": [
    { name: "Milk, toned", unit: "L", price: 44, lastPaid: 44 },
    { name: "Curd", unit: "KG", price: 60, lastPaid: 58 },
    { name: "Ghee", unit: "KG", price: 640, lastPaid: 620 },
  ],
};

const VENDORS = Object.keys(VENDOR_LISTS);

/** Every ingredient the temple keeps, whoever sells it — what the type-to-search boxes look through. */
const ALL_PRODUCTS: Product[] = [
  ...VENDORS.flatMap((v) => VENDOR_LISTS[v]),
  { name: "Cashew, whole", unit: "KG", price: null, lastPaid: null },
  { name: "Tomato puree", unit: "KG", price: null, lastPaid: null },
  { name: "Cardamom", unit: "GM", price: null, lastPaid: null },
];

/** A vendor's own price for an ingredient, if they sell it. */
function listPrice(vendor: string, name: string): number | null {
  return VENDOR_LISTS[vendor]?.find((p) => p.name === name)?.price ?? null;
}

function productNamed(name: string): Product | undefined {
  return ALL_PRODUCTS.find((p) => p.name === name);
}

let nextKey = 1;
const newKey = () => `k${nextKey++}`;

const FIELD = "min-h-touch rounded-control border border-hairline px-3";
const NUM_BOX = "min-h-touch w-28 rounded-control border border-hairline px-3 tabular-nums";
const LABEL = "flex flex-col gap-1 text-sm text-ink-secondary";
const LABEL_TEXT = "pl-field-inset font-medium text-ink";

function num(text: string): number {
  const n = Number(text);
  return Number.isFinite(n) && n > 0 ? n : 0;
}

/** "18 Sept" — the day and month, as the app writes them. */
function dayMonth(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString("en-GB", { day: "numeric", month: "short" });
}

// ---------------------------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------------------------

/** One line on an order being built. Quantity and price are the text in their boxes. */
interface Line {
  key: string;
  name: string;
  unit: string;
  quantity: string;
  price: string;
  /** A one-off line: typed by hand, not a catalogue ingredient, so its unit is chosen. */
  oneOff?: boolean;
}

function lineTotal(l: { quantity: string; price: string }): number | null {
  const q = num(l.quantity);
  const p = num(l.price);
  return q > 0 && p > 0 ? q * p : null;
}

function OrderTotal({ total, count }: { total: number; count: number }) {
  return (
    <p className="mt-4 flex flex-wrap items-baseline gap-x-3 pl-5 text-ink-secondary">
      <span>Order total</span>
      <span className="text-lg font-semibold tabular-nums text-ink">{money(total, "INR")}</span>
      <span className="text-sm">
        {count} {count === 1 ? "item" : "items"}
      </span>
    </p>
  );
}

/**
 * A text box that suggests ingredients as you type. Free text is kept as typed, for something the
 * temple has never bought before.
 */
function ItemSearch({
  value,
  onChange,
  onPick,
  options,
  placeholder,
  label,
  inputRef,
  className = "",
}: {
  value: string;
  onChange: (text: string) => void;
  onPick: (p: Product) => void;
  options: Product[];
  placeholder?: string;
  label: string;
  inputRef?: React.Ref<HTMLInputElement>;
  className?: string;
}) {
  const listId = useId();
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const q = value.trim().toLowerCase();
  const matches = (q === "" ? options : options.filter((p) => p.name.toLowerCase().includes(q))).slice(0, 8);

  function pick(p: Product) {
    onPick(p);
    setOpen(false);
  }

  return (
    <div className={`relative ${className}`}>
      <input
        ref={inputRef}
        role="combobox"
        aria-label={label}
        aria-expanded={open && matches.length > 0}
        aria-controls={listId}
        aria-autocomplete="list"
        value={value}
        placeholder={placeholder}
        onChange={(e) => {
          onChange(e.target.value);
          setOpen(true);
          setActive(0);
        }}
        onFocus={() => setOpen(true)}
        onBlur={() => setOpen(false)}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown") {
            e.preventDefault();
            setOpen(true);
            setActive((a) => Math.min(a + 1, matches.length - 1));
          } else if (e.key === "ArrowUp") {
            e.preventDefault();
            setActive((a) => Math.max(a - 1, 0));
          } else if (e.key === "Enter" && open && matches[active]) {
            e.preventDefault();
            pick(matches[active]);
          } else if (e.key === "Escape") {
            setOpen(false);
          }
        }}
        className={`${FIELD} w-full`}
      />
      {open && matches.length > 0 && (
        <ul
          id={listId}
          role="listbox"
          className="absolute left-0 top-full z-20 mt-1 max-h-72 w-full min-w-64 overflow-y-auto rounded-control border border-hairline bg-raised py-1 shadow-overlay"
        >
          {matches.map((p, i) => (
            <li
              key={p.name}
              role="option"
              aria-selected={i === active}
              onMouseDown={(e) => {
                e.preventDefault();
                pick(p);
              }}
              onMouseEnter={() => setActive(i)}
              className={`flex cursor-pointer items-baseline justify-between gap-4 px-3 py-2 text-sm ${i === active ? "bg-sunken" : ""}`}
            >
              <span className="text-ink">{p.name}</span>
              <span className="whitespace-nowrap text-ink-secondary tabular-nums">
                {p.price != null ? `${money(p.price, "INR")} / ${unitLabel(p.unit)}` : unitLabel(p.unit)}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** A textarea that grows with what is typed, one line to start with. */
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
      rows={1}
      value={value}
      maxLength={1000}
      onChange={(e) => onChange(e.target.value)}
      className="min-h-touch w-full resize-none overflow-hidden rounded-control border border-hairline px-3 py-2.5 leading-6"
    />
  );
}

interface Header {
  vendor: string;
  neededBy: string;
  note: string;
}

const START_HEADER: Header = {
  vendor: KALASIPALYA,
  neededBy: "2026-09-22",
  note: "Please deliver before 7 am at the kitchen back gate.",
};

/** The part of the form Rajeev has already decided: vendor and needed-by side by side, then the note. */
function OrderFields({ header, setHeader }: { header: Header; setHeader: (h: Header) => void }) {
  return (
    <div className="grid gap-4">
      <div className="grid gap-4 sm:grid-cols-2">
        <label className={LABEL}>
          <span className={LABEL_TEXT}>Vendor</span>
          <select
            value={header.vendor}
            onChange={(e) => setHeader({ ...header, vendor: e.target.value })}
            className={FIELD}
          >
            <option value="">Choose a vendor…</option>
            {VENDORS.map((v) => (
              <option key={v} value={v}>
                {v}
              </option>
            ))}
          </select>
        </label>
        <label className={LABEL}>
          <span className={LABEL_TEXT}>Needed by</span>
          <input
            type="date"
            value={header.neededBy}
            min="2026-09-18"
            onChange={(e) => setHeader({ ...header, neededBy: e.target.value })}
            className={FIELD}
          />
        </label>
      </div>
      <label className={LABEL}>
        <span className={LABEL_TEXT}>Note for the vendor</span>
        <GrowingNote value={header.note} onChange={(note) => setHeader({ ...header, note })} />
      </label>
    </div>
  );
}

/** The screen as the app draws a form screen: the sunken band with the task and its two buttons. */
function MockScreen({ children }: { children: ReactNode }) {
  return (
    <div className="rounded-card border border-hairline bg-canvas">
      <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2 rounded-t-card border-b border-hairline bg-sunken px-4 py-2 sm:px-8 sm:py-4">
        <p className="text-lg font-semibold leading-6 text-ink sm:text-xl">Create a purchase order</p>
        <div className="flex flex-wrap gap-2 max-sm:[&_.btn]:px-3 max-sm:[&_.btn]:text-sm">
          <Button variant="secondary">Cancel</Button>
          <Button>Create order</Button>
        </div>
      </div>
      <div className="grid gap-6 px-4 pb-8 pt-6 sm:px-8">{children}</div>
    </div>
  );
}

function Design({ letter, title, recommended, children, about }: {
  letter: string;
  title: string;
  recommended?: boolean;
  about: ReactNode;
  children: ReactNode;
}) {
  return (
    <section className="grid gap-4 border-t border-hairline pt-10" aria-labelledby={`design-${letter}`}>
      <div className="grid gap-1">
        <h2 id={`design-${letter}`} className="flex flex-wrap items-center gap-3 text-xl font-semibold text-ink">
          {letter}. {title}
          {recommended && <Badge tone="accent">Recommended</Badge>}
        </h2>
        <p className="max-w-prose text-ink-secondary">{about}</p>
      </div>
      {children}
    </section>
  );
}

function ItemsHeading() {
  return <h3 className="text-base font-semibold text-ink">Items</h3>;
}

const SAMPLE_LINES: Omit<Line, "key">[] = [
  { name: "Tomato, ripe", unit: "KG", quantity: "9", price: "32" },
  { name: "Coconut, fresh grated", unit: "KG", quantity: "4", price: "120" },
  { name: "Curry leaves", unit: "KG", quantity: "3", price: "60" },
  { name: "Green chilli, slit", unit: "KG", quantity: "2.5", price: "70" },
  { name: "Coriander leaves", unit: "KG", quantity: "2", price: "80" },
];

const sampleLines = (): Line[] => SAMPLE_LINES.map((l) => ({ ...l, key: newKey() }));

/** The unit cell: fixed by the catalogue for a known ingredient, chosen for something new. */
function UnitCell({ line, onUnit }: { line: Line; onUnit: (u: string) => void }) {
  if (line.name === "") return <td className={TD_FIXED} />;
  if (!line.oneOff && productNamed(line.name)) {
    return <td className={`${TD_FIXED} text-ink-secondary`}>{unitLabel(line.unit)}</td>;
  }
  return (
    <td className={TD_FIXED}>
      <select
        aria-label={`Unit for ${line.name}`}
        value={line.unit || "KG"}
        onChange={(e) => onUnit(e.target.value)}
        className={FIELD}
      >
        {FOOD_UNITS.map((u) => (
          <option key={u} value={u}>
            {unitLabel(u)}
          </option>
        ))}
      </select>
    </td>
  );
}

function TotalCell({ line }: { line: Line }) {
  const t = lineTotal(line);
  return (
    <td className={TD_FIXED_NUM} data-label={t == null ? undefined : "Line total"}>
      {t == null ? "" : money(t, "INR")}
    </td>
  );
}

function sum(lines: Line[]) {
  return lines.reduce((a, l) => a + (lineTotal(l) ?? 0), 0);
}

// ---------------------------------------------------------------------------------------------
// A. Inline table
// ---------------------------------------------------------------------------------------------

/** What a pick in A's Item box turns into: a catalogue ingredient, or a one-off line typed by hand. */
type Choice = { kind: "product"; product: Product; vendorPrice: number | null } | { kind: "oneOff"; text: string };

/**
 * A's type-ahead. The chosen vendor's own items come first with their list price, then every other
 * ingredient the temple keeps under an "Other ingredients" divider. When the text matches nothing
 * at all, the one line left offers to add it as a one-off, which is how a thing the catalogue has
 * never heard of gets onto an order without inventing an ingredient for it.
 *
 * It follows the ARIA combobox pattern: the input keeps focus and names the highlighted option with
 * aria-activedescendant; Down and Up move, Enter picks, Escape closes. The list opens as soon as
 * something is typed, or on Down from an empty box to browse.
 */
function IngredientCombobox({
  vendor,
  exclude,
  onChoose,
}: {
  vendor: string;
  exclude: Set<string>;
  onChoose: (c: Choice) => void;
}) {
  const baseId = useId();
  const listId = `${baseId}-list`;
  const otherHeadId = `${baseId}-other`;
  const [text, setText] = useState("");
  const [open, setOpen] = useState(false);
  const [browse, setBrowse] = useState(false);
  const [active, setActive] = useState(0);

  const q = text.trim().toLowerCase();
  const theirList = VENDOR_LISTS[vendor] ?? [];
  const theirNames = new Set(theirList.map((p) => p.name));
  const hit = (p: Product) => !exclude.has(p.name) && (q === "" || p.name.toLowerCase().includes(q));
  const theirs = theirList.filter(hit);
  const others = ALL_PRODUCTS.filter((p) => !theirNames.has(p.name) && hit(p));
  const oneOff = q !== "" && theirs.length === 0 && others.length === 0 ? text.trim() : null;

  const choices: Choice[] = [
    ...theirs.map((p): Choice => ({ kind: "product", product: p, vendorPrice: p.price })),
    ...others.map((p): Choice => ({ kind: "product", product: p, vendorPrice: null })),
    ...(oneOff ? [{ kind: "oneOff", text: oneOff } as Choice] : []),
  ];
  const visible = open && (q !== "" || browse) && choices.length > 0;
  const at = Math.min(active, choices.length - 1);
  const optionId = (i: number) => `${baseId}-opt-${i}`;

  function choose(c: Choice) {
    onChoose(c);
    setText("");
    setOpen(false);
    setBrowse(false);
    setActive(0);
  }

  function option(c: Choice, i: number) {
    const on = i === at;
    return (
      <div
        key={optionId(i)}
        id={optionId(i)}
        role="option"
        aria-selected={on}
        onMouseDown={(e) => {
          e.preventDefault();
          choose(c);
        }}
        onMouseEnter={() => setActive(i)}
        className={`cursor-pointer px-3 py-2 text-sm ${on ? "bg-sunken" : ""}`}
      >
        {c.kind === "oneOff" ? (
          <span className="text-ink">Add ‘{c.text}’ as a one-off item</span>
        ) : (
          <>
            <span className="text-ink">{c.product.name}</span>
            <span className="tabular-nums text-ink-secondary">
              {c.vendorPrice != null
                ? ` · ${money(c.vendorPrice, "INR")}/${unitLabel(c.product.unit)}`
                : ` · ${unitLabel(c.product.unit)}`}
            </span>
          </>
        )}
      </div>
    );
  }

  return (
    <div className="relative min-w-64">
      <input
        role="combobox"
        aria-label="Add an item"
        aria-expanded={visible}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={visible ? optionId(at) : undefined}
        value={text}
        placeholder="Type an ingredient…"
        onChange={(e) => {
          setText(e.target.value);
          setOpen(true);
          setActive(0);
        }}
        onBlur={() => {
          setOpen(false);
          setBrowse(false);
        }}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown") {
            e.preventDefault();
            if (!visible) {
              setOpen(true);
              setBrowse(true);
              setActive(0);
            } else setActive(Math.min(at + 1, choices.length - 1));
          } else if (e.key === "ArrowUp") {
            e.preventDefault();
            if (visible) setActive(Math.max(at - 1, 0));
          } else if (e.key === "Enter") {
            if (visible && choices[at]) {
              e.preventDefault();
              choose(choices[at]);
            }
          } else if (e.key === "Escape") {
            if (visible) {
              e.preventDefault();
              setOpen(false);
              setBrowse(false);
            }
          }
        }}
        className={`${FIELD} w-full`}
      />
      {visible && (
        <div
          id={listId}
          role="listbox"
          aria-label="Matching ingredients"
          className="absolute left-0 top-full z-20 mt-1 max-h-80 w-full min-w-64 overflow-y-auto rounded-control border border-hairline bg-raised py-1 shadow-overlay"
        >
          {theirs.length > 0 && (
            <div role="group" aria-label={`Sold by ${vendor}`}>
              {theirs.map((_, i) => option(choices[i], i))}
            </div>
          )}
          {others.length > 0 && (
            <div role="group" aria-labelledby={otherHeadId}>
              <div
                id={otherHeadId}
                role="presentation"
                className={`px-3 pb-1 pt-2 text-xs font-semibold text-ink-muted ${theirs.length > 0 ? "mt-1 border-t border-hairline" : ""}`}
              >
                Other ingredients
              </div>
              {others.map((_, j) => option(choices[theirs.length + j], theirs.length + j))}
            </div>
          )}
          {oneOff && option(choices[choices.length - 1], choices.length - 1)}
        </div>
      )}
    </div>
  );
}

// Tomato is left off so that typing "tom" shows it, which is the first thing Rajeev will try.
const A_START: Omit<Line, "key">[] = SAMPLE_LINES.slice(1, 4);

function DesignA() {
  const [header, setHeader] = useState(START_HEADER);
  // The lines on the order. The row with the Item box is always drawn after them, empty.
  const [lines, setLines] = useState<Line[]>(() => A_START.map((l) => ({ ...l, key: newKey() })));
  const qtyRefs = useRef(new Map<string, HTMLInputElement>());
  const [focusKey, setFocusKey] = useState<string | null>(null);

  useEffect(() => {
    if (focusKey) qtyRefs.current.get(focusKey)?.focus();
  }, [focusKey]);

  const update = (key: string, patch: Partial<Line>) =>
    setLines((cur) => cur.map((l) => (l.key === key ? { ...l, ...patch } : l)));

  function choose(c: Choice) {
    const key = newKey();
    const line: Line =
      c.kind === "product"
        ? { key, name: c.product.name, unit: c.product.unit, quantity: "", price: c.vendorPrice == null ? "" : String(c.vendorPrice) }
        : { key, name: c.text, unit: "PIECES", quantity: "", price: "", oneOff: true };
    setLines((cur) => [...cur, line]);
    setFocusKey(key);
  }

  return (
    <MockScreen>
      <OrderFields header={header} setHeader={setHeader} />
      <div>
        <ItemsHeading />
        <p className="mt-1 text-sm text-ink-secondary">Start typing an ingredient, or type anything to add a one-off item.</p>
        <table className={`${RULED_TABLE} mt-2`}>
          <thead className={THEAD}>
            <tr>
              <th className={TH_PRIMARY}>Item</th>
              <th className={TH_FIXED}>Quantity</th>
              <th className={TH_FIXED}>Unit</th>
              <th className={TH_FIXED}>Expected price (₹)</th>
              <th className={TH_FIXED}>Line total</th>
              <th className={TH_ACTIONS_FIXED}><span className="sr-only">Remove</span></th>
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
                    min="0"
                    step="any"
                    aria-label={`Quantity of ${l.name}`}
                    value={l.quantity}
                    onChange={(e) => update(l.key, { quantity: e.target.value })}
                    className={NUM_BOX}
                  />
                </td>
                <UnitCell line={l} onUnit={(unit) => update(l.key, { unit })} />
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
                </td>
                <TotalCell line={l} />
                <td className={TD_ACTIONS_FIXED}>
                  <Button
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
            <tr className={`${TR} !align-middle`}>
              <td className={TD_PRIMARY}>
                <IngredientCombobox
                  vendor={header.vendor}
                  exclude={new Set(lines.filter((l) => !l.oneOff).map((l) => l.name))}
                  onChoose={choose}
                />
              </td>
              <td className={TD_FIXED_NUM} />
              <td className={TD_FIXED} />
              <td className={TD_FIXED_NUM} />
              <td className={TD_FIXED_NUM} />
              <td className={TD_ACTIONS_FIXED} />
            </tr>
          </tbody>
        </table>
        <OrderTotal total={sum(lines)} count={lines.length} />
      </div>
    </MockScreen>
  );
}

// ---------------------------------------------------------------------------------------------
// B. Search first
// ---------------------------------------------------------------------------------------------

function EditableLines({
  lines,
  setLines,
  qtyRefs,
}: {
  lines: Line[];
  setLines: React.Dispatch<React.SetStateAction<Line[]>>;
  qtyRefs?: React.MutableRefObject<Map<string, HTMLInputElement>>;
}) {
  const update = (key: string, patch: Partial<Line>) =>
    setLines((cur) => cur.map((l) => (l.key === key ? { ...l, ...patch } : l)));
  return (
    <table className={`${RULED_TABLE} mt-2`}>
      <thead className={THEAD}>
        <tr>
          <th className={TH_PRIMARY}>Item</th>
          <th className={TH_FIXED}>Quantity</th>
          <th className={TH_FIXED}>Unit</th>
          <th className={TH_FIXED}>Expected price (₹)</th>
          <th className={TH_FIXED}>Line total</th>
          <th className={TH_ACTIONS_FIXED}><span className="sr-only">Remove</span></th>
        </tr>
      </thead>
      <tbody>
        {lines.map((l) => (
          <tr key={l.key} className={`${TR} !align-middle`}>
            <td className={TD_PRIMARY}>{l.name}</td>
            <td className={TD_FIXED_NUM} data-label="Quantity">
              <input
                ref={(el) => {
                  if (!qtyRefs) return;
                  if (el) qtyRefs.current.set(l.key, el);
                  else qtyRefs.current.delete(l.key);
                }}
                type="number"
                min="0"
                step="any"
                aria-label={`Quantity of ${l.name}`}
                value={l.quantity}
                onChange={(e) => update(l.key, { quantity: e.target.value })}
                className={NUM_BOX}
              />
            </td>
            <UnitCell line={l} onUnit={(unit) => update(l.key, { unit })} />
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
            </td>
            <TotalCell line={l} />
            <td className={TD_ACTIONS_FIXED}>
              <Button
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
      </tbody>
    </table>
  );
}

function DesignB() {
  const [header, setHeader] = useState(START_HEADER);
  const [lines, setLines] = useState<Line[]>(sampleLines);
  const [search, setSearch] = useState("");
  const qtyRefs = useRef(new Map<string, HTMLInputElement>());
  const [focusKey, setFocusKey] = useState<string | null>(null);

  useEffect(() => {
    if (focusKey) qtyRefs.current.get(focusKey)?.focus();
  }, [focusKey]);

  function add(p: Product) {
    const key = newKey();
    setLines((cur) => [
      ...cur,
      { key, name: p.name, unit: p.unit, quantity: "", price: String(listPrice(header.vendor, p.name) ?? "") },
    ]);
    setSearch("");
    setFocusKey(key);
  }

  const onOrder = new Set(lines.map((l) => l.name));

  return (
    <MockScreen>
      <OrderFields header={header} setHeader={setHeader} />
      <div>
        <ItemsHeading />
        <ItemSearch
          label="Add an item"
          placeholder="Add an item…"
          value={search}
          onChange={setSearch}
          onPick={add}
          options={ALL_PRODUCTS.filter((p) => !onOrder.has(p.name))}
          className="mt-2 max-w-md"
        />
        {lines.length > 0 && <EditableLines lines={lines} setLines={setLines} qtyRefs={qtyRefs} />}
        <OrderTotal total={sum(lines)} count={lines.length} />
      </div>
    </MockScreen>
  );
}

// ---------------------------------------------------------------------------------------------
// C. The vendor's catalogue
// ---------------------------------------------------------------------------------------------

function DesignC() {
  const [header, setHeader] = useState(START_HEADER);
  // Quantities by item name, for the vendor's own list.
  const [qty, setQty] = useState<Record<string, string>>(() =>
    Object.fromEntries(SAMPLE_LINES.map((l) => [l.name, l.quantity])),
  );
  // Things bought from this vendor that are not on their list.
  const [extra, setExtra] = useState<Line[]>([]);
  const [filter, setFilter] = useState("");
  const [adding, setAdding] = useState(false);
  const [addText, setAddText] = useState("");
  const addRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (adding) addRef.current?.focus();
  }, [adding]);
  const qtyRefs = useRef(new Map<string, HTMLInputElement>());
  const [focusKey, setFocusKey] = useState<string | null>(null);

  useEffect(() => {
    if (focusKey) qtyRefs.current.get(focusKey)?.focus();
  }, [focusKey]);

  const list = VENDOR_LISTS[header.vendor] ?? [];
  const f = filter.trim().toLowerCase();
  const shown = f === "" ? list : list.filter((p) => p.name.toLowerCase().includes(f));

  const ordered = list
    .filter((p) => num(qty[p.name] ?? "") > 0)
    .map((p) => ({ quantity: qty[p.name], price: String(p.price ?? "") }));
  const extraOrdered = extra.filter((l) => num(l.quantity) > 0);
  const total = [...ordered, ...extraOrdered].reduce((a, l) => a + (lineTotal(l) ?? 0), 0);
  const count = ordered.length + extraOrdered.length;

  const onList = new Set(list.map((p) => p.name));
  const offList = ALL_PRODUCTS.filter((p) => !onList.has(p.name) && !extra.some((x) => x.name === p.name));

  function addExtra(p: Product) {
    const key = newKey();
    setExtra((cur) => [...cur, { key, name: p.name, unit: p.unit, quantity: "", price: "" }]);
    setAddText("");
    setAdding(false);
    setFocusKey(key);
  }

  function addTyped() {
    const text = addText.trim();
    if (text === "") return;
    addExtra({ name: text, unit: "PIECES", price: null, lastPaid: null });
  }

  return (
    <MockScreen>
      <OrderFields header={header} setHeader={setHeader} />
      <div>
        <ItemsHeading />
        {header.vendor === "" ? (
          <p className="mt-2 text-ink-secondary">Choose a vendor to see what they sell.</p>
        ) : (
          <>
            <div className="mt-2 flex flex-wrap items-end gap-3">
              <label className={LABEL}>
                <span className="sr-only">Find in this vendor’s list</span>
                <input
                  type="search"
                  value={filter}
                  placeholder="Find an item…"
                  onChange={(e) => setFilter(e.target.value)}
                  className={`${FIELD} w-80 max-w-full`}
                />
              </label>
              {adding ? (
                <>
                  <ItemSearch
                    label="An item not on their list"
                    placeholder="An item not on their list…"
                    value={addText}
                    onChange={setAddText}
                    onPick={addExtra}
                    options={offList}
                    inputRef={addRef}
                    className="w-80 max-w-full"
                  />
                  <Button variant="ghost" disabled={addText.trim() === ""} onClick={addTyped}>
                    Add
                  </Button>
                  <Button variant="ghost" onClick={() => { setAdding(false); setAddText(""); }}>
                    Cancel
                  </Button>
                </>
              ) : (
                <Button variant="ghost" onClick={() => setAdding(true)}>
                  Add another item
                </Button>
              )}
            </div>
            <table className={`${RULED_TABLE} mt-3`}>
              <thead className={THEAD}>
                <tr>
                  <th className={TH_PRIMARY}>Item</th>
                  <th className={TH_FIXED}>List price</th>
                  <th className={TH_FIXED}>Last paid</th>
                  <th className={TH_FIXED}>Quantity</th>
                  <th className={TH_FIXED}>Line total</th>
                  <th className={TH_ACTIONS_FIXED}><span className="sr-only">Remove</span></th>
                </tr>
              </thead>
              <tbody>
                {shown.map((p) => {
                  const q = qty[p.name] ?? "";
                  const chosen = num(q) > 0;
                  const t = lineTotal({ quantity: q, price: String(p.price ?? "") });
                  return (
                    <tr key={p.name} className={`${TR} !align-middle ${chosen ? "bg-accent-bg" : ""}`}>
                      <td className={`${TD_PRIMARY} ${chosen ? "font-medium text-ink" : "text-ink-secondary"}`}>{p.name}</td>
                      <td className={TD_FIXED_NUM} data-label="List price">
                        {money(p.price, "INR")} / {unitLabel(p.unit)}
                      </td>
                      <td className={`${TD_FIXED_NUM} text-ink-secondary`} data-label="Last paid">
                        {money(p.lastPaid, "INR")}
                      </td>
                      <td className={TD_FIXED_NUM}>
                        <input
                          type="number"
                          min="0"
                          step="any"
                          aria-label={`Quantity of ${p.name}`}
                          value={q}
                          onChange={(e) => setQty((cur) => ({ ...cur, [p.name]: e.target.value }))}
                          className={NUM_BOX}
                        />{" "}
                        <span className="text-ink-secondary">{unitLabel(p.unit)}</span>
                      </td>
                      <td className={TD_FIXED_NUM} data-label={t == null ? undefined : "Line total"}>
                        {t == null ? "" : money(t, "INR")}
                      </td>
                      <td className={TD_ACTIONS_FIXED} />
                    </tr>
                  );
                })}
                {extra.map((l) => {
                  const chosen = num(l.quantity) > 0;
                  const update = (patch: Partial<Line>) =>
                    setExtra((cur) => cur.map((x) => (x.key === l.key ? { ...x, ...patch } : x)));
                  return (
                    <tr key={l.key} className={`${TR} !align-middle ${chosen ? "bg-accent-bg" : ""}`}>
                      <td className={`${TD_PRIMARY} font-medium text-ink`}>
                        {l.name}
                        <span className="block text-sm font-normal text-ink-secondary">Not on their list</span>
                      </td>
                      <td className={TD_FIXED_NUM} data-label="Expected price (₹)">
                        <input
                          type="number"
                          min="0"
                          step="any"
                          aria-label={`Expected price of ${l.name}`}
                          value={l.price}
                          onChange={(e) => update({ price: e.target.value })}
                          className={NUM_BOX}
                        />
                      </td>
                      <td className={`${TD_FIXED_NUM} text-ink-secondary`} data-label="Last paid">—</td>
                      <td className={TD_FIXED_NUM}>
                        <input
                          ref={(el) => {
                            if (el) qtyRefs.current.set(l.key, el);
                            else qtyRefs.current.delete(l.key);
                          }}
                          type="number"
                          min="0"
                          step="any"
                          aria-label={`Quantity of ${l.name}`}
                          value={l.quantity}
                          onChange={(e) => update({ quantity: e.target.value })}
                          className={NUM_BOX}
                        />{" "}
                        {productNamed(l.name) ? (
                          <span className="text-ink-secondary">{unitLabel(l.unit)}</span>
                        ) : (
                          <select
                            aria-label={`Unit for ${l.name}`}
                            value={l.unit}
                            onChange={(e) => update({ unit: e.target.value })}
                            className={FIELD}
                          >
                            {FOOD_UNITS.map((u) => (
                              <option key={u} value={u}>{unitLabel(u)}</option>
                            ))}
                          </select>
                        )}
                      </td>
                      <TotalCell line={l} />
                      <td className={TD_ACTIONS_FIXED}>
                        <Button
                          variant="ghost"
                          size="sm"
                          aria-label={`Remove ${l.name}`}
                          onClick={() => setExtra((cur) => cur.filter((x) => x.key !== l.key))}
                        >
                          Remove
                        </Button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            {shown.length === 0 && (
              <p className="mt-3 pl-5 text-ink-secondary">Nothing on their list matches “{filter.trim()}”.</p>
            )}
            <OrderTotal total={total} count={count} />
          </>
        )}
      </div>
    </MockScreen>
  );
}

// ---------------------------------------------------------------------------------------------
// D. Two panes
// ---------------------------------------------------------------------------------------------

function DesignD() {
  const [header, setHeader] = useState(START_HEADER);
  const [lines, setLines] = useState<Line[]>(() => sampleLines().slice(0, 4));
  const [filter, setFilter] = useState("");
  const qtyRefs = useRef(new Map<string, HTMLInputElement>());
  const [focusKey, setFocusKey] = useState<string | null>(null);

  useEffect(() => {
    if (focusKey) qtyRefs.current.get(focusKey)?.focus();
  }, [focusKey]);

  const list = VENDOR_LISTS[header.vendor] ?? [];
  const f = filter.trim().toLowerCase();
  const shown = f === "" ? list : list.filter((p) => p.name.toLowerCase().includes(f));
  const onOrder = new Set(lines.map((l) => l.name));

  function add(p: Product) {
    const key = newKey();
    setLines((cur) => [...cur, { key, name: p.name, unit: p.unit, quantity: "", price: String(p.price ?? "") }]);
    setFocusKey(key);
  }

  const update = (key: string, quantity: string) =>
    setLines((cur) => cur.map((l) => (l.key === key ? { ...l, quantity } : l)));

  return (
    <MockScreen>
      <OrderFields header={header} setHeader={setHeader} />
      <div className="grid gap-6 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
        <div className="card grid content-start gap-3 px-4 py-4">
          <h3 className="text-base font-semibold text-ink">
            {header.vendor === "" ? "Their items" : `What ${header.vendor} sells`}
          </h3>
          {header.vendor === "" ? (
            <p className="text-ink-secondary">Choose a vendor to see what they sell.</p>
          ) : (
            <>
              <input
                type="search"
                aria-label="Find in this vendor’s list"
                value={filter}
                placeholder="Find an item…"
                onChange={(e) => setFilter(e.target.value)}
                className={`${FIELD} w-full`}
              />
              <ul className="max-h-[26rem] overflow-y-auto">
                {shown.map((p) => (
                  <li key={p.name} className="flex items-center justify-between gap-3 border-t border-hairline py-2 first:border-t-0">
                    <span className="grid">
                      <span className="text-ink">{p.name}</span>
                      <span className="text-sm tabular-nums text-ink-secondary">
                        {money(p.price, "INR")} / {unitLabel(p.unit)}
                      </span>
                    </span>
                    {onOrder.has(p.name) ? (
                      <span className="px-3 text-sm text-ink-secondary">On the order</span>
                    ) : (
                      <Button variant="ghost" size="sm" aria-label={`Add ${p.name}`} onClick={() => add(p)}>
                        + Add
                      </Button>
                    )}
                  </li>
                ))}
                {shown.length === 0 && <li className="py-2 text-ink-secondary">Nothing matches “{filter.trim()}”.</li>}
              </ul>
            </>
          )}
        </div>
        <div>
          <ItemsHeading />
          {lines.length > 0 && (
            <table className={`${RULED_TABLE} mt-2`}>
              <thead className={THEAD}>
                <tr>
                  <th className={TH_PRIMARY}>Item</th>
                  <th className={TH_FIXED}>Quantity</th>
                  <th className={TH_FIXED}>Line total</th>
                  <th className={TH_ACTIONS_FIXED}><span className="sr-only">Remove</span></th>
                </tr>
              </thead>
              <tbody>
                {lines.map((l) => (
                  <tr key={l.key} className={`${TR} !align-middle`}>
                    <td className={TD_PRIMARY}>
                      {l.name}
                      <span className="block text-sm tabular-nums text-ink-secondary">
                        {money(num(l.price) || null, "INR")} / {unitLabel(l.unit)}
                      </span>
                    </td>
                    <td className={TD_FIXED_NUM}>
                      <input
                        ref={(el) => {
                          if (el) qtyRefs.current.set(l.key, el);
                          else qtyRefs.current.delete(l.key);
                        }}
                        type="number"
                        min="0"
                        step="any"
                        aria-label={`Quantity of ${l.name}`}
                        value={l.quantity}
                        onChange={(e) => update(l.key, e.target.value)}
                        className={NUM_BOX}
                      />{" "}
                      <span className="text-ink-secondary">{unitLabel(l.unit)}</span>
                    </td>
                    <TotalCell line={l} />
                    <td className={TD_ACTIONS_FIXED}>
                      <Button
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
              </tbody>
            </table>
          )}
          <OrderTotal total={sum(lines)} count={lines.length} />
        </div>
      </div>
    </MockScreen>
  );
}

// ---------------------------------------------------------------------------------------------
// E. An existing order: what was ordered and what arrived, in one table
// ---------------------------------------------------------------------------------------------

const REASONS = ["Damaged", "Spoiled", "Wrong item", "Other"];

interface Arrival {
  date: string;
  received: number;
  rejected: number;
  reason: string;
  by: string;
}

interface Return {
  date: string;
  qty: number;
  reason: string;
}

interface OrderedItem {
  key: string;
  name: string;
  unit: string;
  ordered: number;
  arrivals: Arrival[];
  returns: Return[];
}

const START_ORDER: OrderedItem[] = [
  {
    key: "e1", name: "Tomato, ripe", unit: "KG", ordered: 50,
    arrivals: [
      { date: "2026-09-12", received: 30, rejected: 0, reason: "", by: "Karuna Murti Das" },
      { date: "2026-09-14", received: 18, rejected: 2, reason: "Spoiled", by: "Govinda Das" },
    ],
    returns: [],
  },
  {
    key: "e2", name: "Coconut, fresh grated", unit: "KG", ordered: 20,
    arrivals: [{ date: "2026-09-12", received: 20, rejected: 0, reason: "", by: "Karuna Murti Das" }],
    returns: [],
  },
  {
    key: "e3", name: "Curry leaves", unit: "KG", ordered: 3,
    arrivals: [{ date: "2026-09-12", received: 3, rejected: 0, reason: "", by: "Karuna Murti Das" }],
    returns: [{ date: "2026-09-13", qty: 0.5, reason: "Spoiled" }],
  },
  { key: "e4", name: "Green chilli, slit", unit: "KG", ordered: 5, arrivals: [], returns: [] },
  { key: "e5", name: "Coriander leaves", unit: "KG", ordered: 4, arrivals: [], returns: [] },
  {
    key: "e6", name: "Ginger", unit: "KG", ordered: 6,
    arrivals: [{ date: "2026-09-12", received: 6, rejected: 0, reason: "", by: "Karuna Murti Das" }],
    returns: [],
  },
];

const TODAY = "2026-09-18";
const ME = "Karuna Murti Das";

interface Entry {
  received: string;
  rejected: string;
  reason: string;
  expiry: string;
}

function qtyText(n: number, unit: string) {
  const v = Number(n.toFixed(3));
  return `${v} ${unitLabelFor(v, unit)}`;
}

function delivered(i: OrderedItem) {
  return i.arrivals.reduce((a, x) => a + x.received, 0);
}
function rejectedOf(i: OrderedItem) {
  return i.arrivals.reduce((a, x) => a + x.rejected, 0);
}
function returnedOf(i: OrderedItem) {
  return i.returns.reduce((a, x) => a + x.qty, 0);
}
function outstanding(i: OrderedItem) {
  return Math.max(0, i.ordered - delivered(i) - rejectedOf(i));
}

/** The inline row where one item's arrival is typed. Spans the table, so it never widens a column. */
function EntryRow({ item, entry, setEntry, onSave, onCancel }: {
  item: OrderedItem;
  entry: Entry;
  setEntry: (e: Entry) => void;
  onSave?: () => void;
  onCancel?: () => void;
}) {
  const rejecting = num(entry.rejected) > 0;
  return (
    <tr className="border-t border-hairline bg-sunken">
      <td colSpan={6} className="px-5 py-3">
        <div className="flex flex-wrap items-end gap-3">
          <label className={LABEL}>
            <span className={LABEL_TEXT}>Quantity received</span>
            <span className="flex items-center gap-2">
              <input
                type="number"
                min="0"
                step="any"
                aria-label={`Quantity of ${item.name} received`}
                autoFocus={!!onSave}
                value={entry.received}
                onChange={(e) => setEntry({ ...entry, received: e.target.value })}
                className={NUM_BOX}
              />
              <span>{unitLabel(item.unit)}</span>
            </span>
          </label>
          <label className={LABEL}>
            <span className={LABEL_TEXT}>Rejected on delivery</span>
            <span className="flex items-center gap-2">
              <input
                type="number"
                min="0"
                step="any"
                aria-label={`Quantity of ${item.name} rejected on delivery`}
                value={entry.rejected}
                onChange={(e) => setEntry({ ...entry, rejected: e.target.value })}
                className={NUM_BOX}
              />
              <span>{unitLabel(item.unit)}</span>
            </span>
          </label>
          {rejecting && (
            <label className={LABEL}>
              <span className={LABEL_TEXT}>Reason</span>
              <select
                value={entry.reason}
                onChange={(e) => setEntry({ ...entry, reason: e.target.value })}
                className={FIELD}
              >
                <option value="">Choose…</option>
                {REASONS.map((r) => <option key={r} value={r}>{r}</option>)}
              </select>
            </label>
          )}
          <label className={LABEL}>
            <span className={LABEL_TEXT}>Expiry</span>
            <input
              type="date"
              value={entry.expiry}
              onChange={(e) => setEntry({ ...entry, expiry: e.target.value })}
              className={FIELD}
            />
          </label>
          {onSave && onCancel && (
            <span className="flex gap-2">
              <Button variant="ghost" onClick={onCancel}>Cancel</Button>
              <Button onClick={onSave}>Save</Button>
            </span>
          )}
        </div>
      </td>
    </tr>
  );
}

function DesignE() {
  const [items, setItems] = useState<OrderedItem[]>(START_ORDER);
  // Which items have an arrival being typed, and what is in their boxes.
  const [entries, setEntries] = useState<Record<string, Entry>>({});
  const [whole, setWhole] = useState(false);

  const blank = (i: OrderedItem): Entry => ({ received: String(outstanding(i) || ""), rejected: "", reason: "", expiry: "" });

  function openOne(i: OrderedItem) {
    setEntries((cur) => ({ ...cur, [i.key]: blank(i) }));
  }

  function openWhole() {
    setWhole(true);
    setEntries(Object.fromEntries(items.filter((i) => outstanding(i) > 0).map((i) => [i.key, blank(i)])));
  }

  function close(keys: string[]) {
    setEntries((cur) => Object.fromEntries(Object.entries(cur).filter(([k]) => !keys.includes(k))));
  }

  function save(keys: string[]) {
    setItems((cur) =>
      cur.map((i) => {
        const e = entries[i.key];
        if (!keys.includes(i.key) || !e) return i;
        const received = num(e.received);
        const rejected = num(e.rejected);
        if (received === 0 && rejected === 0) return i;
        return {
          ...i,
          arrivals: [...i.arrivals, { date: TODAY, received, rejected, reason: rejected > 0 ? e.reason || "Other" : "", by: ME }],
        };
      }),
    );
    close(keys);
  }

  const open = Object.keys(entries);

  return (
    <div className="rounded-card border border-hairline bg-canvas px-4 pb-8 pt-6 sm:px-8">
      <p className="text-sm text-accent-text">← All purchase orders</p>
      <div className="mb-6 mt-3">
        <p className="text-2xl font-semibold tabular-nums text-ink">PO-2026-0142</p>
        <p className="mt-1 flex items-center gap-2 text-ink-secondary">
          {KALASIPALYA} <Badge>Part delivered</Badge>
        </p>
        <p className="mt-1 text-sm tabular-nums text-ink-secondary">Generated 10 Sept 2026</p>
        <p className="text-sm tabular-nums text-ink-secondary">Sent 10 Sept 2026</p>
        <p className="text-sm tabular-nums text-ink-secondary">Needed by 12 Sept 2026</p>
      </div>

      <section className="card px-6 py-5" aria-labelledby="e-items">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h3 id="e-items" className="text-lg">Items</h3>
          {!whole && (
            <Button onClick={openWhole}>Record the whole delivery</Button>
          )}
        </div>
        <table className={`${RULED_TABLE} mt-3`}>
          <thead className={THEAD}>
            <tr>
              <th className={TH_PRIMARY}>Item</th>
              <th className={TH_FIXED}>Ordered</th>
              <th className={TH_FIXED}>Delivered</th>
              <th className={TH_FIXED}>Rejected on delivery</th>
              <th className={TH_FIXED}>Returned</th>
              <th className={TH_ACTIONS_FIXED}><span className="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
          {items.map((i) => {
            const rej = rejectedOf(i);
            const ret = returnedOf(i);
            const entry = entries[i.key];
            return (
              <Fragment key={i.key}>
                <tr className={TR}>
                  <td className={TD_PRIMARY}>{i.name}</td>
                  <td className={TD_FIXED_NUM} data-label="Ordered">{qtyText(i.ordered, i.unit)}</td>
                  <td className={TD_FIXED_NUM} data-label="Delivered">{qtyText(delivered(i), i.unit)}</td>
                  <td className={TD_FIXED_NUM} data-label="Rejected on delivery">{rej > 0 ? qtyText(rej, i.unit) : "—"}</td>
                  <td className={TD_FIXED_NUM} data-label="Returned">{ret > 0 ? qtyText(ret, i.unit) : "—"}</td>
                  <td className={TD_ACTIONS_FIXED}>
                    {!entry && !whole && (
                      <Button variant="ghost" size="sm" aria-label={`Delivered: record ${i.name}`} onClick={() => openOne(i)}>
                        Delivered
                      </Button>
                    )}
                  </td>
                </tr>
                {[
                  ...i.arrivals.map((a) => ({
                    date: a.date,
                    text:
                      `${dayMonth(a.date)} · ${qtyText(a.received, i.unit)} · received by ${a.by}` +
                      (a.rejected > 0 ? ` · ${qtyText(a.rejected, i.unit)} rejected on delivery (${a.reason.toLowerCase()})` : ""),
                  })),
                  ...i.returns.map((r) => ({
                    date: r.date,
                    text: `${dayMonth(r.date)} · ${qtyText(r.qty, i.unit)} returned to the vendor (${r.reason.toLowerCase()})`,
                  })),
                ]
                  .sort((x, y) => x.date.localeCompare(y.date))
                  .map((s, n) => (
                    <tr key={n} className="align-top !border-t-0 hover:bg-sunken max-lg:!pb-2 max-lg:!pt-0">
                      <td colSpan={6} className="!border-t-0 !pt-0 text-sm tabular-nums text-ink-secondary lg:!px-5 lg:!pb-2">
                        <span className="block pl-4">{s.text}</span>
                      </td>
                    </tr>
                  ))}
                {entry && (
                  <EntryRow
                    item={i}
                    entry={entry}
                    setEntry={(e) => setEntries((cur) => ({ ...cur, [i.key]: e }))}
                    onSave={whole ? undefined : () => save([i.key])}
                    onCancel={whole ? undefined : () => close([i.key])}
                  />
                )}
              </Fragment>
            );
          })}
          </tbody>
        </table>
        {whole && (
          <div className="mt-4 flex flex-wrap gap-2">
            <Button variant="ghost" onClick={() => { setWhole(false); setEntries({}); }}>Cancel</Button>
            <Button onClick={() => { save(open); setWhole(false); }}>Save the delivery</Button>
          </div>
        )}
      </section>
    </div>
  );
}

// ---------------------------------------------------------------------------------------------
// The page
// ---------------------------------------------------------------------------------------------

export default function PurchaseOrderMockPage() {
  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/orders" />
      <main className="min-w-0 flex-1">
        <div className="mx-auto grid max-w-content gap-10 px-4 pb-24 pt-8 sm:px-8">
          <header className="grid gap-1">
            <h1 className="text-2xl font-semibold text-ink">Create a purchase order: four designs</h1>
            <p className="max-w-prose text-ink-secondary">
              A mock to choose from. Every figure is sample data and nothing is saved.
            </p>
          </header>

          <Design
            letter="A"
            title="Inline table"
            about="Type straight into the table. The empty row at the bottom is for the next item."
          >
            <DesignA />
          </Design>

          <Design
            letter="B"
            title="Search first"
            about="Search for an item above the table. Choosing it adds a row and puts you in Quantity."
          >
            <DesignB />
          </Design>

          <Design
            letter="C"
            title="The vendor’s list"
            recommended
            about="Everything the vendor sells is listed. Type a quantity for what you want and leave the rest blank."
          >
            <DesignC />
          </Design>

          <Design
            letter="D"
            title="Two panes"
            about="The vendor’s items on the left, your order on the right. Press Add to move one across."
          >
            <DesignD />
          </Design>

          <Design
            letter="E"
            title="An existing order: ordered and delivered in one table"
            about="Press Delivered on an item to record what came. Use Record the whole delivery when one van brings everything."
          >
            <DesignE />
          </Design>
        </div>
      </main>
    </div>
  );
}
