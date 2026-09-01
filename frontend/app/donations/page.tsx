"use client";

import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { EmptyState } from "@/components/ds/EmptyState";
import { SegmentedControl } from "@/components/ds/SegmentedControl";
import {
  api,
  toApiError,
  type ApiError,
  type CategoryComparison,
  type LedgerPeriodKind,
} from "@/lib/api";
import { money, todayIso } from "@/lib/format";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";
import { TABLE, TD_DATE, TD_NUM, TD_TEXT, THEAD, TH_NUM, TH_TEXT, TR, WRAP } from "@/components/ds/table";

// The order the ledger reads in: what the temple collected online first, then what it wrote down.
const CATEGORIES = ["ONE_TIME", "RECURRING", "WISHLIST", "MANUAL", "IN_KIND"] as const;
const CATEGORY_LABEL: Record<string, string> = {
  ONE_TIME: "One-time",
  RECURRING: "Recurring",
  WISHLIST: "Wish list",
  MANUAL: "Manual",
  IN_KIND: "In-kind",
};

/**
 * How the money arrived, in words.
 *
 * <p>The ledger used to print the stored value straight into the cell, so a row read `CASH` and a
 * bank transfer read `BANK_TRANSFER`, underscore and all — the only shouting left in the app's
 * content, and the one place a copy pass could not find it, because the string is in the database
 * rather than in the source. An unknown mode falls back to what is stored: a value nobody has a word
 * for yet should still be visible rather than blanked.
 */
const PAYMENT_MODE_LABEL: Record<string, string> = {
  CASH: "Cash",
  UPI: "UPI",
  BANK_TRANSFER: "Bank transfer",
  CHEQUE: "Cheque",
  CARD: "Card",
  NETBANKING: "Net banking",
  WALLET: "Wallet",
};

/**
 * The windows the ledger can be read over. "Another year" is last, and is the only one that needs a
 * second choice made — which is why it is a segment that reveals a picker rather than a list of
 * every year the temple has ever had crowded into the control itself.
 */
const PERIODS: readonly { value: LedgerPeriodKind; label: string }[] = [
  { value: "WEEK", label: "This week" },
  { value: "MONTH", label: "This month" },
  { value: "FINANCIAL_YEAR", label: "This financial year" },
  { value: "YEAR", label: "Another year" },
];

/** The window a URL with no period on it means. */
const DEFAULT_PERIOD: LedgerPeriodKind = "MONTH";

function periodFrom(value: string | null): LedgerPeriodKind {
  return PERIODS.some((p) => p.value === value) ? (value as LedgerPeriodKind) : DEFAULT_PERIOD;
}

/** 2025 → "FY 2025–26", the way a financial year is written on an Indian receipt. */
function financialYearLabel(year: number): string {
  return `FY ${year}–${String(year + 1).slice(2)}`;
}

/** "1 Apr 2026". The year is spelled out because a closed financial year is often the one on show. */
function dayMonthYear(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString("en-GB", {
    day: "numeric",
    month: "short",
    year: "numeric",
  });
}

/**
 * What the tile says beneath its figure about the same window a year earlier.
 *
 * <p>Three of the four answers are not percentages, and that is the point of the feature. A prior
 * window of nothing has no denominator, so the tile says so rather than printing an increase of
 * infinity or quietly rounding it to 100%. A temple whose records do not reach back a year has no
 * prior window at all — a different statement again, and not a fall to zero. Only when there was
 * genuinely money there before does a percentage get printed.
 *
 * <p>The wording follows the window: while a period is still running the comparison is against the
 * same point last year, and once it has closed — a financial year the temple has finished — it is
 * against the whole of the year before, because that is what was actually measured.
 */
function comparisonNote(
  comparison: CategoryComparison | undefined,
  hasPriorYear: boolean,
  closed: boolean
): string {
  if (!hasPriorYear) {
    return "nothing recorded that far back";
  }
  if (!comparison || comparison.changePercent === null) {
    return closed ? "nothing in the year before" : "nothing at this point last year";
  }
  const lastYear = closed ? "the year before" : "this point last year";
  if (comparison.changePercent === 0) {
    return `level with ${lastYear}`;
  }
  const direction = comparison.changePercent > 0 ? "up" : "down";
  return `${direction} ${Math.abs(comparison.changePercent)}% on ${lastYear}`;
}

export default function DonationsPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      {/* useSearchParams — the period being read, and what a recorded gift comes back with. */}
      <Suspense>
        <DonationsView />
      </Suspense>
    </RequireRole>
  );
}

function DonationsView() {
  const { appUser } = useAuth();
  const isAdmin = appUser?.role === "TEMPLE_ADMIN";

  // Recording happens on /donations/new and ends here, so the confirmation travels in the URL. The
  // ref guards the capture against a router object that is new on every render.
  const router = useRouter();
  const recorded = useSearchParams().get("recorded");
  const [flash, setFlash] = useState<string | null>(null);
  const captured = useRef(false);
  useEffect(() => {
    if (captured.current || recorded === null) return;
    captured.current = true;
    setFlash(recorded);
    router.replace("/donations");
  }, [recorded, router]);

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/donations" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Donations</h1>
            </div>
            <ButtonLink href="/donations/new">Record a donation</ButtonLink>
          </header>

          {flash !== null && (
            <div className="mb-6">
              {/* A gift recorded against the wrong person cannot be undone here, so the
                  confirmation names who it was recorded against rather than only saying it was
                  saved. Copy about money that has moved is exempt from the twelve-word ceiling. */}
              <InlineNotice
                tone="success"
                autoDismiss
                title={flash ? `The gift from ${flash} was recorded.` : "The anonymous gift was recorded."}
              >
                It is in the ledger below, and a thank-you is on its way to the donor.
              </InlineNotice>
            </div>
          )}

          {isAdmin ? (
            <DonationsLedger />
          ) : (
            <EmptyState title="The ledger is for temple admins">
              Record a gift with the button above.
            </EmptyState>
          )}
        </div>
      </main>
    </div>
  );
}

/**
 * Every gift the temple has received, however it arrived — online, recurring, wish-list, cash and
 * in-kind, in one list, over one window. A gift recorded on /donations/new lands here.
 */
function DonationsLedger() {
  const { getToken } = useAuth();
  const router = useRouter();
  const params = useSearchParams();
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<ApiError | null>(null);

  // Item 22: the window being read is what somebody is looking at, so it is in the address bar. A
  // period or a year is a change of what is shown and is pushed, so back returns to the window
  // before it. The type is a filter narrowing that same window, so it is replaced — otherwise
  // reading down the five categories would leave five entries to press back through.
  const period = periodFrom(params.get("period"));
  const financialYear = params.get("fy") ? Number(params.get("fy")) : null;
  const type = params.get("type") ?? "";

  function go(next: { period?: LedgerPeriodKind; fy?: number | null; type?: string }, how: "push" | "replace") {
    const q = new URLSearchParams();
    const p = next.period ?? period;
    const y = next.fy === undefined ? financialYear : next.fy;
    const t = next.type === undefined ? type : next.type;
    if (p !== DEFAULT_PERIOD) q.set("period", p);
    if (y !== null) q.set("fy", String(y));
    if (t) q.set("type", t);
    const url = q.toString() ? `/donations?${q}` : "/donations";
    if (how === "push") router.push(url);
    else router.replace(url);
  }

  // The summary is asked first and the list second, because the summary is what resolves a period
  // into two dates. The alternative — working the dates out here — would put the April-to-March rule
  // in two places, and the day they drifted apart the tiles and the rows beneath them would be
  // totalling different spans while looking like one screen.
  const fetchSummary = useCallback(
    (token: string | undefined) => api.donationPeriodSummary(period, financialYear, token),
    [period, financialYear]
  );
  const { data: summary, error: summaryError } = useAuthedQuery(fetchSummary);
  // Named apart from the global `window`, which it would otherwise shadow for the whole component.
  const periodWindow = summary?.window ?? null;
  const years = summary?.financialYearsWithGifts ?? [];

  const fetchLedger = useCallback(
    (token: string | undefined) =>
      periodWindow
        ? api.donationLedger({ from: periodWindow.from, to: periodWindow.to, type: type || undefined }, token)
        : Promise.resolve(null),
    [periodWindow?.from, periodWindow?.to, type]
  );
  const { data, error, loading: rowsLoading } = useAuthedQuery(fetchLedger);
  const rows = data ?? [];
  // Until the window is known there is nothing to fetch and nothing true to say, so the list waits
  // rather than flashing "no donations yet" at somebody whose ledger is merely still loading.
  const loading = rowsLoading || (!periodWindow && !summaryError);
  // Either query failing leaves the screen unable to say anything true, so either is shown.
  const failure = summaryError ?? error;

  // A window that ended before today is closed and can no longer move; one that includes today is
  // still filling up. The tiles word their comparison differently for each.
  const closed = periodWindow != null && periodWindow.to < todayIso();

  /** Switching period drops any chosen year, except when Another year is what was chosen. */
  function choosePeriod(next: LedgerPeriodKind) {
    // The interesting "other" year is almost always the one just finished, so it is the default.
    // years arrives newest-first, and its second entry is therefore the last closed year.
    go({ period: next, fy: next === "YEAR" ? (years[1] ?? years[0] ?? null) : null }, "push");
  }

  // The file is fetched with the token and handed over as a blob. A plain link cannot carry an
  // Authorization header, so the old one answered every click with a 401 error page.
  async function exportCsv() {
    setExporting(true);
    setExportError(null);
    try {
      const { blob, filename } = await api.exportLedger(
        { from: periodWindow?.from, to: periodWindow?.to, type: type || undefined },
        await getToken()
      );
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch (e) {
      setExportError(toApiError(e, "We couldn’t export the donations."));
    } finally {
      setExporting(false);
    }
  }

  return (
    <section aria-labelledby="ledger-heading">
      <header className="mb-4 flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 id="ledger-heading" className="text-lg">Every gift received</h2>
        </div>
        <button
          type="button"
          onClick={exportCsv}
          disabled={exporting}
          className="min-h-touch rounded border border-hairline px-5 py-2 text-sm hover:bg-sunken disabled:opacity-60"
        >
          {exporting ? "Preparing…" : "Export CSV"}
        </button>
      </header>

      {exportError && <div className="mb-4"><ErrorNotice error={exportError} /></div>}

      <div className="mb-4 flex flex-wrap items-center gap-3">
        <SegmentedControl
          label="Period"
          value={period}
          onChange={choosePeriod}
          // A temple in its first financial year has no other year to offer, so the segment that
          // would open an empty picker is not shown at all.
          options={years.length > 1 ? PERIODS : PERIODS.filter((p) => p.value !== "YEAR")}
        />
        {period === "YEAR" && (
          <label className="text-sm text-ink-secondary">
            <span className="font-medium text-ink">Year</span>
            <select
              value={financialYear ?? ""}
              onChange={(e) => go({ fy: Number(e.target.value) }, "push")}
              className="ml-2 min-h-touch rounded border border-hairline bg-canvas px-3"
            >
              {years.map((y) => (
                <option key={y} value={y}>{financialYearLabel(y)}</option>
              ))}
            </select>
          </label>
        )}
        {periodWindow && (
          // Said once, here, rather than repeated on five tiles: the figures, the rows and the CSV
          // all cover exactly this span, and an accountant's first question is which one it is.
          <p className="text-sm text-ink-muted">
            {dayMonthYear(periodWindow.from)} to {dayMonthYear(periodWindow.to)}
          </p>
        )}
      </div>

      {summary && (
        // The page's own tiles rather than the design system's StatTile: five of these sit across a
        // row here, where Today shows four across a wider column, and StatTile's larger figure and
        // padding wrap a lakh figure onto two lines at this width.
        <div className="mb-6 grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-5">
          {CATEGORIES.map((cat) => (
            <div key={cat} className="rounded-lg bg-raised px-4 py-3">
              <p className="text-xs text-ink-muted">{CATEGORY_LABEL[cat]}</p>
              <p className="mt-1 text-lg font-medium tabular-nums">
                {money(summary.byCategory[cat]?.total ?? 0, "INR")}
              </p>
              <p className="mt-1 text-xs text-ink-muted">
                {comparisonNote(summary.byCategory[cat], summary.hasPriorYear, closed)}
              </p>
            </div>
          ))}
        </div>
      )}

      <div className="mb-4">
        <label className="text-sm text-ink-secondary">
          <span className="font-medium text-ink">Type</span>
          <select value={type} onChange={(e) => go({ type: e.target.value }, "replace")} className="ml-2 min-h-touch rounded border border-hairline bg-canvas px-3">
            <option value="">All</option>
            {CATEGORIES.map((cat) => (
              <option key={cat} value={cat}>{CATEGORY_LABEL[cat]}</option>
            ))}
          </select>
        </label>
      </div>

      {loading ? (
        <Loading label="Loading donations…" />
      ) : failure ? (
        <ErrorNotice error={failure} />
      ) : rows.length === 0 ? (
        <div className="rounded-lg bg-raised px-6 py-14 text-center">
          <p className="text-lg">No donations in this period</p>
          {/* An empty list is now ambiguous — it can mean a quiet week rather than a new temple —
              so the message names the window instead of implying nothing has ever arrived. */}
          <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
            Nothing was given between {periodWindow ? dayMonthYear(periodWindow.from) : "these dates"} and{" "}
            {periodWindow ? dayMonthYear(periodWindow.to) : "these dates"}. Try a longer period.
          </p>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-lg bg-raised">
          <table className={TABLE}>
            <thead className={THEAD}>
              <tr>
                <th className={TH_TEXT}>Date</th>
                <th className={TH_TEXT}>Type</th>
                <th className={`${TH_TEXT} ${WRAP}`}>Donor</th>
                <th className={TH_NUM}>Amount</th>
                <th className={TH_TEXT}>Mode</th>
                <th className={`${TH_TEXT} ${WRAP}`}>Linked to</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id} className={TR}>
                  {/* Written out, not the stored "2026-08-16": a ledger is read by somebody who
                      knows what day a gift arrived, not by a database. */}
                  <td className={`${TD_DATE} text-ink-secondary`}>{dayMonthYear(r.donatedOn)}</td>
                  <td className={TD_TEXT}>{CATEGORY_LABEL[r.category] ?? r.category}</td>
                  <td className={`${TD_TEXT} ${WRAP}`}>{r.donorDisplay}</td>
                  <td className={TD_NUM}>{money(r.amountInr, "INR")}</td>
                  <td className={`${TD_TEXT} text-ink-secondary`}>{r.paymentMode ? (PAYMENT_MODE_LABEL[r.paymentMode] ?? r.paymentMode) : "—"}</td>
                  <td className={`${TD_TEXT} ${WRAP} text-ink-secondary`}>{r.linkedTo ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
