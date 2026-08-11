import type { PoStatus } from "@/lib/api";

/**
 * Purchase-order status presentation, shared by the list and detail pages. Kept out of a page.tsx
 * because the Next.js app router only allows a page's own reserved exports from those files.
 */

export const STATUSES: PoStatus[] = ["DRAFT", "SENT", "PARTIALLY_RECEIVED", "RECEIVED", "CANCELLED"];

export const STATUS_LABEL: Record<PoStatus, string> = {
  DRAFT: "Draft",
  SENT: "Sent",
  PARTIALLY_RECEIVED: "Partially received",
  RECEIVED: "Received",
  CANCELLED: "Cancelled",
};

const STATUS_CLASS: Record<PoStatus, string> = {
  DRAFT: "bg-sunken text-ink-secondary",
  SENT: "bg-accent-bg text-accent-text",
  PARTIALLY_RECEIVED: "bg-warning-bg text-warning",
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
