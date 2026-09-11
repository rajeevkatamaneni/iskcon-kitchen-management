import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { ApiError, api } from "@/lib/api";
import type {
  GoodsReceiptView,
  IngredientView,
  PurchaseOrderDetailView,
  PurchaseOrderView,
  TodayView,
  VendorPerformance,
  VendorPerformanceRow,
} from "@/lib/api";

/**
 * The vendor's lead time, as one promise, on every screen it shows (T-137, D-25 and D-24a).
 *
 * <p>Rajeev's principle, and it is the reason these four screens are tested in one file: *"We can't
 * forget the Golden Rule: Hold others to the same standards you want to be held to."* A lead time
 * is the vendor's own number, agreed at onboarding and padded on purpose, and it binds both sides.
 *
 * <p><strong>What these guard is that nothing here is worked out twice.</strong> Every date, zone
 * and verdict on these screens arrives from the server, which computes it in one place. So each of
 * these tests hands the page a server answer and asserts the words — and, crucially, one of them
 * hands the page a *draft that a naive screen would judge differently* and proves the screen does
 * not judge it at all. The arithmetic itself is the backend's, guarded by
 * `PurchaseOrderLeadTimeIT`.
 */

const { authRef, returnsRef, reloadMock, queryRef, paramsRef } = vi.hoisted(() => ({
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
  paramsRef: { current: new URLSearchParams() },
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({ id: "po1" }),
  useSearchParams: () => paramsRef.current,
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token", signOut: vi.fn() }),
}));
// One mock for four screens. The order screen issues three queries per render and the Today screen
// two, so the ordered-list-by-call-index shape used by order-detail.test.tsx is reused; the
// single-query screens set `queryRef` and ignore the list.
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => {
    const list = returnsRef.current;
    if (list.length === 0) return { ...queryRef.current, reload: reloadMock };
    const value = list[returnsRef.i % list.length];
    returnsRef.i += 1;
    return { ...value, reload: reloadMock };
  },
}));
// The Today screen's platform-notice strip fetches its own feed and is not what this file is about.
vi.mock("@/components/PlatformNotices", () => ({ PlatformNotices: () => null }));

import PurchaseOrderDetailPage from "@/app/orders/[id]/page";
import PurchaseOrdersPage from "@/app/orders/page";
import TodayPage from "@/app/today/page";
import VendorPerformancePage from "@/app/vendor-performance/page";

// ---------------------------------------------------------------------------
// Rajeev's own worked example: Heritage Fresh Dairy promised two days, curd is
// wanted for the 15th. Every fixture below is one of the days he walked through.
// ---------------------------------------------------------------------------

function order(o: Partial<PurchaseOrderView> = {}): PurchaseOrderView {
  return {
    id: "po1",
    poNumber: "PO-2026-0042",
    vendorId: "v1",
    vendorName: "Heritage Fresh Dairy",
    status: "DRAFT",
    orderDate: "2026-09-11",
    neededBy: "2026-09-15",
    deliveryLocation: null,
    notes: null,
    cancelReason: null,
    vendorAbandoned: false,
    sentAt: null,
    cancelledAt: null,
    createdAt: "2026-09-11T09:00:00Z",
    // Two days, agreed at onboarding, so the last day this can be ordered is the 13th.
    leadTimeDays: 2,
    orderBy: "2026-09-13",
    orderUrgency: "IN_TIME",
    ...o,
  };
}

const LINES = [
  { id: "l1", ingredientId: "ing1", ingredientName: "Curd", description: null, quantity: 20, unit: "KG", expectedPrice: 60, arrivedOn: null },
];
const RECEIPTS: GoodsReceiptView[] = [];
const INGREDIENTS: IngredientView[] = [
  { id: "ing1", name: "Curd", category: "Dairy", unit: "KG", ekadashiProhibited: false, supply: false, libraryDerived: false, aliases: [], createdAt: "2026-01-01T00:00:00Z" },
];

function detail(o: Partial<PurchaseOrderView> = {}): PurchaseOrderDetailView {
  return {
    order: order(o),
    lines: LINES,
    events: [],
    whatsappEverSent: false,
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

/** A date `n` days from the runner's today, in the form the date input takes. */
function todayPlus(n: number): string {
  const d = new Date();
  d.setDate(d.getDate() + n);
  return [d.getFullYear(), String(d.getMonth() + 1).padStart(2, "0"), String(d.getDate()).padStart(2, "0")].join("-");
}

function showList(orders: PurchaseOrderView[]) {
  returnsRef.current = [];
  queryRef.current = { data: orders, error: null, loading: false };
}

beforeEach(() => {
  authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
  returnsRef.current = [];
  returnsRef.i = 0;
  queryRef.current = { data: null, error: null, loading: false };
  paramsRef.current = new URLSearchParams();
  reloadMock.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("the order screen says where this order stands against the vendor's promise", () => {
  it("names the order-by date and whose notice period produced it", () => {
    showOrder(detail());
    render(<PurchaseOrderDetailPage />);

    // The vendor's own number, and the date that follows from it. Both the server's.
    // "13 Sept 2026" in en-GB — September is the one month whose short form runs to four letters,
    // so the match stops before the runner's locale can argue about it.
    expect(screen.getByText(/Order by 13 Sep/)).toBeInTheDocument();
    expect(screen.getByText(/Heritage Fresh Dairy asked for 2 days’ notice/)).toBeInTheDocument();
    // Comfortably inside: "no alarm bells here", so no badge of any kind.
    expect(screen.queryByText(/Order today/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Past the order-by date/)).not.toBeInTheDocument();
  });

  it("nudges on the last day that works, and still offers the ordinary Mark sent", () => {
    showOrder(detail({ orderUrgency: "ORDER_TODAY" }));
    render(<PurchaseOrderDetailPage />);

    expect(screen.getByText("Order today")).toBeInTheDocument();
    expect(screen.getByText(/last day this can be ordered/)).toBeInTheDocument();
    // A nudge and not a gate: nothing is disabled and nothing is hidden.
    expect(screen.getByRole("button", { name: /mark sent/i })).toBeEnabled();
  });

  it("says a different thing once the order-by date has gone, not a louder one", () => {
    showOrder(detail({ orderUrgency: "TOO_LATE", orderBy: "2026-09-13" }));
    render(<PurchaseOrderDetailPage />);

    expect(screen.getByText("Past the order-by date")).toBeInTheDocument();
    // The consequence, which is the whole of why Rajeev wanted the warning: it is a favour we are
    // asking, and a late delivery on it is not held against them.
    expect(screen.getByText(/favour we are asking/)).toBeInTheDocument();
    expect(screen.getByText(/won’t count against them/)).toBeInTheDocument();
  });

  it("says nothing at all for a vendor who has never given a lead time", () => {
    // Rajeev, asked directly: an order for a vendor with no recorded lead time has no cutoff. No
    // nudge, no warning, no exclusion — silence. An assumption printed as their word would be the
    // application inventing a promise on a supplier's behalf.
    showOrder(detail({ leadTimeDays: null, orderBy: null, orderUrgency: null }));
    render(<PurchaseOrderDetailPage />);

    expect(screen.queryByText(/Order by/)).not.toBeInTheDocument();
    expect(screen.queryByText(/notice/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Past the order-by date/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /mark sent/i })).toBeEnabled();
  });

  it("does not decide for itself: a draft the dates would call late shows what the server said", () => {
    // The dates here scream late — needed tomorrow, two days' notice — and the server says IN_TIME,
    // which is what this screen must show. The point is not that the server is right; it is that
    // there is exactly one answer, and this screen is not a second opinion. A screen that did its
    // own subtraction is how the planner, the order screen and the dashboard come to disagree.
    showOrder(detail({ neededBy: "2026-09-12", orderBy: "2026-09-13", orderUrgency: "IN_TIME" }));
    render(<PurchaseOrderDetailPage />);

    // "13 Sept 2026" in en-GB — September is the one month whose short form runs to four letters,
    // so the match stops before the runner's locale can argue about it.
    expect(screen.getByText(/Order by 13 Sep/)).toBeInTheDocument();
    expect(screen.queryByText(/Past the order-by date/)).not.toBeInTheDocument();
  });
});

describe("the edit form warns with the vendor's own number, and never with one of its own", () => {
  it("names the notice this vendor asked for when the date is inside it", () => {
    // T-137's second answer, removed. `leadTimeWarning` compared every date against a hard-coded
    // two days until now, so the form would have said "2 days" on a screen whose Mark sent had just
    // been refused against five — three answers about one order, which is the failure this task
    // exists to prevent.
    showOrder(detail({ leadTimeDays: 5, neededBy: "2026-09-13" }));
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /^edit$/i }));

    fireEvent.change(screen.getByLabelText("Needed by"), { target: { value: todayPlus(2) } });
    expect(
      screen.getByText("Sooner than the 5 days’ notice this vendor asked for")
    ).toBeInTheDocument();
  });

  it("says nothing about notice for a vendor who has never asked for any", () => {
    showOrder(detail({ leadTimeDays: null, orderBy: null, orderUrgency: null }));
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /^edit$/i }));

    fireEvent.change(screen.getByLabelText("Needed by"), { target: { value: todayPlus(0) } });
    // Silence, not the two days this used to assume on everybody's behalf.
    expect(screen.queryByText(/notice/)).not.toBeInTheDocument();
    // The one thing still worth saying about a date has nothing to do with any vendor.
    fireEvent.change(screen.getByLabelText("Needed by"), { target: { value: todayPlus(-1) } });
    expect(screen.getByText("That day has already gone")).toBeInTheDocument();
  });
});

describe("sending an order the vendor's lead time cannot cover", () => {
  it("refuses once, says what it costs, and sends on the second press", async () => {
    showOrder(detail({ orderUrgency: "TOO_LATE" }));
    const refusal = vi.spyOn(api, "sendPurchaseOrder").mockRejectedValue(
      new ApiError(
        {
          code: "KMS-400148",
          message: "This order is going out later than the vendor asked to be given.",
          action:
            "Send it anyway if they have agreed, or change the needed-by date. A late delivery on this order won't count against them.",
          fieldErrors: [],
        },
        409
      )
    );
    const anyway = vi.spyOn(api, "sendPurchaseOrderAnyway").mockResolvedValue(undefined);
    render(<PurchaseOrderDetailPage />);

    // The offer is not hidden and not disabled: ordering late is allowed, and a rule that made it
    // impossible would only teach people to write a date they do not mean.
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /mark sent/i }));
    });
    expect(refusal).toHaveBeenCalledTimes(1);
    expect(screen.getByText(/going out later than the vendor asked to be given/)).toBeInTheDocument();
    expect(anyway).not.toHaveBeenCalled();

    // And then the override, which is a separate, deliberate second press.
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /send it anyway/i }));
    });
    expect(anyway).toHaveBeenCalledTimes(1);
    expect(reloadMock).toHaveBeenCalled();
  });

  it("offers no override for a refusal that is about something else", async () => {
    showOrder(detail({ orderUrgency: "TOO_LATE" }));
    vi.spyOn(api, "sendPurchaseOrder").mockRejectedValue(
      new ApiError(
        { code: "KMS-400051", message: "That isn’t a step this order can take.", action: "Reload the order.", fieldErrors: [] },
        409
      )
    );
    render(<PurchaseOrderDetailPage />);

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /mark sent/i }));
    });
    // "Send it anyway" waives a vendor's promise. It must appear for exactly one refusal.
    expect(screen.queryByRole("button", { name: /send it anyway/i })).not.toBeInTheDocument();
  });

  it("says so on a sent order, and does not offer the no-show tick when cancelling it", () => {
    showOrder(
      detail({
        status: "SENT",
        sentAt: "2026-09-14T04:00:00Z",
        orderUrgency: null,
        sentAfterLeadTime: true,
      })
    );
    render(<PurchaseOrderDetailPage />);

    expect(screen.getByText("Sent late")).toBeInTheDocument();
    expect(screen.getByText(/isn’t counted against them/)).toBeInTheDocument();
    // T-129 as D-25 extends it: the box is not offered, and the absence is explained rather than
    // left as a gap — the person cancelling is the one who most needs to know it counts against
    // nobody.
    expect(
      screen.queryByRole("checkbox", { name: /vendor never delivered this order/i })
    ).not.toBeInTheDocument();
    expect(screen.getByText(/there is nothing to hold them to/)).toBeInTheDocument();
  });

  it("offers the no-show tick on an order we did send in time", () => {
    showOrder(
      detail({ status: "SENT", sentAt: "2026-09-11T04:00:00Z", orderUrgency: null, sentAfterLeadTime: false })
    );
    render(<PurchaseOrderDetailPage />);

    expect(
      screen.getByRole("checkbox", { name: /vendor never delivered this order/i })
    ).toBeInTheDocument();
    expect(screen.getByText(/Sent within the 2 days’ notice/)).toBeInTheDocument();
  });
});

describe("the draft nobody sends", () => {
  it("warns at the top of the purchase-orders page, naming the orders", () => {
    showList([
      order({ id: "a", poNumber: "PO-2026-0042", orderUrgency: "TOO_LATE" }),
      order({ id: "b", poNumber: "PO-2026-0043", vendorName: "Sri Traders", orderUrgency: "ORDER_TODAY" }),
      order({ id: "c", poNumber: "PO-2026-0044", orderUrgency: "IN_TIME" }),
    ]);
    render(<PurchaseOrdersPage />);

    expect(screen.getByText(/1 draft is past the day it had to be ordered/)).toBeInTheDocument();
    expect(screen.getByText(/1 draft has to be sent today/)).toBeInTheDocument();
    const notice = screen.getByText(/PO-2026-0042 \(Heritage Fresh Dairy\)/);
    expect(notice).toBeInTheDocument();
    // The one with time left is not named IN THE NOTICE — it is still in the table below, which is
    // why this reads the notice's own text rather than the whole screen. A warning that named every
    // draft would not be a warning.
    expect(notice.textContent).not.toMatch(/PO-2026-0044/);
  });

  it("says nothing when every draft still has time, or has no cutoff at all", () => {
    showList([
      order({ id: "a", orderUrgency: "IN_TIME" }),
      order({ id: "b", leadTimeDays: null, orderBy: null, orderUrgency: null }),
      order({ id: "c", status: "SENT", sentAt: "2026-09-11T04:00:00Z", orderUrgency: null }),
    ]);
    render(<PurchaseOrdersPage />);

    expect(screen.queryByText(/had to be ordered/)).not.toBeInTheDocument();
    expect(screen.queryByText(/holds its ingredients off the shopping list/)).not.toBeInTheDocument();
  });

  it("warns on the Today dashboard, where somebody will actually see it", () => {
    returnsRef.current = [
      { data: TODAY, error: null, loading: false },
      {
        data: [
          order({ id: "a", orderUrgency: "TOO_LATE" }),
          order({ id: "b", orderUrgency: "IN_TIME" }),
        ],
        error: null,
        loading: false,
      },
    ];
    returnsRef.i = 0;
    render(<TodayPage />);

    expect(screen.getByText(/1 draft order/)).toBeInTheDocument();
    expect(screen.getByText(/past the day it had to go out/)).toBeInTheDocument();
    // The reason it matters, which is what D-24a's whole hole is about.
    expect(screen.getByText(/holds its ingredients off the shopping list/)).toBeInTheDocument();
  });

  it("says nothing on the Today dashboard when no draft is at risk", () => {
    returnsRef.current = [
      { data: TODAY, error: null, loading: false },
      { data: [order({ id: "a", orderUrgency: "IN_TIME" })], error: null, loading: false },
    ];
    returnsRef.i = 0;
    render(<TodayPage />);

    expect(screen.queryByText(/draft order/)).not.toBeInTheDocument();
  });
});

describe("the vendor scorecard shows what it left out", () => {
  it("counts the orders we sent late, beside the percentage, in our own name", () => {
    queryRef.current = { data: report({ ordersSentLate: 2 }), error: null, loading: false };
    render(<VendorPerformancePage />);

    // "we", because this is the one figure on the screen that is about the temple rather than the
    // supplier — and a percentage whose exclusions are invisible cannot be checked.
    expect(screen.getAllByText(/2 orders we sent late — not counted/).length).toBeGreaterThan(0);
    expect(screen.getByText(/sent after the vendor had asked to be given/)).toBeInTheDocument();
    expect(screen.getByText(/left out of the on-time figure/)).toBeInTheDocument();
  });

  it("says nothing about late sending when there was none", () => {
    queryRef.current = { data: report({ ordersSentLate: 0 }), error: null, loading: false };
    render(<VendorPerformancePage />);

    expect(screen.queryByText(/we sent late/)).not.toBeInTheDocument();
  });
});

// ---------------------------------------------------------------------------

function vendorRow(o: Partial<VendorPerformanceRow> = {}): VendorPerformanceRow {
  return {
    vendorId: "v1",
    vendorName: "Heritage Fresh Dairy",
    active: true,
    ordersPlaced: 11,
    ordersJudged: 9,
    onTimeOrders: 8,
    abandonedOrders: 0,
    ordersWithoutNeededBy: 0,
    ordersSentLate: 2,
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
  const sentLate = o.ordersSentLate ?? 0;
  return {
    from: "2026-09-01",
    to: "2026-09-30",
    ordersPlaced: 11,
    ordersJudged: 9,
    onTimeOrders: 8,
    abandonedOrders: 0,
    ordersWithoutNeededBy: 0,
    ordersSentLate: sentLate,
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
    vendors: [vendorRow({ ordersSentLate: sentLate })],
    ...o,
  };
}

/** The morning screen with nothing on it, so the only notice under test is the one this file adds. */
const TODAY: TodayView = {
  date: "2026-09-11",
  calendar: null,
  meals: [],
  platesToday: 0,
  itemsBelowThreshold: 0,
  itemsTracked: 0,
  workforce: { staffIn: 2, volunteers: 1, meals: [] },
  materialsCost: { estimatedTotal: 0, withoutPrice: 0 },
  unrecordedMeals: 0,
  approvals: { ingredientRequests: 0, ingredientRequestsSoon: 0, leaveRequests: 0, leaveRequestsSoon: 0 },
  deliveries: [],
  equipmentOverdue: null,
};
