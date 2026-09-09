"use client";

import { useCallback, useState } from "react";
import { DateRange } from "@/components/ds/DateRange";
import { useRouter } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { api, toApiError, type ApiError, type PurchaseOrderView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { STATUS_LABEL } from "@/app/orders/po-status";
import { shortDate } from "@/lib/format";

/**
 * Record an invoice — seven fields, so a screen of its own.
 *
 * <p>A direct invoice is a cash-market buy with no purchase order behind it, and the tick that says
 * so swaps one field for another rather than adding a second. It sits above the form because it
 * decides what the form asks, and a person who ticks it after choosing a purchase order would have
 * chosen it for nothing.
 *
 * <p><strong>The vendor is asked first, and the purchase order is a dropdown of that vendor's own
 * open orders (T-082).</strong> It used to be a text box labelled "Purchase order id" whose
 * placeholder told the reader to paste it from the order — a database UUID, fetched by hand from
 * another screen. Worse than the typing: the vendor was asked separately, as though the two were
 * unrelated facts, when an order already knows its vendor. Nothing checked that the pair agreed, so
 * an invoice against one vendor quoting another vendor's order was accepted and the variance figure
 * — which is about money owed — was then computed from the wrong order.
 *
 * <p>Making the order depend on the vendor removes the mismatch by construction rather than catching
 * it: the wrong order is not on the list to be picked. The server refuses the pair as well
 * (KMS-400145), because the endpoint is reachable without this screen; that guard is the backstop
 * and this is the fix.
 *
 * <p>The direct path stays deliberately small — one tick that swaps the dropdown for a description.
 * A cash-market purchase is the exception; the purchase orders are the real money. Treating the
 * exception as a co-equal branch is exactly what created the hole, because vendor had to be asked
 * independently to serve the case where there is no order to ask it from.
 */

const FORM = "record-invoice";
const FIELD = "min-h-touch rounded-control border border-hairline px-3";

export default function NewInvoicePage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <NewInvoiceView />
    </RequireRole>
  );
}

function NewInvoiceView() {
  const { getToken } = useAuth();
  const router = useRouter();
  // useCallback, not the inline arrow this was: `useAuthedQuery` keys its effect on the fetcher, so
  // a new function object each render re-fetches on every render the fetch itself causes.
  const fetchVendors = useCallback((t: string | undefined) => api.listVendors(true, t), []);
  const { data: vendorsData } = useAuthedQuery(fetchVendors);
  const vendors = vendorsData ?? [];

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [isDirect, setIsDirect] = useState(false);
  const [vendorId, setVendorId] = useState("");
  const [purchaseOrderId, setPurchaseOrderId] = useState("");

  // Re-fetched whenever the vendor changes, because `vendorId` is in the dependency list of the
  // callback and `useAuthedQuery` keys its effect on the fetcher. With no vendor chosen there is
  // nothing to ask for — the resolved empty list keeps the dropdown's states down to one shape.
  const fetchOrders = useCallback(
    (token: string | undefined) =>
      vendorId && !isDirect
        ? api.listOpenPurchaseOrdersForVendor(vendorId, token)
        : Promise.resolve([]),
    [vendorId, isDirect]
  );
  const { data: ordersData, loading: ordersLoading } = useAuthedQuery(fetchOrders);
  const orders = ordersData ?? [];

  // Changing the vendor cannot leave the previous vendor's order selected. React keeps the value of
  // a <select> whose chosen <option> has been removed, so without this the mismatch the whole change
  // exists to prevent would survive one keystroke of it.
  function chooseVendor(next: string) {
    setVendorId(next);
    setPurchaseOrderId("");
  }

  async function record(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    const invoiceNumber = String(f.get("invoiceNumber") ?? "").trim();
    setBusy(true);
    setError(null);
    try {
      const res = await api.recordInvoice(
        {
          vendorId,
          purchaseOrderId: isDirect ? null : emptyToNull(purchaseOrderId),
          description: isDirect ? emptyToNull(String(f.get("description") ?? "")) : null,
          invoiceNumber,
          invoiceDate: String(f.get("invoiceDate") ?? ""),
          amount: Number(f.get("amount") ?? 0),
          dueDate: emptyToNull(String(f.get("dueDate") ?? "")),
          scanRef: emptyToNull(String(f.get("scanRef") ?? "")),
        },
        await getToken()
      );
      // The duplicate warning travels with the confirmation: it is about the invoice just recorded,
      // and the queue it lands in is where somebody would go looking for the other one.
      const duplicate = res.duplicateWarning ? "&duplicate=1" : "";
      router.push(`/invoices?recorded=${encodeURIComponent(invoiceNumber)}${duplicate}`);
    } catch (e) {
      setError(toApiError(e, "We couldn’t record that invoice."));
      setBusy(false);
    }
  }

  const noOpenOrders = !isDirect && vendorId !== "" && !ordersLoading && orders.length === 0;

  return (
    <FocusScreen
      task="Record an invoice"
      who="What this temple owes a vendor"
      activeHref="/invoices"
      actions={
        <>
          <ButtonLink href="/invoices" variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={FORM} disabled={busy}>
            Record invoice
          </Button>
        </>
      }
    >
      {error && <ErrorNotice error={error} />}

      <label className="flex items-center gap-2 text-sm text-ink-secondary">
        <input type="checkbox" checked={isDirect} onChange={(e) => setIsDirect(e.target.checked)}
                className="accent-accent"
              />
        Direct, with no purchase order
      </label>

      <form id={FORM} className="grid grid-cols-2 gap-4" aria-label="Record an invoice" onSubmit={record}>
        {/* Vendor first, and that order is now load-bearing rather than cosmetic: it is what the
            purchase orders below are drawn from. */}
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Vendor</span>
          <select
            name="vendorId"
            required
            value={vendorId}
            onChange={(e) => chooseVendor(e.target.value)}
            className={FIELD}
          >
            <option value="">Choose a vendor…</option>
            {vendors.map((v) => (
              <option key={v.id} value={v.id}>
                {v.name}
              </option>
            ))}
          </select>
        </label>
        {isDirect ? (
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Description</span>
            <input name="description" required placeholder="Cash market vegetables…" className={FIELD} />
          </label>
        ) : (
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Purchase order</span>
            <select
              name="purchaseOrderId"
              required
              disabled={vendorId === ""}
              value={purchaseOrderId}
              onChange={(e) => setPurchaseOrderId(e.target.value)}
              className={FIELD}
            >
              <option value="">{orderPrompt(vendorId, ordersLoading, orders.length)}</option>
              {orders.map((o) => (
                <option key={o.id} value={o.id}>
                  {orderLabel(o)}
                </option>
              ))}
            </select>
            {/* Said here rather than in an error, because there is nothing wrong with the form yet —
                the reader has simply reached a vendor with nothing to bill against, and the way out
                is the tick above rather than a different vendor. */}
            {noOpenOrders && (
              <span className="pl-field-inset text-xs text-ink-secondary">
                This vendor has no open orders. If it was a cash-market buy, tick “Direct, with no
                purchase order” above.
              </span>
            )}
          </label>
        )}
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Invoice number</span>
          <input name="invoiceNumber" required className={FIELD} />
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Amount (₹)</span>
          <input name="amount" type="number" min="0" step="any" required className={FIELD} />
        </label>
        {/* An invoice cannot fall due before it was issued. */}
        <DateRange
          from={{ name: "invoiceDate", label: "Invoice date", required: true }}
          to={{ name: "dueDate", label: "Due date" }}
          className={FIELD}
        />
        {/* No hint under this one. "Where the receipt goes" said less than the label and the
            placeholder already do, and what it implied was untrue: nothing goes anywhere. The
            column behind it is `scan_ref`, a pointer to a scan filed somewhere else — this
            application stores no file at all — so the receipt does not arrive here, only a note
            of where somebody put it. */}
        <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Scan reference</span>
          <input name="scanRef" placeholder="Uploaded scan id or link" className={FIELD} />
        </label>
      </form>
    </FocusScreen>
  );
}

/** What the unchosen first option says, which is different at each of the three points it is read. */
function orderPrompt(vendorId: string, loading: boolean, count: number): string {
  if (vendorId === "") return "Choose a vendor first";
  if (loading) return "Loading orders…";
  return count === 0 ? "No open orders for this vendor" : "Choose an order…";
}

/**
 * The order as a person recognises it: its number, what has happened to it, and — where the order
 * carries one — the date it was wanted by, which is what separates two otherwise identical standing
 * orders to the same vendor.
 */
function orderLabel(o: PurchaseOrderView): string {
  const needed = o.neededBy ? ` · needed ${shortDate(o.neededBy)}` : "";
  return `${o.poNumber} · ${STATUS_LABEL[o.status]}${needed}`;
}

function emptyToNull(s: string): string | null {
  const t = s.trim();
  return t === "" ? null : t;
}
