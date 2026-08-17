import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { IngredientView, LedgerRow, LedgerSummary, WishlistItemView } from "@/lib/api";

const { authRef, returnsRef, reloadMock, recordMock, exportLedger } = vi.hoisted(() => ({
  exportLedger: vi.fn(),
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
// The page queries in a fixed order — ingredients, then (for an admin) the ledger and its summary.
// Each fetcher keeps the slot it was first seen in, rather than the stub counting calls: when only
// the ledger re-renders it makes two of the three calls, and a counter would hand it the wrong data.
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fetcher: unknown) => {
    if (!returnsRef.slots.has(fetcher)) {
      returnsRef.slots.set(fetcher, returnsRef.slots.size);
    }
    const list = returnsRef.current;
    const slot = Math.min(returnsRef.slots.get(fetcher) as number, list.length - 1);
    return { ...list[slot], reload: reloadMock };
  },
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, recordDonation: recordMock, exportLedger } };
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

const SUMMARY: LedgerSummary = {
  financialYearStart: "2026-04-01",
  monthToDateByCategory: { MANUAL: 5000 },
  financialYearToDateByCategory: { ONE_TIME: 1200, MANUAL: 7500 },
};

function ingredientsOnly() {
  returnsRef.current = [
    { data: [rice], error: null, loading: false },
    { data: [], error: null, loading: false }, // the wish list, empty
  ];
  returnsRef.slots.clear();
}

/**
 * An admin's four queries, in the order the page makes them: ingredients, the wish list, the ledger,
 * its summary. An admin always renders the ledger beneath the form, so every admin fixture needs all
 * four slots even when the test is only looking at the form.
 */
function asAdmin(wishlist: WishlistItemView[]) {
  returnsRef.current = [
    { data: [rice], error: null, loading: false },
    { data: wishlist, error: null, loading: false },
    { data: [CASH_ROW], error: null, loading: false },
    { data: SUMMARY, error: null, loading: false },
    // Recording a gift reloads the ledger, and its fetcher is rebuilt — a new identity, so a new
    // slot. The pair repeats here so the reload lands on ledger data rather than falling off the end.
    { data: [CASH_ROW], error: null, loading: false },
    { data: SUMMARY, error: null, loading: false },
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
    recordMock.mockReset().mockResolvedValue({ id: "d1" });
    exportLedger.mockReset().mockResolvedValue({
      blob: new Blob(["Date,Category\n"], { type: "text/csv" }),
      filename: "donations.csv",
    });
    URL.createObjectURL = vi.fn(() => "blob:donations");
    URL.revokeObjectURL = vi.fn();
  });

  it("shows an admin every gift, the FY summary, a Manual filter, and a CSV export", () => {
    render(<DonationsPage />);
    expect(screen.getByRole("heading", { name: /every gift received/i })).toBeInTheDocument();

    // The row a plain donations list could not show: category, amount, and mode all present.
    expect(screen.getByText("Volunteer One")).toBeInTheDocument();
    expect(screen.getByText("₹5000")).toBeInTheDocument();
    expect(screen.getByText("CASH")).toBeInTheDocument();

    expect(screen.getByText("₹1200")).toBeInTheDocument(); // FY one-time card
    expect(screen.getByRole("option", { name: "Manual" })).toBeInTheDocument();
  });

  it("exports with the token rather than linking — a link cannot carry one, and 401s", async () => {
    render(<DonationsPage />);
    // A button, not an anchor: clicking a bare href at the export URL returned an HTTP 401 page.
    expect(screen.queryByRole("link", { name: /export csv/i })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /export csv/i }));
    await waitFor(() => expect(exportLedger).toHaveBeenCalledWith({ type: undefined }, "test-token"));
  });

  it("does not show the ledger to kitchen staff", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    ingredientsOnly();
    render(<DonationsPage />);
    expect(screen.getByRole("form", { name: /record a donation/i })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /every gift received/i })).not.toBeInTheDocument();
  });
});
