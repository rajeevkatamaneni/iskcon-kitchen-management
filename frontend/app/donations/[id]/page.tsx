"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useParams } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Loading } from "@/components/Loading";
import { Badge } from "@/components/ds/Badge";
import { Button } from "@/components/ds/Button";
import { Card } from "@/components/ds/Card";
import { InlineNotice } from "@/components/ds/InlineNotice";
import {
  api,
  toApiError,
  type ApiError,
  type DonationDetail,
  type DocumentView,
  type LedgerRow,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { dateWithYear, money, moment } from "@/lib/format";
import {
  TABLE,
  TD_DATE,
  TD_NUM,
  TD_TEXT,
  THEAD,
  TH_DATE,
  TH_NUM,
  TH_TEXT,
  TR,
  WRAP,
} from "@/components/ds/table";

/**
 * One gift: what it was, the receipt for it, and what else this donor has given (T-110).
 *
 * <p><strong>One screen and not two, on Rajeev's instruction.</strong> The 80G receipt needed a
 * detail screen to hang on, and `donorHistory` takes a donation id and answers "what else has this
 * person given us". Somebody looking at a gift and deciding whether to receipt it is asking both of
 * those at once, so they are one page. Hanging the history off a donor record was the third option
 * and the largest, and it was refused for a plain reason: donors are not first-class records here.
 *
 * <p><strong>Everything on it is behind `VIEW_DONATIONS`, which is the Temple Admin's alone.</strong>
 * The donations list admits three roles because recording a gift at the gate is `MANAGE_INVENTORY`
 * work; this screen shows a donor's address and says whether their PAN is held, so it admits one.
 */
export default function DonationPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <DonationView />
    </RequireRole>
  );
}

/** How the money arrived, in words. The one label map for it lives on the list; this is the second
 * reader of the same stored values, and both must say the same word for the same row. */
const PAYMENT_MODE_LABEL: Record<string, string> = {
  CASH: "Cash",
  UPI: "UPI",
  BANK_TRANSFER: "Bank transfer",
  CHEQUE: "Cheque",
  CARD: "Card",
  NETBANKING: "Net banking",
  WALLET: "Wallet",
};

/** What kind of giving this was, in the same words the ledger's tiles use. */
const CATEGORY_LABEL: Record<string, string> = {
  ONE_TIME: "One-time",
  WISHLIST: "Wish list",
  MANUAL: "Manual",
  IN_KIND: "In-kind",
};

/**
 * How a payment went — the other question `status` answers, and never the same one as `voided`.
 *
 * <p>A gift can perfectly well have completed and then been struck. The two facts are shown
 * separately here for the reason the ledger gives: folding them makes one column answer two
 * questions and lose whichever was asked second.
 */
const STATUS_LABEL: Record<string, string> = {
  PENDING: "Payment not finished",
  COMPLETED: "Paid",
  FAILED: "Payment failed",
  EXPIRED: "Payment abandoned",
};

/**
 * How often the screen asks again whether the receipt PDF has been written.
 *
 * <p>Four seconds. The one measurement we have is a staging receipt that was ready twenty-four
 * seconds after it was issued, so this asks about six times over a normal wait — often enough that
 * the button lights up while the person is still looking at it, and far short of anything that
 * would be described as hammering. It only runs while the tab is in front (see the effect in
 * {@link TheReceipt}), so an office leaving this page open costs nothing.
 */
const RECEIPT_POLL_MS = 4000;

/**
 * The gifts a person would call good.
 *
 * <p>Rajeev, on what this screen should open showing: *"By default, only show donations that were
 * good. Give them a toggle to unhide the bad and declined ones too."* Good means the money arrived
 * and the row still stands — so a completed gift that was later struck is not good, which is the
 * case the word "declined" does not obviously cover and the one most worth getting right.
 */
function isGoodGift(row: LedgerRow): boolean {
  return row.status === "COMPLETED" && !row.voided;
}

function DonationView() {
  const params = useParams<{ id: string }>();
  const id = params.id;

  const { data, error, loading, reload } = useAuthedQuery(
    useCallback((token: string | undefined) => api.donation(id, token), [id])
  );
  const receipt = useAuthedQuery(
    useCallback((token: string | undefined) => api.donationReceipt(id, token), [id])
  );
  const history = useAuthedQuery(
    useCallback((token: string | undefined) => api.donorHistory(id, token), [id])
  );

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/donations" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto grid max-w-content gap-6">
          {loading ? (
            <Loading label="Loading the donation…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : data ? (
            <>
              <DonationHeader donation={data} />
              <TheGift donation={data} />
              <TheReceipt
                donation={data}
                document={receipt.data ?? null}
                reload={() => {
                  reload();
                  receipt.reload();
                }}
                recheckDocument={receipt.reload}
              />
              <WhatElseTheyGave
                donationId={id}
                rows={history.data ?? []}
                loading={history.loading}
                failure={history.error}
              />
            </>
          ) : null}
        </div>
      </main>
    </div>
  );
}

function DonationHeader({ donation }: { donation: DonationDetail }) {
  const who = donation.anonymous
    ? "A gift given anonymously"
    : donation.donorName ?? "A gift with no donor named";
  return (
    <header className="flex flex-wrap items-start justify-between gap-4">
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-semibold text-ink">{who}</h1>
          {donation.voided && <Badge>Voided</Badge>}
        </div>
        <p className="mt-1 text-ink-secondary">
          {money(donation.amountInr, donation.currency ?? "INR")} on{" "}
          {dateWithYear(donation.donatedOn)}
        </p>
      </div>
    </header>
  );
}

/** One labelled fact, left out entirely when there is nothing to put in it. */
function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  if (children === null || children === undefined || children === "") return null;
  return (
    <div>
      <dt className="text-sm text-ink-muted">{label}</dt>
      <dd className="mt-0.5 text-ink">{children}</dd>
    </div>
  );
}

function TheGift({ donation }: { donation: DonationDetail }) {
  return (
    <Card title="The gift">
      {donation.voided && (
        <div className="mb-4">
          <InlineNotice tone="warning" title="This gift was struck as wrongly recorded">
            {donation.voidReason ??
              "No reason was recorded, which should not be possible. Ask whoever struck it."}{" "}
            It stays in the ledger, marked, and is out of every figure the temple reports under 80G.
          </InlineNotice>
        </div>
      )}

      <dl className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <Fact label="Amount">{money(donation.amountInr, donation.currency ?? "INR")}</Fact>
        <Fact label="Given on">{dateWithYear(donation.donatedOn)}</Fact>
        <Fact label="Kind of giving">
          {CATEGORY_LABEL[donation.category] ?? donation.category}
        </Fact>
        <Fact label="Payment">{STATUS_LABEL[donation.status] ?? donation.status}</Fact>
        <Fact label="Received as">
          {donation.paymentMode
            ? PAYMENT_MODE_LABEL[donation.paymentMode] ?? donation.paymentMode
            : null}
        </Fact>
        <Fact label="Payment reference">{donation.providerRef}</Fact>
        <Fact label="Towards">{donation.linkedTo}</Fact>
        <Fact label="Phone">{donation.donorPhone}</Fact>
        <Fact label="Email">{donation.donorEmail}</Fact>
        <Fact label="Address">{donation.donorAddress}</Fact>
        <Fact label="PAN">
          {donation.hasPan ? "Held, for the 80G receipt" : null}
        </Fact>
        <Fact label="Thanked">
          {donation.acknowledgedAt ? moment(donation.acknowledgedAt) : null}
        </Fact>
      </dl>

      {donation.notes && <p className="mt-4 text-sm text-ink-secondary">{donation.notes}</p>}

      {donation.wishlistApplied !== null && donation.amountInr !== null && (
        // Said here and nowhere near the receipt, deliberately. A gift that no longer fitted the
        // item it named finished that item and the rest went to general funds — but the donor made
        // one payment, and that payment is what the receipt reports. Somebody comparing the receipt
        // against the item's progress will find two different figures, and this is the sentence
        // that explains why before they go looking for a fault.
        <p className="mt-4 text-sm text-ink-secondary">
          {money(donation.wishlistApplied, donation.currency ?? "INR")} of this payment was all the
          wish-list item still needed. The rest went to the general kitchen fund. The receipt reports
          the whole payment, because that is what the donor gave.
        </p>
      )}
    </Card>
  );
}

/**
 * The receipt: issue it, download it, send it again.
 *
 * <p>The three controls are on one card because they are one subject, and the card says what the
 * receipt can honestly claim before anybody presses anything. A temple with no 80G registration, or
 * a gift of goods at a temple that has one, gets a receipt that acknowledges the gift and says
 * plainly that it supports no deduction — because that is what is true, and a donor who finds out
 * otherwise finds out at assessment.
 */
function TheReceipt({
  donation,
  document: existing,
  reload,
  recheckDocument,
}: {
  donation: DonationDetail;
  document: DocumentView | null;
  reload: () => void;
  /** Re-reads the receipt document alone, for the wait below. Not `reload`, which re-reads the gift too. */
  recheckDocument: () => void;
}) {
  const { getToken } = useAuth();
  const [busy, setBusy] = useState<string | null>(null);
  const [failure, setFailure] = useState<ApiError | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const issued = donation.receiptNumber !== null;
  const ready = existing?.status === "READY";
  const reachable = !donation.anonymous && (donation.donorPhone !== null || donation.donorEmail !== null);

  /**
   * While the PDF is being written, ask again — the screen used to wait for ever.
   *
   * <p>The defect, driven on staging on 2026-09-09: pressing *Issue the receipt* left Download
   * disabled under "The receipt is being prepared", and it stayed that way until somebody reloaded
   * the page by hand. The document was `READY` twenty-four seconds later. Issuing and rendering are
   * deliberately two steps — the receipt number is allocated in the transaction, the PDF is written
   * afterwards — so the answer that comes back from `issue()` is genuinely `PENDING`, and the only
   * thing wrong was that nothing ever asked a second time.
   *
   * <p>Two stopping conditions, and no third: it stops the moment the document is ready, and it
   * does not run while the tab is in the background. A receipt that is being prepared is something
   * somebody is standing and waiting for; a tab left open on another monitor is not, and polling it
   * would spend the temple's request budget on a screen nobody is looking at. `visibilitychange`
   * covers both switching tabs and locking the phone.
   *
   * <p>`recheckDocument` is deliberately not in the dependency list: it is rebuilt on every render
   * of the parent, so depending on it would tear the interval down and start a new one several
   * times a second. The ref holds the current one without the effect noticing.
   */
  const recheck = useRef(recheckDocument);
  recheck.current = recheckDocument;
  const waitingForThePdf = issued && !ready && !donation.voided;
  useEffect(() => {
    if (!waitingForThePdf) return;
    let timer: ReturnType<typeof setInterval> | null = null;
    const stop = () => {
      if (timer !== null) {
        clearInterval(timer);
        timer = null;
      }
    };
    const start = () => {
      if (timer === null) timer = setInterval(() => recheck.current(), RECEIPT_POLL_MS);
    };
    const onVisibilityChange = () => (window.document.hidden ? stop() : start());
    if (!window.document.hidden) start();
    window.document.addEventListener("visibilitychange", onVisibilityChange);
    return () => {
      stop();
      window.document.removeEventListener("visibilitychange", onVisibilityChange);
    };
  }, [waitingForThePdf]);

  async function issue() {
    setBusy("issuing");
    setFailure(null);
    setNotice(null);
    try {
      const result = await api.issueDonationReceipt(donation.id, await getToken());
      setNotice(`Receipt ${result.receiptNumber} issued.`);
      reload();
    } catch (e) {
      setFailure(toApiError(e, "We couldn’t issue that receipt."));
    } finally {
      setBusy(null);
    }
  }

  async function download() {
    setBusy("downloading");
    setFailure(null);
    try {
      const blob = await api.downloadDonationReceipt(donation.id, await getToken());
      const url = URL.createObjectURL(blob);
      const a = window.document.createElement("a");
      a.href = url;
      a.download = `receipt-${donation.receiptNumber}.pdf`;
      window.document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch (e) {
      setFailure(toApiError(e, "We couldn’t download that receipt."));
    } finally {
      setBusy(null);
    }
  }

  async function send() {
    setBusy("sending");
    setFailure(null);
    setNotice(null);
    try {
      const result = await api.sendDonationReceipt(donation.id, await getToken());
      setNotice(
        result.sent
          ? "Sent to the donor. The same receipt, not a new one."
          : "There is nobody to send it to. This gift has no phone number and no email address."
      );
      reload();
    } catch (e) {
      setFailure(toApiError(e, "We couldn’t send that receipt."));
    } finally {
      setBusy(null);
    }
  }

  return (
    <Card
      title="The receipt"
      meta={
        issued && donation.receiptIssuedAt
          ? `${donation.receiptNumber}, issued ${moment(donation.receiptIssuedAt)}`
          : undefined
      }
    >
      <p className="max-w-prose text-ink-secondary">{claim(donation)}</p>

      {donation.voided ? (
        // Withheld rather than offered and refused, the same call the ledger makes about the Void
        // button on a gift already struck. The server refuses this too — a stale screen must not be
        // able to issue one — but a person who reads "Voided" at the top of this page and then
        // presses a button to be told so has been made to do work that was thrown away.
        <p className="mt-4 max-w-prose text-ink-secondary">
          No receipt can be issued for a gift the temple has struck. If the money really did arrive,
          record it again and issue the receipt against that.
        </p>
      ) : (
        <>
          {notice && (
            <div className="mt-4">
              <InlineNotice tone="success" autoDismiss>
                {notice}
              </InlineNotice>
            </div>
          )}
          {failure && (
            <div className="mt-4">
              <ErrorNotice error={failure} />
            </div>
          )}

          <div className="mt-5 flex flex-wrap items-center gap-3">
            {!issued && (
              <Button type="button" onClick={issue} busy={busy === "issuing"} disabled={busy !== null}>
                Issue the receipt
              </Button>
            )}
            {issued && (
              <Button
                type="button"
                onClick={download}
                busy={busy === "downloading"}
                disabled={busy !== null || !ready}
              >
                Download the receipt
              </Button>
            )}
            {issued && (
              <Button
                type="button"
                variant="secondary"
                onClick={send}
                busy={busy === "sending"}
                disabled={busy !== null || !reachable}
              >
                Send it to the donor
              </Button>
            )}
          </div>

          {issued && !ready && (
            <p className="mt-3 text-sm text-ink-secondary">
              The receipt is being prepared. It keeps this number whether you download it today or
              next year.
            </p>
          )}
          {issued && !reachable && (
            <p className="mt-3 text-sm text-ink-secondary">
              There is nobody to send it to. This gift carries no phone number and no email address,
              so the receipt has to be handed over at the temple.
            </p>
          )}
        </>
      )}
    </Card>
  );
}

/**
 * What this receipt can honestly say about tax, in the reader's words rather than the enum's.
 *
 * <p>Three answers, from two facts, and the screen has to give the same one the printed sheet will.
 * A person who issues a receipt expecting an 80G document and gets an acknowledgement of goods
 * should learn that here, before they hand it to somebody.
 */
function claim(donation: DonationDetail): string {
  if (!donation.temple80gApproved) {
    return "This temple has no 80G registration recorded, so the receipt acknowledges the gift and says plainly that it supports no tax deduction.";
  }
  if (donation.type === "IN_KIND") {
    return "A gift in kind does not qualify for deduction under Section 80G — only money does. The receipt acknowledges the goods and the value the temple put on them, and says so.";
  }
  return "This temple is registered under Section 80G, so the receipt states that the gift is eligible for deduction under it.";
}

/**
 * What else this donor has given.
 *
 * <p><strong>The copy at the top of this card is not optional, and it must not be dropped.</strong>
 * A donor is not an account here. The server matches these rows on donor account, PAN fingerprint,
 * phone or email — so "this donor" means "rows that look like the same person". That is fine for a
 * screen a human reads and wrong to present as a legal identity, and it is sitting next to a tax
 * document, which is exactly where an implied certainty would eventually be quoted back at
 * somebody.
 *
 * <p><strong>Good gifts by default, everything on a toggle.</strong> Nothing is deleted and nothing
 * is hidden permanently — the same pattern this product already uses for a struck record, which
 * stays in the ledger with the mark on it. The server sends every row whatever became of it, so the
 * toggle is a filter over rows already in hand: instant, and honest about the fact that the hiding
 * is only on this screen.
 */
function WhatElseTheyGave({
  donationId,
  rows,
  loading,
  failure,
}: {
  donationId: string;
  rows: LedgerRow[];
  loading: boolean;
  failure: ApiError | null;
}) {
  const [showAll, setShowAll] = useState(false);

  const good = useMemo(() => rows.filter(isGoodGift), [rows]);
  const shown = showAll ? rows : good;
  const hidden = rows.length - good.length;

  return (
    <Card title="What else this donor has given">
      <p className="max-w-prose text-ink-secondary">
        These are gifts that look like they came from the same person — matched on donor account,
        PAN, phone or email. It is a likeness, not a confirmed identity, so do not treat this list as
        proof of who gave what.
      </p>

      {hidden > 0 && (
        <label className="mt-4 flex items-center gap-2 text-sm text-ink-secondary">
          <input
            type="checkbox"
            checked={showAll}
            onChange={(e) => setShowAll(e.target.checked)}
            className="h-4 w-4 accent-accent"
          />
          <span>
            Show the {hidden} that failed, ran out of time, or were struck
          </span>
        </label>
      )}

      {loading ? (
        <div className="mt-4">
          <Loading label="Loading this donor’s other gifts…" />
        </div>
      ) : failure ? (
        <div className="mt-4">
          <ErrorNotice error={failure} />
        </div>
      ) : shown.length === 0 ? (
        <p className="mt-4 text-ink-secondary">
          {rows.length === 0
            ? "Nothing else here looks like it came from this person. A gift given anonymously, or one with no contact details, cannot be matched to any other."
            : "Every other gift that looks like this person’s either failed, ran out of time, or was struck. Turn the box above on to see them."}
        </p>
      ) : (
        <div className="mt-4 table-wrap overflow-x-auto">
          <table className={TABLE}>
            <thead className={THEAD}>
              <tr>
                <th className={TH_DATE}>Date</th>
                <th className={TH_NUM}>Amount</th>
                <th className={TH_TEXT}>Kind</th>
                <th className={`${TH_TEXT} ${WRAP}`}>Towards</th>
                <th className={TH_TEXT}>Payment</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((r) => (
                <tr key={r.id} className={TR}>
                  <td className={`${TD_DATE} text-ink-secondary`}>
                    {dateWithYear(r.donatedOn)}
                    {/* The gift being read is in this list too, because it is one of this person's
                        gifts and leaving it out would make the list disagree with the total above
                        it. Marked, so nobody has to work out which row is the one they opened. */}
                    {r.id === donationId && (
                      <span className="ml-2 text-xs text-ink-muted">This one</span>
                    )}
                  </td>
                  <td className={`${TD_NUM} ${r.voided ? "text-ink-muted line-through" : ""}`}>
                    {money(r.amountInr, r.currency ?? "INR")}
                  </td>
                  <td className={TD_TEXT}>{CATEGORY_LABEL[r.category] ?? r.category}</td>
                  <td className={`${TD_TEXT} ${WRAP} text-ink-secondary`}>{r.linkedTo ?? "—"}</td>
                  <td className={`${TD_TEXT} text-ink-secondary`}>
                    {r.voided ? <Badge>Voided</Badge> : STATUS_LABEL[r.status] ?? r.status}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}
