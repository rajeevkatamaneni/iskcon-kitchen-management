import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { ApiError, api } from "@/lib/api";
import type {
  GoodsReceiptView,
  IngredientView,
  OrderDeliveryScore,
  PurchaseOrderDetailView,
  PurchaseOrderView,
  VendorPerformance,
  VendorPerformanceRow,
} from "@/lib/api";

/**
 * Closing a part-delivered order, and the score nobody may edit (T-142, D-26).
 *
 * <p>Rajeev's rice: 500 kg ordered, the vendor has 300 and sends it immediately so the kitchen can
 * cook, 200 to follow. *"The 200 KG should still be tied to the PO that raised and sent the 500KG
 * rice order and it should sit in a partially delivered state and the clock keeps ticking."*
 *
 * <p>**The claim this file exists to guard** is the one the ruling turns on: the admin's influence
 * over a supplier's score is a *name*, never a number. So there is a test that walks the screen
 * looking for any control that could move the figure, and a test that proves the named outcome is
 * what reaches the server. The arithmetic is the backend's and is guarded by
 * `PurchaseOrderClosingIT`.
 */

const { authRef, returnsRef, reloadMock, queryRef } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  returnsRef: { current: [] as Array<{ data: unknown; error: null; loading: boolean }>, i: 0 },
  reloadMock: vi.fn(),
  queryRef: {
    current: { data: null as unknown, error: null as ApiError | null, loading: false },
  },
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({ id: "po1" }),
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token", signOut: vi.fn() }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => {
    const list = returnsRef.current;
    if (list.length === 0) return { ...queryRef.current, reload: reloadMock };
    const value = list[returnsRef.i % list.length];
    returnsRef.i += 1;
    return { ...value, reload: reloadMock };
  },
}));

import PurchaseOrderDetailPage from "@/app/orders/[id]/page";
import VendorPerformancePage from "@/app/vendor-performance/page";

// ---------------------------------------------------------------------------
// 500 kg of rice from Govind Wholesale, 300 of it delivered on the day.
// ---------------------------------------------------------------------------

function order(o: Partial<PurchaseOrderView> = {}): PurchaseOrderView {
  return {
    id: "po1",
    poNumber: "PO-2026-0042",
    vendorId: "v1",
    vendorName: "Govind Wholesale",
    status: "PARTIALLY_RECEIVED",
    orderDate: "2026-09-01",
    neededBy: "2026-09-06",
    deliveryLocation: null,
    notes: null,
    cancelReason: null,
    vendorAbandoned: false,
    sentAt: "2026-09-01T09:00:00Z",
    cancelledAt: null,
    createdAt: "2026-09-01T09:00:00Z",
    leadTimeDays: 2,
    orderBy: "2026-09-04",
    orderUrgency: null,
    sentAfterLeadTime: false,
    ...o,
  };
}

const LINES = [
  { id: "l1", ingredientId: "ing1", ingredientName: "Rice", description: null, quantity: 500, unit: "KG", expectedPrice: 60, arrivedOn: null },
];
const RECEIPTS: GoodsReceiptView[] = [];
const INGREDIENTS: IngredientView[] = [
  { id: "ing1", name: "Rice", category: "Grains", unit: "KG", ekadashiProhibited: false, supply: false, libraryDerived: false, aliases: [], createdAt: "2026-01-01T00:00:00Z" },
];

const SIX_OF_TEN: OrderDeliveryScore = { percent: 60, itemsScored: 1, itemsOnTime: 0 };

function detail(
  o: Partial<PurchaseOrderView> = {},
  score: OrderDeliveryScore = SIX_OF_TEN,
): PurchaseOrderDetailView {
  return {
    order: order(o),
    lines: LINES,
    events: [],
    whatsappEverSent: false,
    deliveryScore: score,
  };
}

function showOrder(view: PurchaseOrderDetailView) {
  returnsRef.current = [
    { data: view, error: null, loading: false },
    { data: RECEIPTS, error: null, loading: false },
    { data: INGREDIENTS, error: null, loading: false },
  ];
  returnsRef.i = 0;
}

function closeForm() {
  return screen.getByRole("form", { name: /close this order, part delivered/i });
}

beforeEach(() => {
  returnsRef.current = [];
  returnsRef.i = 0;
  queryRef.current = { data: null, error: null, loading: false };
  authRef.current = {
    status: "signed-in",
    appUser: { role: "KITCHEN_STAFF", userId: "me" },
  };
  vi.restoreAllMocks();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("closing a part-delivered order", () => {
  it("is offered on a part-delivered order, and says what closing does", () => {
    showOrder(detail());
    render(<PurchaseOrderDetailPage />);

    expect(screen.getByRole("heading", { name: /close this order, part delivered/i })).toBeInTheDocument();
    // The two halves of what a person needs to know before pressing it: the balance is released,
    // and the order is finished. Both are consequences they cannot work out from the button.
    expect(screen.getByText(/goes back on the shopping list/i)).toBeInTheDocument();
    expect(screen.getByText(/this order is finished for good/i)).toBeInTheDocument();
  });

  /**
   * The three doors stay distinct (D-24a and D-26). A draft is swept or cancelled, a sent order
   * nothing came against is a cancellation with the no-show tick, and a finished order is finished.
   */
  it.each(["DRAFT", "SENT", "RECEIVED", "CANCELLED", "CLOSED"] as const)(
    "is not offered on a %s order",
    (status) => {
      showOrder(detail({ status }));
      render(<PurchaseOrderDetailPage />);
      expect(screen.queryByRole("heading", { name: /close this order, part delivered/i })).toBeNull();
    },
  );

  it("shows the computed score with the counts behind it", () => {
    showOrder(detail());
    render(<PurchaseOrderDetailPage />);

    expect(screen.getByText("60%")).toBeInTheDocument();
    expect(screen.getByText(/0 of 1 item/)).toBeInTheDocument();
    // The sentence that makes the readout a readout. A number on a form that cannot be changed
    // must say so, or somebody spends a minute hunting for the box.
    expect(screen.getByText(/cannot be changed here/i)).toBeInTheDocument();
  });

  /**
   * The whole ruling, asserted as an absence, and the absence is checked properly rather than
   * assumed: every form control inside the closing panel is enumerated, and the only ones allowed
   * are the three radios, the reason box and the submit button.
   *
   * <p>Rajeev proposed exactly this control — "there is SO MUCH human interaction that no machine
   * or app can capture" — and then ruled against his own proposal: *"Let us not let the admin
   * adjust the score. Just show it to them."* An adjustable score stops being a measurement;
   * nobody ever adjusts downward; and "an admin changed it" is no answer to a vendor who disputes
   * their score.
   */
  it("offers no way at all to change that score", () => {
    showOrder(detail());
    render(<PurchaseOrderDetailPage />);

    const controls = Array.from(
      closeForm().querySelectorAll("input, select, textarea, button"),
    ).map((el) => `${el.tagName.toLowerCase()}:${(el as HTMLInputElement).type ?? ""}`);

    expect(controls).toEqual([
      "input:radio",
      "input:radio",
      "input:radio",
      "input:text",
      "button:submit",
    ]);
    // And the figure itself is not in an editable thing.
    expect(screen.getByText("60%").tagName.toLowerCase()).toBe("span");
  });

  it("starts on the outcome that claims nothing about anybody", () => {
    showOrder(detail());
    render(<PurchaseOrderDetailPage />);

    expect(screen.getByRole("radio", { name: /neither/i })).toBeChecked();
    expect(screen.getByRole("radio", { name: /the vendor let us down/i })).not.toBeChecked();
    expect(screen.getByRole("radio", { name: /made it right/i })).not.toBeChecked();
  });

  it("says what each ending costs the vendor before it is chosen", () => {
    showOrder(detail());
    render(<PurchaseOrderDetailPage />);

    expect(screen.getByText(/recorded against them/i)).toBeInTheDocument();
    expect(
      screen.getByText(/leaves their delivery record entirely — both the on-time figure and the fill rate/i),
    ).toBeInTheDocument();
    expect(screen.getByText(/nothing is recorded for or against them/i)).toBeInTheDocument();
  });

  it("sends the name the person chose, and the sentence with it", async () => {
    const close = vi.spyOn(api, "closePurchaseOrder").mockResolvedValue(undefined as never);
    showOrder(detail());
    render(<PurchaseOrderDetailPage />);

    fireEvent.click(screen.getByRole("radio", { name: /made it right/i }));
    fireEvent.change(screen.getByLabelText(/^why$/i), {
      target: { value: "Rang the same afternoon, 10% off the next load." },
    });
    await act(async () => {
      fireEvent.submit(closeForm());
    });

    expect(close).toHaveBeenCalledWith(
      "po1",
      "SHORTFALL_EXCUSED",
      "Rang the same afternoon, 10% off the next load.",
      "test-token",
    );
  });

  /**
   * "Anything other than as computed requires a sentence" (D-26), asked on the way in rather than
   * only refused on the way out. The server enforces it and so does the row underneath it; this is
   * the same rule said where somebody can still act on it.
   */
  it("requires a sentence for the two outcomes that say something about the supplier", () => {
    showOrder(detail());
    render(<PurchaseOrderDetailPage />);

    expect(screen.getByLabelText(/^why, if you want to say$/i)).not.toBeRequired();

    fireEvent.click(screen.getByRole("radio", { name: /the vendor let us down/i }));
    expect(screen.getByLabelText(/^why$/i)).toBeRequired();
    expect(screen.getByText(/goes on Govind Wholesale’s record/i)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("radio", { name: /made it right/i }));
    expect(screen.getByLabelText(/^why$/i)).toBeRequired();
  });

  it("sends null rather than an empty string when nothing was said", async () => {
    const close = vi.spyOn(api, "closePurchaseOrder").mockResolvedValue(undefined as never);
    showOrder(detail());
    render(<PurchaseOrderDetailPage />);

    await act(async () => {
      fireEvent.submit(closeForm());
    });

    expect(close).toHaveBeenCalledWith("po1", "AS_COMPUTED", null, "test-token");
  });

  /**
   * The one case where the figure is real and the scorecard still will not count it (T-137, D-25).
   * A person choosing between the two endings is entitled to know their choice changes nothing for
   * this order, rather than discovering it on the report a week later.
   */
  it("says so when the order was sent late and is already out of the vendor's record", () => {
    showOrder(detail({ sentAfterLeadTime: true }));
    render(<PurchaseOrderDetailPage />);

    expect(
      screen.getByText(/already left out of their delivery record whatever is chosen below/i),
    ).toBeInTheDocument();
  });

  it("says there is nothing to score when the order never had a needed-by date", () => {
    showOrder(detail({ neededBy: null }, { percent: null, itemsScored: 0, itemsOnTime: 0 }));
    render(<PurchaseOrderDetailPage />);

    expect(screen.getByText(/nothing to be late against/i)).toBeInTheDocument();
    expect(screen.queryByText("60%")).toBeNull();
  });
});

/**
 * The hole T-142 closes (D-26). Cancelling a part-delivered order leaves the scorecard's live-order
 * predicate entirely, so it erases the goods that actually arrived from the supplier's record — and
 * lets somebody tick "Vendor Never Delivered this Order" against a vendor who demonstrably did
 * deliver.
 *
 * <p>The screen is only half of it and knows it: the server refuses the same request with
 * KMS-400150 whatever this file asserts, because *a rule that lives only in a form is not a rule*.
 * `PurchaseOrderClosingIT.aPartDeliveredOrderCannotBeCancelled` posts it straight at the endpoint.
 */
describe("cancelling is no longer one of the doors out of a part delivery", () => {
  it("is not offered on a part-delivered order", () => {
    showOrder(detail());
    render(<PurchaseOrderDetailPage />);

    expect(screen.queryByRole("heading", { name: /cancel this purchase order/i })).toBeNull();
    // And the "Vendor Never Delivered this Order" tick goes with it, which is the half that
    // mattered: it is a permanent claim, and this vendor brought 300 kg.
    expect(screen.queryByRole("checkbox", { name: /vendor never delivered this order/i })).toBeNull();
  });

  /**
   * The one place this screen is allowed to say nothing about a control that vanished. Everywhere
   * else in this file an absent control is explained in a sentence, because "a control that
   * disappears with no explanation reads as a bug or as a missing permission". Here the replacement
   * is structural: `canClose` is the exact complement of the status removed from `canCancel`, so the
   * panel that goes is always replaced, in the same place, by one that says what happens to the
   * goods that came and to the ones that did not.
   */
  it("puts the closing panel where the cancel panel was", () => {
    showOrder(detail());
    render(<PurchaseOrderDetailPage />);

    expect(screen.getByRole("heading", { name: /close this order, part delivered/i })).toBeInTheDocument();
  });

  it.each(["DRAFT", "SENT"] as const)("is still offered on a %s order", (status) => {
    showOrder(detail({ status }));
    render(<PurchaseOrderDetailPage />);

    expect(screen.getByRole("heading", { name: /cancel this purchase order/i })).toBeInTheDocument();
  });
});

describe("a closed order, read back", () => {
  it("says how it ended, what that meant for the vendor, and in whose words", () => {
    showOrder(
      detail({
        status: "CLOSED",
        closedAt: "2026-09-10T09:00:00Z",
        closeOutcome: "VENDOR_LET_US_DOWN",
        closeNote: "Rang four times over a fortnight. Nobody rang back.",
      }),
    );
    render(<PurchaseOrderDetailPage />);

    expect(screen.getByText("Vendor let us down")).toBeInTheDocument();
    expect(screen.getByText(/recorded against Govind Wholesale/i)).toBeInTheDocument();
    expect(screen.getByText(/Nobody rang back/)).toBeInTheDocument();
    expect(screen.getByText("Closed")).toBeInTheDocument();
  });

  it("says when the shortfall was excused, and that it left their record", () => {
    showOrder(
      detail({
        status: "CLOSED",
        closedAt: "2026-09-10T09:00:00Z",
        closeOutcome: "SHORTFALL_EXCUSED",
        closeNote: "Blamed the lorry, 10% off next time.",
      }),
    );
    render(<PurchaseOrderDetailPage />);

    expect(screen.getByText("Shortfall excused")).toBeInTheDocument();
    expect(screen.getByText(/left out of their delivery record/i)).toBeInTheDocument();
  });

  it("claims nothing about the vendor when the outcome claimed nothing", () => {
    showOrder(
      detail({
        status: "CLOSED",
        closedAt: "2026-09-10T09:00:00Z",
        closeOutcome: "AS_COMPUTED",
        closeNote: null,
      }),
    );
    render(<PurchaseOrderDetailPage />);

    expect(screen.getByText("Closed short")).toBeInTheDocument();
    expect(screen.getByText(/nothing was recorded for or against Govind Wholesale/i)).toBeInTheDocument();
  });
});

// ---------------------------------------------------------------------------
// The scorecard: a number whose exclusions are invisible cannot be checked.
// ---------------------------------------------------------------------------

function vendorRow(o: Partial<VendorPerformanceRow> = {}): VendorPerformanceRow {
  return {
    vendorId: "v1",
    vendorName: "Govind Wholesale",
    active: true,
    ordersPlaced: 11,
    ordersJudged: 9,
    onTimeOrders: 8,
    abandonedOrders: 0,
    ordersWithoutNeededBy: 0,
    ordersSentLate: 0,
    ordersExcused: 0,
    itemsScored: 20,
    itemsOnTime: 18,
    onTimePercent: 90,
    linesJudged: 20,
    fillRatePercent: 96,
    rejectedLines: 0,
    rejections: [],
    openOrders: 0,
    openCurrent: 0,
    openDue1To30: 0,
    openOverdue31Plus: 0,
    enoughToRank: true,
    ...o,
  };
}

function report(o: Partial<VendorPerformance> = {}): VendorPerformance {
  const excused = o.ordersExcused ?? 0;
  return {
    from: "2026-09-01",
    to: "2026-09-30",
    ordersPlaced: 11,
    ordersJudged: 9,
    onTimeOrders: 8,
    abandonedOrders: 0,
    ordersWithoutNeededBy: 0,
    ordersSentLate: 0,
    ordersExcused: excused,
    itemsScored: 20,
    itemsOnTime: 18,
    onTimePercent: 90,
    linesJudged: 20,
    fillRatePercent: 96,
    rejectedLines: 0,
    openOrders: 0,
    openCurrent: 0,
    openDue1To30: 0,
    openOverdue31Plus: 0,
    vendors: [vendorRow({ ordersExcused: excused })],
    ...o,
  };
}

describe("the scorecard shows what the excuses left out", () => {
  it("counts the orders the vendor made right, beside BOTH percentages", () => {
    queryRef.current = { data: report({ ordersExcused: 2 }), error: null, loading: false };
    render(<VendorPerformancePage />);

    // Twice in the vendor's row — once under on-time, once under the fill rate — plus the totals
    // line. It is in the fill-rate column because this exclusion moves that percentage too, which
    // is exactly what an order we sent late does NOT do.
    expect(screen.getAllByText("2 orders they made right — not counted")).toHaveLength(3);
    expect(screen.getByText(/left out of the on-time figure and the fill rate alike/i)).toBeInTheDocument();
  });

  it("says plainly that nobody can move a percentage by hand", () => {
    queryRef.current = { data: report({ ordersExcused: 1 }), error: null, loading: false };
    render(<VendorPerformancePage />);

    expect(screen.getByText(/1 order was closed with part of the delivery never made/i)).toBeInTheDocument();
    expect(
      screen.getByText(/Nobody can change a percentage by hand anywhere in this application/i),
    ).toBeInTheDocument();
  });

  it("says nothing at all when nothing was excused", () => {
    queryRef.current = { data: report({ ordersExcused: 0 }), error: null, loading: false };
    render(<VendorPerformancePage />);

    expect(screen.queryByText(/they made right/i)).toBeNull();
    expect(screen.queryByText(/closed with part of the delivery never made/i)).toBeNull();
  });
});
