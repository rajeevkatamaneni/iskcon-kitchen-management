import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { AttachmentView, InvoicePaymentView, VendorInvoiceDetailView } from "@/lib/api";

/**
 * Paying an invoice from its page (R-PAY-1, R-PAY-2; T-274).
 *
 * <p>The payload assertions check `Object.keys` before values (docs/work/README.md, lesson 4): a key
 * that should be absent — a proof id on a cash payment, a reference on cash — reads the same as
 * `undefined` to `objectContaining`, so only the key list can show it was never sent.
 */

const h = vi.hoisted(() => ({
  auth: { current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } },
  getToken: async () => "test-token",
  getInvoice: vi.fn(),
  listInvoicePayments: vi.fn(),
  recordInvoicePayment: vi.fn(),
  uploadPaymentFile: vi.fn(),
  invoiceBill: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({ id: "inv1" }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/invoices/inv1",
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...h.auth.current, getToken: h.getToken }),
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      getInvoice: h.getInvoice,
      listInvoicePayments: h.listInvoicePayments,
      recordInvoicePayment: h.recordInvoicePayment,
      uploadPaymentFile: h.uploadPaymentFile,
      invoiceBill: h.invoiceBill,
    },
  };
});

import InvoiceDetailPage from "@/app/invoices/[id]/page";
import { NOTE_HINT, PHOTO_HINT, PROOF_HINT } from "@/components/PayInvoiceForm";

const INVOICE: VendorInvoiceDetailView = {
  id: "inv1",
  vendorId: "v1",
  vendorName: "Kalasipalya Vegetable Mandi",
  purchaseOrderId: "po44",
  poNumber: "PO-2026-0044",
  direct: false,
  description: null,
  invoiceNumber: "KVM/2026/0917",
  invoiceDate: "2026-09-17",
  amount: 1030,
  dueDate: "2026-10-01",
  scanRef: null,
  status: "PENDING",
  expectedValue: 1003,
  variance: -45,
  overdue: false,
  voidedAt: null,
  voidReason: null,
  creditedAmount: 0,
  createdAt: "2026-09-17T00:00:00Z",
  lines: [],
  deliveries: [],
  subTotal: null,
  gstAmount: null,
  otherCharges: null,
  otherChargesNote: null,
  discount: null,
  grandTotal: null,
  bill: null,
};

const PAID_300: InvoicePaymentView = {
  id: "p1",
  paidOn: "2026-09-17",
  amount: 300,
  method: "UPI",
  reference: "UPI 4261",
  note: null,
  recordedByName: "Govinda Das",
  reverses: null,
  reversedBy: null,
  reverseReason: null,
  receivedByName: null,
  attachments: [],
  createdAt: "",
};

let seq = 0;
beforeEach(() => {
  seq = 0;
  h.getInvoice.mockReset().mockResolvedValue(INVOICE);
  h.listInvoicePayments.mockReset().mockResolvedValue([PAID_300]);
  h.recordInvoicePayment.mockReset().mockResolvedValue({ id: "p9" });
  h.uploadPaymentFile.mockReset().mockImplementation(
    async (file: File, kind: AttachmentView["kind"]): Promise<AttachmentView> => ({
      id: `${kind}-${++seq}`,
      kind,
      contentType: "image/jpeg",
      sizeBytes: file.size,
      originalName: file.name,
      uploadedAt: "",
    })
  );
  URL.createObjectURL = vi.fn(() => "blob:test");
  URL.revokeObjectURL = vi.fn();
});

async function openForm() {
  render(<InvoiceDetailPage />);
  fireEvent.click(await screen.findByRole("button", { name: "Pay this invoice" }));
  return screen.getByRole("form", { name: "Pay this invoice" });
}

/** The upload box by its label, and a file chosen in it. */
function group(form: HTMLElement, label: string) {
  return within(form).getByRole("group", { name: new RegExp(`^${label}`) });
}
async function choose(form: HTMLElement, label: string, name: string) {
  const input = group(form, label).querySelector<HTMLInputElement>('input[type="file"]')!;
  fireEvent.change(input, { target: { files: [new File(["x"], name, { type: "image/jpeg" })] } });
  await within(group(form, label)).findByText(name);
}
const press = (form: HTMLElement) => fireEvent.click(within(form).getByRole("button", { name: "Record payment" }));

describe("Pay this invoice (R-PAY-1)", () => {
  it("opens the form inline in Payments, the amount at what is left, with date, method and reference", async () => {
    const form = await openForm();
    expect(screen.getByRole("region", { name: "Payments" })).toContainElement(form);
    expect(within(form).getByRole("spinbutton", { name: /^Amount \(₹\)/ })).toHaveValue(730);
    expect(within(form).getByLabelText(/^Paid on/)).toHaveAttribute("type", "date");
    expect(within(form).getByRole("combobox", { name: "Method" })).toHaveValue("UPI");
    expect(within(form).getByRole("textbox", { name: "Reference" })).toBeInTheDocument();
    // The button goes while the form is open, as in the mock.
    expect(screen.queryByRole("button", { name: "Pay this invoice" })).not.toBeInTheDocument();
  });

  it("refuses more than is owed, in the mock's words, and sends nothing", async () => {
    const form = await openForm();
    fireEvent.change(within(form).getByRole("spinbutton", { name: /^Amount \(₹\)/ }), { target: { value: "800" } });
    expect(within(form).getByText("That’s more than the ₹730 still owed.")).toHaveClass("text-danger");
    await choose(form, "Proof of payment", "upi.jpg");
    press(form);
    await new Promise((r) => setTimeout(r, 0));
    expect(h.recordInvoicePayment).not.toHaveBeenCalled();
  });
});

describe("proof is required (R-PAY-2)", () => {
  it("UPI without proof says Proof of payment is required and does not call the API", async () => {
    const form = await openForm();
    expect(within(form).getByText(PROOF_HINT)).toBeInTheDocument();
    press(form);
    expect(await within(form).findByText("Proof of payment is required")).toBeInTheDocument();
    await new Promise((r) => setTimeout(r, 0));
    expect(h.recordInvoicePayment).not.toHaveBeenCalled();
  });

  it("switching to cash swaps the reference and proof for Received by and two photos, and back again", async () => {
    const form = await openForm();
    fireEvent.change(within(form).getByRole("combobox", { name: "Method" }), { target: { value: "CASH" } });
    expect(within(form).queryByRole("textbox", { name: "Reference" })).not.toBeInTheDocument();
    expect(within(form).queryByText(PROOF_HINT)).not.toBeInTheDocument();
    expect(within(form).getByRole("textbox", { name: /^Received by/ })).toBeRequired();
    // …and says so the way every other required field does, like the two uploads beside it (T-310).
    expect(within(form).getByText("Received by").closest("label")).toHaveTextContent("Received by(required)");
    expect(within(form).getByText(NOTE_HINT)).toBeInTheDocument();
    expect(within(form).getByText(PHOTO_HINT)).toBeInTheDocument();
    expect(NOTE_HINT).toBe(
      "A note signed by the person who received the cash, e.g. ‘Received ₹1,030 in cash from ISKCON South Bengaluru’"
    );
    expect(PHOTO_HINT).toBe("Their ID card, or a photo of them, so we can recognise who took the money.");
    expect(PROOF_HINT).toBe("Upload proof of payment — a receipt, UPI screenshot or bank confirmation.");
    // No ID-type field.
    expect(within(form).queryByText(/id type/i)).not.toBeInTheDocument();
    expect(within(form).getAllByRole("combobox")).toHaveLength(1);

    fireEvent.change(within(form).getByRole("combobox", { name: "Method" }), { target: { value: "CHEQUE" } });
    expect(within(form).getByRole("textbox", { name: "Reference" })).toBeInTheDocument();
    expect(within(form).queryByRole("textbox", { name: /^Received by/ })).not.toBeInTheDocument();
    expect(within(form).getByText(PROOF_HINT)).toBeInTheDocument();
  });

  it("cash needs all three: with none, each is named, and nothing is sent", async () => {
    const form = await openForm();
    fireEvent.change(within(form).getByRole("combobox", { name: "Method" }), { target: { value: "CASH" } });
    press(form);
    expect(await within(form).findByText("Received by is required")).toBeInTheDocument();
    expect(within(form).getByText("Signed note is required")).toBeInTheDocument();
    expect(within(form).getByText("Photo of the person who took the cash is required")).toBeInTheDocument();
    await new Promise((r) => setTimeout(r, 0));
    expect(h.recordInvoicePayment).not.toHaveBeenCalled();
  });

  it("cash with the name and the note but no photo still sends nothing", async () => {
    const form = await openForm();
    fireEvent.change(within(form).getByRole("combobox", { name: "Method" }), { target: { value: "CASH" } });
    fireEvent.change(within(form).getByRole("textbox", { name: /^Received by/ }), { target: { value: "Manjunath K." } });
    await choose(form, "Signed note", "note.jpg");
    press(form);
    expect(await within(form).findByText("Photo of the person who took the cash is required")).toBeInTheDocument();
    await new Promise((r) => setTimeout(r, 0));
    expect(h.recordInvoicePayment).not.toHaveBeenCalled();
  });
});

describe("what is sent (RecordInvoicePaymentInput)", () => {
  it("UPI sends exactly its own fields: the proof, the reference, and nothing of cash", async () => {
    const form = await openForm();
    fireEvent.change(within(form).getByRole("textbox", { name: "Reference" }), { target: { value: " UPI 4261 8837 0417 " } });
    fireEvent.change(within(form).getByLabelText(/^Paid on/), { target: { value: "2026-09-18" } });
    await choose(form, "Proof of payment", "upi.jpg");
    expect(h.uploadPaymentFile).toHaveBeenCalledWith(expect.any(File), "PAYMENT_PROOF", "test-token");
    press(form);
    await waitFor(() => expect(h.recordInvoicePayment).toHaveBeenCalledTimes(1));
    const [invoiceId, body, token] = h.recordInvoicePayment.mock.calls[0];
    expect(invoiceId).toBe("inv1");
    expect(token).toBe("test-token");
    expect(Object.keys(body).sort()).toEqual(["amount", "method", "paidOn", "proofAttachmentId", "reference"]);
    expect(body).toEqual({
      paidOn: "2026-09-18",
      amount: 730,
      method: "UPI",
      reference: "UPI 4261 8837 0417",
      proofAttachmentId: "PAYMENT_PROOF-1",
    });
  });

  it("a bank transfer with no reference leaves the reference out rather than sending an empty one", async () => {
    const form = await openForm();
    fireEvent.change(within(form).getByRole("combobox", { name: "Method" }), { target: { value: "BANK_TRANSFER" } });
    fireEvent.change(within(form).getByRole("spinbutton", { name: /^Amount \(₹\)/ }), { target: { value: "500" } });
    await choose(form, "Proof of payment", "neft.pdf");
    press(form);
    await waitFor(() => expect(h.recordInvoicePayment).toHaveBeenCalledTimes(1));
    const body = h.recordInvoicePayment.mock.calls[0][1];
    expect(Object.keys(body).sort()).toEqual(["amount", "method", "paidOn", "proofAttachmentId"]);
    expect(body.method).toBe("BANK_TRANSFER");
    expect(body.amount).toBe(500);
  });

  it("cash sends the receiver and both photos, and no proof or reference, even with a proof chosen earlier", async () => {
    const form = await openForm();
    // A proof chosen under UPI, then the method changed: it must not travel with the cash payment.
    await choose(form, "Proof of payment", "stray.jpg");
    fireEvent.change(within(form).getByRole("textbox", { name: "Reference" }), { target: { value: "stray ref" } });
    fireEvent.change(within(form).getByRole("combobox", { name: "Method" }), { target: { value: "CASH" } });
    fireEvent.change(within(form).getByRole("textbox", { name: /^Received by/ }), { target: { value: "  Manjunath K. " } });
    await choose(form, "Signed note", "note.jpg");
    await choose(form, "Photo of the person who took the cash", "face.jpg");
    expect(h.uploadPaymentFile).toHaveBeenCalledWith(expect.any(File), "CASH_SIGNED_NOTE", "test-token");
    expect(h.uploadPaymentFile).toHaveBeenCalledWith(expect.any(File), "CASH_RECEIVER_PHOTO", "test-token");
    press(form);
    await waitFor(() => expect(h.recordInvoicePayment).toHaveBeenCalledTimes(1));
    const body = h.recordInvoicePayment.mock.calls[0][1];
    expect(Object.keys(body).sort()).toEqual([
      "amount",
      "method",
      "paidOn",
      "receivedByName",
      "receiverPhotoAttachmentId",
      "signedNoteAttachmentId",
    ]);
    expect(body).toEqual({
      paidOn: body.paidOn,
      amount: 730,
      method: "CASH",
      receivedByName: "Manjunath K.",
      signedNoteAttachmentId: "CASH_SIGNED_NOTE-2",
      receiverPhotoAttachmentId: "CASH_RECEIVER_PHOTO-3",
    });
  });

  it("confirms the payment with what is left, closes the form and reloads", async () => {
    const form = await openForm();
    fireEvent.change(within(form).getByRole("spinbutton", { name: /^Amount \(₹\)/ }), { target: { value: "500" } });
    await choose(form, "Proof of payment", "upi.jpg");
    h.listInvoicePayments.mockResolvedValue([PAID_300, { ...PAID_300, id: "p9", amount: 500 }]);
    press(form);
    expect(await screen.findByText("Payment of ₹500 recorded. ₹230 is still owed on this invoice.")).toBeInTheDocument();
    expect(screen.queryByRole("form", { name: "Pay this invoice" })).not.toBeInTheDocument();
    await waitFor(() => expect(h.listInvoicePayments).toHaveBeenCalledTimes(2));
  });
});
