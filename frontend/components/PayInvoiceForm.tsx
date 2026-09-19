"use client";

import { useState } from "react";

import { AttachmentUpload } from "@/components/AttachmentUpload";
import { Button } from "@/components/ds/Button";
import { Form } from "@/components/ds/Form";
import { ErrorNotice } from "@/components/ErrorNotice";
import {
  api,
  toApiError,
  type ApiError,
  type AttachmentView,
  type RecordInvoicePaymentInput,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { money, todayIso } from "@/lib/format";

/**
 * Paying an invoice from its own page (R-PAY-1, R-PAY-2; T-274). The `dev-invoices` mock's design D
 * `PayForm`, made real.
 *
 * <p><b>Who sees it.</b> Only a person allowed to pay, and that decision is the page's, not this
 * form's: the page renders it for the same reader it shows the payment list to (MANAGE_VENDOR_PAYMENTS,
 * the Temple Admin today). R-PAY-1 says who can pay must not widen, and one check in one place is how
 * it stays that way. The server refuses everyone else in any case.
 *
 * <p><b>Amount defaults to what is left</b> on the invoice (R-PAY-1), because the usual payment settles
 * the bill. It can be less (a part payment), never more: more than is owed is said under the box in the
 * mock's words, and the server refuses it too (KMS-400070).
 *
 * <p><b>Proof is required, and what counts as proof depends on the method</b> (R-PAY-2, Rajeev
 * 2026-09-19):
 * <ul>
 *   <li>UPI, bank transfer or cheque: one upload, the receipt or screenshot (PAYMENT_PROOF).</li>
 *   <li>Cash has no bank record behind it, so it is proved by the person who took it: their name, a
 *       photo of the note they signed (CASH_SIGNED_NOTE), and a photo of them or their ID card
 *       (CASH_RECEIVER_PHOTO). No ID-type field: staff are told in training what to photograph.</li>
 * </ul>
 * Changing the method swaps the fields. Where the reference box stood, cash asks for the receiver's
 * name instead, because for cash the person is what identifies the payment. A file chosen under one
 * method is kept if the person switches away and back, and is only ever sent with its own method: the
 * payload is built from the method on screen, so a stray upload never travels (the server would ignore
 * it anyway, T-272, but it is not sent).
 *
 * <p><b>Required, in the form's own words.</b> The boxes carry `required` and `Form` names them
 * ("Received by is required"). An upload cannot be `required` in HTML, so `AttachmentUpload` hears the
 * form's submit and says "Proof of payment is required" itself. This component keeps its own check as
 * the twin guard, so nothing is sent without the proof even if either of those were removed.
 */
export interface PayInvoiceFormProps {
  invoiceId: string;
  /** What is still owed on the invoice: the amount's default and its ceiling. */
  owed: number;
  onCancel: () => void;
  /** Called with the amount once the server has recorded the payment. */
  onPaid: (amount: number) => void;
}

type Method = RecordInvoicePaymentInput["method"];

/** The contract's four methods, in the mock's order and words. */
export const PAYMENT_METHODS: { value: Method; label: string }[] = [
  { value: "UPI", label: "UPI" },
  { value: "BANK_TRANSFER", label: "Bank transfer" },
  { value: "CHEQUE", label: "Cheque" },
  { value: "CASH", label: "Cash" },
];

/** R-PAY-2's hints, word for word (curly quotes by the conductor's ruling of 2026-09-19). */
export const PROOF_HINT = "Upload proof of payment — a receipt, UPI screenshot or bank confirmation.";
export const NOTE_HINT =
  "A note signed by the person who received the cash, e.g. ‘Received ₹1,030 in cash from ISKCON South Bengaluru’";
export const PHOTO_HINT = "Their ID card, or a photo of them, so we can recognise who took the money.";

/** The mock's one input look: the 44px touch height, the control corner. */
const FIELD = "min-h-touch rounded-control border border-hairline bg-canvas px-3 tabular-nums";
const LABEL = "flex flex-col gap-1 text-sm text-ink-secondary";
const LABEL_TEXT = "pl-field-inset font-medium text-ink";
/**
 * The app's "(required)" beside a required box's name, exactly as `Field` and `AttachmentUpload` draw
 * it (R-PAY-2, T-310). The cash form showed it on its two uploads and not on "Received by" beside
 * them, which is just as required; Amount and Paid on were missing it the same way. `Form` still names
 * the box without it ("Received by is required"): it drops this grey span when it reads the label.
 */
const REQUIRED = <span className="ml-1 text-ink-muted">(required)</span>;

const figure = (text: string): number | null => {
  if (text.trim() === "") return null;
  const n = Number(text);
  return Number.isFinite(n) ? n : null;
};
const round2 = (n: number) => Math.round(n * 100) / 100;

export function PayInvoiceForm({ invoiceId, owed, onCancel, onPaid }: PayInvoiceFormProps) {
  const { getToken } = useAuth();
  const today = todayIso();
  const [amount, setAmount] = useState(String(round2(owed)));
  const [paidOn, setPaidOn] = useState(today);
  const [method, setMethod] = useState<Method>("UPI");
  const [reference, setReference] = useState("");
  const [receivedBy, setReceivedBy] = useState("");
  const [proof, setProof] = useState<AttachmentView | null>(null);
  const [note, setNote] = useState<AttachmentView | null>(null);
  const [photo, setPhoto] = useState<AttachmentView | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const a = figure(amount);
  const tooMuch = a != null && a > owed + 0.005;
  const cash = method === "CASH";
  const proved = cash ? note != null && photo != null && receivedBy.trim() !== "" : proof != null;

  const upload = (kind: "PAYMENT_PROOF" | "CASH_SIGNED_NOTE" | "CASH_RECEIVER_PHOTO") => async (file: File) =>
    api.uploadPaymentFile(file, kind, await getToken());

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // The twin guard: Form has already named a blank box, and each upload names itself.
    if (a == null || a <= 0 || tooMuch || !proved) return;
    // Exactly the method's own fields (RecordInvoicePaymentInput). Cash has no reference; a blank
    // reference is left out rather than sent as "".
    const input: RecordInvoicePaymentInput = cash
      ? {
          paidOn,
          amount: a,
          method,
          receivedByName: receivedBy.trim(),
          signedNoteAttachmentId: note!.id,
          receiverPhotoAttachmentId: photo!.id,
        }
      : {
          paidOn,
          amount: a,
          method,
          ...(reference.trim() ? { reference: reference.trim() } : {}),
          proofAttachmentId: proof!.id,
        };
    setBusy(true);
    setError(null);
    try {
      await api.recordInvoicePayment(invoiceId, input, await getToken());
      onPaid(a);
    } catch (caught) {
      setError(toApiError(caught, "We couldn’t record that payment."));
      setBusy(false);
    }
  }

  return (
    <Form
      onSubmit={submit}
      aria-label="Pay this invoice"
      className="mt-4 grid gap-4 rounded-control border border-hairline bg-sunken px-4 py-4"
    >
      <p className="font-semibold text-ink">Record a payment</p>
      <div className="flex flex-wrap gap-x-4 gap-y-3">
        <label className={LABEL}>
          <span className={LABEL_TEXT}>
              Amount (₹)
              {REQUIRED}
            </span>
          <input
            type="number"
            inputMode="decimal"
            name="amount"
            min="0"
            data-more-than="0"
            step="any"
            required
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            aria-invalid={tooMuch || undefined}
            className={`${FIELD} w-32 min-w-24 ${tooMuch ? "border-danger" : ""}`}
          />
          {tooMuch && (
            <span className="pl-field-inset text-sm text-danger">{`That’s more than the ${money(owed, "INR")} still owed.`}</span>
          )}
        </label>
        <label className={LABEL}>
          <span className={LABEL_TEXT}>
              Paid on
              {REQUIRED}
            </span>
          <input
            type="date"
            name="paidOn"
            required
            value={paidOn}
            max={today}
            onChange={(e) => setPaidOn(e.target.value)}
            className={FIELD}
          />
        </label>
        <label className={LABEL}>
          <span className={LABEL_TEXT}>Method</span>
          <select
            name="method"
            value={method}
            onChange={(e) => setMethod(e.target.value as Method)}
            className={FIELD}
          >
            {PAYMENT_METHODS.map((m) => (
              <option key={m.value} value={m.value}>
                {m.label}
              </option>
            ))}
          </select>
        </label>
        {/* Cash has no reference number. The person who took it is what identifies it, so the same
            place in the row asks for their name instead (the mock's construction). */}
        {cash ? (
          <label key="receivedBy" className={`${LABEL} grow basis-48`}>
            <span className={LABEL_TEXT}>
              Received by
              {REQUIRED}
            </span>
            <input
              name="receivedByName"
              required
              maxLength={200}
              value={receivedBy}
              onChange={(e) => setReceivedBy(e.target.value)}
              placeholder="Their full name"
              className={FIELD}
            />
          </label>
        ) : (
          <label key="reference" className={`${LABEL} grow basis-48`}>
            <span className={LABEL_TEXT}>Reference</span>
            <input
              name="reference"
              value={reference}
              onChange={(e) => setReference(e.target.value)}
              placeholder="Bank, UPI or cheque number"
              className={FIELD}
            />
          </label>
        )}
      </div>

      {cash ? (
        <>
          <AttachmentUpload
            key="note"
            label="Signed note"
            hint={NOTE_HINT}
            required
            value={note}
            onChange={setNote}
            upload={upload("CASH_SIGNED_NOTE")}
          />
          <AttachmentUpload
            key="photo"
            label="Photo of the person who took the cash"
            hint={PHOTO_HINT}
            required
            value={photo}
            onChange={setPhoto}
            upload={upload("CASH_RECEIVER_PHOTO")}
          />
        </>
      ) : (
        <AttachmentUpload
          key="proof"
          label="Proof of payment"
          hint={PROOF_HINT}
          required
          value={proof}
          onChange={setProof}
          upload={upload("PAYMENT_PROOF")}
        />
      )}

      {error && <ErrorNotice error={error} />}

      <div className="flex flex-wrap justify-end gap-3">
        <Button variant="ghost" onClick={onCancel} disabled={busy} className="max-sm:flex-1">
          Cancel
        </Button>
        <Button type="submit" busy={busy} className="max-sm:flex-1">
          Record payment
        </Button>
      </div>
    </Form>
  );
}
