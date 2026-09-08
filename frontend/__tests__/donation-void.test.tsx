import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ApiError, type LedgerRow, type PeriodSummary } from "@/lib/api";
import { todayIso } from "@/lib/format";

/**
 * Striking a gift from the ledger (T-012).
 *
 * <p>What is asserted here is the half a person can see: that the control is offered on a gift that
 * stands and withheld on one already struck, that nothing is sent until a reason has been written,
 * that a struck gift is still on the list and marked, and that both queries are reloaded afterwards
 * — the rows for the mark, the tiles for the money. Whether the stock actually comes back out is
 * `DonationVoidIT`'s business, against a real database.
 */
const { authRef, returnsRef, reloadMock, voidMock, exportLedger, periodSummaryMock } = vi.hoisted(
  () => ({
    exportLedger: vi.fn(),
    periodSummaryMock: vi.fn(),
    voidMock: vi.fn(),
    authRef: {
      current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
        status: string;
        appUser: { role: string; userId: string } | null;
      },
    },
    returnsRef: {
      current: [] as Array<{ data: unknown; error: null; loading: boolean }>,
      slots: new Map<unknown, number>(),
    },
    reloadMock: vi.fn(),
  })
);

const { pushMock, replaceMock, paramsRef } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  replaceMock: vi.fn(),
  paramsRef: { current: new URLSearchParams() },
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
  useSearchParams: () => paramsRef.current,
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
// The same fixture the ledger's own tests use: each fetcher keeps the slot it was first seen in,
// because when only the rows re-render the screen makes fewer calls than there are slots and a
// counter would hand it the wrong data.
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fetcher: unknown) => {
    if (!returnsRef.slots.has(fetcher)) {
      returnsRef.slots.set(fetcher, returnsRef.slots.size);
      const result = (fetcher as (token?: string) => unknown)("test-token");
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
      voidDonation: voidMock,
      exportLedger,
      donationPeriodSummary: periodSummaryMock,
      donationLedger: vi.fn(),
    },
  };
});

import DonationsPage from "@/app/donations/page";

const FOOD: LedgerRow = {
  id: "d-food", donatedOn: "2026-08-16", category: "IN_KIND", donorDisplay: "Govind Das",
  amountInr: 2500, currency: "INR", paymentMode: null, providerRef: null, status: "COMPLETED",
  linkedTo: "In-kind intake", voided: false, voidReason: null,
};

/** The same gift after it was struck. It is still here, which is the point of marking rather than deleting. */
const STRUCK: LedgerRow = {
  ...FOOD, id: "d-struck", donorDisplay: "Radha Devi",
  voided: true, voidReason: "Entered twice at the gate.",
};

const CASH: LedgerRow = {
  id: "d-cash", donatedOn: "2026-08-16", category: "MANUAL", donorDisplay: "Volunteer One",
  amountInr: 5000, currency: "INR", paymentMode: "CASH", providerRef: null, status: "COMPLETED",
  linkedTo: null, voided: false, voidReason: null,
};

const SUMMARY: PeriodSummary = {
  window: {
    period: "MONTH", financialYear: null,
    from: "2026-08-01", to: todayIso(), previousFrom: "2025-08-01", previousTo: "2025-08-19",
  },
  hasPriorYear: true,
  byCategory: { IN_KIND: { total: 2500, previousTotal: 0, changePercent: null } },
  financialYearsWithGifts: [2026],
};

function withRows(rows: LedgerRow[]) {
  returnsRef.current = [
    { data: SUMMARY, error: null, loading: false },
    { data: rows, error: null, loading: false },
    { data: SUMMARY, error: null, loading: false },
    { data: rows, error: null, loading: false },
  ];
  returnsRef.slots.clear();
}

/** The row for one gift, found by the donor's name — the only value unique to it on screen. */
function rowFor(donor: string): HTMLElement {
  return screen.getByText(donor).closest("tr") as HTMLElement;
}

describe("striking a gift that was recorded wrongly", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    withRows([FOOD, CASH]);
    reloadMock.mockReset();
    voidMock.mockReset().mockResolvedValue(undefined);
    periodSummaryMock.mockReset();
    exportLedger.mockReset();
    paramsRef.current = new URLSearchParams();
    pushMock.mockReset();
    replaceMock.mockReset();
  });

  it("sends the id and the reason, and reloads both the rows and the tiles", async () => {
    render(<DonationsPage />);
    fireEvent.click(within(rowFor("Govind Das")).getByRole("button", { name: "Void" }));

    const dialog = screen.getByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/why it is being voided/i), {
      target: { value: "Entered twice at the gate." },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: /void this gift/i }));

    await waitFor(() =>
      expect(voidMock).toHaveBeenCalledWith("d-food", "Entered twice at the gate.", "test-token")
    );
    // Both halves of the screen move: the row gains its mark, the tiles lose the money. Reloading
    // only the rows would leave an 80G figure on screen the server has stopped reporting.
    await waitFor(() => expect(reloadMock).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  /**
   * The stock half is the one nobody would think to check, and it is only true of a gift of goods.
   * The equipment sentence is here because a void deliberately does not undo an asset registration,
   * and the place to say so is before the button is pressed rather than in a release note.
   */
  it("says what happens to the goods before the button is pressed, and only for goods", () => {
    render(<DonationsPage />);

    fireEvent.click(within(rowFor("Govind Das")).getByRole("button", { name: "Void" }));
    const goods = screen.getByRole("dialog");
    expect(within(goods).getByText(/goes back out of stock/i)).toBeInTheDocument();
    expect(within(goods).getByText(/stays in the register/i)).toBeInTheDocument();
    // And it names the gift being struck rather than "this item", because two rows on this screen
    // can look alike and the wrong one is exactly the mistake being corrected.
    expect(within(goods).getByText(/₹2,500 from Govind Das on 16 Aug 2026/)).toBeInTheDocument();
    fireEvent.click(within(goods).getByRole("button", { name: /cancel/i }));

    fireEvent.click(within(rowFor("Volunteer One")).getByRole("button", { name: "Void" }));
    const cash = screen.getByRole("dialog");
    expect(within(cash).queryByText(/goes back out of stock/i)).not.toBeInTheDocument();
  });

  it("sends nothing until a reason has been written, and a space is not one", () => {
    render(<DonationsPage />);
    fireEvent.click(within(rowFor("Govind Das")).getByRole("button", { name: "Void" }));

    const dialog = screen.getByRole("dialog");
    const commit = within(dialog).getByRole("button", { name: /void this gift/i });
    const box = within(dialog).getByLabelText(/why it is being voided/i);

    expect(commit).toBeDisabled();
    // A space bar is not a reason. The server refuses a blank one and the column's CHECK refuses one
    // behind that; this is only the earliest of the three, and the one that does not make somebody
    // press a button to be told.
    fireEvent.change(box, { target: { value: "   " } });
    expect(commit).toBeDisabled();

    fireEvent.change(box, { target: { value: "Entered twice." } });
    expect(commit).toBeEnabled();
    expect(voidMock).not.toHaveBeenCalled();
  });

  /**
   * The row stays. Marking rather than deleting is the whole design: an accountant reconciling
   * against a receipt book has to be able to find the gift that was struck, and read why.
   */
  it("keeps a struck gift on the list, marked, with the reason it was struck", () => {
    withRows([STRUCK, CASH]);
    render(<DonationsPage />);

    const row = rowFor("Radha Devi");
    expect(within(row).getByText("Voided")).toBeInTheDocument();
    expect(within(row).getByText("Entered twice at the gate.")).toBeInTheDocument();
    // Struck through, because the money column is what somebody adds up down the page.
    expect(within(row).getByText("₹2,500").className).toContain("line-through");
  });

  /**
   * Withheld rather than offered and refused. The server answers a second void with KMS-400134, and
   * the row is holding that answer already — offering the control would take a reason off somebody
   * and throw it away. Same call as the Correct control on the inventory ledger.
   */
  it("offers no Void on a gift it can already see is struck", () => {
    withRows([STRUCK, CASH]);
    render(<DonationsPage />);

    expect(within(rowFor("Radha Devi")).queryByRole("button", { name: "Void" })).not.toBeInTheDocument();
    // And it is withheld rather than disabled: a control that never becomes pressable only asks the
    // reader to work out what would make it pressable.
    expect(within(rowFor("Volunteer One")).getByRole("button", { name: "Void" })).toBeEnabled();
  });

  it("puts the server's refusal on screen rather than a blank failure", async () => {
    // The real envelope, because ErrorNotice renders an ApiError and toApiError replaces anything
    // else with its own fallback — a plain object here would have this test asserting the fallback.
    voidMock.mockRejectedValue(
      new ApiError(
        {
          code: "KMS-400134",
          message: "This donation has already been voided.",
          action: "Record it again if it was actually received.",
          fieldErrors: [],
        },
        409
      )
    );
    render(<DonationsPage />);

    fireEvent.click(within(rowFor("Govind Das")).getByRole("button", { name: "Void" }));
    const dialog = screen.getByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/why it is being voided/i), {
      target: { value: "Entered twice." },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: /void this gift/i }));

    await waitFor(() =>
      expect(screen.getByText("This donation has already been voided.")).toBeInTheDocument()
    );
    expect(reloadMock).not.toHaveBeenCalled();
  });
});
