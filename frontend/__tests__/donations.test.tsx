import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { IngredientView, LedgerRow, PeriodSummary, WishlistItemView } from "@/lib/api";
import { todayIso } from "@/lib/format";

const { authRef, returnsRef, reloadMock, recordMock, exportLedger, periodSummaryMock } = vi.hoisted(() => ({
  exportLedger: vi.fn(),
  periodSummaryMock: vi.fn(),
  authRef: {
    current: { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  returnsRef: {
    current: [] as Array<{ data: unknown; error: null; loading: boolean }>,
    slots: new Map<unknown, number>(),
  },
  reloadMock: vi.fn(),
  recordMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
// The page queries in a fixed order — ingredients, the wish list, then (for an admin) the period
// summary and the ledger rows it scopes. Each fetcher keeps the slot it was first seen in, rather
// than the stub counting calls: when only the ledger re-renders it makes two of the four calls, and
// a counter would hand it the wrong data.
//
// Each fetcher is also run once, the first time it is seen, exactly as the real hook would. The
// screen's period control is only observable through what it asks the API for — the data itself
// comes back from the fixture — so without this the period tests would have nothing to assert on.
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fetcher: unknown) => {
    if (!returnsRef.slots.has(fetcher)) {
      returnsRef.slots.set(fetcher, returnsRef.slots.size);
      const result = (fetcher as (token?: string) => unknown)("test-token");
      // A fetcher wrapping an api method this file has not stubbed would reject into an unhandled
      // rejection and fail the run for a reason unrelated to the test; it is swallowed here.
      if (result && typeof (result as Promise<unknown>).catch === "function") {
        (result as Promise<unknown>).catch(() => undefined);
      }
    }
    const list = returnsRef.current;
    const slot = Math.min(returnsRef.slots.get(fetcher) as number, list.length - 1);
    return { ...list[slot], reload: reloadMock };
  },
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      recordDonation: recordMock,
      exportLedger,
      donationPeriodSummary: periodSummaryMock,
      donationLedger: vi.fn(),
      listIngredients: vi.fn(),
      listWishlist: vi.fn(),
    },
  };
});

import DonationsPage from "@/app/donations/page";

const rice: IngredientView = {
  id: "ing1",
  name: "Rice",
  category: "Grains",
  unit: "KG",
  sattvicProhibited: false,
  aliases: [],
  createdAt: "2026-08-01T00:00:00Z",
};

const CASH_ROW: LedgerRow = {
  id: "d1", donatedOn: "2026-08-16", category: "MANUAL", donorDisplay: "Volunteer One",
  amountInr: 5000, currency: "INR", paymentMode: "CASH", providerRef: null, status: "COMPLETED",
  linkedTo: null,
};

const GRINDER: WishlistItemView = {
  id: "w1", title: "Wet grinder", description: null, imageRef: null, priceInr: 15000,
  category: "EQUIPMENT", quantityWanted: 1, sponsoredQuantity: 0, paidInr: 5000, sortOrder: 0,
  status: "ACTIVE", note: null, createdAt: "2026-08-01T00:00:00Z",
};

/**
 * A window that is still running — it ends on the temple's today — so the tiles word their
 * comparison as "this point last year". Built from the real today rather than a fixed date, because
 * a hard-coded one turns into a closed window the moment it passes and the wording flips under the
 * test's feet.
 *
 * <p>One-time giving grew, and the temple has been handed no cash at all this period against ₹0
 * last period — the case that has no denominator and must not print a percentage.
 */
const SUMMARY: PeriodSummary = {
  window: {
    period: "MONTH",
    financialYear: null,
    from: "2026-08-01",
    to: todayIso(),
    previousFrom: "2025-08-01",
    previousTo: "2025-08-19",
  },
  hasPriorYear: true,
  byCategory: {
    ONE_TIME: { total: 124000, previousTotal: 105085, changePercent: 18 },
    RECURRING: { total: 40000, previousTotal: 50000, changePercent: -20 },
    MANUAL: { total: 7500, previousTotal: 0, changePercent: null },
  },
  financialYearsWithGifts: [2026, 2025, 2024],
};

/** The same figures at a temple whose books do not reach back a year at all. */
const FIRST_YEAR_SUMMARY: PeriodSummary = { ...SUMMARY, hasPriorYear: false };

function ingredientsOnly() {
  returnsRef.current = [
    { data: [rice], error: null, loading: false },
    { data: [], error: null, loading: false }, // the wish list, empty
  ];
  returnsRef.slots.clear();
}

/**
 * An admin's four queries, in the order the page makes them: ingredients, the wish list, the period
 * summary, then the ledger rows that summary's window scopes. An admin always renders the ledger
 * beneath the form, so every admin fixture needs all four slots even when the test is only looking
 * at the form.
 */
function asAdmin(wishlist: WishlistItemView[], summary: PeriodSummary = SUMMARY) {
  returnsRef.current = [
    { data: [rice], error: null, loading: false },
    { data: wishlist, error: null, loading: false },
    { data: summary, error: null, loading: false },
    { data: [CASH_ROW], error: null, loading: false },
    // Recording a gift, or switching period, rebuilds a fetcher — a new identity, so a new slot.
    // The pair repeats here so the next query lands on the right kind of data rather than falling
    // off the end of the list.
    { data: summary, error: null, loading: false },
    { data: [CASH_ROW], error: null, loading: false },
  ];
  returnsRef.slots.clear();
}

function withLedger() {
  asAdmin([GRINDER]);
}

describe("recording a donation", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    ingredientsOnly();
    reloadMock.mockReset();
    recordMock.mockReset().mockResolvedValue({ id: "d1" });
  });

  it("hides donor fields when the gift is anonymous", () => {
    render(<DonationsPage />);
    expect(screen.getByLabelText(/donor name/i)).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText(/anonymous donor/i));
    expect(screen.queryByLabelText(/donor name/i)).not.toBeInTheDocument();
  });

  it("records a food donation", async () => {
    render(<DonationsPage />);
    const form = screen.getByRole("form", { name: /record a donation/i });
    fireEvent.change(within(form).getByLabelText(/donor name/i), { target: { value: "Govind Das" } });

    fireEvent.click(within(form).getByRole("button", { name: /add food item/i }));
    fireEvent.change(within(form).getByLabelText(/food ingredient 1/i), { target: { value: "ing1" } });
    fireEvent.change(within(form).getByLabelText(/quantity 1/i), { target: { value: "5" } });

    fireEvent.click(within(form).getByRole("button", { name: /record donation/i }));

    await waitFor(() =>
      expect(recordMock).toHaveBeenCalledWith(
        expect.objectContaining({
          anonymous: false,
          donorName: "Govind Das",
          cashAmountInr: null,
          ingredients: [expect.objectContaining({ ingredientId: "ing1", quantity: 5, unit: "KG" })],
        }),
        "test-token"
      )
    );
  });

  it("records a cash gift with no goods", async () => {
    render(<DonationsPage />);
    const form = screen.getByRole("form", { name: /record a donation/i });
    fireEvent.change(within(form).getByLabelText(/donor name/i), { target: { value: "Walk-in Devotee" } });
    fireEvent.change(within(form).getByLabelText(/cash amount/i), { target: { value: "5000" } });

    fireEvent.click(within(form).getByRole("button", { name: /record donation/i }));

    await waitFor(() =>
      expect(recordMock).toHaveBeenCalledWith(
        expect.objectContaining({
          donorName: "Walk-in Devotee",
          cashAmountInr: 5000,
          estimatedValueInr: null,
          ingredients: [],
          equipment: [],
        }),
        "test-token"
      )
    );
  });

  /**
   * Cash "for the grinder" is the commonest thing the office is handed, and it used to survive only
   * as a note nothing could read. The picker offers what is still needed so whoever takes the money
   * can see what it finishes.
   */
  it("links cash to a wish-list item when an admin says what it was for", async () => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    asAdmin([GRINDER]);
    render(<DonationsPage />);
    const form = screen.getByRole("form", { name: /record a donation/i });

    fireEvent.change(within(form).getByLabelText(/donor name/i), { target: { value: "Govind Das" } });
    fireEvent.change(within(form).getByLabelText(/cash amount/i), { target: { value: "5000" } });

    const towards = within(form).getByLabelText(/towards/i);
    expect(within(towards as HTMLElement).getByRole("option", { name: /wet grinder/i }))
      .toHaveTextContent("₹10,000 still needed");
    fireEvent.change(towards, { target: { value: "w1" } });

    fireEvent.click(within(form).getByRole("button", { name: /record donation/i }));

    await waitFor(() =>
      expect(recordMock).toHaveBeenCalledWith(
        expect.objectContaining({ cashAmountInr: 5000, wishlistItemId: "w1" }),
        "test-token"
      )
    );
  });

  it("offers nothing to link to when the temple keeps no wish list", () => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    asAdmin([]);
    render(<DonationsPage />);
    expect(screen.queryByLabelText(/towards/i)).not.toBeInTheDocument();
  });

  it("keeps cash and goods apart — each closes the other, as the server requires", () => {
    render(<DonationsPage />);
    const form = screen.getByRole("form", { name: /record a donation/i });

    fireEvent.change(within(form).getByLabelText(/cash amount/i), { target: { value: "5000" } });
    expect(within(form).getByRole("button", { name: /add food item/i })).toBeDisabled();
    expect(within(form).getByRole("button", { name: /add equipment/i })).toBeDisabled();

    fireEvent.change(within(form).getByLabelText(/cash amount/i), { target: { value: "" } });
    fireEvent.click(within(form).getByRole("button", { name: /add food item/i }));
    expect(within(form).getByLabelText(/cash amount/i)).toBeDisabled();
  });

  it("refuses a role without inventory access", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } };
    render(<DonationsPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});

describe("the ledger under the form", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    withLedger();
    reloadMock.mockReset();
    periodSummaryMock.mockReset();
    recordMock.mockReset().mockResolvedValue({ id: "d1" });
    exportLedger.mockReset().mockResolvedValue({
      blob: new Blob(["Date,Category\n"], { type: "text/csv" }),
      filename: "donations.csv",
    });
    URL.createObjectURL = vi.fn(() => "blob:donations");
    URL.revokeObjectURL = vi.fn();
  });

  it("shows an admin every gift, the period tiles, a Manual filter, and a CSV export", () => {
    render(<DonationsPage />);
    expect(screen.getByRole("heading", { name: /every gift received/i })).toBeInTheDocument();

    // The row a plain donations list could not show: category, amount, and mode all present.
    expect(screen.getByText("Volunteer One")).toBeInTheDocument();
    expect(screen.getByText("₹5,000")).toBeInTheDocument();
    expect(screen.getByText("CASH")).toBeInTheDocument();

    expect(screen.getByText("₹1,24,000")).toBeInTheDocument(); // the one-time tile, in lakhs
    expect(screen.getByRole("option", { name: "Manual" })).toBeInTheDocument();
  });

  /**
   * The comparison is the point of the tiles, and three of its four answers are not percentages.
   * Each is a different fact and has to read as one.
   */
  it("says how each figure compares with the same point a year earlier", () => {
    render(<DonationsPage />);
    expect(screen.getByText("up 18% on this point last year")).toBeInTheDocument();
    expect(screen.getByText("down 20% on this point last year")).toBeInTheDocument();
    // ₹0 last year has no denominator, so the tile says so rather than printing an infinite rise.
    // Three tiles say it: the cash one, and the two kinds of gift this temple has never received.
    expect(screen.getAllByText("nothing at this point last year")).toHaveLength(3);
  });

  it("tells a first-year temple there is nothing to compare with, rather than a fall of 100%", () => {
    asAdmin([GRINDER], FIRST_YEAR_SUMMARY);
    render(<DonationsPage />);
    expect(screen.queryByText(/on this point last year/)).not.toBeInTheDocument();
    expect(screen.getAllByText("nothing recorded that far back").length).toBeGreaterThan(0);
  });

  /**
   * The control is what makes the screen worth having: the tiles, the rows and the CSV all have to
   * move together, so the period is asked of the server and the window it answers with drives all
   * three.
   */
  it("asks the server for the chosen period rather than working the dates out itself", async () => {
    render(<DonationsPage />);
    expect(periodSummaryMock).toHaveBeenCalledWith("MONTH", null, "test-token");

    fireEvent.click(screen.getByRole("tab", { name: "This financial year" }));
    await waitFor(() =>
      expect(periodSummaryMock).toHaveBeenCalledWith("FINANCIAL_YEAR", null, "test-token")
    );
  });

  /**
   * "Another year" opens a picker of the years the temple actually has gifts in — not a fixed span
   * of recent ones — and defaults to the year just closed, which is the one an accountant reaches
   * for. A separate render from the test above: the fixture hands data out by the order the queries
   * were first seen, so one switch per render keeps that order honest.
   */
  it("offers the temple's own financial years, defaulting to the one just closed", async () => {
    render(<DonationsPage />);
    fireEvent.click(screen.getByRole("tab", { name: "Another year" }));

    await waitFor(() => expect(periodSummaryMock).toHaveBeenCalledWith("YEAR", 2025, "test-token"));
    expect(screen.getByRole("option", { name: "FY 2025–26" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "FY 2024–25" })).toBeInTheDocument();
  });

  it("exports with the token rather than linking — a link cannot carry one, and 401s", async () => {
    render(<DonationsPage />);
    // A button, not an anchor: clicking a bare href at the export URL returned an HTTP 401 page.
    expect(screen.queryByRole("link", { name: /export csv/i })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /export csv/i }));
    // The file covers exactly the window on screen, so the accountant's spreadsheet and the tiles
    // above it are totalling the same gifts.
    await waitFor(() =>
      expect(exportLedger).toHaveBeenCalledWith(
        { from: "2026-08-01", to: todayIso(), type: undefined },
        "test-token"
      )
    );
  });

  it("does not show the ledger to kitchen staff", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    ingredientsOnly();
    render(<DonationsPage />);
    expect(screen.getByRole("form", { name: /record a donation/i })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /every gift received/i })).not.toBeInTheDocument();
  });
});
