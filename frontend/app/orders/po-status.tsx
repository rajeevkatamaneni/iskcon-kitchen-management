import type { PoStatus } from "@/lib/api";

/**
 * Purchase-order status presentation, shared by the list and detail pages. Kept out of a page.tsx
 * because the Next.js app router only allows a page's own reserved exports from those files.
 */

export const STATUSES: PoStatus[] = [
  "DRAFT",
  "SENT",
  "PARTIALLY_RECEIVED",
  "CLOSED",
  "RECEIVED",
  "CANCELLED",
];

export const STATUS_LABEL: Record<PoStatus, string> = {
  DRAFT: "Draft",
  SENT: "Sent",
  /**
   * "Part delivered", as mock dev-po design E draws it (T-265, conductor's ruling 2026-09-19: the
   * mock wins where the document is silent). One label, here, so the list, the order page and the
   * invoice's order picker all say the same thing.
   */
  PARTIALLY_RECEIVED: "Part delivered",
  /**
   * A part-delivered order somebody ended (T-142, D-26). "Closed" and never "Part delivered" or
   * "Short closed": the word has to say that nothing more is expected on this order, which is the
   * whole difference between this and the status above it — and it must not read as a cancellation,
   * because goods arrived against it and are owed for.
   */
  CLOSED: "Closed",
  RECEIVED: "Received",
  CANCELLED: "Cancelled",
};

const STATUS_CLASS: Record<PoStatus, string> = {
  DRAFT: "bg-sunken text-ink-secondary",
  SENT: "bg-accent-bg text-accent-text",
  // Neutral, as the mock draws it (T-265). Amber is for something the reader should act on now,
  // and a part-delivered order is a standing state: the rest is simply still owed. Where it is
  // overdue, that is the Deliveries screen's amber pill to say, not this chip's (colour rule,
  // DESIGN_SYSTEM v1.13).
  PARTIALLY_RECEIVED: "bg-sunken text-ink-secondary",
  // The muted tone a finished-and-not-celebrated order wears, the same as a cancellation. Not the
  // success green: some of what was asked for never arrived, and not the warning amber either,
  // because nobody is waiting on it any more.
  CLOSED: "bg-sunken text-ink-secondary",
  // Neutral too. Received is the good end of an order, but green is kept for the moment the
  // reader's own action succeeds, not a standing state on a list (Rajeev, 2026-09-18, T-227).
  RECEIVED: "bg-sunken text-ink-secondary",
  CANCELLED: "bg-sunken text-ink-muted",
};

export function statusChip(status: PoStatus) {
  return (
    <span className={`rounded-control px-2 py-1 text-xs ${STATUS_CLASS[status]}`}>
      {STATUS_LABEL[status]}
    </span>
  );
}
