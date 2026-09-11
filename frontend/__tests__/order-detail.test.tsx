import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { api } from "@/lib/api";
import type {
  GoodsReceiptView, IngredientView, PurchaseOrderDetailView,
} from "@/lib/api";

// The detail page issues three useAuthedQuery calls in a fixed order: PO detail, receipts, and the
// ingredient catalogue the draft-edit picker chooses from. The mock returns them by call index
// modulo length, so it maps correctly on any number of renders — which matters here, because
// opening the edit form re-renders the page.
const { authRef, returnsRef, reloadMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  returnsRef: { current: [] as Array<{ data: unknown; error: null; loading: boolean }>, i: 0 },
  reloadMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({ id: "po1" }),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => {
    const list = returnsRef.current;
    const value = list[returnsRef.i % list.length];
    returnsRef.i += 1;
    return { ...value, reload: reloadMock };
  },
}));

import PurchaseOrderDetailPage from "@/app/orders/[id]/page";

const DETAIL: PurchaseOrderDetailView = {
  order: {
    id: "po1",
    poNumber: "PO-2026-0042",
    vendorId: "v1",
    vendorName: "Govind Wholesale",
    status: "SENT",
    orderDate: "2026-08-01",
    neededBy: "2026-08-20",
    deliveryLocation: "Main store",
    notes: null,
    cancelReason: null,
    vendorAbandoned: false,
    sentAt: "2026-08-01T10:00:00Z",
    cancelledAt: null,
    createdAt: "2026-08-01T09:00:00Z",
  },
  lines: [
    // `description: null` is stated rather than left off. PurchaseOrderLineView declares it
    // required-and-nullable (T-024), so a fixture that omits it does not compile — which is the
    // point of the convention: every construction site says which kind of line this is.
    { id: "l1", ingredientId: "ing1", ingredientName: "Rice", description: null, quantity: 30, unit: "KG", expectedPrice: 45, arrivedOn: null },
  ],
  events: [
    { eventType: "SENT", detail: "PO-2026-0042 sent to vendor", actorName: "Staff A", createdAt: "2026-08-01T10:00:00Z" },
  ],
  // This temple's WhatsApp has actually sent something, so the button is on offer (T-136). Stated
  // on the base fixture because most of these tests are about something else and want the screen
  // in its ordinary state; the tests that are about the gate say `false` for themselves.
  whatsappEverSent: true,
};

const RECEIPTS: GoodsReceiptView[] = [];
const INGREDIENTS: IngredientView[] = [
  { id: "ing1", name: "Rice", category: "Grains", unit: "KG", ekadashiProhibited: false, supply: false, libraryDerived: false, aliases: [], createdAt: "2026-01-01T00:00:00Z" },
  { id: "ing2", name: "Toor Dal", category: "Pulses", unit: "KG", ekadashiProhibited: false, supply: false, libraryDerived: false, aliases: [], createdAt: "2026-01-01T00:00:00Z" },
];

function withDetail(detail: PurchaseOrderDetailView) {
  returnsRef.current = [
    { data: detail, error: null, loading: false },
    { data: RECEIPTS, error: null, loading: false },
    { data: INGREDIENTS, error: null, loading: false },
  ];
  returnsRef.i = 0;
}

// A draft has never been sent, so `sentAt` is null on it. Stated rather than inherited from
// DETAIL: the cancel panel now reads that field to decide whether the vendor can be blamed for
// anything (T-129), and a fixture that says a draft was sent at ten in the morning would have
// tested the opposite of the rule while looking correct.
const DRAFT: PurchaseOrderDetailView = {
  ...DETAIL,
  order: { ...DETAIL.order, status: "DRAFT", sentAt: null },
};

// The two kinds of cancelled order, which is the whole of T-126: the same status and the same
// reason, differing only in whether the temple is holding the vendor responsible for it.
const CANCELLED: PurchaseOrderDetailView = {
  ...DETAIL,
  order: {
    ...DETAIL.order,
    status: "CANCELLED",
    cancelReason: "festival moved to next month",
    cancelledAt: "2026-08-05T09:00:00Z",
  },
};
const ABANDONED: PurchaseOrderDetailView = {
  ...DETAIL,
  order: {
    ...DETAIL.order,
    status: "CANCELLED",
    cancelReason: "never answered the phone",
    vendorAbandoned: true,
    cancelledAt: "2026-08-05T09:00:00Z",
  },
};

describe("purchase order detail", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    withDetail(DETAIL);
    reloadMock.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("says when the order was generated, above the date it has to meet", () => {
    render(<PurchaseOrderDetailPage />);

    // The two read as a span — raised then, wanted by then — and the first half was on no screen
    // until 2026-09-05.
    expect(screen.getByText(/^Generated 1 Aug 2026$/)).toBeInTheDocument();
    const generated = screen.getByText(/^Generated 1 Aug 2026$/);
    const neededBy = screen.getByText(/Needed by/);
    expect(generated.compareDocumentPosition(neededBy) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("renders the PO with its lines and SENT-state actions, and nothing else", () => {
    render(<PurchaseOrderDetailPage />);
    expect(screen.getByRole("heading", { name: "PO-2026-0042" })).toBeInTheDocument();
    expect(screen.getByText("Rice")).toBeInTheDocument();
    // A sent PO can be received, sent on WhatsApp, and cancelled — but not "marked sent" again.
    expect(screen.getByRole("button", { name: /send on whatsapp/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /receive delivery/i })).toBeInTheDocument();
    // Cancelling reads "Cancel order" now, inside its own block at the foot of the page. The bare
    // "Cancel" that used to sit in the header bank is gone: Rajeev asked what it cancelled, the
    // screen or the order, and a button nobody can answer that about is not a button (T-135).
    expect(screen.getByRole("button", { name: /cancel order/i })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^cancel$/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /mark sent/i })).not.toBeInTheDocument();
    // And it cannot be edited: the offer is absent, not merely refused when pressed (A9).
    expect(screen.queryByRole("button", { name: /^edit$/i })).not.toBeInTheDocument();
    // The screen is the order itself: no event trail, no list of generated sheets to come back to.
    expect(screen.queryByRole("heading", { name: /activity/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /documents/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/sent to vendor/i)).not.toBeInTheDocument();
  });

  it("offers Mark sent and Edit on a draft, and no receiving", () => {
    withDetail(DRAFT);
    render(<PurchaseOrderDetailPage />);
    expect(screen.getByRole("button", { name: /mark sent/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^edit$/i })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /receive delivery/i })).not.toBeInTheDocument();
  });

  it("edits a draft's quantities and lines, but never its vendor", () => {
    withDetail(DRAFT);
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /^edit$/i }));

    // The quantity is editable, and the picker offers the ingredients not already on the order.
    const quantity = screen.getByLabelText("Quantity of Rice") as HTMLInputElement;
    expect(quantity.value).toBe("30");
    fireEvent.change(quantity, { target: { value: "45" } });
    expect((screen.getByLabelText("Quantity of Rice") as HTMLInputElement).value).toBe("45");

    const picker = screen.getByLabelText(/add an ingredient/i) as HTMLSelectElement;
    expect(screen.getByRole("option", { name: "Toor Dal" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "Rice" })).not.toBeInTheDocument();
    fireEvent.change(picker, { target: { value: "ing2" } });
    fireEvent.click(screen.getByRole("button", { name: /add line/i }));
    expect(screen.getByLabelText("Quantity of Toor Dal")).toBeInTheDocument();

    // The vendor is not among what can be changed — the form offers no way to choose another.
    expect(screen.queryByLabelText(/vendor/i)).not.toBeInTheDocument();
  });

  it("pre-fills the received price from the order and shows what was expected", () => {
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /receive delivery/i }));

    // The order's expected price is the starting point, because it is usually right and retyping a
    // figure that has not changed is how a storekeeper stops filling the field in at all.
    const price = screen.getByLabelText(/price paid per Kg of Rice/i) as HTMLInputElement;
    expect(price.value).toBe("45");
    // What was budgeted stays visible beside it, so a bill of ₹80 is visibly not the ₹45 expected.
    // Information, not a gate: nothing blocks recording it.
    expect(screen.getByText("expected ₹45 / Kg")).toBeInTheDocument();
  });

  it("sends a blank price as null, never as zero", async () => {
    const receive = vi.spyOn(api, "receiveDelivery").mockResolvedValue({} as GoodsReceiptView);
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /receive delivery/i }));

    fireEvent.change(screen.getByLabelText("Received Rice"), { target: { value: "30" } });
    fireEvent.change(screen.getByLabelText(/price paid per Kg of Rice/i), { target: { value: "" } });
    await act(async () => {
      fireEvent.submit(screen.getByRole("form", { name: /record a delivery/i }));
    });

    // A delivery that arrived ahead of its bill is not a delivery that cost nothing. A zero here
    // would be written back as the vendor's price and quietly wreck every costing figure.
    expect(receive).toHaveBeenCalledTimes(1);
    expect(receive.mock.calls[0][1].lines[0].unitPrice).toBeNull();
  });

  /**
   * The cancel dialog's tick box (T-124).
   *
   * <p>What these guard is not the wording but the default. Ticking it is a permanent statement
   * about somebody else's business — it scores the order 0% on the vendor's record and names them
   * as a no-show — and two defects this week came from boxes that were already ticked. So the tests
   * that matter are that it starts unticked, that an untouched form sends `false`, and that a
   * ticked one sends `true`.
   */
  it("offers the never-delivered box unticked, with the line saying what ticking it does", () => {
    render(<PurchaseOrderDetailPage />);
    // Nothing is pressed to get here. Since T-135 the cancellation is a block at the foot of the
    // page rather than a panel behind a button in the header.

    // Rajeev's own wording, 2026-09-09, and not to be improved.
    const box = screen.getByLabelText(/Vendor Never Delivered this Order/) as HTMLInputElement;
    expect(box.checked).toBe(false);
    expect(
      screen.getByText(/This counts against the vendor’s delivery record/)
    ).toBeInTheDocument();
    // And the reason is still required either way: the box carries the fact, the sentence the story.
    expect(screen.getByLabelText("Reason")).toBeRequired();
  });

  it("sends false when nobody touched the box, and true when somebody ticked it", async () => {
    const cancel = vi.spyOn(api, "cancelPurchaseOrder").mockResolvedValue(undefined);
    render(<PurchaseOrderDetailPage />);
    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "festival moved" } });
    await act(async () => {
      fireEvent.submit(screen.getByRole("button", { name: /cancel order/i }).closest("form")!);
    });

    // The third argument, and it is deliberately not optional in `api.cancelPurchaseOrder`: every
    // caller has to say which of the two kinds of cancellation this is.
    expect(cancel.mock.calls[0][2]).toBe(false);

    fireEvent.click(screen.getByLabelText(/Vendor Never Delivered this Order/));
    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "never answered the phone" } });
    await act(async () => {
      fireEvent.submit(screen.getByRole("button", { name: /cancel order/i }).closest("form")!);
    });

    expect(cancel.mock.calls[1][2]).toBe(true);
  });

  it("does not carry a tick from one cancellation into the next", async () => {
    // The same rule as before T-135, now guarding a different moment. There is no panel to close
    // and reopen: the block is part of the page. So what must not survive is a completed
    // cancellation — a claim about a supplier left sitting there ticked, waiting for somebody to
    // press a button meaning something else.
    vi.spyOn(api, "cancelPurchaseOrder").mockResolvedValue(undefined);
    render(<PurchaseOrderDetailPage />);
    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "never answered" } });
    fireEvent.click(screen.getByLabelText(/Vendor Never Delivered this Order/));
    expect((screen.getByLabelText(/Vendor Never Delivered this Order/) as HTMLInputElement).checked)
      .toBe(true);

    await act(async () => {
      fireEvent.submit(screen.getByRole("button", { name: /cancel order/i }).closest("form")!);
    });

    expect((screen.getByLabelText(/Vendor Never Delivered this Order/) as HTMLInputElement).checked)
      .toBe(false);
    // And the sentence somebody wrote goes with it, for the same reason.
    expect((screen.getByLabelText("Reason") as HTMLInputElement).value).toBe("");
  });

  /**
   * And the box is not offered at all on an order nobody sent (T-129).
   *
   * <p>Rajeev's ruling of 2026-09-10, taken from three options. The coordinator raised
   * PO-2026-0036 as a draft on staging, never pressed Mark sent, cancelled it with the box ticked,
   * and Heritage Fresh Dairy's scorecard then read 0% on time for an order the vendor had never
   * heard of.
   *
   * <p>Two separate things are asserted, and the second is the one that is easy to leave out: that
   * the control is gone, and that its absence is explained. A control that simply disappears reads
   * as a bug or as a missing permission, and the person cancelling is the one who most needs to
   * know that this cancellation counts against nobody.
   */
  it("does not offer the never-delivered box on an order that was never sent, and says why", () => {
    withDetail(DRAFT);
    render(<PurchaseOrderDetailPage />);

    expect(screen.queryByLabelText(/Vendor Never Delivered this Order/)).not.toBeInTheDocument();
    expect(
      screen.getByText(/This order was never sent, so there is nothing to hold the vendor to/)
    ).toBeInTheDocument();
    // The cancellation itself is still offered, and still wants a reason. The ruling removed one
    // claim a person could make, not the ability to call an order off.
    expect(screen.getByLabelText("Reason")).toBeRequired();
    expect(screen.getByRole("button", { name: /cancel order/i })).toBeInTheDocument();
  });

  it("cancels an unsent order with the no-show flag false, without being asked", async () => {
    // The server refuses the pairing outright (KMS-400147), so what is checked here is that the
    // screen never puts it in a position to. `false` is asserted on the value itself rather than
    // with objectContaining or a truthiness check: a missing third argument and an explicit false
    // read identically to the convenient assertion, and this project has paid for that before.
    const cancel = vi.spyOn(api, "cancelPurchaseOrder").mockResolvedValue(undefined);
    withDetail(DRAFT);
    render(<PurchaseOrderDetailPage />);
    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "raised against the wrong vendor" } });
    await act(async () => {
      fireEvent.submit(screen.getByRole("button", { name: /cancel order/i }).closest("form")!);
    });

    expect(cancel.mock.calls[0].length).toBeGreaterThan(2);
    expect(cancel.mock.calls[0][2]).toBe(false);
  });

  it("says on a cancelled order's face that the vendor never delivered it", () => {
    // T-124 recorded the tick and scored the vendor 0% for it, and put it on no screen except the
    // activity trail. This is the screen where somebody asks why an order was cancelled, so this
    // is where the answer belongs.
    withDetail(ABANDONED);
    render(<PurchaseOrderDetailPage />);

    // The operator's own sentence survives verbatim beside it — the box carries the fact and the
    // sentence carries the story, and neither one replaces the other.
    expect(screen.getByText("Cancelled: never answered the phone")).toBeInTheDocument();
    expect(screen.getByText(/The vendor never delivered this order/)).toBeInTheDocument();
    // And it says what ticking it did, in the same words the tick's own hint used on the way in.
    expect(screen.getByText(/counts against their delivery record/)).toBeInTheDocument();
  });

  it("says nothing about the vendor on a cancellation nobody marked against them", () => {
    // Silence blames nobody, and that is the point of the default. A cancellation for our own
    // reasons must read as exactly that, with no marker and no sentence anywhere near it.
    withDetail(CANCELLED);
    render(<PurchaseOrderDetailPage />);

    expect(screen.getByText("Cancelled: festival moved to next month")).toBeInTheDocument();
    expect(screen.queryByText(/never delivered/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/delivery record/i)).not.toBeInTheDocument();
  });

  it("shows a sent order's needed-by date as a readout, with why it can no longer be moved", () => {
    render(<PurchaseOrderDetailPage />);
    // The reader's own locale formats the day, so this matches the parts rather than the order.
    expect(screen.getByText(/Needed by .*Aug.*2026/)).toBeInTheDocument();
    // The vendor has been told this date and the scorecard measures them against it, so there is
    // no field here at all — not a field that refuses when pressed.
    expect(screen.queryByLabelText("Needed by")).not.toBeInTheDocument();
    expect(screen.getByText(/fixed when the order was sent/i)).toBeInTheDocument();
  });

  it("offers the needed-by date on a draft, pre-filled with what is already there", () => {
    withDetail(DRAFT);
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /^edit$/i }));

    const neededBy = screen.getByLabelText("Needed by") as HTMLInputElement;
    expect(neededBy.value).toBe("2026-08-20");
    // And it cannot offer a day behind the order itself.
    expect(neededBy.min).toBe("2026-08-01");
  });

  it("saves a changed needed-by date, and sends a cleared one as null", async () => {
    const update = vi.spyOn(api, "updatePurchaseOrder").mockResolvedValue(undefined);
    withDetail(DRAFT);
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /^edit$/i }));

    fireEvent.change(screen.getByLabelText("Needed by"), { target: { value: "2026-09-04" } });
    await act(async () => {
      fireEvent.submit(screen.getByRole("form", { name: /edit the draft order/i }));
    });
    expect(update.mock.calls[0][1].neededBy).toBe("2026-09-04");

    // Cleared is a date deliberately removed, not a field left unanswered: an order with nothing
    // to meet is a real order, and E5-S9 counts those aside rather than scoring them.
    fireEvent.click(screen.getByRole("button", { name: /^edit$/i }));
    fireEvent.change(screen.getByLabelText("Needed by"), { target: { value: "" } });
    await act(async () => {
      fireEvent.submit(screen.getByRole("form", { name: /edit the draft order/i }));
    });
    expect(update.mock.calls[1][1].neededBy).toBeNull();
  });

  it("refuses a date behind the order itself, without troubling the server", async () => {
    const update = vi.spyOn(api, "updatePurchaseOrder").mockResolvedValue(undefined);
    withDetail(DRAFT);
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /^edit$/i }));

    fireEvent.change(screen.getByLabelText("Needed by"), { target: { value: "2026-07-25" } });
    await act(async () => {
      fireEvent.submit(screen.getByRole("form", { name: /edit the draft order/i }));
    });

    // The server refuses this too, with KMS-400014. This only spares the round trip.
    expect(update).not.toHaveBeenCalled();
    expect(screen.getByText(/before the order was raised/i)).toBeInTheDocument();
  });

  it("warns about a date inside the vendor's usual notice, and saves it anyway", async () => {
    const update = vi.spyOn(api, "updatePurchaseOrder").mockResolvedValue(undefined);
    withDetail(DRAFT);
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /^edit$/i }));

    // A date in the past on a draft still sitting there — worth saying out loud, and still the
    // temple's to ask for. The buffer is a planning default, not a rule about what a vendor can do.
    expect(screen.getByText("That day has already gone")).toBeInTheDocument();
    await act(async () => {
      fireEvent.submit(screen.getByRole("form", { name: /edit the draft order/i }));
    });
    expect(update).toHaveBeenCalledTimes(1);
    expect(update.mock.calls[0][1].neededBy).toBe("2026-08-20");
  });

  /**
   * The bank of buttons, in the order Rajeev dictated on 2026-09-10 while driving the deployed
   * application: Vendor's language, Generate PDF, Print, Edit, Mark as sent (D-24 §3).
   *
   * <p>The order is asserted rather than the presence of each, because presence was never the
   * complaint. Print and Generate PDF were the other way round and "Edit lines" sat between two
   * sending actions, and a test that only checked each button existed would have passed on the
   * screen he objected to.
   */
  it("lays the buttons out in the order they were asked for", () => {
    withDetail(DRAFT);
    render(<PurchaseOrderDetailPage />);

    const bank = screen.getByRole("button", { name: "Generate PDF" }).closest("div")!;
    expect(within(bank).getAllByRole("button").map((b) => b.textContent)).toEqual([
      "Generate PDF", "Print", "Edit", "Mark sent", "Send on WhatsApp",
    ]);
    // The language picker leads, which is the first thing on his list and is a select, not a
    // button, so it is checked on its own rather than in the row above.
    const language = screen.getByLabelText("Document language");
    expect(
      language.compareDocumentPosition(within(bank).getByRole("button", { name: "Generate PDF" }))
        & Node.DOCUMENT_POSITION_FOLLOWING
    ).toBeTruthy();
  });

  /**
   * Edit mode shows two buttons: Save and Cancel (D-24 §4).
   *
   * <p>Rajeev: "Why do we need all the other buttons in edit mode?" — so the whole bank goes,
   * including the language picker, which exists only to steer Print and Generate PDF. "Stop
   * editing" goes with it; Cancel is the way out now.
   */
  it("shows nothing but Save and Cancel while a draft is being edited", () => {
    withDetail(DRAFT);
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /^edit$/i }));

    expect(screen.getByRole("button", { name: /^save$/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^cancel$/i })).toBeInTheDocument();

    for (const gone of [/generate pdf/i, /^print$/i, /^edit$/i, /mark sent/i, /send on whatsapp/i, /stop editing/i]) {
      expect(screen.queryByRole("button", { name: gone })).not.toBeInTheDocument();
    }
    expect(screen.queryByLabelText("Document language")).not.toBeInTheDocument();
  });

  /**
   * The order's lines are on the screen once (T-134).
   *
   * <p>They were on it twice in edit mode: the editable table inside the form, and the read-only
   * "What was ordered" table still rendered underneath it — same order, same line, and the one
   * underneath showing the saved figure while the box above showed the one being typed. Found by
   * driving the deployed app as a Temple Admin, on Wave A as shipped.
   *
   * <p>Not a Wave A regression, and the test says so by covering the whole cycle: the read-only
   * table has been unconditional since T-024 and the form has always opened above it. What Wave A
   * changed is that the bank of buttons no longer sits between the two, which is what made it
   * visible.
   *
   * <p>Asserted in all three states — before, during and after — because an assertion that the
   * table is absent in edit mode would pass just as well on a screen that had lost the table
   * altogether, and that is the opposite defect.
   */
  it("shows the order's lines once while editing, and brings the readout back after", () => {
    withDetail(DRAFT);
    render(<PurchaseOrderDetailPage />);

    expect(screen.getByRole("table", { name: "What was ordered" })).toBeInTheDocument();
    expect(screen.getAllByText("Rice")).toHaveLength(1);

    fireEvent.click(screen.getByRole("button", { name: /^edit$/i }));
    expect(screen.queryByRole("table", { name: "What was ordered" })).not.toBeInTheDocument();
    // One Rice on the screen, and it is the one with a box beside it.
    expect(screen.getAllByText("Rice")).toHaveLength(1);
    expect(screen.getByLabelText("Quantity of Rice")).toBeInTheDocument();
    // The needed-by date is the other thing that was on the screen twice: a readout of the saved
    // date beside a box holding the one being typed. The date field stays; the readout goes.
    expect(screen.queryByText(/^Needed by \d/)).not.toBeInTheDocument();
    expect(screen.getByLabelText("Needed by")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /^cancel$/i }));
    expect(screen.getByRole("table", { name: "What was ordered" })).toBeInTheDocument();
    expect(screen.getByText(/^Needed by \d/)).toBeInTheDocument();
  });

  /**
   * Cancelling the order is at the foot of the page, on the view screen and the edit screen alike.
   *
   * <p>"Is it cancelling out of this screen OR cancelling the PO?" was Rajeev's question about the
   * old header button. The answer is that this one says so in its own heading, sits below
   * everything else, and takes a reason.
   */
  it("puts cancelling the order at the foot of the page, in both modes", () => {
    withDetail(DRAFT);
    render(<PurchaseOrderDetailPage />);

    const heading = screen.getByRole("heading", { name: /cancel this purchase order/i });
    const table = screen.getByRole("table", { name: "What was ordered" });
    expect(table.compareDocumentPosition(heading) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByLabelText("Reason")).toBeRequired();

    // And it is still there with the edit form open, which is the half that is easy to lose: the
    // header bank is not rendered in edit mode and the cancellation is not part of it.
    fireEvent.click(screen.getByRole("button", { name: /^edit$/i }));
    expect(screen.getByRole("heading", { name: /cancel this purchase order/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /cancel order/i })).toBeInTheDocument();
  });

  /**
   * And it is gated on there being a purchase-order number, not on which screen this is (T-134).
   *
   * <p>T-134 opens this same edit form as a panel over the shopping list, for an order that does
   * not exist yet, and Rajeev was explicit: show the cancel control "only when there is a
   * purchase-order number". The fixture below is artificial — a real order always has one — and it
   * is the only way to prove the condition is about the data rather than about the route, which is
   * the thing T-134 is going to rely on.
   */
  it("hides the cancellation entirely when there is no purchase-order number yet", () => {
    withDetail({ ...DRAFT, order: { ...DRAFT.order, poNumber: "" } });
    render(<PurchaseOrderDetailPage />);

    expect(screen.queryByRole("heading", { name: /cancel this purchase order/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /cancel order/i })).not.toBeInTheDocument();
  });

  /**
   * Send on WhatsApp exists only where WhatsApp has actually sent something (T-136).
   *
   * <p>Rajeev's ruling, 2026-09-10: shown "only after a message has actually gone through it
   * successfully", not merely configured — and where it does not apply, not there at all.
   *
   * <p>Both halves are asserted, and the second is the one that is easy to get wrong: absent, not
   * disabled. A greyed button is still an offer, and a person who presses it learns nothing.
   */
  it("does not offer Send on WhatsApp until a WhatsApp message has actually gone out", () => {
    withDetail({ ...DETAIL, whatsappEverSent: false });
    render(<PurchaseOrderDetailPage />);

    expect(screen.queryByRole("button", { name: /send on whatsapp/i })).not.toBeInTheDocument();
    // Everything else on the bank is untouched: this gate is about WhatsApp and nothing else.
    expect(screen.getByRole("button", { name: "Generate PDF" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /receive delivery/i })).toBeInTheDocument();
  });

  it("treats a missing WhatsApp fact as 'never sent', which hides the button", () => {
    // `whatsappEverSent` is optional on the interface (see lib/api.ts), so `undefined` is reachable
    // from an older payload. It must read as not-proven: hiding the button is the safe direction,
    // and it is the ruling's own default.
    const { whatsappEverSent: _omitted, ...withoutTheFact } = DETAIL;
    withDetail(withoutTheFact);
    render(<PurchaseOrderDetailPage />);

    expect(screen.queryByRole("button", { name: /send on whatsapp/i })).not.toBeInTheDocument();
  });

  /**
   * The Remove button, which Rajeev found "washed out" and asked to read as an available control.
   *
   * <p>It was disabled on a one-line draft — dimmed to 45%, saying nothing about why. The rule it
   * was enforcing is real (an order with nothing on it is a cancellation, not an empty order) and
   * is kept; it is now said in words on save, which is what the rest of this screen already does
   * and what /orders/new/lines does with the identical control.
   */
  it("offers Remove on the last line, and refuses an emptied order in words", async () => {
    const update = vi.spyOn(api, "updatePurchaseOrder").mockResolvedValue(undefined);
    withDetail(DRAFT);
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /^edit$/i }));

    const remove = screen.getByRole("button", { name: /remove rice/i });
    expect(remove).toBeEnabled();
    fireEvent.click(remove);
    // Pressing it removes the row and does not submit the form on the way — the button used to
    // carry no `type`, which defaults to submit inside a <form>.
    expect(screen.queryByLabelText("Quantity of Rice")).not.toBeInTheDocument();
    expect(update).not.toHaveBeenCalled();

    await act(async () => {
      fireEvent.submit(screen.getByRole("form", { name: /edit the draft order/i }));
    });

    expect(update).not.toHaveBeenCalled();
    expect(screen.getByText(/An order needs at least one line/)).toBeInTheDocument();
  });

  it("names the uncatalogued adder the way Rajeev asked", () => {
    withDetail(DRAFT);
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /^edit$/i }));

    // "Or describe something not in the catalogue" became "An item not in the catalogue" (D-24 §4).
    // `{ selector }` because the field is hinted: InfoHint's "i" button carries the accessible name
    // "More about <label>", so a bare getByLabelText matches the input and the button both.
    expect(screen.getByLabelText(/an item not in the catalogue/i, { selector: "input" }))
      .toBeInTheDocument();
    expect(screen.queryByLabelText(/describe something not in the catalogue/i)).not.toBeInTheDocument();
  });
});
