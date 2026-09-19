"use client";

import { useCallback, useState } from "react";
import { Button } from "@/components/ds/Button";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Loading } from "@/components/Loading";
import { api, toApiError, type ApiError, type MyDonation } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { dateWithYear, money } from "@/lib/format";

/**
 * A devotee's own gifts, and the receipt for each one the temple has issued (T-179).
 *
 * <p>Rajeev, 2026-09-13: the admin's Send receipt is the safety net, "Not the default. The default
 * should be, the users should be able to see all the donations they made and receipts for each one
 * of those donations that were sucessful." So this is the default: nobody has to ask the office.
 *
 * <p><b>What the server decides and this page does not.</b> Which gifts are yours — your account,
 * or a counter gift on a phone or email you have proven — and which count as successful are both
 * settled in `MyDonationsService`, never here. The page draws exactly what it is sent. That matters
 * because the receipt for an 80G gift prints a PAN: a screen that filtered a wider list for itself
 * would be a screen that had already been handed somebody else's gifts.
 *
 * <p><b>Volunteers only</b>, matching `VIEW_OWN_DONATIONS` and the menu row in `nav.ts`. The guard is
 * a courtesy — the API refuses everybody else with 403 — but a refused page is still better than a
 * list that fails to load.
 */
export default function MyDonationsPage() {
  return (
    <RequireRole roles={["VOLUNTEER"]}>
      <MyDonationsView />
    </RequireRole>
  );
}

function MyDonationsView() {
  const { getToken } = useAuth();
  const gifts = useAuthedQuery(useCallback((t: string | undefined) => api.myDonations(t), []));

  const [downloading, setDownloading] = useState<string | null>(null);
  const [failure, setFailure] = useState<ApiError | null>(null);

  async function download(gift: MyDonation) {
    setDownloading(gift.id);
    setFailure(null);
    try {
      const blob = await api.downloadMyDonationReceipt(gift.id, await getToken());
      // Handed to the browser from memory, never a plain link: the file sits behind the person's
      // token, and a link would not carry it. The same steps the donation screen takes.
      const url = URL.createObjectURL(blob);
      const a = window.document.createElement("a");
      a.href = url;
      a.download = `receipt-${gift.receiptNumber}.pdf`;
      window.document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch (e) {
      setFailure(toApiError(e, "We couldn’t download that receipt."));
    } finally {
      setDownloading(null);
    }
  }

  const list = gifts.data ?? [];

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/my-donations" />
      <main className="min-w-0 flex-1 px-4 py-10 sm:px-8">
        <div className="mx-auto max-w-content">
          <header className="mb-6">
            <h1>My donations</h1>
          </header>

          {failure && <div className="mb-6"><ErrorNotice error={failure} /></div>}

          {gifts.loading ? (
            <Loading />
          ) : gifts.error ? (
            <ErrorNotice error={gifts.error} />
          ) : list.length === 0 ? (
            <div className="card px-6 py-14 text-center">
              <p className="text-ink-secondary">Your gifts to the temple will appear here.</p>
            </div>
          ) : (
            <ul className="space-y-3">
              {list.map((gift) => (
                <li key={gift.id} className="card flex flex-wrap items-center justify-between gap-3 px-5 py-4">
                  <div className="min-w-0">
                    {/* Money leads with the amount, goods with what was given: each is the fact a
                        person recognises their own gift by. */}
                    <p className="font-medium tabular-nums">
                      {gift.kind === "MONEY" ? money(gift.amount, "INR") : gift.description}
                    </p>
                    {gift.kind === "MONEY" && <p className="text-sm text-ink-secondary">{gift.description}</p>}
                    <p className="text-sm text-ink-muted tabular-nums">
                      {dateWithYear(gift.receivedOn)}
                      {gift.receiptNumber && <> · Receipt {gift.receiptNumber}</>}
                    </p>
                  </div>
                  {/* Only where a receipt has been issued. A gift without one has nothing to download,
                      and a button that could only refuse would be the wrong thing to offer. */}
                  {gift.receiptNumber && (
                    <Button variant="secondary" disabled={downloading !== null} onClick={() => download(gift)}>
                      {downloading === gift.id ? "Downloading…" : "Download receipt"}
                    </Button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      </main>
    </div>
  );
}
