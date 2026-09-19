"use client";

import { Tooltip } from "@/components/ds/Tooltip";
import { money, shortDate } from "@/lib/format";

/**
 * Which way a list price moved from the one before it (R-VEN-3), or null when there is nothing to
 * compare with.
 *
 * <p>Exported on its own so the direction can be tested without rendering, and so the page and the
 * marker cannot disagree about it. The comparison is of the two prices per stock unit, which is how
 * the server keeps the history: a vendor who changes a 25 Kg bag to a 30 Kg bag at a new pack price
 * has changed the price per Kg, and that is the figure the arrow is about.
 */
export type PriceDirection = "up" | "down" | "same";

export function priceDirection(
  current: number | null | undefined,
  previous: number | null | undefined,
): PriceDirection | null {
  if (current === null || current === undefined) return null;
  if (previous === null || previous === undefined) return null;
  // The history stores four decimal places. Anything smaller than that is float dust from the JSON,
  // not a change, and must not paint a red arrow on a price nobody moved.
  if (Math.abs(current - previous) < 0.00005) return "same";
  return current > previous ? "up" : "down";
}

/*
  The three markers. Red up and green down is the one written exception to the colour rule — red
  for serious, green only for the user's own action succeeding — and it is an exception for price
  trends only (R-VEN-3). Rajeev confirmed it on the Decisions Desk on 2026-09-19 (question Q-4):
  "Yes, red up and green down, written into the design system". It is written into
  DESIGN_SYSTEM.md §2 as v1.14. A rise costs the temple money, which is why it is the red one.
  Unchanged is neutral, like everything else that is not a warning.

  Each is an outline Tabler glyph, never the only signal: the marker's spoken name says the
  direction in words, and the tip beside it says what it moved from.
*/
const LOOK: Record<PriceDirection, { icon: string; tone: string; word: string }> = {
  up: { icon: "ti-arrow-up", tone: "text-danger", word: "Up from" },
  down: { icon: "ti-arrow-down", tone: "text-success", word: "Down from" },
  same: { icon: "ti-minus", tone: "text-ink-secondary", word: "Unchanged from" },
};

/**
 * The marker beside a List price: a red up arrow, a green down arrow, a neutral dash, or nothing.
 *
 * <p>Hovering it, focusing it with the keyboard, or tapping it on a phone shows the previous price
 * and its date, "₹60 on 12 Sept", through the shared {@link Tooltip}. It takes focus (`tabIndex=0`)
 * because a keyboard user has to be able to reach the tip; it is not a button because pressing it
 * does nothing.
 *
 * <p>The spoken name carries the whole fact — "Up from ₹60 on 12 Sept" — rather than relying on
 * the tip's `aria-describedby`, because the shared tooltip puts that on a wrapper around the
 * focused element, where not every screen reader looks.
 *
 * <p>`format` turns a price per stock unit into the figure for the tip, in the same unit as the price
 * the marker sits beside, and a page that shows a List price should always pass one. Beside "₹1,600 /
 * bag · ₹64 / Kg" a per-Kg "₹60 on 19 Sept" read as ₹60 a bag (VERIFY2-A, N2), so the vendor page and
 * the ingredient page both pass `previousPriceText` (components/ingredient/supply.tsx): the old price
 * per bag for a supply sold in bags, "₹1,500 on 19 Sept", and per Kg otherwise, "₹60 on 19 Sept". A
 * price kept per gram is said per Kg there (₹0.068 / gm is unreadable, and two decimals would make
 * ₹0.065 and ₹0.068 look identical beside an arrow saying they differ). The words stay "₹<amount> on
 * <date>", as R-VEN-3's acceptance check fixes them.
 */
export function PriceTrend({
  current,
  previous,
  previousOn,
  format = (n) => money(n, "INR"),
}: {
  current: number | null | undefined;
  previous: number | null | undefined;
  previousOn: string | null | undefined;
  format?: (pricePerUnit: number) => string;
}) {
  const direction = priceDirection(current, previous);
  if (direction === null || previous === null || previous === undefined) return null;

  const was = previousOn ? `${format(previous)} on ${shortDate(previousOn)}` : format(previous);
  const look = LOOK[direction];

  return (
    <Tooltip text={was}>
      <span
        role="img"
        tabIndex={0}
        aria-label={`${look.word} ${was}`}
        data-direction={direction}
        // 24px drawn, 44px to touch: the `before` layer reaches 10px past each side, so a finger
        // finds it (§5, touch targets) without the row growing to fit a 44px box.
        className={`relative inline-flex h-6 w-6 items-center justify-center rounded-control before:absolute before:-inset-2.5 before:content-[''] ${look.tone}`}
      >
        <i className={`ti ${look.icon} text-base`} aria-hidden="true" />
      </span>
    </Tooltip>
  );
}
