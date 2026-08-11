"use client";

import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { TEMPLE_NAV } from "@/lib/nav";
import { api } from "@/lib/api";
import { useAuthedQuery } from "@/lib/use-authed-query";

const CATEGORY_LABEL: Record<string, string> = {
  ONE_TIME: "One-time",
  RECURRING: "Recurring",
  WISHLIST: "Wish list",
  IN_KIND: "In-kind",
};

export default function LedgerPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <LedgerView />
    </RequireRole>
  );
}

function LedgerView() {
  const [type, setType] = useState("");
  const fetchLedger = useCallback(
    (token: string | undefined) => api.donationLedger({ type: type || undefined }, token),
    [type]
  );
  const { data, error, loading } = useAuthedQuery(fetchLedger);
  const { data: summary } = useAuthedQuery(useCallback((t: string | undefined) => api.donationSummary(t), []));
  const rows = data ?? [];

  return (
    <div className="flex min-h-screen">
      <Sidebar templeName="Your temple" items={TEMPLE_NAV} activeHref="/ledger" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Donations ledger</h1>
              <p className="mt-1 text-ink-secondary">Every gift — online, recurring, wish-list, in-kind — in one place.</p>
            </div>
            <a href={api.ledgerExportUrl()} className="min-h-touch rounded border border-hairline px-5 py-2 text-sm hover:bg-sunken">
              Export CSV
            </a>
          </header>

          {summary && (
            <section className="mb-6 grid grid-cols-2 gap-4 sm:grid-cols-4">
              {["ONE_TIME", "RECURRING", "WISHLIST", "IN_KIND"].map((cat) => (
                <div key={cat} className="rounded-lg bg-raised px-4 py-3">
                  <p className="text-xs text-ink-muted">{CATEGORY_LABEL[cat]} · FY to date</p>
                  <p className="mt-1 text-lg font-medium tabular-nums">
                    ₹{summary.financialYearToDateByCategory[cat] ?? 0}
                  </p>
                </div>
              ))}
            </section>
          )}

          <div className="mb-4">
            <label className="text-sm text-ink-secondary">
              Type{" "}
              <select value={type} onChange={(e) => setType(e.target.value)} className="ml-1 min-h-touch rounded border border-hairline bg-canvas px-3">
                <option value="">All</option>
                <option value="ONE_TIME">One-time</option>
                <option value="RECURRING">Recurring</option>
                <option value="WISHLIST">Wish list</option>
                <option value="IN_KIND">In-kind</option>
              </select>
            </label>
          </div>

          {loading ? (
            <p className="text-ink-secondary">Loading ledger…</p>
          ) : error ? (
            <ErrorNotice error={error} />
          ) : rows.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">No donations yet</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">Completed gifts appear here as they come in.</p>
            </div>
          ) : (
            <div className="overflow-x-auto rounded-lg bg-raised">
              <table className="w-full text-left">
                <thead className="bg-sunken text-sm text-ink-secondary">
                  <tr>
                    <th className="px-5 py-3 font-medium">Date</th>
                    <th className="px-5 py-3 font-medium">Type</th>
                    <th className="px-5 py-3 font-medium">Donor</th>
                    <th className="px-5 py-3 font-medium text-right">Amount</th>
                    <th className="px-5 py-3 font-medium">Mode</th>
                    <th className="px-5 py-3 font-medium">Linked to</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.id} className="border-t border-hairline align-middle">
                      <td className="px-5 py-3 tabular-nums text-ink-secondary">{r.donatedOn}</td>
                      <td className="px-5 py-3">{CATEGORY_LABEL[r.category] ?? r.category}</td>
                      <td className="px-5 py-3">{r.donorDisplay}</td>
                      <td className="px-5 py-3 text-right tabular-nums">₹{r.amountInr ?? "—"}</td>
                      <td className="px-5 py-3 text-ink-secondary">{r.paymentMode ?? "—"}</td>
                      <td className="px-5 py-3 text-ink-secondary">{r.linkedTo ?? "—"}</td>
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
