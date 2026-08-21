"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { api, toApiError, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * Record an invoice — seven fields, so a screen of its own.
 *
 * <p>A direct invoice is a cash-market buy with no purchase order behind it, and the tick that says
 * so swaps one field for another rather than adding a second. It sits above the form because it
 * decides what the form asks, and a person who ticks it after typing a purchase order number would
 * have typed it for nothing.
 */

const FORM = "record-invoice";
const FIELD = "min-h-touch rounded border border-hairline bg-canvas px-3";

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
  const { data: vendorsData } = useAuthedQuery((t) => api.listVendors(true, t));
  const vendors = vendorsData ?? [];

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [isDirect, setIsDirect] = useState(false);

  async function record(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    const invoiceNumber = String(f.get("invoiceNumber") ?? "").trim();
    setBusy(true);
    setError(null);
    try {
      const res = await api.recordInvoice(
        {
          vendorId: String(f.get("vendorId") ?? ""),
          purchaseOrderId: isDirect ? null : emptyToNull(String(f.get("purchaseOrderId") ?? "")),
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
      setError(toApiError(e, "We couldn't record that invoice."));
      setBusy(false);
    }
  }

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
        <input type="checkbox" checked={isDirect} onChange={(e) => setIsDirect(e.target.checked)} />
        Direct, with no purchase order
      </label>

      <form id={FORM} className="grid grid-cols-2 gap-4" aria-label="Record an invoice" onSubmit={record}>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Vendor</span>
          <select name="vendorId" required className={FIELD}>
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
            <span className="pl-field-inset font-medium text-ink">Purchase order id</span>
            <input name="purchaseOrderId" required placeholder="PO uuid" className={FIELD} />
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
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Invoice date</span>
          <input name="invoiceDate" type="date" required className={FIELD} />
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Due date</span>
          <input name="dueDate" type="date" className={FIELD} />
        </label>
        <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Scan reference</span>
          <input name="scanRef" placeholder="Uploaded scan id or link" className={FIELD} />
          <span className="pl-field-inset text-sm text-ink-secondary">Where the receipt goes</span>
        </label>
      </form>
    </FocusScreen>
  );
}

function emptyToNull(s: string): string | null {
  const t = s.trim();
  return t === "" ? null : t;
}
