import { redirect } from "next/navigation";

/**
 * The Payments page is gone (R-PAY-4, T-275). Its only job was the unpaid invoices by age and the
 * total owed, and that is the Invoices list's Unpaid and overdue filters now, with the total owed
 * above them; paying moves to the invoice's own page.
 *
 * <p>The route stays as a redirect because somebody has /money bookmarked, and a 404 is a worse way
 * to learn a screen moved. It is a server component calling `redirect()`, so the answer is an HTTP
 * redirect from the server rather than a page that loads and then moves. `force-dynamic` keeps it a
 * real response on every request instead of a prerendered page. Temporary (307), not permanent: a
 * browser caches a permanent redirect indefinitely, and where this lands may still change.
 *
 * <p>No role check here: the Invoices list decides what each reader sees, and a Kitchen Manager who
 * follows an old link lands on the list as they always see it.
 */
export const dynamic = "force-dynamic";

export default function PaymentsMovedPage(): never {
  redirect("/invoices?filter=unpaid");
}
