"use client";

import Link from "next/link";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { SegmentedControl } from "@/components/ds/SegmentedControl";
import { EmptyState } from "@/components/ds/EmptyState";
import { api, type PayableView, type VendorInvoiceView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";
import { dateWithYear, money, todayIso, wholeDaysBetween } from "@/lib/format";
import { RULED_TABLE, THEAD, TR, TH_LEAD, TD_LEAD, TH_PRIMARY, TD_PRIMARY, TH_FIXED, TD_FIXED, TD_FIXED_NUM } from "@/components/ds/table";

export default function InvoicesPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      {/* useSearchParams — for the confirmation a recorded invoice comes back with. */}
      <Suspense>
        <InvoicesView />
      </Suspense>
    </RequireRole>
  );
}

/**
 * One filter set over the list (R-INV-8, T-275), replacing the Status dropdown and the "Overdue only"
 * tick it had before. The conductor's ruling of 2026-09-19: the Paid and Voided views stay, "Unpaid"
 * is the one word for a bill still owed (never "Pending" in one view and "Unpaid" in another), and
 * the four filters that age what is owed, with the total owed above them, are for people who can
 * pay. Everyone else keeps what the old controls gave them: unpaid, overdue, paid and voided.
 * Unpaid means the same rows for both, money still owed (T-281), and so does Overdue's "still owed".
 *
 * <p>The value is what the address bar carries, `/invoices?filter=unpaid`, because the Payments page
 * this replaces redirects there (R-PAY-4) and a filter somebody can link to is one they can bookmark.
 */
type Filter =
  | "all"
  | "unpaid"
  | "due-this-week"
  | "overdue-1-30"
  | "overdue-31"
  | "overdue"
  | "paid"
  | "voided";

/** For people who can pay: what is owed, aged, beside the settled views. */
const PAYER_FILTERS: readonly { value: Filter; label: string }[] = [
  { value: "all", label: "All" },
  { value: "unpaid", label: "Unpaid" },
  { value: "due-this-week", label: "Due this week" },
  { value: "overdue-1-30", label: "1–30 days overdue" },
  { value: "overdue-31", label: "31+ days overdue" },
  { value: "paid", label: "Paid" },
  { value: "voided", label: "Voided" },
];

/** For everyone else: the old Status choices and the old "Overdue only" tick, as one set. */
const OTHER_FILTERS: readonly { value: Filter; label: string }[] = [
  { value: "all", label: "All" },
  { value: "unpaid", label: "Unpaid" },
  { value: "overdue", label: "Overdue" },
  { value: "paid", label: "Paid" },
  { value: "voided", label: "Voided" },
];

/**
 * The filters that age what is owed, answered from `/api/v1/payables`, which only a payer may read.
 * Unpaid is not one of them since T-281: it goes to the server as `owed=true` for everybody, below.
 */
const AGING_FILTERS: readonly Filter[] = ["due-this-week", "overdue-1-30", "overdue-31"];

/**
 * "Due this week" is the next seven days counted from the temple's today, both ends in: due today up
 * to due seven days from now. Not the calendar week, because on a Saturday that would be two days
 * and a payer planning the week's cheques wants the same horizon whatever day it is.
 */
const DUE_SOON_DAYS = 7;

/** What a filter shows when it matches nothing. Each names the view, so an empty filter is not
 * mistaken for an empty list. */
const NOTHING_HERE: Record<Filter, { title: string; body: string }> = {
  all: { title: "No invoices", body: "Create an invoice when a vendor’s bill arrives." },
  unpaid: { title: "Nothing is owed", body: "Every vendor invoice is paid." },
  "due-this-week": { title: "Nothing due this week", body: "No unpaid invoice falls due in the next seven days." },
  "overdue-1-30": { title: "Nothing 1–30 days overdue", body: "No unpaid invoice is between 1 and 30 days late." },
  "overdue-31": { title: "Nothing 31+ days overdue", body: "No unpaid invoice is more than 30 days late." },
  overdue: { title: "Nothing overdue", body: "No unpaid invoice is past its due date." },
  paid: { title: "No paid invoices", body: "An invoice shows here once it is paid in full." },
  voided: { title: "No voided invoices", body: "An invoice shows here if it is struck off as never owed." },
};

/** Whether an owed invoice belongs under an aging filter, counted in days from the temple's today. */
function owedMatches(filter: Filter, p: PayableView, today: string): boolean {
  // An invoice with no due date is owed but has no age, so it is Unpaid and nothing narrower.
  if (!p.dueDate) return false;
  // Positive once the due date has passed: 1 is the day after it fell due.
  const late = wholeDaysBetween(p.dueDate, today);
  if (filter === "due-this-week") return late <= 0 && late >= -DUE_SOON_DAYS;
  if (filter === "overdue-1-30") return late >= 1 && late <= 30;
  if (filter === "overdue-31") return late >= 31;
  return false;
}

function InvoicesView() {
  const { appUser } = useAuth();
  // THE one condition (Desk Q-17: Rajeev confirmed it as built, 2026-09-19): the total owed and the
  // filters that age it are shown only to people who can pay, which is MANAGE_VENDOR_PAYMENTS on the
  // server and, of the roles that open this page, the Temple Admin alone. The same test the invoice's
  // own page makes for its payments. A Kitchen Manager would otherwise be offered figures the API
  // refuses them (`/api/v1/payables` is behind that permission). To widen or narrow it, change this
  // line and nothing else.
  const canSeeWhatIsOwed = appUser?.role === "TEMPLE_ADMIN";
  const filters = canSeeWhatIsOwed ? PAYER_FILTERS : OTHER_FILTERS;

  const router = useRouter();
  const params = useSearchParams();
  // An address asking for a filter this reader isn't offered (a Kitchen Manager following an old
  // /money link to ?filter=due-this-week, say) shows All rather than a view they can't select.
  const asked = params.get("filter");
  const filter: Filter = filters.find((f) => f.value === asked)?.value ?? "all";
  const byAge = canSeeWhatIsOwed && AGING_FILTERS.includes(filter);

  // Replaced rather than pushed, as the ingredient requests' filter does: it narrows one screen, and
  // Back should leave the screen rather than walk back through every filter tried.
  function choose(next: Filter) {
    router.replace(next === "all" ? "/invoices" : `/invoices?filter=${next}`);
  }

  // Unpaid is `owed=true` for everybody (T-281): bills money is still owed on, which the server works
  // out exactly as `/api/v1/payables` does, so the label shows the same rows to a Kitchen Manager as
  // to the Temple Admin, and a payer's Unpaid rows add up to the total owed. Before this a payer's
  // Unpaid came from payables and everyone else's from `status=PENDING`, and a PENDING bill with
  // nothing left to pay showed for one and not the other.
  //
  // An aging filter is answered from all invoices joined to what is owed on each, so its rows are
  // invoices the total below counts. Overdue (non-payers) is the server's `overdue=true`, which is
  // past due and still owed. Paid and Voided are the server's status filter.
  const fetchInvoices = useCallback(
    (token: string | undefined) =>
      api.listInvoices(
        byAge || filter === "all"
          ? {}
          : filter === "unpaid"
            ? { owed: true }
            : filter === "overdue"
              ? { overdue: true }
              : { status: filter === "paid" ? "PAID" : "VOIDED" },
        token
      ),
    [byAge, filter]
  );
  const { data, error, loading } = useAuthedQuery(fetchInvoices);

  // What is owed on each unpaid invoice. Asked for only by a payer; for anybody else the hook still
  // runs (hooks cannot be conditional) but resolves to nothing without calling the API.
  const fetchOwed = useCallback(
    (token: string | undefined): Promise<PayableView[]> =>
      canSeeWhatIsOwed ? api.payables(token) : Promise.resolve([]),
    [canSeeWhatIsOwed]
  );
  const owedQuery = useAuthedQuery(fetchOwed);
  const owed = canSeeWhatIsOwed ? (owedQuery.data ?? []) : [];

  // Always everything owed, whichever filter is on (the conductor's ruling, 2026-09-19): it is the
  // figure the Payments page led with, and a total that changed with the filter would read as the
  // temple owing less the moment somebody picked "31+ days overdue".
  const totalOwed = owed.reduce((sum, p) => sum + p.outstanding, 0);

  const today = todayIso();
  let invoices: VendorInvoiceView[] = data ?? [];
  if (byAge) {
    const wanted = new Set(owed.filter((p) => owedMatches(filter, p, today)).map((p) => p.invoiceId));
    invoices = invoices.filter((inv) => wanted.has(inv.id));
  }

  const busy = loading || (canSeeWhatIsOwed && owedQuery.loading);
  const failure = error ?? (canSeeWhatIsOwed ? owedQuery.error : null);
  const empty = NOTHING_HERE[filter];

  // Recording happens on /invoices/new and ends here, so the confirmation travels in the URL. The
  // ref guards the capture: setting state re-renders, and a router object that is new each render
  // would otherwise re-run this effect for ever.
  const recorded = params.get("recorded");
  const duplicate = params.get("duplicate") === "1";
  const [flash, setFlash] = useState<{ number: string; duplicate: boolean } | null>(null);
  const captured = useRef(false);
  useEffect(() => {
    if (captured.current || !recorded) return;
    captured.current = true;
    setFlash({ number: recorded, duplicate });
    router.replace("/invoices");
  }, [recorded, duplicate, router]);

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/invoices" />
      <main className="min-w-0 flex-1 px-4 py-10 sm:px-8">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Invoices</h1>
              <p className="mt-1 text-ink-secondary">
                Captured against a purchase order, or direct for a cash-market buy.
              </p>
            </div>
            <ButtonLink href="/invoices/new">Create an invoice</ButtonLink>
          </header>

          {flash && (
            <div className="mb-6">
              {flash.duplicate ? (
                <InlineNotice tone="warning" title={`Invoice ${flash.number} was recorded.`}>
                  Another invoice from this vendor already uses that number.
                </InlineNotice>
              ) : (
                <InlineNotice tone="success" autoDismiss title={`Invoice ${flash.number} was recorded.`} />
              )}
            </div>
          )}

          {/* The filters and the total owed share one row while they fit, the total at the trailing
              end: it is context for the list, not the headline, so it is a label and a figure in the
              tile's type sizes without the tile's card. Neutral, because owing a vendor is normal;
              the overdue filters are where lateness is read. */}
          <div className="mb-6 flex flex-wrap items-center justify-between gap-x-6 gap-y-3">
            <SegmentedControl label="Filter invoices" options={filters} value={filter} onChange={choose} />
            {canSeeWhatIsOwed && !owedQuery.loading && !owedQuery.error && (
              <p className="flex items-baseline gap-2" data-testid="total-owed">
                <span className="text-sm text-ink-secondary">Total owed</span>
                <span className="text-lg font-semibold tabular-nums text-ink">{money(totalOwed, "INR")}</span>
              </p>
            )}
          </div>

          {busy ? (
            <Loading label="Loading invoices…" />
          ) : failure ? (
            <ErrorNotice error={failure} />
          ) : invoices.length === 0 ? (
            <EmptyState title={empty.title}>{empty.body}</EmptyState>
          ) : (
            <div className="table-wrap overflow-x-auto">
              {/* On the table rule since 2026-09-18 (T-233): the invoice number leads on the left,
                  fixed, because it is the row's link (the exception Rajeev accepted); the vendor is
                  the primary flexible column; everything after it is fixed (one line). Since T-236 every
                  column reads left and the spare width is shared evenly between them. */}
              <table className={RULED_TABLE}>
                <thead className={THEAD}>
                  <tr>
                    <th className={TH_LEAD}>Invoice</th>
                    <th className={TH_PRIMARY}>Vendor</th>
                    <th className={TH_FIXED}>Against</th>
                    <th className={TH_FIXED}>Amount</th>
                    <th className={TH_FIXED}>Due</th>
                    <th className={TH_FIXED}>Status</th>
                  </tr>
                </thead>
                <tbody>
                  {invoices.map((inv) => (
                    <tr key={inv.id} className={TR}>
                      <td className={TD_LEAD}>
                        <Link href={`/invoices/${inv.id}`} className="font-medium text-accent-text hover:underline">
                          {inv.invoiceNumber}
                        </Link>
                      </td>
                      <td className={`${TD_PRIMARY} text-ink-secondary`}>{inv.vendorName}</td>
                      <td className={`${TD_FIXED} text-ink-secondary`}>
                        {inv.direct ? (
                          <span className="rounded-control bg-sunken px-2 py-1 text-xs font-semibold">Direct</span>
                        ) : (
                          <span className="tabular-nums">{inv.poNumber ?? "—"}</span>
                        )}
                        {inv.variance != null && inv.variance !== 0 && (
                          <span className="ml-2 rounded-control bg-warning-bg px-2 py-0.5 text-xs text-warning font-semibold">
                            {/* "Difference", because the invoice page calls this same figure that
                                (R-INV-7), and one figure must not wear two names (T-310). It read
                                "Variance". The page's "more than received" is left off here: at 1024
                                it pushed the Due date onto two lines (measured), and the sign says
                                the same thing, as it did before. */}
                            Difference {money(inv.variance, "INR")}
                          </span>
                        )}
                      </td>
                      <td className={TD_FIXED_NUM}>
                        {money(inv.amount, "INR")}
                        {/* What is owed, where it differs from what was billed. A credit note leaves
                            the invoiced figure alone — that is what the vendor sent — so the row has
                            to say the rest out loud or it reads as money still to pay. */}
                        {inv.creditedAmount > 0 && (
                          <span className="ml-2 text-xs text-ink-secondary">
                            less {money(inv.creditedAmount, "INR")} credited
                          </span>
                        )}
                      </td>
                      <td className={`${TD_FIXED} text-ink-secondary`} data-label="Due">
                        {inv.dueDate ? dateWithYear(inv.dueDate) : "—"}
                        {inv.overdue && <span className="ml-2 rounded-control bg-danger-bg px-2 py-0.5 text-xs text-danger font-semibold">Overdue</span>}
                      </td>
                      <td className={TD_FIXED}>
                        {inv.status === "VOIDED" ? (
                          <span className="rounded-control bg-sunken px-2 py-1 text-xs text-ink-secondary font-semibold">Voided</span>
                        ) : inv.status === "PAID" ? (
                          // Neutral: paid is a settled state, and green is kept for the moment the
                          // reader's own action succeeds (Rajeev, 2026-09-18, T-227).
                          <span className="rounded-control bg-sunken px-2 py-1 text-xs text-ink-secondary font-semibold">Paid</span>
                        ) : (
                          // "Unpaid", not "Pending": the filter above says Unpaid, and one state keeps
                          // one word in every view (the conductor, T-275).
                          <span className="rounded-control bg-accent-bg px-2 py-1 text-xs text-accent-text font-semibold">Unpaid</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </main>
    </div>
  );
}
