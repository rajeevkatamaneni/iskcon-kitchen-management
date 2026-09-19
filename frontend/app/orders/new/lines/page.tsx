import { redirect } from "next/navigation";

/**
 * Retired: this was step two of creating an order by hand, the lines, after a screen that asked
 * only for the vendor (T-026, D-7). R-PO-1 (2026-09-19) removed the two-step shape — "Create a
 * purchase order" now goes straight to one form at `/orders/new` (T-263).
 *
 * <p>Kept as a redirect rather than deleted, because an address that worked yesterday is somebody's
 * bookmark or browser history, and a 404 is a worse way to learn a screen moved. The vendor chosen
 * on the old step one travelled here as `?vendor=`; it is carried across, and `/orders/new` chooses
 * that vendor if it is still active. The same reasoning as the `/order-list` redirect in
 * `next.config.js`, done here because it has a parameter to carry.
 *
 * <p>A server component, so the redirect happens before anything is drawn.
 */
export default function RetiredPurchaseOrderLinesPage({
  searchParams,
}: {
  searchParams: { vendor?: string | string[] };
}) {
  const vendor = Array.isArray(searchParams.vendor) ? searchParams.vendor[0] : searchParams.vendor;
  redirect(vendor ? `/orders/new?vendor=${encodeURIComponent(vendor)}` : "/orders/new");
}
