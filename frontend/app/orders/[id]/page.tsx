"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useRef, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type CloseOutcome, type GoodsReceiptLineView, type OrderDeliveryScore, type PurchaseOrderLineView, type PurchaseOrderView, type ReturnReason } from "@/lib/api";
import { generateAndDownload } from "@/lib/document-download";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { dateWithYear, money, quantity, unitLabel, templeDay } from "@/lib/format";
import { ALL_LANGUAGES } from "@/lib/languages";
import { statusChip } from "../po-status";
import { BusyPot, Loading } from "@/components/Loading";
import { TABLE, THEAD, TR, TH_TEXT, TH_NUM, TH_ACTIONS, TD_TEXT, TD_NUM, TD_DATE, TD_ACTIONS, WRAP } from "@/components/ds/table";
// The edit form, which this screen and the shopping-list panel both mount — see T-134 and the
// note on the component. `subjectOf` comes with it because the tables below print the same
// subject and two copies of that rule is how one of them comes to print an empty cell.
import { PurchaseOrderEditor, subjectOf, type PurchaseOrderDraft } from "@/components/PurchaseOrderEditor";
import { Button } from "@/components/ds/Button";
import { Badge } from "@/components/ds/Badge";
import { HintedField } from "@/components/ds/InfoHint";

const REJECT_REASONS = ["DAMAGED", "SPOILED", "WRONG_ITEM", "OTHER"];

/**
 * Why goods that were already taken into stock went back to the vendor (T-013).
 *
 * <p>Four of these are the rejection reasons said a day later. The fifth is not: `NOT_DELIVERED` is
 * the quantity keyed wrongly — fifty kilos entered when five arrived — where nothing is physically
 * going back because nothing physically came, and the stock still has to leave the ledger.
 */
const RETURN_REASONS: ReturnReason[] = ["DAMAGED", "SPOILED", "WRONG_ITEM", "NOT_DELIVERED", "OTHER"];

/**
 * A stored reason as a person reads it. Written out rather than printed raw for the reason
 * `design-system.test.ts` checks for: `SPOILED` and `NOT_DELIVERED` are database values, and a
 * column of shouted underscores is not a sentence anybody wrote.
 */
function reasonLabel(reason: string): string {
  return reason.replace(/_/g, " ").toLowerCase();
}

/**
 * A quantity, with the unit agreeing with the number in front of it (T-107).
 *
 * <p>`quantity()` names its unit from `UNIT_LABEL`, which holds one label per unit — so one plastic
 * stool reads "1 pieces" on every screen in the application. `PIECES` is the only unit in that
 * table this can happen to: the other four are a mass or a volume, and "1 Kg" and "1 ml" are what a
 * person says. A count is the only one with a singular to get wrong.
 *
 * <p>The number is compared rather than the rendered string, because the rendered string is not
 * this file's to predict — `quantity()` promotes 0.6 Kg to 600 gm and may promote more later.
 * `PIECES` is the one unit it can never promote (there is no larger sibling to count into), so a
 * value of exactly 1 in that unit is the whole of the case.
 *
 * <p>Local to this screen deliberately, and it should not stay that way. The defect is in the
 * shared helper and every screen that prints a count carries it — stock, receipts, recipes. T-107's
 * path contract is this page and its three tests, and editing `lib/format.ts` would be editing a
 * file read by roughly half the application without being able to run its tests. Raised in
 * `docs/work/proof/T-107.md` so the real fix can be scheduled as its own task.
 */
function quantitySaid(value: number | null | undefined, unit: string): string {
  if (value === 1 && (unit ?? "").toUpperCase() === "PIECES") return "1 piece";
  return quantity(value, unit);
}

/**
 * "2 days’ notice" — a lead time in the words a person would say it in.
 *
 * <p>Zero is a real answer and means cash and carry: the shop somebody walks into, where the goods
 * come back in the same van as the person. It must not read as "no notice at all", which is what
 * "0 days’ notice" sounds like.
 */
function leadTimeSaid(days: number | null | undefined): string {
  if (days == null) return "the notice they asked for";
  if (days === 0) return "same-day collection";
  return days === 1 ? "1 day’s notice" : `${days} days’ notice`;
}

/**
 * The three endings, in the words a person chooses between (T-142, D-26).
 *
 * <p>Rajeev's two real scenarios and the third that is neither. The labels are what he described,
 * not the enum's names: the one who went silent and never rang back, and the one who apologised,
 * blamed the weather, offered a discount next time and said buy it elsewhere.
 */
const CLOSE_OUTCOMES: { value: CloseOutcome; label: string; effect: string }[] = [
  {
    value: "VENDOR_LET_US_DOWN",
    label: "The vendor let us down",
    effect:
      "Recorded against them. The order is scored on what actually arrived, as every order is — this says the temple holds them responsible for the rest.",
  },
  {
    value: "SHORTFALL_EXCUSED",
    label: "They fell short but made it right",
    effect:
      "This order leaves their delivery record entirely — both the on-time figure and the fill rate — and the count of orders left out is shown on the scorecard.",
  },
  {
    value: "AS_COMPUTED",
    label: "Neither — score it as it stands",
    effect: "Nothing is recorded for or against them. The figures stand as the deliveries made them.",
  },
];

/**
 * What this order scored, shown to the person closing it and editable nowhere (T-142, D-26).
 *
 * <p>Rajeev proposed a control beside this number that let an admin adjust it — "there is SO MUCH
 * human interaction that no machine or app can capture" — and then ruled against his own proposal:
 * "Let us not let the admin adjust the score. Just show it to them." So this is a readout. There is
 * no input, no stepper and no override anywhere near it, and the sentence underneath says so out
 * loud, because a number on a form that cannot be changed should say that rather than leave
 * somebody hunting for the box.
 *
 * <p>Null percent is not a failure: an order with no needed-by date has nothing to be late against,
 * which is the same choice the scorecard makes rather than scoring a silent hundred per cent.
 */
function deliveryScoreLine(score: OrderDeliveryScore | null | undefined, vendorName: string) {
  if (!score || score.percent == null) {
    return (
      <p className="max-w-prose text-sm text-ink-secondary">
        There is no needed-by date on this order, so there is nothing to be late against and it is
        outside {vendorName}’s on-time figure either way.
      </p>
    );
  }
  return (
    <p className="max-w-prose text-sm text-ink-secondary">
      <span className="text-lg font-medium tabular-nums text-ink">{score.percent}%</span>{" "}
      of this order was there in time — {score.itemsOnTime} of {score.itemsScored}{" "}
      {score.itemsScored === 1 ? "item" : "items"}. This is computed from what actually arrived
      against what was ordered, and it is the same figure {vendorName}’s scorecard reports. It
      cannot be changed here.
    </p>
  );
}

/**
 * Where this order stands against its vendor's promise, as one line under the dates (T-137, D-25).
 *
 * <p>Every fact in it is the server's: `orderBy` is the last day the order could be placed,
 * `orderUrgency` is which of Rajeev's three zones today is in, and `sentAfterLeadTime` is the
 * verdict stamped on the order when it went out. This function chooses words and nothing else — it
 * does no date arithmetic, because the whole of T-137 is that the arithmetic happens once.
 *
 * <p>Null when the vendor has no recorded lead time for anything on this order, or when there is no
 * needed-by date to count back from. That silence is the ruling and not an oversight: with no
 * agreed lead time there is no cutoff, so there is nothing to warn about and nobody to hold to
 * anything.
 */
function leadTimeLine(po: PurchaseOrderView) {
  if (po.sentAt) {
    if (po.sentAfterLeadTime) {
      return (
        <p className="mt-2 flex flex-wrap items-baseline gap-2 text-sm">
          <Badge tone="warning">Sent late</Badge>
          <span className="max-w-prose text-ink-secondary">
            This went out after {po.orderBy ? dateWithYear(po.orderBy) : "the last day it could be ordered"},
            the last day {po.vendorName} could have filled it — they asked for{" "}
            {leadTimeSaid(po.leadTimeDays)}. A late delivery on this order isn’t counted against
            them.
          </span>
        </p>
      );
    }
    // The quiet half of the same fact, and worth printing: it is the evidence that the figure on
    // the vendor's scorecard was measured against what they actually agreed to, on this order,
    // whatever their profile says today.
    return po.leadTimeDays == null ? null : (
      <p className="mt-1 max-w-prose text-sm text-ink-muted">
        Sent within the {leadTimeSaid(po.leadTimeDays)} {po.vendorName} asked for.
      </p>
    );
  }
  if (!po.orderBy || !po.orderUrgency) return null;
  const orderBy = dateWithYear(po.orderBy);
  const asked = `${po.vendorName} asked for ${leadTimeSaid(po.leadTimeDays)}.`;
  if (po.orderUrgency === "TOO_LATE") {
    return (
      <p className="mt-2 flex flex-wrap items-baseline gap-2 text-sm">
        <Badge tone="danger">Past the order-by date</Badge>
        <span className="max-w-prose text-ink-secondary">
          This had to be ordered by {orderBy} to arrive in time. {asked} Sending it now is a favour
          we are asking, and a late delivery on it won’t count against them.
        </span>
      </p>
    );
  }
  if (po.orderUrgency === "ORDER_TODAY") {
    return (
      <p className="mt-2 flex flex-wrap items-baseline gap-2 text-sm">
        <Badge tone="warning">Order today</Badge>
        <span className="max-w-prose text-ink-secondary">
          Today is the last day this can be ordered and still arrive for {po.neededBy ? dateWithYear(po.neededBy) : "the day it is needed"}. {asked}
        </span>
      </p>
    );
  }
  return (
    <p className="mt-1 text-sm tabular-nums text-ink-secondary">
      Order by {orderBy} — {asked}
    </p>
  );
}

export default function PurchaseOrderDetailPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <PurchaseOrderDetailView />
    </RequireRole>
  );
}

function PurchaseOrderDetailView() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const { getToken } = useAuth();

  const fetchPo = useCallback((token: string | undefined) => api.getPurchaseOrder(id, token), [id]);
  const { data, error, loading, reload } = useAuthedQuery(fetchPo);
  const fetchReceipts = useCallback((token: string | undefined) => api.listReceipts(id, token), [id]);
  const { data: receiptsData, reload: reloadReceipts } = useAuthedQuery(fetchReceipts);
  // The ingredient catalogue is for the picker that adds a line to a draft. Fetched with the page
  // rather than when the edit form opens: it is a small, cacheable read, and a screen whose set of
  // queries changes as panels open is the harder thing to reason about.
  const fetchIngredients = useCallback((token: string | undefined) => api.listIngredients(token), []);
  const { data: ingredientsData } = useAuthedQuery(fetchIngredients);

  const [busy, setBusy] = useState(false);
  const [preparingPdf, setPreparingPdf] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [showReceive, setShowReceive] = useState(false);
  // "Vendor Never Delivered this Order" (T-124). Unticked to begin with, and — since T-135 moved
  // the whole cancellation to the foot of the page where it is always open — reset on a successful
  // cancellation rather than when a panel closes, because there is no longer a panel to close.
  const [vendorAbandoned, setVendorAbandoned] = useState(false);
  /**
   * How a part-delivered order is being closed (T-142, D-26), and the sentence that goes with it.
   *
   * <p>It starts on "neither", which is the outcome that says nothing about anybody. The two named
   * ones are claims — one holds the supplier responsible, the other takes an order out of their
   * figures — and a claim must be chosen rather than arrived at by leaving a control alone. Two
   * defects in this application came from boxes that were already ticked.
   */
  const [closeOutcome, setCloseOutcome] = useState<CloseOutcome>("AS_COMPUTED");
  const [closeNote, setCloseNote] = useState("");
  // Null while nobody is returning anything. Non-null names the one receipt line the form is open
  // against: a return is about the sack somebody opened, so one line at a time is the whole
  // interaction and the server takes one line per request for the same reason.
  const [returning, setReturning] = useState<{ receiptId: string; line: GoodsReceiptLineView } | null>(null);
  // "" means the vendor's own preferred language; otherwise an explicit override for print / PDF.
  const [docLanguage, setDocLanguage] = useState("");
  // Whether the person has been told this order is going out after the vendor's agreed lead time
  // and is being offered the chance to send it anyway (T-137, D-25).
  //
  // It is set only by the server's own refusal (KMS-400148), never by this screen working the date
  // out for itself. The whole of T-137 is that one place decides where an order stands against its
  // vendor's promise; a second opinion computed in the browser is exactly how the planner, this
  // screen and the dashboard come to say three different things about one order.
  const [lateSend, setLateSend] = useState(false);
  // Whether the edit form is open, and nothing more. The working copy of the lines and of the
  // needed-by date belongs to PurchaseOrderEditor, which is mounted while this is true and
  // unmounted when it is not — so abandoning an edit costs nothing and there is no second copy of
  // the draft up here to fall out of step with the one being typed into (T-134).
  const [editing, setEditing] = useState(false);

  // The code of the last refusal, for the one caller that has to branch on which refusal it was.
  // A ref and not state: it is read immediately after the await that set it, and a state update
  // would not have landed by then.
  const lastRefusal = useRef<string | null>(null);

  async function run(mutation: (token: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    setActionError(null);
    try {
      await mutation(await getToken());
      reload();
      reloadReceipts();
      return true;
    } catch (e) {
      const refusal = toApiError(e, failure);
      lastRefusal.current = refusal.code;
      setActionError(refusal);
      return false;
    } finally {
      setBusy(false);
    }
  }

  // Generating a sheet ends the same way it does on a recipe: with the file itself. Every version is
  // still kept server-side for the record; this screen just hands over the one that was asked for.
  async function generatePdf() {
    setBusy(true);
    setPreparingPdf(true);
    setActionError(null);
    try {
      const token = await getToken();
      await generateAndDownload({
        request: () => api.requestPurchaseOrderPdf(id, docLanguage || undefined, token),
        status: (documentId) => api.getPurchaseOrderDocument(id, documentId, token),
        download: (documentId) => api.downloadPurchaseOrderDocument(id, documentId, token),
        filename: `${po?.poNumber ?? "purchase-order"}.pdf`,
      });
    } catch (e) {
      setActionError(toApiError(e, "We couldn’t generate that PDF."));
    } finally {
      setPreparingPdf(false);
      setBusy(false);
    }
  }

  async function print() {
    setActionError(null);
    try {
      const res = await fetch(api.purchaseOrderPrintUrl(id, docLanguage || undefined), {
        headers: { Authorization: `Bearer ${await getToken()}` },
      });
      if (!res.ok) throw new Error("print failed");
      const html = await res.text();
      const w = window.open("", "_blank");
      if (w) {
        w.document.write(html);
        w.document.close();
      }
    } catch {
      setActionError(toApiError(null, "We couldn’t open the print view. Download the PDF instead."));
    }
  }

  const po = data?.order;
  const lines = data?.lines ?? [];
  const receipts = receiptsData ?? [];
  const showPrices = lines.some((l) => l.expectedPrice != null);
  const canSend = po?.status === "DRAFT";
  // Only a draft can be changed, and only in its quantities and its lines — never its vendor. An
  // order addressed to somebody else is a different order, so "change the vendor" would be
  // cancel-and-regenerate wearing a disguise; the server refuses it by not accepting a vendor at all.
  const canEdit = po?.status === "DRAFT";
  const canReceive = po?.status === "SENT" || po?.status === "PARTIALLY_RECEIVED";
  /**
   * Whether the order can be called off (T-124, narrowed by T-142/D-26).
   *
   * <p><strong>A part-delivered order is no longer cancellable, and the reason is a number rather
   * than tidiness.</strong> A cancellation leaves the vendor scorecard's live-order predicate
   * entirely, so cancelling this order would erase the 300 kg that actually arrived from the
   * supplier's record — and the "Vendor Never Delivered this Order" tick could then be put against a
   * vendor who demonstrably did deliver. The server refuses it with KMS-400150 whatever this screen
   * does, because a rule that lives only in a form is not a rule.
   *
   * <p>The control disappearing needs no sentence of its own here, which is the one place this
   * screen is allowed to stay silent about a missing control: `canClose` is the exact complement of
   * the status removed from this list, so the panel that vanishes is always replaced, in the same
   * spot, by "Close this order, part delivered" — which says what happens to the goods that came
   * and to the ones that did not.
   */
  const canCancel = po?.status === "DRAFT" || po?.status === "SENT";
  /**
   * Whether this order can be closed part-delivered (T-142, D-26).
   *
   * <p>PARTIALLY_RECEIVED and nothing else, which matches the server. An order nothing arrived
   * against is a cancellation — there is nothing to close and nothing is owed for. A fully received
   * order is finished. Closing exists for the one case neither describes: goods came, not all of
   * them, and somebody has decided the rest never will.
   */
  const canClose = po?.status === "PARTIALLY_RECEIVED";
  /**
   * Whether the outcome being offered says something about the supplier, and so needs a sentence.
   *
   * <p>D-26: anything other than "as computed" requires a sentence. The server enforces it and so
   * does the row underneath it; this is the same rule said on the way in, so that somebody making a
   * permanent statement about a supplier is asked for their reason rather than shown a 400.
   */
  const closeNeedsASentence = closeOutcome !== "AS_COMPUTED";
  /**
   * Send on WhatsApp is offered only where WhatsApp demonstrably works (T-136).
   *
   * <p>Rajeev's ruling, 2026-09-10: the button is shown "only after a message has actually gone
   * through it successfully", not merely configured — and where it does not apply it is **not there
   * at all**, not disabled and not greyed. So this is an `&&` on the render and never a `disabled`.
   *
   * <p>`whatsappEverSent` is a fact about the temple that arrives on this order's own payload,
   * which is the whole point: the screen must not ask `api.whatsappSettings()` for it, because that
   * endpoint is behind `MANAGE_TEMPLE_SETTINGS` and the person raising a purchase order need not
   * hold it. The button would then vanish for a Kitchen Manager whose WhatsApp works perfectly.
   *
   * <p>`=== true` rather than a truthiness check, because the field is optional on the interface
   * (see `PurchaseOrderDetailView` in `lib/api.ts`) and `undefined` must read as "not proven".
   */
  const whatsappWorks = data?.whatsappEverSent === true;
  const canWhatsApp = whatsappWorks
    && (po?.status === "DRAFT" || po?.status === "SENT" || po?.status === "PARTIALLY_RECEIVED");
  /**
   * Whether the "Cancel this purchase order" block at the foot of the page is rendered at all.
   *
   * <p>Gated on there being a purchase-order NUMBER, not on which screen this is (T-135, for
   * T-134). T-134 opens this same edit form as a panel over the shopping list, for an order that
   * does not exist yet — and Rajeev was explicit that the cancel control must not appear there,
   * "because no order exists yet. Show it only when there is a purchase-order number." Written as a
   * question about the data so that the answer is the same wherever the form is rendered; a
   * condition on the route or on a `mode` prop would have to be remembered by the next caller.
   */
  const hasPoNumber = (po?.poNumber ?? "") !== "";
  // Whether anything was ever asked of the vendor, which is what gates the "Vendor Never Delivered
  // this Order" tick on the cancel panel (T-129). Read off sentAt and not off the status: a
  // cancelled order's status no longer says whether it was ever sent, and that is exactly the case
  // the ruling is about — a draft, cancelled, with the box ticked.
  const wasSent = po?.sentAt != null;
  /**
   * Whether this cancellation may be laid at the vendor's door (T-129, extended by T-137/D-25).
   *
   * <p>Two conditions, and they are the same idea twice. The order has to have been sent, because
   * nothing was asked of a supplier who never saw it. And it has to have been sent in time, because
   * an order we submitted after their agreed lead time asked for something they never promised —
   * Rajeev: "BECAUSE it is their fault NOT the vendors", and on such an order the fault is ours.
   *
   * <p>`sentAfterLeadTime` is the verdict the server stamped when the order went out, never a
   * judgement this screen makes.
   */
  const canBlameVendor = wasSent && po?.sentAfterLeadTime !== true;

  /**
   * The described lines on this order that nobody has yet said arrived (T-066).
   *
   * <p>`ingredientId === null` is the discriminator for a line the store room cannot take in, and
   * `arrivedOn === null` is the discriminator for one that is still outstanding. A described line
   * is the ONLY line that can carry an arrival date — the server's own CHECK says so — which is
   * why this filter does not need to ask whether the date belongs on this row.
   */
  const outstandingArrivals = lines.filter((l) => l.ingredientId === null && l.arrivedOn === null);

  const receivedByLine = new Map<string, number>();
  for (const r of receipts) {
    for (const l of r.lines) {
      receivedByLine.set(l.poLineId, (receivedByLine.get(l.poLineId) ?? 0) + l.receivedQty);
    }
  }

  async function receive(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const f = new FormData(form);
    const receiptLines = lines
      // A described line has no boxes to read — the store room does not track it, and the server
      // refuses a receipt against one with KMS-400129. Filtered here rather than relied on to
      // produce zeros: the field names below would not exist at all for such a line, and
      // `Number(null ?? 0)` happening to be 0 is a coincidence, not a guard.
      .filter((l) => l.ingredientId !== null)
      .map((l) => {
        const received = Number(f.get(`received_${l.id}`) ?? 0) || 0;
        const rejected = Number(f.get(`rejected_${l.id}`) ?? 0) || 0;
        const reason = String(f.get(`reason_${l.id}`) ?? "") || null;
        const expiry = String(f.get(`expiry_${l.id}`) ?? "") || null;
        // Blank stays blank. Number("") is 0, and a 0 here would be written back as the vendor's
        // price — "the bill hasn't come yet" turned into "this costs nothing" by a coercion.
        const priceText = String(f.get(`price_${l.id}`) ?? "").trim();
        const unitPrice = priceText === "" ? null : Number(priceText);
        return { poLineId: l.id, receivedQty: received, rejectedQty: rejected, rejectReason: reason as never, expiryDate: expiry, unitPrice };
      })
      .filter((l) => l.receivedQty > 0 || l.rejectedQty > 0);
    if (receiptLines.length === 0) {
      setActionError(toApiError(null, "Enter what arrived on at least one line."));
      return;
    }
    if (receiptLines.some((l) => l.unitPrice != null && (!Number.isFinite(l.unitPrice) || l.unitPrice < 0))) {
      setActionError(toApiError(null, "A price is an amount in rupees. Leave it blank if the bill hasn’t arrived."));
      return;
    }
    const ok = await run(
      (t) => api.receiveDelivery(id, { idempotencyKey: crypto.randomUUID(), lines: receiptLines }, t),
      "We couldn’t record that delivery."
    );
    if (ok) {
      form.reset();
      setShowReceive(false);
    }
  }

  /**
   * Records that described lines on this order turned up (T-066).
   *
   * <p><strong>This is the action KMS-400129 has been telling storekeepers to take since T-024, and
   * which existed nowhere until now.</strong> An order of nothing but described lines could take no
   * goods receipt at all — the server refuses one, correctly — so it never left SENT: it aged in the
   * vendor scorecard's open-orders bucket for ever and scored late for ever. This closes it.
   *
   * <p>It moves no stock and it is not a receipt. The store room does not track a plastic stool,
   * and there is no batch, no expiry and no on-hand quantity that would mean anything about one.
   *
   * <p>Ticked rather than assumed. An order from a hardware shop may carry four stools that came on
   * Tuesday and a mixer motor repair that happens on Friday, and one button claiming both would be
   * a statement nobody made.
   */
  async function recordArrivals(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const poLineIds = new FormData(form).getAll("arrived").map(String);
    if (poLineIds.length === 0) {
      setActionError(toApiError(null, "Tick what arrived. Leave a line unticked if it hasn’t."));
      return;
    }
    await run((t) => api.recordArrivals(id, { poLineIds }, t), "We couldn’t record that.");
  }

  /**
   * Sends part or all of one received line back to the vendor (T-013).
   *
   * <p>The quantity is capped on the server against everything already returned against this line
   * (KMS-400140), and it is not pre-checked here beyond being a positive number. The screen knows
   * what it last fetched; the server knows what is true, and a second storekeeper returning the
   * same sack a minute ago is exactly the case a client-side cap would wave through.
   */
  async function submitReturn(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!returning) return;
    const form = event.currentTarget;
    const f = new FormData(form);
    const qty = Number(String(f.get("return_qty") ?? "").trim());
    if (!Number.isFinite(qty) || qty <= 0) {
      setActionError(toApiError(null, "Enter how much went back to the vendor."));
      return;
    }
    const note = String(f.get("return_note") ?? "").trim();
    const ok = await run(
      (t) => api.returnReceivedGoods(returning.receiptId, {
        idempotencyKey: crypto.randomUUID(),
        receiptLineId: returning.line.id,
        quantity: qty,
        reason: String(f.get("return_reason") ?? "OTHER") as ReturnReason,
        note: note === "" ? null : note,
      }, t),
      "We couldn’t record that return."
    );
    if (ok) {
      form.reset();
      setReturning(null);
    }
  }

  /**
   * Marks the order as sent — and the vendor's lead time is enforced on the way (T-137, D-25).
   *
   * <p>Rajeev's rule is that ordering after a supplier's agreed notice period is allowed and is not
   * a mistake: <em>"That is a FAVOR we are asking."</em> So the first press asks, the server refuses
   * with KMS-400148 and says what it will cost — a delay on this delivery cannot then be counted
   * against them — and the second press means it.
   *
   * <p>The refusal is the server's and the consent is the person's. This screen does not decide
   * whether the order is late, does not hide the button, and does not warn in place of asking.
   */
  async function markSent(anyway: boolean) {
    const ok = await run(
      (t) => (anyway ? api.sendPurchaseOrderAnyway(id, t) : api.sendPurchaseOrder(id, t)),
      "We couldn’t send that order."
    );
    setLateSend(!ok && !anyway && lastRefusal.current === "KMS-400148");
  }

  /**
   * Writes an edited draft back (T-134).
   *
   * <p>The draft itself is built and validated by PurchaseOrderEditor, which hands it over in the
   * shape the endpoint takes. What is left here is what only this screen knows: which order it is,
   * the two header fields the form does not offer, and what to do afterwards.
   */
  async function saveLines(draft: PurchaseOrderDraft) {
    if (!po) return;
    // The endpoint replaces a draft wholesale, so the header fields travel back with the lines —
    // otherwise correcting a quantity would quietly erase the delivery address somebody typed last
    // week. If the order was sent from another screen in the meantime the server refuses with
    // KMS-400050, and that refusal is shown as it arrives rather than swallowed.
    const ok = await run(
      (t) => api.updatePurchaseOrder(id, {
        neededBy: draft.neededBy,
        deliveryLocation: po.deliveryLocation,
        notes: po.notes,
        lines: draft.lines,
      }, t),
      "We couldn’t save those changes."
    );
    if (ok) setEditing(false);
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/orders" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <Link href="/orders" className="text-sm text-accent-text hover:underline">← All purchase orders</Link>

          {loading ? (
            <Loading label="Loading purchase order…" />
          ) : error ? (
            <div className="mt-6"><ErrorNotice error={error} /></div>
          ) : !po ? null : (
            <>
              <header className="mb-6 mt-3 flex flex-wrap items-start justify-between gap-4">
                <div>
                  <h1 className="tabular-nums">{po.poNumber}</h1>
                  <p className="mt-1 flex items-center gap-2 text-ink-secondary">
                    {po.vendorName} {statusChip(po.status)}
                  </p>
                  {/* The date the temple asked for, on every order and in every state. On a draft
                      it is editable below; once the order has gone to the vendor it is a readout
                      and nothing else — that date is what they were asked for, and what the vendor
                      scorecard measures their delivery against. */}
                  {/* When this order came into being, above the date it has to meet. The two read
                      as a span — raised then, wanted by then — and until 2026-09-05 the first half
                      was on no screen at all. */}
                  {/* The three dates of an order, in the order they happen. Rajeev, 2026-09-05:
                      the gap between the first two is whether we were late, and the gap between the
                      last two is whether the vendor was — one date could never answer both. */}
                  <p className="mt-1 text-sm tabular-nums text-ink-secondary">
                    Generated {dateWithYear(po.orderDate)}
                  </p>
                  <p className="text-sm tabular-nums text-ink-secondary">
                    {po.sentAt ? `Sent ${templeDay(po.sentAt)}` : "Not sent yet"}
                  </p>
                  {/* The same rule as the lines table below, and found by the same sweep (T-134):
                      the form carries a "Needed by" box, so in edit mode this readout is a second
                      copy of one field — and it shows the saved date while the box shows the one
                      being typed, which is two answers to one question. The other two dates stay:
                      when the order was raised and whether it has gone are facts the form does not
                      offer and cannot change. */}
                  {!editing && (
                    <p className="text-sm tabular-nums text-ink-secondary">
                      {po.neededBy ? `Needed by ${dateWithYear(po.neededBy)}` : "No needed-by date"}
                    </p>
                  )}
                  {po.sentAt && <p className="text-sm text-ink-muted">Fixed when the order was sent</p>}
                  {/*
                    The vendor's own promise, said on the order it applies to (T-137, D-25).

                    A lead time is agreed at onboarding and it is theirs — "we are good with 1 day
                    lead time BUT we want to be safe than sorry so we need 3 days notice" — so the
                    order-by date underneath it is not our deadline, it is the last day their
                    promise can still be kept. Every figure here comes from the server, which works
                    it out in the one place that arithmetic exists; nothing on this screen subtracts
                    a date from another.

                    Three zones on a draft, which are Rajeev's own: comfortably inside says so
                    quietly, the last day gets a nudge (he called the nudge optional), and past it
                    is a different sentence rather than a louder one — there is no longer an action
                    that produces the outcome the warning was about.

                    On a sent order the zone is gone and the verdict has taken its place. That
                    verdict was decided when the order went out and is stamped on it, so editing
                    the vendor's profile afterwards cannot move it: "No retroactive change here."

                    Nothing at all for a vendor with no recorded lead time. Rajeev was explicit
                    that this case is silence — no nudge, no warning, no exclusion, nothing held
                    against anybody — so an absent figure prints nothing rather than an assumption
                    dressed up as their word.
                  */}
                  {leadTimeLine(po)}
                  {po.cancelReason && (
                    <p className="mt-1 text-sm text-ink-muted">
                      {/* "Auto Cancelled", not "Cancelled" (D-24a). Nobody in the temple did this,
                          and a person reading the order will otherwise go looking for who did. */}
                      {po.autoCancelled ? "Auto Cancelled" : "Cancelled"}: {po.cancelReason}
                    </p>
                  )}
                  {po.autoCancelled && (
                    <p className="mt-1 max-w-prose text-sm text-ink-secondary">
                      It was still a draft on the day it was needed, so it was cancelled
                      automatically and what it asked for went back on the shopping list. Nothing
                      was sent to {po.vendorName}, so nothing counts against them.
                    </p>
                  )}
                  {/*
                    The other half of the cancellation, and until T-126 it was on no screen at all.
                    T-124 recorded the "Vendor Never Delivered this Order" tick in the row, the
                    activity trail and the audit record, and scored the vendor 0% for it — but a
                    person opening the cancelled order saw only the reason, so the one screen where
                    somebody asks "why was this cancelled?" could not answer the question the tick
                    exists to answer.

                    Said in a sentence rather than left as a badge on its own. "Never delivered"
                    beside a line that already says Cancelled is ambiguous — it could as easily mean
                    the goods never came because we called it off. The sentence names who we are
                    holding responsible, which is the whole difference between the two kinds of
                    cancellation, and the second half is the same promise the tick's own hint made
                    on the way in: it counts against their record.

                    The badge is the screen's existing marker for a state, in the same warning tone
                    the vendor scorecard already uses for "1 order never delivered". No new style.
                  */}
                  {po.vendorAbandoned && (
                    <p className="mt-2 flex flex-wrap items-baseline gap-2 text-sm text-ink-secondary">
                      <Badge tone="warning">Never delivered</Badge>
                      {/* The second half of the sentence has to stay true (T-137). This order was
                          marked as a no-show, but if we sent it after the vendor's agreed lead time
                          the scorecard leaves it out of their figures entirely — so promising that
                          it counts against them would be the screen saying something the numbers
                          do not do. The mark stays on the record; what it costs them changes. */}
                      <span className="max-w-prose">
                        {po.sentAfterLeadTime
                          ? `The vendor never delivered this order. We sent it after ${po.vendorName} asked to be given, so it is left out of their delivery record rather than counted against them.`
                          : "The vendor never delivered this order. It counts against their delivery record."}
                      </span>
                    </p>
                  )}
                </div>
                {/*
                  How this order ended, on the order itself (T-142, D-26).

                  The same lesson T-126 paid for with the no-show tick: a fact captured on a form
                  and shown nowhere is a fact nobody can check. Somebody opening a closed order
                  months later asks two questions — why did this end with part of it undelivered,
                  and what did that mean for the vendor — and both answers are here, the second in
                  the words of the person who made the decision.

                  The sentence names what the outcome DOES rather than repeating the label, because
                  the label is a judgement and the consequence is the thing a reader cannot work out
                  for themselves.
                */}
                {po.closeOutcome && (
                  <div className="mt-2 text-sm">
                    <p className="flex flex-wrap items-baseline gap-2">
                      <Badge tone={po.closeOutcome === "VENDOR_LET_US_DOWN" ? "warning" : "neutral"}>
                        {po.closeOutcome === "VENDOR_LET_US_DOWN"
                          ? "Vendor let us down"
                          : po.closeOutcome === "SHORTFALL_EXCUSED"
                            ? "Shortfall excused"
                            : "Closed short"}
                      </Badge>
                      <span className="max-w-prose text-ink-secondary">
                        {po.closeOutcome === "VENDOR_LET_US_DOWN"
                          ? `Closed with part of it never delivered, and recorded against ${po.vendorName}. It is scored on what actually arrived.`
                          : po.closeOutcome === "SHORTFALL_EXCUSED"
                            ? `Closed with part of it never delivered. ${po.vendorName} made it right, so this order is left out of their delivery record.`
                            : `Closed with part of it never delivered. Nothing was recorded for or against ${po.vendorName}.`}
                      </span>
                    </p>
                    {po.closeNote && (
                      <p className="mt-1 max-w-prose text-ink-muted">“{po.closeNote}”</p>
                    )}
                  </div>
                )}
                {/*
                  The bank of buttons, in the order Rajeev dictated on 2026-09-10 while driving the
                  deployed application (D-24 §3): Vendor's language, Generate PDF, Print, Edit, Mark
                  as sent. That is the whole of the order and it is not a suggestion — Print and
                  Generate PDF were the other way round, and "Edit lines" sat between two send
                  actions.

                  Three things are true of this bank that were not before T-135:

                  * It is gone entirely in edit mode. "Why do we need all the other buttons in edit
                    mode?" — in edit mode there are two buttons, Save and Cancel, and they live on
                    the form itself. The language picker goes with them: it exists to steer Print
                    and Generate PDF, and neither is offered while a draft is being edited.
                  * Send on WhatsApp is here only where WhatsApp has actually sent something
                    (T-136). See `canWhatsApp`.
                  * Cancel has left it. It was ambiguous where it stood — "Is it cancelling out of
                    this screen OR cancelling the PO?" — and cancelling a purchase order is a
                    deliberate act, so it is now a titled block at the foot of the page that
                    somebody has to go to on purpose.

                  Receive delivery keeps the tail of the bank. It is not in Rajeev's five because
                  the order he was looking at was a draft and it does not appear on one; it is the
                  other thing a person does from this screen, and it belongs beside them.
                */}
                {/* `!(editing && canEdit)` rather than `!editing`, and the difference only shows in
                    one case: if the order stops being a draft while somebody has the form open —
                    marked sent from another screen — the form is no longer rendered, and a bank
                    hidden on `editing` alone would leave the screen with no actions on it at all. */}
                {!(editing && canEdit) && (
                  <div className="flex flex-wrap items-center gap-2">
                    <select
                      aria-label="Document language"
                      value={docLanguage}
                      onChange={(e) => setDocLanguage(e.target.value)}
                      className="min-h-touch rounded-control border border-hairline px-3 text-sm"
                    >
                      <option value="">Vendor’s language</option>
                      {ALL_LANGUAGES.map((l) => <option key={l.code} value={l.code}>{l.label}</option>)}
                    </select>
                    <button type="button" disabled={busy} onClick={generatePdf} className="min-h-touch rounded border border-hairline px-4 transition-colors duration-state hover:bg-sunken disabled:opacity-60">{preparingPdf ? (<span className="inline-flex items-center gap-2"><BusyPot />Preparing PDF…</span>) : "Generate PDF"}</button>
                    <button type="button" disabled={busy} onClick={print} className="min-h-touch rounded border border-hairline px-4 transition-colors duration-state hover:bg-sunken disabled:opacity-60">Print</button>
                    {/* "Edit", not "Edit lines" — Rajeev, 2026-09-10: "because that is what you are
                        doing. EDITING the whole PO, not just 1 line." The form below edits the
                        needed-by date as well as the lines, so the old label was describing less
                        than the button did. It no longer doubles as the way out of edit mode
                        either: it is not rendered there at all. */}
                    {canEdit && <button type="button" disabled={busy} onClick={() => { setActionError(null); setEditing(true); }} className="min-h-touch rounded border border-hairline px-4 transition-colors duration-state hover:bg-sunken disabled:opacity-60">Edit</button>}
                    {canSend && <button type="button" disabled={busy} onClick={() => markSent(false)} className="btn btn-primary min-h-touch px-4 transition-colors duration-state disabled:opacity-60">Mark sent</button>}
                    {canWhatsApp && <button type="button" disabled={busy} onClick={() => run((t) => api.sendPurchaseOrderWhatsApp(id, t), "We couldn’t send it on WhatsApp.")} className="btn btn-primary min-h-touch px-4 transition-colors duration-state disabled:opacity-60">Send on WhatsApp</button>}
                    {canReceive && <button type="button" disabled={busy} onClick={() => setShowReceive((s) => !s)} className="min-h-touch rounded border border-hairline px-4 transition-colors duration-state hover:bg-sunken disabled:opacity-60">Receive delivery</button>}
                  </div>
                )}
              </header>

              {actionError && (
                <div className="mb-6 grid gap-3">
                  <ErrorNotice error={actionError} />
                  {/*
                    The override on a late send (T-137, D-25), and it is deliberately a second,
                    separate press rather than a confirm dialog over the first.

                    Rajeev did not want this refused: "That is a FAVOR we are asking." What he
                    wanted was that the person knows what it costs before they ask for it — the
                    refusal above says that a delay on this delivery will not count against the
                    vendor — and then that they can go ahead. Nothing is hidden and nothing is
                    disabled; the ordinary Mark sent button is still there above.
                  */}
                  {lateSend && canSend && (
                    <div className="flex flex-wrap items-center gap-3">
                      <button
                        type="button"
                        disabled={busy}
                        onClick={() => markSent(true)}
                        className="btn btn-primary min-h-touch px-4 transition-colors duration-state disabled:opacity-60"
                      >
                        Send it anyway
                      </button>
                      <span className="max-w-prose text-sm text-ink-secondary">
                        {po.vendorName} agreed to {leadTimeSaid(po.leadTimeDays)}, so this one goes
                        out as a favour. A late delivery on it won’t count against them.
                      </span>
                    </div>
                  )}
                  {/* A few refusals name the lines they are about — a unit the ingredient cannot be
                      measured in (KMS-400013) is one. An order can run to twenty lines, and being told
                      that one of them is wrong without being told which is not much of a refusal. */}
                  {actionError.fieldErrors.length > 0 && (
                    <ul className="grid gap-1 rounded border border-hairline bg-raised px-5 py-4 text-sm">
                      {actionError.fieldErrors.map((f) => (
                        <li key={f.field}>
                          <span className="font-medium">{f.field}</span>: {f.message}
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              )}

              {/*
                The edit form itself lives in components/PurchaseOrderEditor.tsx, and the whole of
                T-134 on this side is that it was moved there rather than copied (D-24 §6). Rajeev:
                "The panel and the edit screen are the same thing. If they are built twice they
                will drift." The shopping list mounts this identical component as a panel over
                itself, for an order that does not exist yet; the only things that differ are the
                words and what Save does with the finished draft.

                What stayed behind on this page is the half that is not the form: the bank of
                buttons above, the tables below, and the cancellation at the foot of the page.

                `leadTimeDays` and `vendorName` are what this vendor asked for at onboarding, so
                the form can say it while somebody picks a date (T-137). A statement of their own
                number and nothing more: where the order stands against it is decided on the server,
                on Mark sent, and is never worked out twice.
              */}
              {editing && canEdit && (
                <PurchaseOrderEditor
                  words={{
                    heading: "Edit this draft",
                    formLabel: "Edit the draft order",
                    intro: "The vendor cannot be changed. Cancel this order at the foot of the page and raise it against the right one. Once it is sent, nothing here can be changed at all.",
                    emptyOrder: "An order needs at least one line. Add what is being bought, or cancel the order at the foot of the page.",
                    dateBeforeFloor: "That date is before the order was raised. Choose a day on or after it.",
                  }}
                  initialLines={lines.map((l) => ({
                    key: l.id,
                    ingredientId: l.ingredientId,
                    ingredientName: l.ingredientName,
                    description: l.description,
                    quantity: String(l.quantity),
                    unit: l.unit,
                    expectedPrice: l.expectedPrice,
                  }))}
                  initialNeededBy={po.neededBy ?? ""}
                  minNeededBy={po.orderDate}
                  leadTimeDays={po.leadTimeDays ?? null}
                  vendorName={po.vendorName}
                  ingredients={ingredientsData ?? []}
                  busy={busy}
                  onSave={saveLines}
                  onCancel={() => setEditing(false)}
                  onRefuse={(message) => setActionError(toApiError(null, message))}
                />
              )}


              {showReceive && canReceive && (
                <section className="card mb-6 px-6 py-5" aria-labelledby="receive-heading">
                  <h2 id="receive-heading" className="text-lg">Record a delivery</h2>
                  <p className="mt-1 text-sm text-ink-secondary">Rejected goods need a reason and never enter stock. The price is what the bill says — correct it if it differs, or leave it blank for a delivery that came without one.</p>
                  <form className="mt-4" aria-label="Record a delivery" onSubmit={receive}>
                    <div className="overflow-x-auto">
                    <table className={`${TABLE} text-sm`}>
                      <thead className={THEAD}>
                        <tr>
                          {/* A floor, not a width. Seven of these eight columns hold a fixed-width
                              control or a two-word heading and sit at their minimum whatever the
                              card is, so a full-width table has nothing to share out and the one
                              column that may wrap is handed whatever is left — measured at 142px,
                              which broke a 69-character ingredient name over five lines and made a
                              125px-tall row nobody can read. `min-w` gives it a floor of 13rem
                              (three lines, and the knee of the curve: 16rem buys one more line for
                              twice the scroll) and lets the table run past the card, which now
                              scrolls rather than clipping. Deliberately a `min-w` and never a
                              `max-w` — see the note on WRAP in ds/table.ts. */}
                          <th className={`${TH_TEXT} ${WRAP} min-w-[13rem]`}>Item</th>
                          <th className={TH_NUM}>Ordered</th>
                          <th className={TH_NUM}>Received so far</th>
                          <th className={TH_NUM}>Received now</th>
                          <th className={TH_NUM}>Rejected</th>
                          <th className={TH_TEXT}>Reason</th>
                          <th className={TH_TEXT}>Expiry</th>
                          <th className={TH_NUM}>Price paid</th>
                        </tr>
                      </thead>
                      <tbody>
                        {lines.map((l) => (
                          <tr key={l.id} className={TR}>
                            <td className={`${TD_TEXT} ${WRAP} min-w-[13rem]`}>{subjectOf(l)}</td>
                            {/* A described line is orderable and payable but never receivable: the
                                store room counts ingredients, and there is no batch, no expiry and
                                no on-hand quantity that would mean anything about a plastic stool.
                                So the row is here — it is part of the order and the storekeeper
                                needs to see that it was on the lorry — with no boxes to type into.
                                The server refuses it too (KMS-400129); this is the offer being
                                absent rather than merely refused when pressed. */}
                            {l.ingredientId === null ? (
                              <td className={`${TD_TEXT} text-ink-muted`} colSpan={7}>
                                {l.arrivedOn
                                  ? `Not stocked · arrived ${dateWithYear(l.arrivedOn)}`
                                  : "Not stocked — say below whether it arrived"}
                              </td>
                            ) : (
                            <>
                            {/* Ledger form on both, and for one reason: this row exists so a
                                store-keeper can see what is still owed. Round the ordered figure
                                and not the receipts against it and a fully delivered line reads as
                                over-delivered. "Received so far" was printing a bare number with no
                                unit at all, which is the same defect one step further on. */}
                            <td className={TD_NUM}>{quantitySaid(l.quantity, l.unit)}</td>
                            <td className={`${TD_NUM} text-ink-secondary`}>{quantitySaid(receivedByLine.get(l.id) ?? 0, l.unit)}</td>
                            <td className={TD_NUM}><input name={`received_${l.id}`} type="number" min="0" step="any" aria-label={`Received ${subjectOf(l)}`} className="w-24 rounded-control border border-hairline px-2 py-1 tabular-nums" /></td>
                            <td className={TD_NUM}><input name={`rejected_${l.id}`} type="number" min="0" step="any" aria-label={`Rejected ${subjectOf(l)}`} className="w-20 rounded-control border border-hairline px-2 py-1 tabular-nums" /></td>
                            <td className={TD_TEXT}>
                              <select name={`reason_${l.id}`} className="rounded-control border border-hairline px-2 py-1">
                                <option value="">—</option>
                                {REJECT_REASONS.map((r) => <option key={r} value={r}>{r.replace("_", " ").toLowerCase()}</option>)}
                              </select>
                            </td>
                            <td className={TD_DATE}><input name={`expiry_${l.id}`} type="date" className="rounded-control border border-hairline px-2 py-1" /></td>
                            {/* Pre-filled from the order and editable, because the bill that arrived
                                with the lorry is the truth and the order was only ever a guess. The
                                expected figure stays visible underneath rather than being replaced,
                                so a storekeeper can see that ₹80 is not the ₹45 that was budgeted —
                                as information, not as a gate. Whatever is typed here becomes the
                                vendor's last-known price for this ingredient. */}
                            <td className={`${TD_NUM} align-top`}>
                              <input
                                name={`price_${l.id}`}
                                type="number"
                                min="0"
                                step="0.01"
                                defaultValue={l.expectedPrice ?? ""}
                                aria-label={`Price paid per ${unitLabel(l.unit)} of ${subjectOf(l)}, optional`}
                                className="w-24 rounded-control border border-hairline px-2 py-1 tabular-nums"
                              />
                              <span className="mt-1 block pl-field-inset text-xs text-ink-muted">
                                {l.expectedPrice == null
                                  ? `optional, per ${unitLabel(l.unit)}`
                                  : `expected ${money(l.expectedPrice, "INR")} / ${unitLabel(l.unit)}`}
                              </span>
                            </td>
                            </>
                            )}
                          </tr>
                        ))}
                      </tbody>
                    </table>
                    </div>
                    <button type="submit" disabled={busy} className="btn btn-primary mt-4 min-h-touch px-5 transition-colors duration-state disabled:opacity-60">Record delivery</button>
                  </form>
                </section>
              )}

              {/* The door KMS-400129 has been pointing at since T-024 (T-066).

                  Outside the "Record a delivery" panel, and always open, deliberately. An order of
                  nothing but described lines has no delivery to record — every row in that table
                  would be a row with no boxes — so anything hidden behind that button would be
                  hidden behind a button the storekeeper has no reason to press. This is the only
                  way such an order is ever closed, and it has to be the thing you see. */}
              {canReceive && outstandingArrivals.length > 0 && (
                <section className="card mb-6 px-6 py-5" aria-labelledby="arrivals-heading">
                  <h2 id="arrivals-heading" className="text-lg">Did these arrive?</h2>
                  {/* Rewritten with the default (T-107). The first two sentences still orient —
                      they say why these lines are here and why the order will not close without
                      an answer — but the third used to be reassurance aimed at a panel that had
                      already ticked everything on the reader's behalf. Nothing is ticked now, so
                      the copy has to ask for the tick, and the thing worth saying alongside it is
                      that leaving a line alone costs nothing: an unticked line is still offered
                      next time this screen is opened, which is what makes it safe to record the
                      stools today and the mixer repair on Friday. */}
                  <p className="mt-1 max-w-prose text-sm text-ink-secondary">
                    The store room doesn’t track these, so they can’t be received into stock —
                    but the order isn’t finished until somebody says whether they turned up.
                    Tick only what has arrived. Anything left unticked stays here for next time,
                    and recording it changes nothing in the store.
                  </p>
                  <form className="mt-4" aria-label="Record what arrived" onSubmit={recordArrivals}>
                    <ul className="grid gap-2">
                      {outstandingArrivals.map((l) => (
                        <li key={l.id}>
                          {/* Nothing ticked when the panel opens (T-107). It used to open with
                              every box ticked, on the reasoning that somebody opens this because
                              the goods are in front of them — and that reasoning quietly made the
                              destructive answer the default one. An arrival is written once: the
                              endpoint only ever sets `arrived_on` where it is still null, there is
                              no reverse of it anywhere in the API, and so a Kitchen Manager who
                              opened this order to see what was still outstanding and pressed the
                              button recorded "repair the mixer motor" as delivered, permanently.

                              A tick is now a thing somebody did rather than a thing they failed to
                              undo, which is the only form a statement this irreversible should
                              take. (The volunteer roster had the same shape and the same defect;
                              the difference is that an attendance mark can be corrected.) */}
                          <label className="flex items-center gap-3 text-sm">
                            <input
                              type="checkbox"
                              name="arrived"
                              value={l.id}
                              className="h-5 w-5 rounded-sm border-hairline-strong accent-accent"
                            />
                            <span>
                              {subjectOf(l)}{" "}
                              <span className="text-ink-secondary tabular-nums">
                                {quantitySaid(l.quantity, l.unit)}
                              </span>
                            </span>
                          </label>
                        </li>
                      ))}
                    </ul>
                    {/* Enabled with nothing ticked, and answered in words rather than by going
                        grey (T-107). `recordArrivals` refuses an empty selection before it reaches
                        the network and says "Tick what arrived" — so the endpoint's @NotEmpty is
                        unreachable from this screen and nobody is shown a validation error for
                        pressing a button too early.

                        Disabling it instead would be silent: a storekeeper who presses and sees
                        nothing happen is told neither what is wrong nor what to do, and there is
                        nowhere on a greyed button to put the sentence that would tell them. It
                        would also disagree with the receiving panel directly above, which takes
                        exactly this approach for exactly this case ("Enter what arrived on at
                        least one line"). Same screen, same mistake, same answer. */}
                    <button
                      type="submit"
                      disabled={busy}
                      className="btn btn-primary mt-4 min-h-touch px-5 transition-colors duration-state disabled:opacity-60"
                    >
                      Record as arrived
                    </button>
                  </form>
                </section>
              )}

              {/*
                Not while the draft is being edited (T-134).

                The lines were on this screen twice in edit mode: the editable table inside the form
                — Item, Quantity, Remove, with the quantity in a box — and this read-only one
                underneath it, showing the same line and the *saved* figure. Typing 45 into the box
                left "Jaggery 5 Kg" sitting below it, so the screen disagreed with itself about what
                the order says, and the second answer was the stale one.

                It is not a Wave A regression: this table has been rendered unconditionally since
                T-024, and the edit form has always opened above it. What Wave A changed is that the
                bank of buttons no longer sits between them, which is what made it easy to see.

                Rajeev's own question settles it — "Why do we need all the other buttons in edit
                mode?" — and it answers this the same way. In edit mode the order's lines are the
                thing being edited; a second, uneditable copy of them is not context, it is a
                contradiction. It comes back the moment Save or Cancel is pressed.
              */}
              {!editing && (
              <section className="table-wrap mb-8 overflow-x-auto">
                {/* Named, because this screen can show four tables at once — the order as issued,
                    the receiving form, and one per delivery — and until T-066 none of them could be
                    told apart by anything but the words inside them. A subject now appears in two
                    places at once (here, and on the "Did these arrive?" list), so "the line is on
                    the screen" stopped being the same claim as "the line is on the order". */}
                <table className={TABLE} aria-label="What was ordered">
                  <thead className={THEAD}>
                    <tr>
                      <th className={`${TH_TEXT} ${WRAP}`}>Item</th>
                      <th className={TH_NUM}>Quantity</th>
                      {showPrices && <th className={TH_NUM}>Price</th>}
                    </tr>
                  </thead>
                  <tbody>
                    {lines.map((l: PurchaseOrderLineView) => (
                      <tr key={l.id} className={TR}>
                        <td className={`${TD_TEXT} ${WRAP}`}>
                          {subjectOf(l)}
                          {/* Only ever on a described line, and only once somebody has said so
                              (T-066). A catalogue line's arrival is the delivery table below; this
                              is the only record a described line will ever have, so the order it
                              sits on is the place to read it. */}
                          {l.arrivedOn && (
                            <span className="block text-xs tabular-nums text-ink-muted">
                              Arrived {dateWithYear(l.arrivedOn)}
                            </span>
                          )}
                        </td>
                        {/* The order as issued, beside what it is expected to cost — the figure
                            the delivery above and the vendor's invoice are both checked against, so
                            it is exact and agrees line for line with the receiving table. */}
                        <td className={TD_NUM}>{quantitySaid(l.quantity, l.unit)}</td>
                        {showPrices && <td className={TD_NUM}>{money(l.expectedPrice, "INR")}</td>}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </section>
              )}

              {/* What actually arrived, delivery by delivery, and what has since gone back (T-013).
                  Until now this screen fetched the receipts only to add up "received so far" in the
                  receiving form, so a delivery could be recorded and then never read again. It has
                  to be readable to be returnable: a return is made against one line of one
                  delivery, and there is no other place in the application that shows which line
                  that is. */}
              {receipts.length > 0 && (
                <section className="mb-8" aria-labelledby="deliveries-heading">
                  <h2 id="deliveries-heading" className="text-lg">Deliveries received</h2>
                  {receipts.map((r) => (
                    <div key={r.id} className="mt-4">
                      <p className="text-sm text-ink-secondary">
                        {templeDay(r.receivedAt)}{r.receivedByName ? ` · ${r.receivedByName}` : ""}
                      </p>
                      <div className="table-wrap mt-2 overflow-x-auto">
                        <table className={TABLE}>
                          <thead className={THEAD}>
                            <tr>
                              <th className={`${TH_TEXT} ${WRAP}`}>Item</th>
                              <th className={TH_NUM}>Received</th>
                              <th className={TH_NUM}>Rejected at the gate</th>
                              <th className={TH_NUM}>Returned</th>
                              <th className={TH_ACTIONS}>&nbsp;</th>
                            </tr>
                          </thead>
                          <tbody>
                            {r.lines.map((l) => (
                              <tr key={l.id} className={TR}>
                                <td className={`${TD_TEXT} ${WRAP}`}>{l.ingredientName}</td>
                                {/* Ledger form throughout, as in the receiving table above: these
                                    figures are checked against each other and against the order, so
                                    a rounded one would read as a discrepancy that is not there. */}
                                <td className={TD_NUM}>{quantitySaid(l.receivedQty, l.unit)}</td>
                                <td className={`${TD_NUM} text-ink-secondary`}>
                                  {l.rejectedQty > 0
                                    ? `${quantitySaid(l.rejectedQty, l.unit)} · ${reasonLabel(l.rejectReason ?? "")}`
                                    : "—"}
                                </td>
                                {/* The receipt itself is never edited, so this is not a column of
                                    it: the server sums the returns recorded against the line. A
                                    line nothing has gone back on reads as a dash rather than 0, so
                                    the exceptions are the only things the eye stops on. */}
                                <td className={TD_NUM}>
                                  {l.returnedQty > 0 ? quantitySaid(l.returnedQty, l.unit) : "—"}
                                </td>
                                <td className={TD_ACTIONS}>
                                  {/* Offered only where there is something left to send back.
                                      A line rejected in full never entered stock, and a line
                                      already returned in full has nothing more to take out — in
                                      both the server would refuse it (KMS-400140), and an offer
                                      that is refused when pressed is worse than no offer. */}
                                  {l.receivedQty > l.returnedQty && (
                                    <Button
                                      variant="secondary"
                                      disabled={busy}
                                      onClick={() => { setActionError(null); setReturning({ receiptId: r.id, line: l }); }}
                                    >
                                      Return to vendor
                                    </Button>
                                  )}
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    </div>
                  ))}
                </section>
              )}

              {returning && (
                <section className="card mb-8 px-6 py-5" aria-labelledby="return-heading">
                  <h2 id="return-heading" className="text-lg">Return {returning.line.ingredientName} to the vendor</h2>
                  {/* The quantity still available is stated here, in the open, and not inside the
                      field's "i". A hint holds guidance somebody may want; this is the number the
                      form is about to be judged against — the server caps on exactly this figure
                      (KMS-400140) — and a cap nobody can see until they press the button is how a
                      person ends up guessing. */}
                  <p className="mt-1 text-sm text-ink-secondary">
                    {quantitySaid(returning.line.receivedQty - returning.line.returnedQty, returning.line.unit)} of
                    this delivery can still go back. This takes the goods out of stock. The delivery
                    record stays exactly as it was signed for.
                  </p>
                  <form className="mt-4" aria-label="Return goods to the vendor" onSubmit={submitReturn}>
                    <div className="flex flex-wrap items-end gap-4">
                      <HintedField label={`Quantity in ${unitLabel(returning.line.unit)}`}>
                        {(id) => (
                          <input
                            id={id}
                            name="return_qty"
                            type="number"
                            min="0"
                            step="any"
                            aria-label={`Quantity of ${returning.line.ingredientName} to return`}
                            className="w-32 min-h-touch rounded-control border border-hairline px-2 tabular-nums"
                          />
                        )}
                      </HintedField>
                      <HintedField label="Reason" hint="Say why, so the vendor’s record shows it.">
                        {(id) => (
                          <select id={id} name="return_reason" aria-label="Reason for the return" className="min-h-touch rounded-control border border-hairline px-2">
                            {RETURN_REASONS.map((r) => (
                              <option key={r} value={r}>{reasonLabel(r)}</option>
                            ))}
                          </select>
                        )}
                      </HintedField>
                      <HintedField label="Note" hint="Anything the vendor should be told. Optional.">
                        {(id) => (
                          <input
                            id={id}
                            name="return_note"
                            type="text"
                            maxLength={1000}
                            aria-label="Note about the return, optional"
                            className="w-64 min-h-touch rounded-control border border-hairline px-2"
                          />
                        )}
                      </HintedField>
                    </div>
                    <div className="mt-4 flex items-center gap-4">
                      <button type="submit" disabled={busy} className="btn btn-primary min-h-touch px-5 transition-colors duration-state disabled:opacity-60">Record return</button>
                      <button type="button" disabled={busy} onClick={() => setReturning(null)} className="text-sm text-ink-secondary hover:underline disabled:opacity-60">Cancel</button>
                    </div>
                  </form>
                </section>
              )}

              {/*
                Closing a part-delivered order (T-142, D-26).

                Rajeev's rice: 500 kg ordered, the vendor has 300 and sends it straight away so the
                kitchen can cook, 200 to follow in two days. "The 200 KG should still be tied to the
                PO that raised and sent the 500KG rice order and it should sit in a partially
                delivered state and the clock keeps ticking." So the balance stays with the vendor,
                and THIS is the decision that ends it — releasing what never came back to the
                shopping list, where it is suggested again with a fresh date.

                Above the cancel block on purpose. For a part-delivered order this is the act that
                is meant, and cancelling one would withdraw an order 300 kg of real rice arrived
                against; a person scrolling to the foot of the page should meet the right door
                first.

                THE SCORE IS SHOWN AND THERE IS NO CONTROL ON IT. Rajeev asked for an adjustment
                here and then ruled against his own request — "Let us not let the admin adjust the
                score. Just show it to them." What a person may say is one of three names, and the
                two that say something about the supplier require a sentence.
              */}
              {canClose && hasPoNumber && (
                <section className="card mb-8 px-6 py-5" aria-labelledby="close-heading">
                  <h2 id="close-heading" className="text-lg">Close this order, part delivered</h2>
                  <p className="mt-1 max-w-prose text-sm text-ink-secondary">
                    {po.vendorName} still owes what never arrived on {po.poNumber}, and while this
                    order is open it stays with them. Closing it says the rest is not coming: what
                    was never delivered goes back on the shopping list, and this order is finished
                    for good.
                  </p>

                  <div className="mt-4 rounded border border-hairline bg-sunken px-4 py-3">
                    <h3 className="text-sm font-medium text-ink">
                      What {po.vendorName} scored on this order
                    </h3>
                    <div className="mt-1">{deliveryScoreLine(data?.deliveryScore, po.vendorName)}</div>
                    {/* The one case where the figure above is real and the scorecard still will not
                        count it (T-137, D-25). Said here rather than left for somebody to discover
                        on the report, because a person choosing between "let us down" and "made it
                        right" is entitled to know their choice changes nothing for this order. */}
                    {po.sentAfterLeadTime && (
                      <p className="mt-2 max-w-prose text-sm text-ink-muted">
                        We sent this order after {po.vendorName} asked to be given, so it is already
                        left out of their delivery record whatever is chosen below.
                      </p>
                    )}
                  </div>

                  <form className="mt-4" aria-label="Close this order, part delivered" onSubmit={async (e) => {
                    e.preventDefault();
                    const note = closeNote.trim();
                    const ok = await run(
                      (t) => api.closePurchaseOrder(id, closeOutcome, note === "" ? null : note, t),
                      "We couldn’t close that order."
                    );
                    if (ok) {
                      // Reset to the outcome that claims nothing, exactly as the cancellation
                      // resets its tick: a statement about a supplier must never be left sitting in
                      // a form after the act it belonged to.
                      setCloseOutcome("AS_COMPUTED");
                      setCloseNote("");
                      setEditing(false);
                    }
                  }}>
                    <fieldset>
                      <legend className="text-sm font-medium text-ink">
                        What happened with {po.vendorName}?
                      </legend>
                      {CLOSE_OUTCOMES.map((option) => (
                        <label key={option.value} className="mt-3 flex items-start gap-2 text-sm">
                          <input
                            type="radio"
                            name="closeOutcome"
                            value={option.value}
                            checked={closeOutcome === option.value}
                            onChange={() => setCloseOutcome(option.value)}
                            className="mt-1 h-4 w-4 shrink-0 accent-accent"
                          />
                          <span>
                            <span className="text-ink">{option.label}</span>
                            <span className="mt-1 block max-w-prose text-ink-secondary">
                              {option.effect}
                            </span>
                          </span>
                        </label>
                      ))}
                    </fieldset>

                    {/*
                      "Anything other than as computed requires a sentence" (D-26), said on the way
                      in rather than only refused on the way out. A permanent statement about
                      somebody else's business with no explanation beside it is the record somebody
                      will want to read back in a year and be unable to — which is the same reason
                      the cancellation keeps its reason required beside its tick box.

                      Offered on the third option too, as optional, because a temple that closed an
                      order because it only needed 300 after all may still want to say so.
                    */}
                    <label className="mt-4 flex flex-col gap-1 text-sm text-ink-secondary">
                      <span className="pl-field-inset font-medium text-ink">
                        {closeNeedsASentence ? "Why" : "Why, if you want to say"}
                      </span>
                      <input
                        name="closeNote"
                        value={closeNote}
                        onChange={(e) => setCloseNote(e.target.value)}
                        required={closeNeedsASentence}
                        maxLength={500}
                        aria-describedby={closeNeedsASentence ? "close-note-hint" : undefined}
                        className="min-h-touch rounded-control border border-hairline px-3"
                      />
                    </label>
                    {/* Outside the label and pointed at by aria-describedby, not inside it. A hint
                        nested in a label becomes part of the field's accessible NAME, so the box
                        would be called "Why This goes on Govind Wholesale's record beside the
                        outcome" to a screen reader and to anything else that asks. */}
                    {closeNeedsASentence && (
                      <p id="close-note-hint" className="mt-1 max-w-prose text-sm text-ink-muted">
                        This goes on {po.vendorName}’s record beside the outcome.
                      </p>
                    )}

                    <div className="mt-4">
                      <button type="submit" disabled={busy} className="btn btn-primary min-h-touch px-5 transition-colors duration-state disabled:opacity-60">
                        Close order
                      </button>
                    </div>
                  </form>
                </section>
              )}

              {/*
                Cancelling the purchase order, at the foot of the page — on the view screen and on
                the edit screen alike (D-24 §3 and §4, Rajeev, 2026-09-10).

                It used to be a button called "Cancel" in the bank at the top, which opened this
                panel just under the header. His objection was the word: "Is it cancelling out of
                this screen OR cancelling the PO?" — and the answer, that it ends the order the
                vendor may already be filling, is not something a person should learn by pressing
                it. "Cancelling a purchase order is a deliberate act... Somebody should have to go
                there on purpose."

                So: the foot of the page, open, under its own heading, with the reason box and the
                tick box in it. No toggle. The deliberateness is the journey down the page rather
                than a disclosure to expand — a collapsed panel at the bottom would be the same
                two presses as before with more scrolling, which is ceremony rather than intent.

                WHAT IT IS GATED ON, and this is load-bearing for T-134. `hasPoNumber` — the
                purchase order having a number — and never "which screen is this". T-134 opens the
                edit form above as a panel over the shopping list for an order that does not exist
                yet, and reuses this file rather than copying it. Rajeev: show the cancel control
                "only when there is a purchase-order number". A condition written about the route,
                or about a `mode` prop, is a condition the next caller has to remember; this one
                answers itself from the data.
              */}
              {canCancel && hasPoNumber && (
                <section className="card mb-8 px-6 py-5" aria-labelledby="cancel-heading">
                  <h2 id="cancel-heading" className="text-lg">Cancel this purchase order</h2>
                  <p className="mt-1 max-w-prose text-sm text-ink-secondary">
                    This calls off {po.poNumber} with {po.vendorName}. It cannot be undone — raise a
                    new order if it is needed again.
                  </p>
                  <form className="mt-3" aria-label="Cancel this purchase order" onSubmit={async (e) => {
                    e.preventDefault();
                    const form = e.currentTarget;
                    const reason = String(new FormData(form).get("reason") ?? "").trim();
                    const ok = await run(
                      // wasSent, not the state alone: the box is not rendered on an unsent order,
                      // so the state cannot be true there today - but the endpoint refuses the
                      // pairing outright (KMS-400147), and a screen that could send a request it
                      // knows will be refused is a screen waiting to show somebody an error it
                      // could have avoided.
                      (t) => api.cancelPurchaseOrder(id, reason, canBlameVendor && vendorAbandoned, t),
                      "We couldn’t cancel that order."
                    );
                    if (ok) {
                      // The panel no longer closes — it is part of the page — so the form is
                      // emptied instead, and the tick with it. A claim about a supplier must never
                      // be left sitting there after the act it belonged to, waiting for somebody
                      // to press a button meaning something else.
                      form.reset();
                      setVendorAbandoned(false);
                      // Nothing is being edited any more either: a cancelled order cannot be, and
                      // leaving the form open would offer a Save the server would refuse.
                      setEditing(false);
                    }
                  }}>
                    <div className="flex flex-wrap items-end gap-3">
                      <label className="flex flex-1 flex-col gap-1 text-sm text-ink-secondary">
                        <span className="pl-field-inset font-medium text-ink">Reason</span>
                        <input name="reason" required className="min-h-touch rounded-control border border-hairline px-3" />
                      </label>
                      <button type="submit" disabled={busy} className="min-h-touch rounded bg-danger px-5 text-ink-inverse disabled:opacity-60">Cancel order</button>
                    </div>

                    {/*
                      The one new fact anybody enters for the whole of T-124, and Rajeev's own
                      wording of it (2026-09-09). Ticking it is a permanent statement about somebody
                      else's business — it scores this order 0% on the vendor's record and names them
                      as a no-show — so it starts unticked and stays that way unless a person means
                      it. Two defects this week came from boxes that were already ticked, both
                      recording things nobody meant to say.

                      The reason field above stays required either way: the box carries the fact and
                      the sentence carries the story.
                    */}
                    {/*
                      And it is only offered once the order has been sent (T-129, Rajeev's ruling of
                      2026-09-10). A draft nobody sent is an order the vendor has never heard of, so
                      there is nothing to hold them to; the coordinator ticked the box on exactly
                      such a draft on staging and gave a dairy 0% for it.

                      The absence is said out loud rather than left as a gap. A control that
                      disappears with no explanation reads as a bug or as a missing permission, and
                      the person cancelling is the one who most needs to know that this cancellation
                      will not count against anybody.
                    */}
                    {canBlameVendor ? (
                      <label className="mt-4 flex items-start gap-2 text-sm">
                        <input
                          type="checkbox"
                          name="vendorAbandoned"
                          checked={vendorAbandoned}
                          onChange={(e) => setVendorAbandoned(e.target.checked)}
                          className="mt-1 h-4 w-4 shrink-0 accent-accent"
                        />
                        <span>
                          <span className="text-ink">Vendor Never Delivered this Order</span>
                          <span className="mt-1 block max-w-prose text-ink-secondary">
                            This counts against the vendor’s delivery record. Leave it alone if we are
                            cancelling for our own reasons.
                          </span>
                        </span>
                      </label>
                    ) : wasSent ? (
                      /*
                        Sent, but sent after the vendor's own lead time (T-137, D-25). The box is
                        not offered for the same reason it is not offered on a draft: we are not
                        entitled to the claim. Rajeev: "That is a FAVOR we are asking" — we asked
                        for something their agreed notice period could not deliver, so their not
                        delivering it is not a failure of theirs to record.

                        Said out loud rather than left as a gap, like the sentence below it. A
                        control that disappears with no explanation reads as a bug or a missing
                        permission, and the person cancelling is the one who most needs to know
                        this cancellation will not count against anybody.
                      */
                      <p className="mt-4 max-w-prose text-sm text-ink-secondary">
                        We sent this order after {po.vendorName} asked to be given —{" "}
                        {leadTimeSaid(po.leadTimeDays)} — so there is nothing to hold them to.
                        Cancelling it counts against nobody’s delivery record.
                      </p>
                    ) : (
                      <p className="mt-4 max-w-prose text-sm text-ink-secondary">
                        This order was never sent, so there is nothing to hold the vendor to.
                        Cancelling it counts against nobody’s delivery record.
                      </p>
                    )}
                  </form>
                </section>
              )}
            </>
          )}
        </div>
      </main>
    </div>
  );
}
