"use client";

/*
 * THROWAWAY MOCK for T-246 — delete before any deploy.
 *
 * A dedicated Deliveries screen for the storekeeper and the Kitchen Manager, so Rajeev can judge it
 * by using it. The person at the gate thinks "the van from Kalasipalya is here", not "open
 * PO-0044", so everything on this screen is grouped by vendor, and one "Record a delivery" per
 * vendor gathers every line that vendor still owes across all of their open orders. No prices
 * anywhere: a price belongs to the invoice, and the person counting sacks at the gate should not
 * have to look past one.
 *
 * Everything is sample data held in component state: no API call, no session, nothing saved. It is
 * built from the app's own parts (Button, Badge, StatTile, SegmentedControl, InlineNotice, the
 * ruled-table and entry-grid constants, the quantity formatter) so the table fitter and the phone
 * card layout in globals.css apply to it exactly as they would on a real screen.
 *
 * The menu is a copy of the real Sidebar's markup, not the component itself, because the real one
 * reads its destinations from nav.ts and there is no Deliveries entry there yet — adding one is a
 * change to a shared file, which this mock is not allowed to make. The copy takes the Kitchen
 * Manager's real menu from `navForRole` and slots Deliveries in after Purchase orders, so what is
 * shown is exactly the menu that person would see with the one new line in it.
 */

import Link from "next/link";
import { useId, useMemo, useState, type ReactNode } from "react";
import { navForRole, type NavGroup } from "@/lib/nav";
import { quantity, unitLabel } from "@/lib/format";
import { Button } from "@/components/ds/Button";
import { Badge } from "@/components/ds/Badge";
import { PageHeader } from "@/components/ds/PageHeader";
import { StatTile } from "@/components/ds/StatTile";
import { SegmentedControl } from "@/components/ds/SegmentedControl";
import { InlineNotice } from "@/components/ds/InlineNotice";
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

// ---------------------------------------------------------------------------------------------
// Sample data. The vendors are the ones the app's own sample temple uses; the quantities are what
// a kitchen of this size orders. "Today" is fixed so the overdue marks always mean the same thing.
// ---------------------------------------------------------------------------------------------

const TODAY = "2026-09-19";
const ME = "Govinda Das";

type Unit = "KG" | "GM" | "L" | "PIECES";

/** One line of one purchase order. What has come in against it is read from the deliveries. */
interface Line {
  id: string;
  vendor: string;
  po: string;
  item: string;
  unit: Unit;
  ordered: number;
  neededBy: string;
  /** Dairy and packaged goods carry a use-by date; vegetables and dry goods do not ask for one. */
  perishable?: boolean;
}

/** What one delivery did to one order line: kept, and refused at the gate. */
interface Part {
  lineId: string;
  qty: number;
  rejected?: number;
  /** As a person reads it: "spoiled", "damaged". */
  reason?: string;
}

interface Delivery {
  id: string;
  date: string;
  vendor: string;
  parts: Part[];
  by: string;
  returned: string[];
}

const KALASIPALYA = "Kalasipalya Vegetable Mandi";
const HERITAGE = "Heritage Fresh Dairy";
const BALAJI = "Sri Balaji Traders";
const ANAND = "Anand Masala Depot";
const GANESH = "Ganesh Oil & Provisions";

/*
 * Every order line the sample knows about, finished ones included: the per-item history needs the
 * line a delivery was against to say "50 of 50 Kg", so a delivery can only name a line that exists
 * here. The open ones are the first block; the rest were completed by the deliveries in HISTORY.
 */
const LINES: Line[] = [
  { id: "k1", vendor: KALASIPALYA, po: "PO-0044", item: "Tomato, ripe", unit: "KG", ordered: 25, neededBy: "2026-09-19" },
  { id: "k2", vendor: KALASIPALYA, po: "PO-0044", item: "Potato", unit: "KG", ordered: 40, neededBy: "2026-09-19" },
  { id: "k3", vendor: KALASIPALYA, po: "PO-0044", item: "Beans", unit: "KG", ordered: 8, neededBy: "2026-09-19" },
  { id: "k4", vendor: KALASIPALYA, po: "PO-0044", item: "Coriander leaves", unit: "KG", ordered: 3, neededBy: "2026-09-19" },
  { id: "k5", vendor: KALASIPALYA, po: "PO-0044", item: "Green chilli", unit: "KG", ordered: 2, neededBy: "2026-09-19" },
  { id: "k6", vendor: KALASIPALYA, po: "PO-0044", item: "Banana leaf", unit: "PIECES", ordered: 300, neededBy: "2026-09-19" },
  { id: "k7", vendor: KALASIPALYA, po: "PO-0039", item: "Onion, big", unit: "KG", ordered: 50, neededBy: "2026-09-17" },
  { id: "h1", vendor: HERITAGE, po: "PO-0045", item: "Milk, toned", unit: "L", ordered: 120, neededBy: "2026-09-19", perishable: true },
  { id: "h2", vendor: HERITAGE, po: "PO-0045", item: "Curd", unit: "KG", ordered: 20, neededBy: "2026-09-19", perishable: true },
  { id: "h3", vendor: HERITAGE, po: "PO-0045", item: "Paneer", unit: "KG", ordered: 6, neededBy: "2026-09-19", perishable: true },
  { id: "b1", vendor: BALAJI, po: "PO-0041", item: "Toor dal", unit: "KG", ordered: 50, neededBy: "2026-09-17" },
  { id: "b2", vendor: BALAJI, po: "PO-0041", item: "Rice, Sona Masoori", unit: "KG", ordered: 100, neededBy: "2026-09-17" },
  { id: "b3", vendor: BALAJI, po: "PO-0041", item: "Jaggery", unit: "KG", ordered: 15, neededBy: "2026-09-17" },
  { id: "b4", vendor: BALAJI, po: "PO-0038", item: "Basmati rice", unit: "KG", ordered: 50, neededBy: "2026-09-14" },
  { id: "a1", vendor: ANAND, po: "PO-0046", item: "Turmeric powder", unit: "KG", ordered: 2, neededBy: "2026-09-19" },
  { id: "a2", vendor: ANAND, po: "PO-0046", item: "Cumin seeds", unit: "KG", ordered: 3, neededBy: "2026-09-19" },
  { id: "a3", vendor: ANAND, po: "PO-0046", item: "Mustard seeds", unit: "KG", ordered: 2, neededBy: "2026-09-19" },
  { id: "a4", vendor: ANAND, po: "PO-0046", item: "Asafoetida", unit: "GM", ordered: 250, neededBy: "2026-09-19", perishable: true },
  { id: "a5", vendor: ANAND, po: "PO-0040", item: "Cardamom, green", unit: "GM", ordered: 1000, neededBy: "2026-09-21" },
  { id: "g1", vendor: GANESH, po: "PO-0043", item: "Groundnut oil", unit: "L", ordered: 60, neededBy: "2026-09-22", perishable: true },
  { id: "g2", vendor: GANESH, po: "PO-0043", item: "Ghee", unit: "KG", ordered: 15, neededBy: "2026-09-22", perishable: true },
  // Completed by the deliveries below.
  { id: "x1", vendor: ANAND, po: "PO-0040", item: "Black pepper", unit: "KG", ordered: 1, neededBy: "2026-09-16" },
  { id: "m1", vendor: HERITAGE, po: "PO-0036", item: "Milk, toned", unit: "L", ordered: 120, neededBy: "2026-09-16", perishable: true },
  { id: "m2", vendor: HERITAGE, po: "PO-0036", item: "Curd", unit: "KG", ordered: 20, neededBy: "2026-09-16", perishable: true },
  { id: "m3", vendor: HERITAGE, po: "PO-0036", item: "Paneer", unit: "KG", ordered: 6, neededBy: "2026-09-16", perishable: true },
  { id: "v1", vendor: KALASIPALYA, po: "PO-0037", item: "Tomato, ripe", unit: "KG", ordered: 20, neededBy: "2026-09-15" },
  { id: "v2", vendor: KALASIPALYA, po: "PO-0037", item: "Potato", unit: "KG", ordered: 40, neededBy: "2026-09-15" },
  { id: "v3", vendor: KALASIPALYA, po: "PO-0037", item: "Carrot", unit: "KG", ordered: 10, neededBy: "2026-09-15" },
  { id: "v4", vendor: KALASIPALYA, po: "PO-0037", item: "Beans", unit: "KG", ordered: 6, neededBy: "2026-09-15" },
  { id: "v5", vendor: KALASIPALYA, po: "PO-0037", item: "Curry leaves", unit: "KG", ordered: 1.5, neededBy: "2026-09-15" },
  { id: "v6", vendor: KALASIPALYA, po: "PO-0037", item: "Coconut, fresh grated", unit: "KG", ordered: 12, neededBy: "2026-09-15" },
  { id: "r1", vendor: BALAJI, po: "PO-0035", item: "Rice, Sona Masoori", unit: "KG", ordered: 100, neededBy: "2026-09-14" },
  { id: "r2", vendor: BALAJI, po: "PO-0035", item: "Toor dal", unit: "KG", ordered: 50, neededBy: "2026-09-14" },
];

const LINE_BY_ID = new Map(LINES.map((l) => [l.id, l]));

/** Newest first, as the Received view lists them. */
const HISTORY: Delivery[] = [
  { id: "d8", date: "2026-09-17", vendor: KALASIPALYA, parts: [{ lineId: "k7", qty: 30 }], by: ME, returned: [] },
  { id: "d7", date: "2026-09-17", vendor: GANESH, parts: [{ lineId: "g2", qty: 10 }], by: ME, returned: [] },
  { id: "d6", date: "2026-09-16", vendor: KALASIPALYA, parts: [{ lineId: "v1", qty: 3 }], by: "Karuna Murti Das", returned: [] },
  { id: "d5", date: "2026-09-16", vendor: ANAND, parts: [{ lineId: "a5", qty: 500 }, { lineId: "x1", qty: 1 }], by: "Madhava Das", returned: [] },
  {
    id: "d4", date: "2026-09-16", vendor: HERITAGE,
    parts: [{ lineId: "m1", qty: 120 }, { lineId: "m2", qty: 20 }, { lineId: "m3", qty: 6 }],
    by: ME, returned: ["Paneer 1 Kg, spoiled, 17 Sept"],
  },
  {
    id: "d3", date: "2026-09-15", vendor: KALASIPALYA,
    parts: [
      { lineId: "v1", qty: 17, rejected: 3, reason: "damaged" }, { lineId: "v2", qty: 40 }, { lineId: "v3", qty: 10 },
      { lineId: "v4", qty: 6 }, { lineId: "v5", qty: 1.5 }, { lineId: "v6", qty: 12 },
    ],
    by: "Madhava Das", returned: [],
  },
  { id: "d2", date: "2026-09-14", vendor: BALAJI, parts: [{ lineId: "r1", qty: 100 }, { lineId: "r2", qty: 50 }], by: "Madhava Das", returned: [] },
  // 32 Kg came off the van: 30 kept, 2 refused. The refused 2 stay owed, so 20 is still to come.
  { id: "d1", date: "2026-09-12", vendor: BALAJI, parts: [{ lineId: "b4", qty: 30, rejected: 2, reason: "spoiled" }], by: "Karuna Murti Das", returned: [] },
];

const REASONS = [
  { value: "DAMAGED", label: "Damaged" },
  { value: "SPOILED", label: "Spoiled" },
  { value: "WRONG_ITEM", label: "Wrong item" },
  { value: "OTHER", label: "Other" },
];

// ---------------------------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------------------------

interface PartOnDate {
  date: string;
  by: string;
  part: Part;
}

/** Every delivery against one line, oldest first. HISTORY is newest first, so it is walked backwards. */
function partsOf(lineId: string, history: Delivery[]): PartOnDate[] {
  const out: PartOnDate[] = [];
  for (let i = history.length - 1; i >= 0; i--) {
    for (const part of history[i].parts) {
      if (part.lineId === lineId) out.push({ date: history[i].date, by: history[i].by, part });
    }
  }
  return out.sort((a, b) => a.date.localeCompare(b.date));
}

const receivedOf = (l: Line, history: Delivery[]) =>
  Number(partsOf(l.id, history).reduce((sum, p) => sum + p.part.qty, 0).toFixed(3));

/**
 * What is still to come: ordered less what was kept. Rejected goods are not taken off, because a
 * refused sack is still owed — the backend's rule too (`PurchaseOrderService.isFullyAccountedFor`).
 */
const stillToCome = (l: Line, history: Delivery[]) => Number((l.ordered - receivedOf(l, history)).toFixed(3));

/** The date the line's kept total first reached what was ordered, or null while it is still owed. */
function completedOn(l: Line, history: Delivery[]): string | null {
  let total = 0;
  for (const p of partsOf(l.id, history)) {
    total += p.part.qty;
    if (total >= l.ordered - 1e-9) return p.date;
  }
  return null;
}

/** Day-first with a month name, the format already used everywhere in the app. */
function day(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString("en-GB", { day: "numeric", month: "short" });
}

function daysBetween(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000);
}

function days(n: number): string {
  return n === 1 ? "1 day" : `${n} days`;
}

/** The input box every entry on this screen uses: the 44px touch height, the control corner. */
const FIELD = "min-h-touch rounded-control border border-hairline bg-canvas px-3 tabular-nums";

// ---------------------------------------------------------------------------------------------
// The menu: the real Sidebar's markup, fed the Kitchen Manager's real menu plus Deliveries.
// ---------------------------------------------------------------------------------------------

const DELIVERIES_HREF = "/dev-deliveries";

function menuWithDeliveries(): NavGroup[] {
  return navForRole("KITCHEN_MANAGER").map((group) => {
    const at = group.items.findIndex((i) => i.href === "/orders");
    if (at < 0) return group;
    const items = [...group.items];
    items.splice(at + 1, 0, { ...group.items[at], href: DELIVERIES_HREF, label: "Deliveries", icon: "package-import" });
    return { ...group, items };
  });
}

function MockSidebar() {
  const [open, setOpen] = useState(false);
  const groups = useMemo(menuWithDeliveries, []);
  return (
    <>
      <div
        className={
          open
            ? "fixed inset-y-0 left-0 z-50 flex shadow-overlay motion-safe:animate-drawer-in lg:contents"
            : "hidden lg:contents"
        }
      >
        <nav
          aria-label="Main"
          className="sidebar-surface sticky top-0 flex h-screen w-sidebar shrink-0 flex-col gap-4 overflow-hidden px-4 py-6 max-lg:h-full"
        >
          {open && (
            <button
              type="button"
              onClick={() => setOpen(false)}
              className="absolute right-3 top-3 z-10 flex min-h-touch items-center gap-2 rounded-control px-3 text-sm text-ink-secondary hover:bg-sunken hover:text-ink lg:hidden"
            >
              <i className="ti ti-x text-lg" aria-hidden="true" />
              Close
            </button>
          )}
          <div className="grid justify-items-center gap-1 px-2">
            <img src="/brand/iskcon-icon.svg" alt="" aria-hidden="true" className="h-16 w-16 flex-none object-contain" />
            <span className="block text-center text-lg font-medium leading-tight text-ink">Sample Temple</span>
          </div>
          <div className="-mx-3 grid min-h-0 flex-1 content-start gap-6 overflow-y-auto px-3">
            {groups.map((group) => (
              <div key={group.title ?? "main"} className="grid gap-1">
                {group.title && (
                  <span className="mb-1 px-3 text-xs uppercase tracking-eyebrow text-ink-muted">{group.title}</span>
                )}
                {group.items.map((item) => {
                  const active = item.href === DELIVERIES_HREF;
                  return (
                    <Link
                      key={item.href}
                      href={item.href}
                      aria-current={active ? "page" : undefined}
                      className={[
                        "flex min-h-touch items-center gap-3 rounded px-3 text-base",
                        "transition-[transform,box-shadow,background-color,color] duration-state ease-out",
                        active
                          ? "bg-accent-bg font-semibold text-accent-text"
                          : "text-ink-secondary hover:-translate-y-0.5 hover:bg-sunken hover:text-ink hover:shadow-lift",
                      ].join(" ")}
                    >
                      <i className={`ti ti-${item.icon} text-lg`} aria-hidden="true" />
                      {item.label}
                    </Link>
                  );
                })}
              </div>
            ))}
          </div>
          <div className="border-t border-hairline px-3 pt-3 text-sm">
            <span className="block font-medium text-ink">{ME}</span>
            <span className="block text-ink-secondary">Kitchen Manager</span>
          </div>
        </nav>
      </div>
      {open && (
        <div aria-hidden="true" onClick={() => setOpen(false)} className="fixed inset-0 z-40 bg-ink/40 lg:hidden" />
      )}
      <header className="app-topbar topbar-surface sticky top-0 z-30 order-first flex min-h-14 items-center gap-3 px-4 py-1 lg:hidden">
        <button
          type="button"
          onClick={() => setOpen(true)}
          aria-expanded={open}
          className="-ms-3 flex min-h-touch flex-none items-center gap-2 rounded-control px-3 text-base font-medium text-ink hover:bg-sunken"
        >
          <i className="ti ti-menu-2 text-lg" aria-hidden="true" />
          Menu
        </button>
        <span className="min-w-0 flex-1 font-medium leading-tight text-ink">Sample Temple</span>
      </header>
    </>
  );
}

// ---------------------------------------------------------------------------------------------
// Record a delivery: the inline panel, one per vendor.
// ---------------------------------------------------------------------------------------------

interface Entry {
  received: string;
  rejected: string;
  reason: string;
  expiry: string;
}

const BLANK: Entry = { received: "", rejected: "", reason: "", expiry: "" };

/** Earlier deliveries of this line that had something refused at the gate. */
function refusedBefore(l: Line, history: Delivery[]): PartOnDate[] {
  return partsOf(l.id, history).filter((p) => (p.part.rejected ?? 0) > 0);
}

function RecordPanel({
  vendor,
  lines,
  history,
  prefill = false,
  onCancel,
  onSave,
}: {
  vendor: string;
  lines: Line[];
  history: Delivery[];
  /**
   * Start with Received now filled in as everything still to come. Used from Partly delivered,
   * where the van is bringing the rest: the usual answer is "all of it", so the storekeeper only
   * types where that is not true.
   */
  prefill?: boolean;
  onCancel: () => void;
  onSave: (entries: Record<string, Entry>) => void;
}) {
  const owed = (l: Line) => stillToCome(l, history);
  const [entries, setEntries] = useState<Record<string, Entry>>(() =>
    prefill ? Object.fromEntries(lines.map((l) => [l.id, { ...BLANK, received: String(owed(l)) }])) : {},
  );
  const [tried, setTried] = useState(false);
  const get = (id: string) => entries[id] ?? BLANK;
  const set = (id: string, patch: Partial<Entry>) =>
    setEntries((e) => ({ ...e, [id]: { ...(e[id] ?? BLANK), ...patch } }));

  function everythingArrived() {
    setEntries((e) => {
      const next = { ...e };
      for (const l of lines) next[l.id] = { ...(e[l.id] ?? BLANK), received: String(owed(l)) };
      return next;
    });
  }

  /** What is wrong with a line, if anything, in the words that go under its box. */
  function problems(l: Line): { received?: string; reason?: string } {
    const e = get(l.id);
    const out: { received?: string; reason?: string } = {};
    const received = Number(e.received || 0);
    const rejected = Number(e.rejected || 0);
    // What came off the van is what was kept plus what was refused, and that cannot be more than
    // is owed. The usual way to hit this: press Everything arrived, then refuse 2 Kg of curd
    // without taking the 2 Kg off Received.
    if (received > owed(l)) out.received = `Only ${quantity(owed(l), l.unit)} is still to come`;
    else if (received + rejected > owed(l))
      out.received = `Received and rejected add up to more than the ${quantity(owed(l), l.unit)} still to come`;
    if (Number(e.rejected || 0) > 0 && !e.reason) out.reason = "Choose why it was rejected";
    return out;
  }

  // A vendor who sells nothing with a use-by date (the vegetable mandi, the dry-goods trader) gets
  // no Expiry column at all, rather than an empty one with a heading over it.
  const anyPerishable = lines.some((l) => l.perishable);
  const anything = lines.some((l) => Number(get(l.id).received || 0) > 0 || Number(get(l.id).rejected || 0) > 0);
  const hasProblems = lines.some((l) => Object.keys(problems(l)).length > 0);

  function save() {
    setTried(true);
    if (!anything || hasProblems) return;
    onSave(entries);
  }

  return (
    // 16px sides on a phone rather than 20px: at 390 the 20px version left the entry grid 301px,
    // 3px short of two 144px boxes side by side, and every box fell onto a row of its own.
    <div className="grid gap-4 border-t border-hairline bg-raised px-4 py-5 lg:px-5">
      <div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3">
        <div className="grid min-w-0 grow basis-60 gap-1">
          <h3 className="font-semibold text-ink">Record a delivery from {vendor}</h3>
          <p className="text-sm text-ink-secondary">
            Type what came off the van. Leave a line blank if it did not come. Rejected goods stay owed and never enter stock.
          </p>
        </div>
        <Button variant="ghost" icon="checks" onClick={everythingArrived}>
          Everything arrived
        </Button>
      </div>

      {/* The entry grid: spaced like every other table from 1024px up, and below that each line
          becomes a card with a small label over every box (the `data-label` on each cell). */}
      {/* `max-lg:[&_tbody_td]:border-0`: on a phone card the base table style would still draw a rule
          above every labelled box, which read as six separate rows rather than one item. */}
      <table className={`${TABLE} ${ENTRY_GRID} text-sm max-lg:[&_tbody_td]:border-0`}>
        <thead className={THEAD}>
          <tr>
            <th className={TH_TEXT}>Item</th>
            <th className={TH_NUM}>Still to come</th>
            <th className={TH_NUM}>Received now</th>
            <th className={TH_NUM}>Rejected on delivery</th>
            <th className={TH_TEXT}>Reason</th>
            {anyPerishable && <th className={TH_TEXT}>Expiry</th>}
          </tr>
        </thead>
        <tbody>
          {lines.map((l) => {
            const e = get(l.id);
            const p = tried ? problems(l) : {};
            const u = unitLabel(l.unit);
            return (
              <tr key={l.id} className={TR}>
                <td className={TD_TEXT}>
                  <span className="block font-medium text-ink">{l.item}</span>
                  <span className="block text-xs font-normal text-ink-muted">{l.po}</span>
                </td>
                <td className={TD_NUM} data-label="Still to come">
                  <span className="inline-flex min-h-touch items-center">{quantity(owed(l), l.unit)}</span>
                  {/* Why a vendor who "sent 32 of 50" still owes 20: say so where the 20 is. */}
                  {refusedBefore(l, history).map((r) => (
                    <span key={r.date} className="block text-xs text-ink-muted">
                      includes {quantity(r.part.rejected ?? 0, l.unit)} rejected <Day iso={r.date} />
                    </span>
                  ))}
                </td>
                <td className={TD_NUM} data-label="Received now">
                  <span className="flex items-center gap-2">
                    <input
                      type="number"
                      inputMode="decimal"
                      min="0"
                      step="any"
                      value={e.received}
                      onChange={(ev) => set(l.id, { received: ev.target.value })}
                      aria-label={`${l.item} received now, in ${u}`}
                      aria-invalid={p.received ? true : undefined}
                      className={`${FIELD} min-w-24 ${p.received ? "border-danger" : ""}`}
                    />
                    <span className="text-ink-secondary">{u}</span>
                  </span>
                  {p.received && <span className="mt-1 block text-xs text-danger">{p.received}</span>}
                </td>
                <td className={TD_NUM} data-label="Rejected on delivery">
                  <span className="flex items-center gap-2">
                    <input
                      type="number"
                      inputMode="decimal"
                      min="0"
                      step="any"
                      value={e.rejected}
                      onChange={(ev) => set(l.id, { rejected: ev.target.value })}
                      aria-label={`${l.item} rejected on delivery, in ${u}`}
                      className={`${FIELD} min-w-24`}
                    />
                    <span className="text-ink-secondary">{u}</span>
                  </span>
                </td>
                <td className={TD_TEXT} data-label="Reason">
                  <select
                    value={e.reason}
                    onChange={(ev) => set(l.id, { reason: ev.target.value })}
                    disabled={!(Number(e.rejected || 0) > 0)}
                    aria-label={`Why ${l.item} was rejected`}
                    aria-invalid={p.reason ? true : undefined}
                    className={`${FIELD} disabled:opacity-45 ${p.reason ? "border-danger" : ""}`}
                  >
                    <option value="">Choose</option>
                    {REASONS.map((r) => (
                      <option key={r.value} value={r.value}>
                        {r.label}
                      </option>
                    ))}
                  </select>
                  {p.reason && <span className="mt-1 block text-xs text-danger">{p.reason}</span>}
                </td>
                {!anyPerishable ? null : l.perishable ? (
                  <td className={TD_TEXT} data-label="Expiry">
                    <input
                      type="date"
                      value={e.expiry}
                      onChange={(ev) => set(l.id, { expiry: ev.target.value })}
                      aria-label={`${l.item} expiry date`}
                      className={FIELD}
                    />
                  </td>
                ) : (
                  // No expiry is asked for vegetables or dry goods. The empty cell keeps the
                  // column on a wide screen and takes no room on a phone card.
                  <td className={`${TD_TEXT} max-lg:!hidden`} />
                )}
              </tr>
            );
          })}
        </tbody>
      </table>

      {tried && !anything && (
        <p className="text-sm text-danger">Type what arrived on at least one line, or press Everything arrived.</p>
      )}

      <div className="flex flex-wrap justify-end gap-3">
        <Button variant="ghost" onClick={onCancel} className="max-sm:flex-1">
          Cancel
        </Button>
        <Button onClick={save} className="max-sm:flex-1">
          Save delivery
        </Button>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------------------------
// The three views
// ---------------------------------------------------------------------------------------------

interface VendorGroup {
  vendor: string;
  lines: Line[];
  earliest: string;
  overdueBy: number;
}

function groupByVendor(lines: Line[]): VendorGroup[] {
  const map = new Map<string, Line[]>();
  for (const l of lines) map.set(l.vendor, [...(map.get(l.vendor) ?? []), l]);
  return [...map.entries()]
    .map(([vendor, ls]) => {
      const earliest = ls.map((l) => l.neededBy).sort()[0];
      return { vendor, lines: ls, earliest, overdueBy: Math.max(0, daysBetween(earliest, TODAY)) };
    })
    // Overdue first, most overdue at the top; then by the earliest date anything is needed.
    .sort((a, b) => b.overdueBy - a.overdueBy || a.earliest.localeCompare(b.earliest) || a.vendor.localeCompare(b.vendor));
}

/**
 * A date, always written as a date, with a pill beside it when it is today or late.
 *
 * <p>Rajeev's choice (2026-09-19): never the word "Today" in place of the date. A date that is today
 * says so in an info (blue) pill, the same Badge at the same size as the amber late pill; it is
 * standing information, not a warning. Late stays amber, because something late needs chasing.
 * Only a needed-by date can be late, so only the needed-by column asks for that pill.
 */
function Day({ iso, late = false }: { iso: string; late?: boolean }) {
  const lateBy = daysBetween(iso, TODAY);
  const today = iso === TODAY;
  const lateNow = late && lateBy > 0;
  if (!today && !lateNow) return <>{day(iso)}</>;
  return (
    <span className="inline-flex flex-wrap items-center gap-x-2 gap-y-1 align-baseline">
      {day(iso)}
      {today && <Badge tone="info">Today</Badge>}
      {lateNow && <Badge tone="warning">{days(lateBy)} late</Badge>}
    </span>
  );
}

function NeededBy({ iso }: { iso: string }) {
  return <Day iso={iso} late />;
}

/** "30 of 50 Kg" when both halves are in the same unit, "500 gm of 1 Kg" when they are not. */
function ofTotal(got: number, ordered: number, unit: Unit): string {
  const a = quantity(got, unit);
  const b = quantity(ordered, unit);
  const unitA = a.slice(a.lastIndexOf(" ") + 1);
  const unitB = b.slice(b.lastIndexOf(" ") + 1);
  return unitA === unitB ? `${a.slice(0, a.lastIndexOf(" "))} of ${b}` : `${a} of ${b}`;
}

/**
 * Every delivery of one order line, folded away under a quiet "▸ 2 deliveries".
 *
 * <p>The same component in Partly delivered and in Received, so the breakdown reads identically
 * wherever it is opened. Closed by default: most of the time the row is enough, and six of these
 * open on a vegetable delivery would bury the table. It is a real button with `aria-expanded`, so
 * Tab reaches it and Enter or Space opens it. Its hit area is the 44px the design system asks of
 * every target, pulled back into the line with negative margins so it takes a text line's room.
 *
 * <p>Secondary text throughout, and no colour: nothing in a history needs acting on, and the
 * completion date is a settled fact, which the colour rule keeps neutral.
 */
function ItemHistory({ line, history }: { line: Line; history: Delivery[] }) {
  const [open, setOpen] = useState(false);
  const id = useId();
  const parts = partsOf(line.id, history);
  if (parts.length === 0) return null;
  const got = receivedOf(line, history);
  const done = completedOn(line, history);
  return (
    <div className="text-sm font-normal text-ink-secondary">
      <button
        type="button"
        aria-expanded={open}
        aria-controls={id}
        onClick={() => setOpen((o) => !o)}
        className="-my-2.5 flex min-h-touch w-fit items-center gap-1 rounded-control text-start hover:text-ink"
      >
        <span aria-hidden="true" className="inline-block w-3">
          {open ? "▾" : "▸"}
        </span>
        {parts.length === 1 ? "1 delivery" : `${parts.length} deliveries`}
        <span className="sr-only"> of {line.item}</span>
      </button>
      {open && (
        <ol id={id} className="mt-1 grid gap-1 ps-4">
          {parts.map((p, i) => (
            <li key={`${p.date}-${i}`}>
              <Day iso={p.date} />
              {[
                `${quantity(p.part.qty, line.unit)} received`,
                (p.part.rejected ?? 0) > 0 ? `${quantity(p.part.rejected ?? 0, line.unit)} rejected, ${p.part.reason}` : null,
                // Rajeev, 2026-09-19: "Received by:" with its label, and no order number here,
                // because the order already shows twice in the same tab.
                `Received by: ${p.by}`,
              ]
                .filter(Boolean)
                // Each piece kept whole, so a wrap falls between pieces, never inside a name:
                // at 390 "Received by: Govinda Das" was splitting before "Das".
                .map((bit) => (
                  <span key={bit}>
                    {" · "}
                    <span className="whitespace-nowrap">{bit}</span>
                  </span>
                ))}
            </li>
          ))}
          <li className="font-medium">
            Received {ofTotal(got, line.ordered, line.unit)} ordered
            {done ? (
              <>
                {" · complete "}
                <Day iso={done} />
              </>
            ) : null}
          </li>
        </ol>
      )}
    </div>
  );
}

/** One vendor's card: the heading, its one "Record a delivery", and either the table or the panel. */
function VendorCard({
  group,
  history,
  open,
  onOpen,
  onCancel,
  onSave,
  prefill,
  children,
}: {
  group: VendorGroup;
  history: Delivery[];
  open: boolean;
  onOpen: () => void;
  onCancel: () => void;
  onSave: (entries: Record<string, Entry>) => void;
  prefill?: boolean;
  children: ReactNode;
}) {
  const orders = [...new Set(group.lines.map((l) => l.po))];
  return (
    <section className="card overflow-hidden" aria-label={group.vendor}>
      <div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3 px-5 py-4">
        <div className="grid min-w-0 grow basis-60 gap-1">
          <h2 className="flex flex-wrap items-center gap-x-3 gap-y-1 text-lg font-semibold text-ink">
            {group.vendor}
            {group.overdueBy > 0 && <Badge tone="warning">Overdue</Badge>}
          </h2>
          <p className="text-sm text-ink-secondary">
            {group.lines.length} {group.lines.length === 1 ? "item" : "items"} on {orders.length === 1 ? "order" : "orders"}{" "}
            {orders.join(", ")}
          </p>
        </div>
        {!open && (
          <Button variant="ghost" icon="package-import" onClick={onOpen}>
            Record a delivery
          </Button>
        )}
      </div>
      {open ? (
        <RecordPanel
          vendor={group.vendor}
          lines={group.lines}
          history={history}
          prefill={prefill}
          onCancel={onCancel}
          onSave={onSave}
        />
      ) : (
        children
      )}
    </section>
  );
}

interface ViewProps {
  groups: VendorGroup[];
  history: Delivery[];
  recording: string | null;
  setRecording: (v: string | null) => void;
  onSave: (vendor: string, entries: Record<string, Entry>) => void;
}

function ExpectedView({ groups, history, recording, setRecording, onSave }: ViewProps) {
  if (groups.length === 0) {
    return <p className="card px-5 py-6 text-ink-secondary">Nothing is expected. Every sent order has arrived.</p>;
  }
  return (
    <div className="grid gap-6">
      {groups.map((g) => (
        <VendorCard
          key={g.vendor}
          group={g}
          history={history}
          open={recording === g.vendor}
          onOpen={() => setRecording(g.vendor)}
          onCancel={() => setRecording(null)}
          onSave={(entries) => onSave(g.vendor, entries)}
        >
          <table className={RULED_TABLE}>
            <thead className={THEAD}>
              <tr>
                <th className={TH_PRIMARY}>Item</th>
                <th className={TH_FIXED}>Order</th>
                <th className={TH_FIXED}>Still to come</th>
                <th className={TH_FIXED}>Needed by</th>
              </tr>
            </thead>
            <tbody>
              {g.lines.map((l) => {
                const got = receivedOf(l, history);
                const first = partsOf(l.id, history)[0];
                return (
                  <tr key={l.id} className={TR}>
                    <td className={TD_PRIMARY}>
                      {l.item}
                      {got > 0 && first && (
                        <span className="ms-2 text-sm font-normal text-ink-muted">
                          {quantity(got, l.unit)} came <Day iso={first.date} />
                        </span>
                      )}
                    </td>
                    <td className={`${TD_FIXED} text-ink-secondary`}>{l.po}</td>
                    <td className={TD_FIXED_NUM} data-label="Still to come">
                      {quantity(stillToCome(l, history), l.unit)}
                    </td>
                    <td className={TD_FIXED} data-label="Needed by">
                      <NeededBy iso={l.neededBy} />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </VendorCard>
      ))}
    </div>
  );
}

/**
 * Part-delivered lines, by vendor, each vendor with its own "Record a delivery" for the rest. That
 * panel lists only the part-delivered lines and starts filled in, because the van this is for is
 * bringing the balance; a vendor's untouched lines are recorded from Expected as before.
 */
function PartlyView({ groups, history, recording, setRecording, onSave }: ViewProps) {
  if (groups.length === 0) {
    return <p className="card px-5 py-6 text-ink-secondary">No order is part delivered.</p>;
  }
  return (
    <div className="grid gap-6">
      {groups.map((g) => (
        <VendorCard
          key={g.vendor}
          group={g}
          history={history}
          prefill
          open={recording === g.vendor}
          onOpen={() => setRecording(g.vendor)}
          onCancel={() => setRecording(null)}
          onSave={(entries) => onSave(g.vendor, entries)}
        >
          <table className={RULED_TABLE}>
            <thead className={THEAD}>
              <tr>
                <th className={TH_PRIMARY}>Item</th>
                <th className={TH_FIXED}>Order</th>
                <th className={TH_FIXED}>Ordered</th>
                <th className={TH_FIXED}>Received</th>
                <th className={TH_FIXED}>Still owed</th>
                <th className={TH_FIXED}>Owed for</th>
              </tr>
            </thead>
            <tbody>
              {g.lines.map((l) => {
                const first = partsOf(l.id, history)[0]?.date ?? TODAY;
                const owedFor = daysBetween(first, TODAY);
                return (
                  <tr key={l.id} className={TR}>
                    <td className={TD_PRIMARY}>
                      {l.item}
                      <ItemHistory line={l} history={history} />
                    </td>
                    <td className={`${TD_FIXED} text-ink-secondary`}>{l.po}</td>
                    <td className={TD_FIXED_NUM} data-label="Ordered">{quantity(l.ordered, l.unit)}</td>
                    <td className={TD_FIXED_NUM} data-label="Received">{quantity(receivedOf(l, history), l.unit)}</td>
                    <td className={TD_FIXED_NUM} data-label="Still owed">{quantity(stillToCome(l, history), l.unit)}</td>
                    <td className={TD_FIXED} data-label="Owed for">
                      {owedFor === 0 ? (
                        <Day iso={first} />
                      ) : (
                        <>
                          {days(owedFor)}, since <Day iso={first} />
                        </>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </VendorCard>
      ))}
    </div>
  );
}

function ReceivedView({ history }: { history: Delivery[] }) {
  return (
    <div className="card overflow-hidden">
      <table className={RULED_TABLE}>
        <thead className={THEAD}>
          <tr>
            <th className={TH_FIXED}>Date</th>
            <th className={TH_SECOND}>Vendor</th>
            <th className={TH_PRIMARY}>Items</th>
            <th className={TH_FIXED}>Received by</th>
            {/* Rejected and Returned are text, not short values: "Paneer 1 Kg, spoiled, 17 Sept"
                held on one line took width from the Vendor and Items columns and wrapped those into
                three lines each at 1280. Marked as text, they share the wrapping with the others. */}
            <th className={TH_SECOND}>Rejected</th>
            <th className={TH_SECOND}>Returned</th>
          </tr>
        </thead>
        <tbody>
          {/* "None" is kept on a wide screen, where an empty cell under a heading reads as missing
              data, and dropped from a phone card, where "Rejected None · Returned None" on every
              delivery was two lines of noise per card. */}
          {history.map((d) => {
            const rejected = d.parts
              .filter((p) => (p.rejected ?? 0) > 0)
              .map((p) => {
                const l = LINE_BY_ID.get(p.lineId)!;
                return `${l.item} ${quantity(p.rejected ?? 0, l.unit)}, ${p.reason}`;
              });
            return (
              <tr key={d.id} className={TR}>
                <td className={TD_FIXED}>
                  <Day iso={d.date} />
                </td>
                <td className={TD_SECOND}>{d.vendor}</td>
                <td className={`${TD_PRIMARY} font-normal`}>
                  {/* One item to a line, like the delivery note itself. The history toggle appears
                      only where there is more to it than this row: a line that came in more than
                      one part, or one still owed. A line delivered whole in one go has a history
                      of exactly this row, and six "▸ 1 delivery" under a vegetable delivery
                      would be noise. */}
                  {d.parts.map((p) => {
                    const l = LINE_BY_ID.get(p.lineId)!;
                    const more = partsOf(l.id, history).length > 1 || stillToCome(l, history) > 0;
                    return (
                      <div key={p.lineId} className="[&+&]:mt-1">
                        {l.item} <span className="whitespace-nowrap tabular-nums">{quantity(p.qty, l.unit)}</span>
                        {more && <ItemHistory line={l} history={history} />}
                      </div>
                    );
                  })}
                </td>
                <td className={TD_FIXED} data-label="Received by">{d.by}</td>
                <td className={`${TD_SECOND} ${rejected.length ? "" : "max-lg:!hidden"}`} data-label="Rejected">
                  {rejected.length ? rejected.map((r) => <span key={r} className="block">{r}</span>) : "None"}
                </td>
                <td className={`${TD_SECOND} ${d.returned.length ? "" : "max-lg:!hidden"}`} data-label="Returned">
                  {d.returned.length ? d.returned.map((r) => <span key={r} className="block">{r}</span>) : "None"}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

// ---------------------------------------------------------------------------------------------
// The page
// ---------------------------------------------------------------------------------------------

type View = "expected" | "partly" | "received";

export default function DeliveriesMockPage() {
  const [history, setHistory] = useState<Delivery[]>(HISTORY);
  const [view, setView] = useState<View>("expected");
  const [recording, setRecording] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);

  const owed = LINES.filter((l) => stillToCome(l, history) > 0);
  const groups = groupByVendor(owed);
  const partly = owed.filter((l) => receivedOf(l, history) > 0);
  const partlyGroups = groupByVendor(partly);
  const dueToday = groups.filter((g) => g.lines.some((l) => l.neededBy === TODAY)).length;
  const overdue = groups.filter((g) => g.overdueBy > 0).length;
  const partlyOrders = new Set(partly.map((l) => l.po)).size;
  const weekAgo = new Date(Date.parse(`${TODAY}T00:00:00Z`) - 6 * 86_400_000).toISOString().slice(0, 10);
  const thisWeek = history.filter((d) => d.date >= weekAgo).length;

  function save(vendor: string, entries: Record<string, Entry>) {
    const parts: Part[] = [];
    for (const l of LINES) {
      const e = entries[l.id];
      if (!e) continue;
      const got = Number(e.received || 0);
      const refused = Number(e.rejected || 0);
      if (got <= 0 && refused <= 0) continue;
      const reason = REASONS.find((r) => r.value === e.reason)?.label.toLowerCase();
      parts.push({ lineId: l.id, qty: got, ...(refused > 0 ? { rejected: refused, reason } : {}) });
    }
    const next: Delivery[] = [
      { id: `new-${history.length}`, date: TODAY, vendor, parts, by: ME, returned: [] },
      ...history,
    ];
    setHistory(next);
    setRecording(null);
    // Name what this delivery finished, so a completed part delivery is seen to complete.
    const finished = parts
      .map((p) => LINE_BY_ID.get(p.lineId)!)
      .filter((l) => receivedOf(l, history) > 0 && stillToCome(l, next) <= 0)
      .map((l) => `${l.item} is complete: ${ofTotal(receivedOf(l, next), l.ordered, l.unit)}.`);
    const still = LINES.filter((l) => l.vendor === vendor && stillToCome(l, next) > 0).length;
    const n = parts.filter((p) => p.qty > 0).length;
    setSaved(
      [
        `Delivery from ${vendor} recorded. ${n} ${n === 1 ? "item" : "items"} went into stock.`,
        ...finished,
        still > 0 ? `${still} ${still === 1 ? "line is" : "lines are"} still to come from them.` : "Nothing more is owed by them.",
      ].join(" "),
    );
  }

  const viewProps = { history, recording, setRecording, onSave: save };

  return (
    <div className="flex min-h-screen">
      <MockSidebar />
      <main className="min-w-0 flex-1 px-4 pb-24 pt-8 sm:px-8">
        <div className="mx-auto grid max-w-content gap-6">
          <PageHeader
            title="Deliveries"
            subtitle="What vendors are bringing, and what has come in. Sample data: nothing here is saved."
          />

          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <StatTile label="Due today" value={`${dueToday} ${dueToday === 1 ? "vendor" : "vendors"}`} />
            <StatTile
              label="Overdue"
              value={`${overdue} ${overdue === 1 ? "vendor" : "vendors"}`}
              tone={overdue > 0 ? "warning" : "neutral"}
            />
            <StatTile label="Partly delivered" value={`${partlyOrders} ${partlyOrders === 1 ? "order" : "orders"}`} />
            <StatTile label="Received this week" value={`${thisWeek} ${thisWeek === 1 ? "delivery" : "deliveries"}`} />
          </div>

          {saved && (
            <InlineNotice tone="success" autoDismiss>
              {saved}
            </InlineNotice>
          )}

          <SegmentedControl<View>
            label="Deliveries view"
            value={view}
            onChange={(v) => {
              setView(v);
              setRecording(null);
            }}
            // No counts in the labels. With them the three tabs measured wider than a 390 phone and
            // wrapped onto two rows, and the tiles above already carry the counts that matter.
            options={[
              { value: "expected", label: "Expected" },
              { value: "partly", label: "Partly delivered" },
              { value: "received", label: "Received" },
            ]}
          />

          {view === "expected" && <ExpectedView groups={groups} {...viewProps} />}
          {view === "partly" && <PartlyView groups={partlyGroups} {...viewProps} />}
          {view === "received" && <ReceivedView history={history} />}
        </div>
      </main>
    </div>
  );
}
