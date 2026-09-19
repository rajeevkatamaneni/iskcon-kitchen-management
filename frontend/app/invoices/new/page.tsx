"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { AttachmentUpload } from "@/components/AttachmentUpload";
import { ErrorNotice } from "@/components/ErrorNotice";
import {
  InvoiceItemsTable,
  fromDelivery,
  lineInput,
  linePacks,
  num,
  orderPackOf,
  packOrQuantity,
  type InvoiceLineDraft,
} from "@/components/InvoiceItemsTable";
import { EMPTY_TOTALS, InvoiceTotals, draftCheck, figure, type InvoiceTotalsDraft } from "@/components/InvoiceTotals";
import type { ComboChoice, ComboItem } from "@/components/ItemCombobox";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { DateRange } from "@/components/ds/DateRange";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { Form } from "@/components/ds/Form";
import { InlineNotice } from "@/components/ds/InlineNotice";
import {
  api,
  toApiError,
  type ApiError,
  type AttachmentView,
  type BillableDeliveryView,
  type IngredientView,
  type RecordInvoiceInput,
  type VendorDetailView,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { convertQuantity, shortDate, unitLabel } from "@/lib/format";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * Create an invoice (R-INV-1..5; design A of `docs/work/mocks/dev-invoices.page.tsx`; T-273).
 *
 * <p><b>What changed, and why.</b> Until T-273 this was "Record an invoice": seven fields, a single
 * typed Amount and a "Scan reference" text box that pointed at a scan filed somewhere else. The
 * "Issued from the temple store" report showed rice at ₹0 because an invoice held a total and no
 * items, so no price ever came from a bill (PROCUREMENT-REQUIREMENTS §0). Now a bill is typed in item
 * by item, tied to the deliveries it bills, with a copy of the bill uploaded and its totals checked.
 * The form opens directly from "Create an invoice" (R-INV-1).
 *
 * <p><b>In the mock's order:</b> the Direct tick; the header fields (for a direct invoice vendor and
 * description on the first row, the number and the two dates on the second; billing deliveries, the
 * four boxes in one row from 1280 up, two below, and the deliveries full width under them, T-290);
 * the copy of the bill (R-INV-2); the items (R-INV-3, R-INV-4, {@link InvoiceItemsTable}); the
 * totals on the right (R-INV-5, {@link InvoiceTotals}). Cancel and "Save invoice" are in the
 * screen's header.
 *
 * <p><b>The vendor is still asked first (T-082), and what is billed depends on it.</b> Where the mock
 * had a "Purchase order" dropdown, R-INV-3 asks for the delivery or deliveries being billed, because
 * vendors usually bill per delivery (Rajeev). So there is one tick box per delivery from this vendor
 * that no standing invoice bills yet, "PO-2026-0044 · delivered 12 Sept · received by Govinda Das ·
 * 45 Kg" (conductor's ruling, 2026-09-19: tick boxes; the mock has no picker, so this is the
 * document's addition; what came was added by T-290, see {@link deliveryLabels}), full width under
 * the header's boxes since T-290. Changing the vendor clears what was ticked, for the reason T-082
 * gave: a stale choice surviving a vendor change is the original mismatch wearing a new control.
 * The server refuses a delivery of another vendor, or one already billed, as well; that is the
 * backstop, this is the fix.
 *
 * <p><b>Direct invoices keep the mock's tick</b> (the clarifier: R-INV-3 does not say how a direct
 * invoice is chosen, so the mock's tick stands). Ticked, the deliveries give way to a Description,
 * optional since the lines say what was bought (conductor's ruling), and the items are typed with
 * the purchase order's type-ahead.
 *
 * <p><b>Saving.</b> `Form` names every blank box that has to be filled (the number, the date, a
 * billed quantity, an amount on a billed line, the grand total) and the upload names itself. What no
 * box can say is listed at the top in the mock's words: no copy of the bill, nothing to bill, and
 * figures that do not add up. Sub total is never sent; the server sums the lines itself and refuses
 * a mismatch with KMS-400168. Its other refusals (KMS-400169, 400170, 400167) arrive as an
 * `ErrorNotice`, as everywhere else. Saved, it goes to the new invoice's page, carrying the number
 * and, when another invoice from this vendor already uses it, the duplicate warning, in the same
 * `recorded` / `duplicate` parameters the list used to read (conductor's ruling: the invoice's page,
 * the next task, reads both and shows the same confirmation and warning).
 */

const FORM = "create-invoice";
const FIELD = "min-h-touch rounded-control border border-hairline bg-canvas px-3";
const LABEL = "flex flex-col gap-1 text-sm text-ink-secondary";
const LABEL_TEXT = "pl-field-inset font-medium text-ink";
/** Billing deliveries at 1280 and up: the four single boxes on one row, the vendor's twice as wide. */
const ONE_ROW = "xl:grid-cols-[2fr_1fr_1fr_1fr]";

/** Module scope, so `useAuthedQuery` sees one fetcher and does not fetch for ever. */
const activeVendors = (token: string | undefined) => api.listVendors(true, token);
const allIngredients = (token: string | undefined) => api.listIngredients(token);

let keySeq = 0;
const newKey = () => `line-${++keySeq}`;
const round2 = (n: number) => Math.round(n * 100) / 100;

/**
 * What came on a delivery, as its tick box says it: the one line's quantity in the order's own terms
 * ("45 Kg", "4 × Bag (25 Kg)", the Delivered column's words), or "4 items" when several came.
 */
function whatCame(d: BillableDeliveryView): string | null {
  if (d.lines.length === 0) return null;
  if (d.lines.length > 1) return `${d.lines.length} items`;
  const l = d.lines[0];
  return packOrQuantity(l.deliveredQty, l.unit, orderPackOf(l.packSizeId, l.packLabel, l.packQuantity));
}

const ordinal = (n: number) => (n === 1 ? "1st" : n === 2 ? "2nd" : n === 3 ? "3rd" : `${n}th`);

/**
 * One label per delivery, in the order given: R-INV-3's words first, "PO-2026-0044 · delivered 12
 * Sept · received by Govinda Das", then what came, "· 45 Kg" (T-290).
 *
 * <p>R-INV-3's example alone names an order and a day, and an order can arrive in parts on one day.
 * VERIFY-D found exactly that: PO-2026-0051 came as 45 Kg and then 5 Kg on 19 Sept, both ticks read
 * the same, and the only way to tell them apart was to tick one and read the Delivered column. What
 * was delivered is what the person holding the bill can match against it, so it goes on every label,
 * not only on the ones that collide: a list whose labels change shape depending on their neighbours
 * reads as two kinds of thing.
 *
 * <p>Two deliveries of one order, on one day, of the same amount would still match. The time of day
 * would separate them, but the API gives the temple's day only (`receivedOn` is a date), and changing
 * that is outside this task; so such labels are numbered in the order they arrived, "· 2nd that day",
 * which relies on the server listing them oldest first.
 */
function deliveryLabels(ds: BillableDeliveryView[]): string[] {
  const base = ds.map((d) => {
    const by = d.receivedByName ? ` · received by ${d.receivedByName}` : "";
    const what = whatCame(d);
    return `${d.poNumber} · delivered ${shortDate(d.receivedOn)}${by}${what ? ` · ${what}` : ""}`;
  });
  const total = new Map<string, number>();
  for (const b of base) total.set(b, (total.get(b) ?? 0) + 1);
  const seen = new Map<string, number>();
  return base.map((b) => {
    if ((total.get(b) ?? 0) < 2) return b;
    const n = (seen.get(b) ?? 0) + 1;
    seen.set(b, n);
    return `${b} · ${ordinal(n)} that day`;
  });
}

/**
 * By order number, then by the day it came (T-290). The server lists them this way too, oldest first
 * within an order (`VendorInvoiceService.billableDeliveries`); sorting here as well keeps the list in
 * order against a server that has not been restarted onto that, and the sort is stable, so deliveries
 * of one order on one day keep the server's order, which the "2nd that day" numbering relies on.
 */
function inOrder(ds: BillableDeliveryView[]): BillableDeliveryView[] {
  return [...ds].sort(
    (a, b) =>
      a.poNumber.localeCompare(b.poNumber, "en", { numeric: true }) || a.receivedOn.localeCompare(b.receivedOn),
  );
}

/** "PO-1", "PO-1 and PO-2", "PO-1, PO-2 and PO-3". */
function listed(xs: string[]): string {
  return xs.length <= 1 ? xs.join("") : `${xs.slice(0, -1).join(", ")} and ${xs[xs.length - 1]}`;
}

/** The unit a newly added ingredient is billed in: its family's large unit, Kg or L, else its own. */
function readableUnit(unit: string): string {
  if (convertQuantity(1, "KG", unit) !== null) return "KG";
  if (convertQuantity(1, "L", unit) !== null) return "L";
  return unit;
}

export default function NewInvoicePage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <CreateInvoiceView />
    </RequireRole>
  );
}

function CreateInvoiceView() {
  const { getToken } = useAuth();
  const router = useRouter();

  const { data: vendorsData } = useAuthedQuery(activeVendors);
  const vendors = vendorsData ?? [];
  const { data: ingredientsData, reload: reloadIngredients } = useAuthedQuery(allIngredients);
  const ingredients: IngredientView[] = ingredientsData ?? [];

  const [isDirect, setIsDirect] = useState(false);
  const [vendorId, setVendorId] = useState("");
  const [description, setDescription] = useState("");
  const [bill, setBill] = useState<AttachmentView | null>(null);
  const [totals, setTotals] = useState<InvoiceTotalsDraft>(EMPTY_TOTALS);
  const [tried, setTried] = useState(false);
  const [problems, setProblems] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  // What is billable from this vendor, re-fetched when the vendor changes (the fetcher closes over it).
  const fetchDeliveries = useCallback(
    (token: string | undefined): Promise<BillableDeliveryView[]> =>
      vendorId && !isDirect ? api.listBillableDeliveries(vendorId, token) : Promise.resolve([]),
    [vendorId, isDirect],
  );
  const { data: deliveriesData, loading: deliveriesLoading, error: deliveriesError } = useAuthedQuery(fetchDeliveries);
  const deliveries = inOrder(deliveriesData ?? []);

  // The vendor's own items, first in a direct invoice's type-ahead.
  const fetchVendor = useCallback(
    (token: string | undefined): Promise<VendorDetailView | null> =>
      vendorId && isDirect ? api.getVendor(vendorId, token) : Promise.resolve(null),
    [vendorId, isDirect],
  );
  const { data: vendorDetail } = useAuthedQuery(fetchVendor);

  /** Receipt ids ticked, and what has been typed on each pulled-in line, keyed by its receipt line. */
  const [chosen, setChosen] = useState<string[]>([]);
  const [pulled, setPulled] = useState<Record<string, InvoiceLineDraft>>({});
  const [directLines, setDirectLines] = useState<InvoiceLineDraft[]>([]);

  function chooseVendor(next: string) {
    setVendorId(next);
    setChosen([]);
    setPulled({});
  }

  function toggle(d: BillableDeliveryView, on: boolean) {
    setChosen((cur) => (on ? [...cur, d.receiptId] : cur.filter((id) => id !== d.receiptId)));
    if (on) {
      setPulled((cur) => {
        const next = { ...cur };
        for (const l of d.lines) next[l.goodsReceiptLineId] ??= fromDelivery(l.goodsReceiptLineId, l);
        return next;
      });
    }
  }

  const chosenDeliveries = deliveries.filter((d) => chosen.includes(d.receiptId));
  const deliveryLines = chosenDeliveries.flatMap((d) => d.lines.map((l) => pulled[l.goodsReceiptLineId]).filter(Boolean));
  const lines = isDirect ? directLines : deliveryLines;
  const byId = new Map(ingredients.map((i) => [i.id, i]));
  const packsFor = (l: InvoiceLineDraft) => (l.oneOff ? [] : linePacks(l, l.ingredientId ? byId.get(l.ingredientId) : undefined));
  const subTotal = round2(lines.reduce((s, l) => s + (num(l.amount) ?? 0), 0));

  function changeLine(key: string, patch: Partial<InvoiceLineDraft>) {
    if (isDirect) setDirectLines((cur) => cur.map((l) => (l.key === key ? { ...l, ...patch } : l)));
    else setPulled((cur) => (cur[key] ? { ...cur, [key]: { ...cur[key], ...patch } } : cur));
  }

  // Focus moves to the billed quantity of a line the moment it is added: the next thing to type.
  const [focusName, setFocusName] = useState<string | null>(null);
  const formRef = useRef<HTMLFormElement>(null);
  useEffect(() => {
    if (!focusName) return;
    const wanted = `${focusName} billed quantity`;
    Array.from(formRef.current?.querySelectorAll<HTMLInputElement>("input[aria-label]") ?? [])
      .find((el) => el.getAttribute("aria-label") === wanted)
      ?.focus();
    setFocusName(null);
  }, [focusName]);

  function chooseItem(c: ComboChoice<ComboItem>) {
    const key = newKey();
    const ingredient = c.kind === "item" ? byId.get(c.item.id) : undefined;
    const line: InvoiceLineDraft =
      c.kind === "item"
        ? {
            key,
            goodsReceiptLineId: null,
            ingredientId: c.item.id,
            name: c.item.name,
            oneOff: false,
            stockUnit: ingredient?.unit ?? "KG",
            orderedQty: null,
            deliveredQty: null,
            orderPack: null,
            billedIn: readableUnit(ingredient?.unit ?? "KG"),
            qty: "",
            amount: "",
          }
        : // A one-off is counted in pieces until somebody says otherwise, as on a purchase order.
          {
            key,
            goodsReceiptLineId: null,
            ingredientId: null,
            name: c.text,
            oneOff: true,
            stockUnit: "PIECES",
            orderedQty: null,
            deliveredQty: null,
            orderPack: null,
            billedIn: "PIECES",
            qty: "",
            amount: "",
          };
    setDirectLines((cur) => [...cur, line]);
    setFocusName(line.name);
  }

  const theirs: ComboItem[] =
    vendorDetail?.vendor?.id === vendorId && vendorId !== ""
      ? [...(vendorDetail?.supplies ?? [])]
          .sort((a, b) => a.ingredientName.localeCompare(b.ingredientName))
          .map((s) => ({ id: s.ingredientId, name: s.ingredientName, detail: unitLabel(s.unit) }))
      : [];
  const everyone: ComboItem[] = [...ingredients]
    .sort((a, b) => a.name.localeCompare(b.name))
    .map((i) => ({ id: i.id, name: i.name, detail: unitLabel(i.unit) }));
  const vendor = vendors.find((v) => v.id === vendorId);

  /** Why Save refuses, in the order a person would fix them (the mock's `savingProblems`). */
  function savingProblems(): string[] {
    const out: string[] = [];
    if (!bill) out.push("Upload a copy of the bill.");
    if (!isDirect && chosen.length === 0)
      out.push("Choose the deliveries this bill is for, or tick Direct, with no purchase order.");
    if (isDirect && directLines.length === 0) out.push("Add the items on the bill.");
    const check = draftCheck(subTotal, totals);
    if (check.grand == null) out.push("Type the grand total from the bill.");
    else if (!check.matches)
      out.push("The sub total, GST, other charges and discount don’t add up to the grand total on the bill.");
    return out;
  }

  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setTried(true);
    const found = savingProblems();
    setProblems(found);
    if (found.length > 0 || !bill) return;

    const f = new FormData(event.currentTarget);
    const invoiceNumber = String(f.get("invoiceNumber") ?? "").trim();
    const dueDate = String(f.get("dueDate") ?? "");
    const note = totals.otherChargesNote.trim();
    const input: RecordInvoiceInput = {
      vendorId,
      description: isDirect && description.trim() !== "" ? description.trim() : null,
      invoiceNumber,
      invoiceDate: String(f.get("invoiceDate") ?? ""),
      dueDate: dueDate === "" ? null : dueDate,
      receiptIds: isDirect ? [] : chosenDeliveries.map((d) => d.receiptId),
      lines: lines.map((l) => lineInput(l, packsFor(l))),
      gstAmount: figure(totals.gst) ?? 0,
      otherCharges: figure(totals.otherCharges) ?? 0,
      otherChargesNote: note === "" ? null : note,
      discount: figure(totals.discount) ?? 0,
      grandTotal: figure(totals.grandTotal) ?? 0,
      billAttachmentId: bill.id,
    };

    setBusy(true);
    setError(null);
    try {
      const res = await api.recordInvoice(input, await getToken());
      const duplicate = res.duplicateWarning ? "&duplicate=1" : "";
      router.push(`/invoices/${res.invoice.id}?recorded=${encodeURIComponent(invoiceNumber)}${duplicate}`);
    } catch (e) {
      setError(toApiError(e, "We couldn’t save that invoice."));
      setBusy(false);
    }
  }

  const poNumbers = Array.from(new Set(chosenDeliveries.map((d) => d.poNumber)));

  return (
    <FocusScreen
      task="Create an invoice"
      activeHref="/invoices"
      actions={
        <>
          <ButtonLink href="/invoices" variant="ghost">
            Cancel
          </ButtonLink>
          <Button type="submit" form={FORM} busy={busy}>
            Save invoice
          </Button>
        </>
      }
    >
      {error && <ErrorNotice error={error} />}
      {problems.length > 0 && (
        <InlineNotice tone="danger" title="This invoice can’t be saved yet.">
          <ul className="list-disc ps-5">
            {problems.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        </InlineNotice>
      )}

      <label className="flex min-h-touch items-center gap-2 text-sm text-ink-secondary">
        <input
          type="checkbox"
          checked={isDirect}
          onChange={(e) => setIsDirect(e.target.checked)}
          className="accent-accent"
        />
        <span>Direct, with no purchase order</span>
      </label>

      <Form ref={formRef} id={FORM} aria-label="Create an invoice" onSubmit={save} className="grid gap-6">
        {/* A direct invoice is laid out as the mock is: Vendor | Description, then Invoice number |
            Invoice date | Due date. Billing deliveries is not (T-290). T-273 put the tick boxes in the
            mock's Purchase order slot beside Vendor, but a select is one box tall and the tick boxes
            are one 44px row per delivery: measured at 1280 with five, the Deliveries column was 220px
            tall and Vendor beside it was empty for 460 × 176px under its box, the dead block the
            layout rule forbids. So the deliveries get the full width under the single boxes, however
            many there are, and nothing is left beside a column that grows.
            The four single boxes then take one row from 1280 up, Vendor twice as wide as the rest
            (measured at 1280: 355px for the vendor, whose longest name today is 164px of text, and
            178px for the number and each date, which show "09/17/2026" and the picker whole). That
            keeps the header the height it had, 328px with five deliveries and 152px with one, rather
            than adding a row. Below 1280 the dates would be too narrow on one row, so they take two
            even rows, Vendor | Invoice number and Invoice date | Due date; on a phone, one box a row.
            The mock never showed a picker (R-INV-3 added it), so no drawn layout is departed from
            for this mode, only the slot the mock gave its dropdown. */}
        <div className={`grid gap-4 sm:grid-cols-6 ${isDirect ? "" : ONE_ROW}`}>
          <label className={`${LABEL} sm:col-span-3 ${isDirect ? "" : "xl:col-span-1"}`}>
            <span className={LABEL_TEXT}>Vendor</span>
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
          {isDirect && (
            <label className={`${LABEL} sm:col-span-3`}>
              <span className={LABEL_TEXT}>Description</span>
              <input
                name="description"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="Cash market vegetables"
                className={FIELD}
              />
            </label>
          )}
          <label className={`${LABEL} ${isDirect ? "sm:col-span-2" : "sm:col-span-3 xl:col-span-1"}`}>
            <span className={LABEL_TEXT}>Invoice number</span>
            <input name="invoiceNumber" required className={FIELD} />
          </label>
          {/* An invoice cannot fall due before it was issued. */}
          <DateRange
            from={{ name: "invoiceDate", label: "Invoice date", required: true }}
            to={{ name: "dueDate", label: "Due date" }}
            className={FIELD}
            wrapper={`${LABEL} ${isDirect ? "sm:col-span-2" : "sm:col-span-3 xl:col-span-1"}`}
          />
          {!isDirect && (
            <div role="group" aria-labelledby="deliveries-label" className={`${LABEL} sm:col-span-6 xl:col-span-4`}>
              <span id="deliveries-label" className={LABEL_TEXT}>
                Deliveries being billed
              </span>
              <DeliveryChoices
                vendorChosen={vendorId !== ""}
                loading={deliveriesLoading}
                failed={deliveriesError}
                deliveries={deliveries}
                chosen={chosen}
                onToggle={toggle}
              />
            </div>
          )}
        </div>

        <AttachmentUpload
          label="Copy of the bill"
          hint="Upload a copy of the bill — a photo, PDF or scan."
          required
          value={bill}
          onChange={setBill}
          upload={async (file) => api.uploadBill(file, await getToken())}
          invalid={tried && !bill}
        />

        <div className="grid gap-2">
          <h2 className="text-base font-semibold text-ink">Items</h2>
          <p className="text-sm text-ink-secondary">
            {isDirect
              ? "Add each item on the bill. Type the amount printed for the line, and the rate is worked out."
              : poNumbers.length > 0
                ? `Pulled in from ${listed(poNumbers)}. Type the quantity and the amount printed on the bill for each line.`
                : "Choose the deliveries this bill is for, and their items appear here."}
          </p>
        </div>

        {(isDirect || deliveryLines.length > 0) && (
          <InvoiceItemsTable
            mode={isDirect ? "direct" : "delivery"}
            lines={lines}
            onChange={changeLine}
            ingredients={ingredients}
            onPackAdded={reloadIngredients}
            tried={tried}
            onRemove={(key) => setDirectLines((cur) => cur.filter((l) => l.key !== key))}
            onChoose={chooseItem}
            firstItems={theirs}
            firstGroupLabel={vendor ? `Sold by ${vendor.name}` : "Sold by this vendor"}
            otherItems={everyone}
          />
        )}

        <div className="grid border-t border-hairline pt-5">
          <InvoiceTotals subTotal={subTotal} value={totals} onChange={setTotals} tried={tried} />
        </div>
      </Form>
    </FocusScreen>
  );
}

/** The deliveries a bill can be for: one tick box each, or a line saying why there are none. */
function DeliveryChoices({
  vendorChosen,
  loading,
  failed,
  deliveries,
  chosen,
  onToggle,
}: {
  vendorChosen: boolean;
  loading: boolean;
  failed: ApiError | null;
  deliveries: BillableDeliveryView[];
  chosen: string[];
  onToggle: (d: BillableDeliveryView, on: boolean) => void;
}) {
  const say = (text: string) => <span className="flex min-h-touch items-center pl-field-inset text-ink-secondary">{text}</span>;
  if (!vendorChosen) return say("Choose a vendor first.");
  if (failed) return <ErrorNotice error={failed} />;
  if (loading) return say("Loading deliveries…");
  if (deliveries.length === 0)
    return say("Nothing from this vendor is waiting to be billed. For a cash-market buy, tick Direct above.");
  const labels = deliveryLabels(deliveries);
  return (
    <div className="grid">
      {deliveries.map((d, i) => (
        <label key={d.receiptId} className="flex min-h-touch items-center gap-2 pl-field-inset text-ink">
          <input
            type="checkbox"
            checked={chosen.includes(d.receiptId)}
            onChange={(e) => onToggle(d, e.target.checked)}
            className="accent-accent"
          />
          <span>{labels[i]}</span>
        </label>
      ))}
    </div>
  );
}
