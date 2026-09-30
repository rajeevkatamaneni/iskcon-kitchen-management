// RETIRED MOCK — approved by Rajeev 2026-09-29 and built on app/today/page.tsx. Kept as the specification.
"use client";

/*
 * THROWAWAY MOCK for fix 5 on the 2026-09-29 list — delete before any deploy.
 *
 * Rajeev, 2026-09-29, on Today's "Meals planned for today": the panel takes the whole width of the
 * page; the meals sit side by side, with a toggle back to one under another (side by side by
 * default); each meal's recipes and quantities line up like a real table with no grid lines, and the
 * quantity sits on a contrasting background so it is clearly visible. And Deliveries moves below it.
 *
 * The layout toggle is the real feature. The A / B / C switch above the page is mock-only: three ways
 * of giving the quantity its contrasting background, to choose between by using them.
 *
 * Built from the app's own parts — Sidebar, Screen, PageHeader, StatTile, Card, Badge,
 * SegmentedControl — so every token and the page column apply exactly as on the real Today. Sample
 * data is 30 Sept on the local seeded temple, plus one event. No API, nothing saved, no shared file
 * changed.
 */

import { useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { Screen } from "@/components/ds/Screen";
import { PageHeader } from "@/components/ds/PageHeader";
import { StatTile } from "@/components/ds/StatTile";
import { Card } from "@/components/ds/Card";
import { Badge } from "@/components/ds/Badge";
import { Button } from "@/components/ds/Button";

interface Dish {
  name: string;
  amount: string;
}
interface Meal {
  name: string;
  readyBy: string;
  servings: number;
  kitchen: string;
  occasion?: string;
  recorded: boolean;
  dishes: Dish[];
}

const MEALS: Meal[] = [
  {
    name: "Breakfast", readyBy: "07:30", servings: 43, kitchen: "Main Kitchen", recorded: false,
    dishes: [{ name: "Idli (Karnataka)", amount: "172 pieces" }],
  },
  {
    name: "Lunch", readyBy: "12:00", servings: 102, kitchen: "Main Kitchen", recorded: false,
    dishes: [
      { name: "Arbi ki Sabzi (Haryana)", amount: "18 L" },
      { name: "Paruppu Rasam", amount: "26 L" },
      { name: "Kosu Palya", amount: "10 L" },
      { name: "Chitranna", amount: "31 L" },
    ],
  },
  {
    name: "Birthday", readyBy: "17:00", servings: 50, kitchen: "Main Kitchen", occasion: "Event", recorded: true,
    dishes: [
      { name: "Chapati", amount: "80 pieces" },
      { name: "Chitranna", amount: "15 L" },
      { name: "Kesari Bath", amount: "8 L" },
      { name: "Balekayi Palya", amount: "10 L" },
    ],
  },
  {
    name: "Dinner", readyBy: "19:30", servings: 24, kitchen: "Main Kitchen", recorded: false,
    dishes: [
      { name: "Kabuli Chana Masala (Bihar)", amount: "6 L" },
      { name: "Chapati (Karnataka)", amount: "72 pieces" },
    ],
  },
];

type Layout = "across" | "down";
type Contrast = "A" | "B" | "C";

/** The three ways of making the quantity stand out. Mock-only. */
const CONTRAST: Record<Contrast, { cell: string; pill: string; label: string }> = {
  // A: a neutral pill behind just the figure.
  A: { cell: "", pill: "rounded-control bg-sunken px-2.5 py-1 font-semibold text-ink", label: "A · Grey pill" },
  // B: the whole quantity column is a tinted band, figures bold.
  B: { cell: "bg-sunken", pill: "font-semibold text-ink", label: "B · Tinted column" },
  // C: the accent tint behind the figure.
  C: { cell: "", pill: "rounded-control bg-accent-bg px-2.5 py-1 font-semibold text-accent-text", label: "C · Accent pill" },
};

export default function DevTodayMeals() {
  const [layout, setLayout] = useState<Layout>("across");
  // Rajeev chose A, the grey pill, on 2026-09-29.
  const contrast: Contrast = "A";

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/today" />
      <main className="min-w-0 flex-1">
        <Screen>
          <PageHeader title="Today" subtitle="Wednesday, 30 September · Krsna Caturthi · Bharani naksatra · Padmanabha masa" />

          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <StatTile label="Servings today" value="219" icon="bowl" note="Breakfast 43 · Lunch 102 · Birthday 50 · Dinner 24" />
            <StatTile label="Items below reorder level" value={5} tone="warning" icon="package" note="Order these before they run out" />
            <StatTile label="Working today" value="4 staff · 0 volunteers" icon="users" note="Breakfast 3 of 2 · Lunch 4 of 6 · Dinner 0 of 2" />
            <StatTile label="Cost of materials" value="₹1,785" icon="receipt" note="3 meals from the plan" />
          </div>

          {/* The whole width of the page, and the layout toggle in the card's own action slot. */}
          <Card
            title="Meals planned for today"
            meta="In the order they are due"
            action={
              // Two icon buttons instead of the words (Rajeev, 2026-09-29). The one in use is the
              // filled one; each still says what it is to a screen reader and on hover.
              <span role="group" aria-label="Meal layout" className="flex gap-1">
                <Button
                  size="icon"
                  variant={layout === "across" ? "primary" : "ghost"}
                  aria-pressed={layout === "across"}
                  aria-label="Horizontal"
                  title="Horizontal"
                  onClick={() => setLayout("across")}
                >
                  <i className="ti ti-layout-columns" aria-hidden="true" />
                </Button>
                <Button
                  size="icon"
                  variant={layout === "down" ? "primary" : "ghost"}
                  aria-pressed={layout === "down"}
                  aria-label="Vertical"
                  title="Vertical"
                  onClick={() => setLayout("down")}
                >
                  <i className="ti ti-layout-rows" aria-hidden="true" />
                </Button>
              </span>
            }
          >
            {layout === "across" ? (
              <div className="grid gap-4 [grid-template-columns:repeat(auto-fit,minmax(16rem,1fr))]">
                {MEALS.map((m) => (
                  <a
                    key={m.name}
                    href="#"
                    onClick={(e) => e.preventDefault()}
                    className="grid content-start gap-3 rounded-card border border-hairline p-4 transition-colors duration-state hover:bg-sunken"
                  >
                    <MealHeading meal={m} />
                    <DishTable dishes={m.dishes} contrast={contrast} layout={layout} />
                  </a>
                ))}
              </div>
            ) : (
              // One grid for the whole card, shared by every meal through subgrid: the heading, the
              // recipe and the figure are columns as wide as their widest entry in ANY meal, so every
              // figure lines up. 6rem after the heading, 2.25rem between a recipe and its figure; the
              // last column takes what is left so the rule between meals runs the full width.
              // Each meal is its own card now, the same rounded border as the side-by-side tiles, with
              // 12px between cards instead of a rule (Rajeev, 2026-09-29).
              <div className="grid gap-3 md:grid-cols-[max-content_max-content_max-content_1fr] md:gap-x-24">
                {MEALS.map((m) => (
                  <div key={m.name} className="md:col-span-full md:grid md:grid-cols-subgrid">
                    <a
                      href="#"
                      onClick={(e) => e.preventDefault()}
                      className="grid gap-3 rounded-card border border-hairline p-4 transition-colors duration-state hover:bg-sunken md:col-span-full md:grid-cols-subgrid md:gap-x-24"
                    >
                      <MealHeading meal={m} />
                      <DishRows dishes={m.dishes} contrast={contrast} />
                    </a>
                  </div>
                ))}
              </div>
            )}
          </Card>

          {/* Deliveries moved below the meals, with what is going out beside it. */}
          <div className="grid items-start gap-4 xl:grid-cols-2">
            <Card title="Deliveries" meta="From orders you have sent">
              <div className="grid gap-3 text-sm">
                {[
                  ["Heritage Fresh Dairy", "PO-2026-0029 · 21 Aug", "Awaited"],
                  ["Jayanagar Hardware Store", "PO-2026-0034 · 14 Sept", "Awaited"],
                  ["Jayanagar Hardware Store", "PO-2026-0035 · 16 Sept", "Awaited"],
                  ["Sri Balaji Traders", "PO-2026-0028 · 27 Aug", "Invoice overdue"],
                ].map(([v, po, s]) => (
                  <div key={po} className="flex items-start justify-between gap-3">
                    <span className="grid">
                      <span className="font-medium text-ink">{v}</span>
                      <span className="text-xs text-ink-muted">{po}</span>
                    </span>
                    <Badge tone={s === "Awaited" ? "neutral" : "danger"}>{s}</Badge>
                  </div>
                ))}
              </div>
            </Card>
            <Card title="Going out of the temple" meta="In the days ahead">
              <p className="text-sm text-ink-secondary">Nothing is going out in the next fortnight.</p>
            </Card>
          </div>
        </Screen>
      </main>
    </div>
  );
}

function MealHeading({ meal }: { meal: Meal }) {
  // Three lines in every meal, always the same three, so the tables below start level across the row.
  return (
    <span className="grid content-start gap-1">
      <span className="flex flex-wrap items-baseline gap-x-2">
        <span className="text-base font-semibold text-ink">{meal.name}</span>
        {meal.occasion && <span className="text-sm text-ink-secondary">{meal.occasion}</span>}
      </span>
      <span className="text-sm text-ink-secondary">
        Ready by <span className="font-semibold tabular-nums text-ink">{meal.readyBy}</span>
        <span className="text-ink-muted"> · {meal.servings} servings · {meal.kitchen}</span>
      </span>
      <span>{meal.recorded ? <Badge>Recorded</Badge> : <span className="text-xs text-ink-muted">Not yet recorded</span>}</span>
    </span>
  );
}

/** Recipe and quantity as a real table: aligned columns, no rules. */
function DishTable({ dishes, contrast, layout }: { dishes: Dish[]; contrast: Contrast; layout: Layout }) {
  const c = CONTRAST[contrast];
  // Side by side, the recipe takes whatever the tile leaves and the figure column is only as wide as
  // its widest figure. One under another, both columns are fixed — recipe 24rem, figure 9rem — so the
  // figures form one column down the whole card and sit beside the names, not across a gap.
  const across = layout === "across";
  const width = across ? "w-full" : "w-[33rem] max-w-full table-fixed";
  const qty = across ? "w-0" : "w-[9rem]";
  return (
    // No rules and no lifting row: the app's table defaults put a hairline on every cell and raise a
    // row under the pointer. Switched off here, on this table only.
    <table className={`${width} text-left text-sm [&_td]:border-0 [&_tr:hover]:!transform-none [&_tr:hover]:!shadow-none`}>
      <thead>
        <tr>
          <th scope="col" className="pb-1 pr-4 text-xs font-semibold uppercase tracking-wide text-ink-secondary">Recipe</th>
          <th scope="col" className={`${qty} whitespace-nowrap px-3 pb-1 text-xs font-semibold uppercase tracking-wide text-ink-secondary ${c.cell} ${contrast === "B" ? "rounded-t-control pt-1" : ""}`}>Planned</th>
        </tr>
      </thead>
      <tbody>
        {dishes.map((d, i) => (
          <tr key={d.name}>
            <td className="py-1.5 pr-4 text-ink">{d.name}</td>
            <td
              className={`${qty} whitespace-nowrap px-3 py-1.5 tabular-nums ${c.cell} ${
                contrast === "B" && i === dishes.length - 1 ? "rounded-b-control" : ""
              }`}
            >
              <span className={`inline-block ${c.pill}`}>{d.amount}</span>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** The recipe list inside the shared grid of "one under another": two columns of that grid. */
function DishRows({ dishes, contrast }: { dishes: Dish[]; contrast: Contrast }) {
  const c = CONTRAST[contrast];
  const head = "pb-1 text-xs font-semibold uppercase tracking-wide text-ink-secondary";
  return (
    <div role="table" className="grid grid-cols-[1fr_auto] items-center gap-x-6 gap-y-1 text-sm md:col-span-2 md:gap-x-9 md:grid-cols-subgrid">
      <div role="row" className="contents">
        <span role="columnheader" className={head}>Recipe</span>
        <span role="columnheader" className={head}>Planned</span>
      </div>
      {dishes.map((d) => (
        <div role="row" key={d.name} className="contents">
          <span role="cell" className="py-1 text-ink">{d.name}</span>
          <span role="cell" className="py-1 tabular-nums">
            <span className={`inline-block ${c.pill}`}>{d.amount}</span>
          </span>
        </div>
      ))}
    </div>
  );
}
