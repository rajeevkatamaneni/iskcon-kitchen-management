import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { api } from "@/lib/api";
import type {
  GoodsReceiptView, IngredientView, PurchaseOrderDetailView,
} from "@/lib/api";

/**
 * A purchase-order line that names something the catalogue has never heard of (T-024, D-1).
 *
 * <p>Four plastic stools from a furniture shop. The order carries them, the vendor sheet prints
 * them, the bill pays for them — and the store room never sees them, because it counts ingredients
 * and a stool is not one.
 *
 * <p>This file is about the screen. The server half is DescribedPurchaseLineIT.
 */

// Same three-query shape as order-detail.test.tsx: PO detail, receipts, then the ingredient
// catalogue the draft-edit picker reads. Returned by call index modulo length so it survives any
// number of re-renders.
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

/**
 * One ingredient line and TWO described lines.
 *
 * <p>Two rather than one, deliberately. One described line would pass a table keyed on
 * `ingredientId`; two is what makes the old key collide, because both carry null and React would
 * treat them as the same row.
 */
const MIXED: PurchaseOrderDetailView = {
  order: {
    id: "po1",
    poNumber: "PO-2026-0077",
    vendorId: "v1",
    vendorName: "Govind Wholesale",
    status: "SENT",
    orderDate: "2026-08-01",
    neededBy: "2026-08-20",
    deliveryLocation: "Main store",
    notes: null,
    cancelReason: null,
    // Required on PurchaseOrderView since T-126, so every fixture states it. This order is not a
    // cancellation at all, let alone one held against the vendor.
    vendorAbandoned: false,
    sentAt: "2026-08-01T10:00:00Z",
    cancelledAt: null,
    createdAt: "2026-08-01T09:00:00Z",
  },
  lines: [
    { id: "l1", ingredientId: "ing1", ingredientName: "Rice", description: null, quantity: 30, unit: "KG", expectedPrice: 45, arrivedOn: null },
    // `arrivedOn: null` is stated rather than left off, for the same reason `description` is: it is
    // required-and-nullable, so every fixture has to say whether this line has been accounted for.
    { id: "l2", ingredientId: null, ingredientName: null, description: "Plastic stool", quantity: 4, unit: "PIECES", expectedPrice: 250, arrivedOn: null },
    { id: "l3", ingredientId: null, ingredientName: null, description: "Extension cord", quantity: 2, unit: "PIECES", expectedPrice: 180, arrivedOn: null },
  ],
  events: [],
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

const MIXED_DRAFT: PurchaseOrderDetailView = {
  ...MIXED,
  order: { ...MIXED.order, status: "DRAFT" },
};

describe("a purchase-order line that isn't in the catalogue", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    withDetail(MIXED);
    reloadMock.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("shows described lines on the order beside the ingredient ones", () => {
    render(<PurchaseOrderDetailPage />);

    // The read-only table is the order as issued. All three lines are on it, with their own
    // quantities and prices — a described line is an ordinary line to everybody except the store.
    //
    // Scoped to that table since T-066, and the scoping is the point rather than a workaround: a
    // described line's subject now also appears on the "Did these arrive?" list, so an unscoped
    // getByText would be asserting "this text is somewhere on the screen" while reading as "this
    // line is on the order". It threw on the ambiguity, which is the right way to find that out.
    const ordered = within(screen.getByRole("table", { name: /what was ordered/i }));
    expect(ordered.getByText("Rice")).toBeInTheDocument();
    expect(ordered.getByText("Plastic stool")).toBeInTheDocument();
    expect(ordered.getByText("Extension cord")).toBeInTheDocument();
  });

  it("offers no boxes for a described line in the receiving table, and says why", () => {
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /receive delivery/i }));

    // The ingredient line takes a delivery.
    expect(screen.getByLabelText("Received Rice")).toBeInTheDocument();

    // The described ones do not, and the row says so rather than being blank or absent. Absent
    // would be worse than either: the storekeeper is holding a delivery note that lists stools.
    expect(screen.queryByLabelText("Received Plastic stool")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Received Extension cord")).not.toBeInTheDocument();
    // CHANGED AT T-066. This used to assert the row read "record it as delivered on the
    // order" — KMS-400129's words repeated verbatim, pointing at an action that existed
    // nowhere. The row now points at the form that does exist, three sections down.
    expect(screen.getAllByText(/say below whether it arrived/i)).toHaveLength(2);
    expect(screen.queryByText(/record it as delivered on the order/i)).toBeNull();
  });

  it("submits only the ingredient lines when a delivery is recorded", async () => {
    const receive = vi.spyOn(api, "receiveDelivery").mockResolvedValue({} as GoodsReceiptView);
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /receive delivery/i }));

    fireEvent.change(screen.getByLabelText("Received Rice"), { target: { value: "30" } });
    await act(async () => {
      fireEvent.submit(screen.getByRole("form", { name: /record a delivery/i }));
    });

    expect(receive).toHaveBeenCalledTimes(1);
    const submitted = receive.mock.calls[0][1].lines;
    // Exactly one line, and it is the rice. A described line reaching the server would be refused
    // with KMS-400129 — this is the offer being absent rather than the refusal being caught.
    expect(submitted).toHaveLength(1);
    expect(submitted[0].poLineId).toBe("l1");
  });

  it("gives two described lines distinct React keys", () => {
    // Asserted on React's own duplicate-key warning, and that choice is worth explaining, because
    // the obvious test does not work.
    //
    // The obvious test — type into one described row, check the other did not change — PASSES with
    // the broken `key={l.ingredientId}` still in place. Measured, not assumed: with two rows keyed
    // `null` React renders both rows and both quantities correctly, because every input here is
    // controlled from `draftLines` and gets its value re-supplied on each render. So a value-based
    // assertion proves nothing about the key.
    //
    // What React actually does is log, via console.error: "Encountered two children with the same
    // key, `null`. Keys should be unique so that components maintain their identity across updates.
    // Non-unique keys may cause children to be duplicated and/or omitted." That is the defect —
    // undefined reconciliation behaviour on a form somebody is typing into — and it is the one
    // deterministic signal available, so it is what this asserts.
    const errors = vi.spyOn(console, "error").mockImplementation(() => {});
    withDetail(MIXED_DRAFT);
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /^edit$/i }));

    const duplicateKeyWarnings = errors.mock.calls
      .map((c) => String(c[0]))
      .filter((m) => m.includes("two children with the same key"));
    expect(duplicateKeyWarnings).toEqual([]);

    // And the rows really are both there, with their own quantities, however they are keyed.
    expect((screen.getByLabelText("Quantity of Plastic stool") as HTMLInputElement).value).toBe("4");
    expect((screen.getByLabelText("Quantity of Extension cord") as HTMLInputElement).value).toBe("2");
  });

  it("sends description on every line, and never both a description and an ingredient", async () => {
    const update = vi.spyOn(api, "updatePurchaseOrder").mockResolvedValue(undefined);
    withDetail(MIXED_DRAFT);
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /^edit$/i }));

    await act(async () => {
      fireEvent.submit(screen.getByRole("form", { name: /edit the draft order/i }));
    });

    const sent = update.mock.calls[0][1].lines;
    expect(sent).toHaveLength(3);

    // `objectContaining` cannot test an absence here: it would pass just as happily on a line that
    // omitted `description` entirely as on one that sent null, and those two are the whole point of
    // the required-and-nullable convention. So inspect the keys, then the values.
    for (const line of sent) {
      expect(Object.keys(line)).toContain("description");
      expect(Object.keys(line)).toContain("ingredientId");
    }
    expect(sent[0].ingredientId).toBe("ing1");
    expect(sent[0].description).toBeNull();
    expect(sent[1].ingredientId).toBeNull();
    expect(sent[1].description).toBe("Plastic stool");
    expect(sent[2].ingredientId).toBeNull();
    expect(sent[2].description).toBe("Extension cord");
  });

  it("adds a described line to a draft, which is the only way to create one", async () => {
    const update = vi.spyOn(api, "updatePurchaseOrder").mockResolvedValue(undefined);
    withDetail(MIXED_DRAFT);
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /^edit$/i }));

    // `{ selector }` because the field is hinted: InfoHint's "i" button carries the accessible
    // name "More about <label>", so a bare getByLabelText matches the input and the button both.
    const box = screen.getByLabelText(/an item not in the catalogue/i, { selector: "input" });
    fireEvent.change(box, { target: { value: "  Steel trolley  " } });
    fireEvent.click(screen.getByRole("button", { name: /add described line/i }));

    // It arrives as its own row, trimmed, counted in pieces by default.
    const quantity = screen.getByLabelText("Quantity of Steel trolley") as HTMLInputElement;
    fireEvent.change(quantity, { target: { value: "1" } });

    await act(async () => {
      fireEvent.submit(screen.getByRole("form", { name: /edit the draft order/i }));
    });

    const sent = update.mock.calls[0][1].lines;
    expect(sent).toHaveLength(4);
    expect(sent[3]).toEqual({
      ingredientId: null,
      description: "Steel trolley",
      quantity: 1,
      unit: "PIECES",
      expectedPrice: null,
    });
  });

  it("cannot build a line that names both an ingredient and a description", () => {
    withDetail(MIXED_DRAFT);
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /^edit$/i }));

    // The two adders are separate controls with separate buttons, so the exclusivity the server
    // enforces with KMS-400128 cannot be violated from this form by accident. The ingredient
    // picker offers no description box and the description box offers no picker.
    const picker = screen.getByLabelText(/add an ingredient/i).closest("div");
    expect(picker).not.toBeNull();
    expect(within(picker as HTMLElement).queryByLabelText(/an item not in the catalogue/i)).toBeNull();

    // And an empty description cannot be added at all — the button stays disabled.
    expect(screen.getByRole("button", { name: /add described line/i })).toBeDisabled();
  });

  it("offers a way to say the described lines arrived, and closes the order with it", async () => {
    // The whole of T-066 on this screen. KMS-400129 tells a storekeeper to record the stools as
    // delivered on the order; until now there was no control anywhere in the application that did
    // that, so an order of nothing but described lines could never be closed and aged in the
    // vendor scorecard for ever.
    const record = vi.spyOn(api, "recordArrivals").mockResolvedValue(undefined);
    render(<PurchaseOrderDetailPage />);

    // Outside the "Record a delivery" panel and already open: an order of only described lines has
    // no delivery to record, so anything behind that button would be behind a button nobody with
    // such an order has a reason to press.
    const form = screen.getByRole("form", { name: /record what arrived/i });
    expect(form).toBeInTheDocument();

    // CHANGED AT T-107. This test used to submit the form untouched and expect both lines, because
    // the panel opened with every box ticked. A tick has to be an act now, so the act is here.
    fireEvent.click(within(form).getByLabelText(/plastic stool/i));
    fireEvent.click(within(form).getByLabelText(/extension cord/i));

    await act(async () => {
      fireEvent.submit(form);
    });

    expect(record).toHaveBeenCalledTimes(1);
    expect(record.mock.calls[0][0]).toBe("po1");
    // Both described lines and never the rice: a catalogue line is accounted for by the ledger and
    // the server refuses an arrival against one.
    expect(record.mock.calls[0][1]).toEqual({ poLineIds: ["l2", "l3"] });
    // And the screen re-reads the order, because that call may have closed it.
    expect(reloadMock).toHaveBeenCalled();
  });

  it("opens with nothing ticked (T-107)", () => {
    // The defect this task exists for. The panel used to open with every box ticked and one button
    // reading "Record as arrived", over a write that cannot be undone: `POST /{id}/arrivals` is the
    // only arrivals endpoint on PurchaseOrderController, and the update behind it is
    // `SET arrived_on = ? WHERE ... AND arrived_on IS NULL`, so it is write-once by construction.
    // A Kitchen Manager opening the order to see what was still outstanding and pressing the one
    // button on the panel permanently recorded "repair the mixer motor" as delivered.
    //
    // Asserted on the DOM and not on the props of anything, deliberately: `defaultChecked` absent
    // and `defaultChecked={false}` are the same object to `objectContaining`, and this test has to
    // be able to tell them apart. `.checked` is what the browser actually did with it.
    render(<PurchaseOrderDetailPage />);

    const form = screen.getByRole("form", { name: /record what arrived/i });
    const boxes = within(form).getAllByRole("checkbox") as HTMLInputElement[];

    // Both described lines are offered — this is not passing because the list is empty.
    expect(boxes).toHaveLength(2);
    for (const box of boxes) {
      expect(box.checked).toBe(false);
    }
  });

  it("sends only the lines that were ticked", async () => {
    // The stools came on the lorry; the mixer repair happens on Friday. One button claiming both
    // would be a statement nobody made — and, until T-107, the statement the panel made by default.
    //
    // This is the assertion that proves the fix is real rather than cosmetic: unticking the boxes
    // would be pointless if the request were built from the lines rather than from the ticks.
    const record = vi.spyOn(api, "recordArrivals").mockResolvedValue(undefined);
    render(<PurchaseOrderDetailPage />);

    fireEvent.click(within(screen.getByRole("form", { name: /record what arrived/i }))
      .getByLabelText(/plastic stool/i));

    await act(async () => {
      fireEvent.submit(screen.getByRole("form", { name: /record what arrived/i }));
    });

    expect(record.mock.calls[0][1]).toEqual({ poLineIds: ["l2"] });
  });

  it("answers an empty selection in words and posts nothing (T-107)", async () => {
    // Now that the panel opens with nothing ticked, pressing the button first and reading second
    // is the ordinary mistake rather than an odd one. The button stays enabled and the screen says
    // what to do: the endpoint's @NotEmpty would refuse this with a validation error, which is a
    // technical answer to a person's mistake, and a greyed-out button is no answer at all.
    const record = vi.spyOn(api, "recordArrivals").mockResolvedValue(undefined);
    render(<PurchaseOrderDetailPage />);

    const button = screen.getByRole("button", { name: /record as arrived/i });
    expect(button).toBeEnabled();

    await act(async () => {
      fireEvent.submit(screen.getByRole("form", { name: /record what arrived/i }));
    });

    // Nothing left the browser. This is the half that matters: an empty POST would come back as a
    // 400 and the storekeeper would be reading about a constraint.
    expect(record).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent(/tick what arrived/i);

    // And the panel is still there to be used, with the boxes still empty.
    expect(screen.getByRole("form", { name: /record what arrived/i })).toBeInTheDocument();
  });

  it("says one piece and four pieces, not one pieces (T-107)", () => {
    // "1 pieces" on the arrivals panel and on the order table. `quantity()` names its unit from a
    // single label per unit, so a count of one disagrees with its noun everywhere in the
    // application; `quantitySaid` on this screen is the local repair, and the shared one is a task
    // of its own (see docs/work/proof/T-107.md).
    withDetail({
      ...MIXED,
      lines: [
        MIXED.lines[0],
        MIXED.lines[1],
        { ...MIXED.lines[2], description: "Mixer motor repair", quantity: 1 },
      ],
    });
    render(<PurchaseOrderDetailPage />);

    const arrivals = within(screen.getByRole("form", { name: /record what arrived/i }));
    expect(arrivals.getByText("1 piece")).toBeInTheDocument();
    expect(arrivals.getByText("4 pieces")).toBeInTheDocument();
    expect(arrivals.queryByText("1 pieces")).toBeNull();

    const ordered = within(screen.getByRole("table", { name: /what was ordered/i }));
    expect(ordered.getByText("1 piece")).toBeInTheDocument();
    expect(ordered.getByText("4 pieces")).toBeInTheDocument();
    expect(ordered.queryByText("1 pieces")).toBeNull();

    // A mass keeps its label at one — "1 Kg" is what a person says, and only the count has a
    // singular to get wrong.
    expect(ordered.getByText("30 Kg")).toBeInTheDocument();
  });

  it("stops offering a line somebody has already recorded, and says when it arrived", () => {
    withDetail({
      ...MIXED,
      lines: [
        MIXED.lines[0],
        { ...MIXED.lines[1], arrivedOn: "2026-08-18" },
        MIXED.lines[2],
      ],
    });
    render(<PurchaseOrderDetailPage />);

    const form = screen.getByRole("form", { name: /record what arrived/i });
    expect(within(form).queryByLabelText(/plastic stool/i)).toBeNull();
    expect(within(form).getByLabelText(/extension cord/i)).toBeInTheDocument();

    // The arrival is readable on the order itself, which is the only record a described line will
    // ever have — there is no delivery table row for it anywhere.
    expect(screen.getByText(/arrived 18 Aug 2026/i)).toBeInTheDocument();
  });

  it("does not offer the form at all once every described line is accounted for", () => {
    withDetail({
      ...MIXED,
      lines: MIXED.lines.map((l) =>
        l.ingredientId === null ? { ...l, arrivedOn: "2026-08-18" } : l),
    });
    render(<PurchaseOrderDetailPage />);

    expect(screen.queryByRole("form", { name: /record what arrived/i })).toBeNull();
  });

  it("does not offer the form on a draft, which has been sent to nobody", () => {
    withDetail(MIXED_DRAFT);
    render(<PurchaseOrderDetailPage />);

    // Nothing can have arrived against an order the vendor has never seen, and the server refuses
    // it with KMS-400051. An offer that is refused when pressed is worse than no offer.
    expect(screen.queryByRole("form", { name: /record what arrived/i })).toBeNull();
  });
});
