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
  PARTIALLY_RECEIVED: "Partially received",
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
  PARTIALLY_RECEIVED: "bg-warning-bg text-warning",
  // The muted tone a finished-and-not-celebrated order wears, the same as a cancellation. Not the
  // success green: some of what was asked for never arrived, and not the warning amber either,
  // because nobody is waiting on it any more.
  CLOSED: "bg-sunken text-ink-secondary",
  RECEIVED: "bg-success-bg text-success",
  CANCELLED: "bg-sunken text-ink-muted",
};

export function statusChip(status: PoStatus) {
  return (
    <span className={`rounded-sm px-2 py-1 text-xs ${STATUS_CLASS[status]}`}>
      {STATUS_LABEL[status]}
    </span>
  );
}
