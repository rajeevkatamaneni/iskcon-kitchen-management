import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ApiError, type MyDonation } from "@/lib/api";
import { dateWithYear, money } from "@/lib/format";

/**
 * My donations (T-179): the half a volunteer can see.
 *
 * <p>Which gifts are theirs, and that a stranger's receipt is refused, is `MyDonationsIT`'s business
 * against a real database. A screen cannot prove who owns a gift and must not try: it draws what the
 * server sent. What is asserted here is that it draws it faithfully — the amount or what was given,
 * the date, and a Download receipt button on exactly the gifts that have a receipt and no others.
 */
const { authRef, returnsRef, reloadMock, downloadMock } = vi.hoisted(() => ({
  downloadMock: vi.fn(),
  authRef: {
    current: { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  returnsRef: { current: { data: [] as unknown, error: null as unknown, loading: false } },
  reloadMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
// One query on this page, so one slot.
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ ...returnsRef.current, reload: reloadMock }),
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, downloadMyDonationReceipt: downloadMock } };
});

import MyDonationsPage from "@/app/my-donations/page";

const ONLINE: MyDonation = {
  id: "g-1",
  kind: "MONEY",
  receivedOn: "2026-09-10",
  amount: 5000,
  description: "Online donation",
  receiptNumber: "R-2026-0007",
};

const COUNTER: MyDonation = {
  id: "g-2",
  kind: "MONEY",
  receivedOn: "2026-09-01",
  amount: 1200,
  description: "Given at the temple",
  receiptNumber: null,
};

const GOODS: MyDonation = {
  id: "g-3",
  kind: "GOODS",
  receivedOn: "2026-08-15",
  amount: null,
  description: "Rice, 25 Kg",
  receiptNumber: null,
};

function rowFor(text: string): HTMLElement {
  return screen.getByText(text).closest("li") as HTMLElement;
}

describe("my donations", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } };
    returnsRef.current = { data: [ONLINE, COUNTER, GOODS], error: null, loading: false };
    downloadMock.mockReset().mockResolvedValue(new Blob(["%PDF"]));
    reloadMock.mockReset();
  });

  it("shows each gift with its date and its amount or what was given", () => {
    render(<MyDonationsPage />);

    expect(screen.getByRole("heading", { name: "My donations" })).toBeInTheDocument();

    const online = rowFor(money(5000, "INR"));
    expect(within(online).getByText("Online donation")).toBeInTheDocument();
    expect(within(online).getByText(new RegExp(dateWithYear("2026-09-10")))).toBeInTheDocument();
    expect(within(online).getByText(/Receipt R-2026-0007/)).toBeInTheDocument();

    const counter = rowFor(money(1200, "INR"));
    expect(within(counter).getByText("Given at the temple")).toBeInTheDocument();
    expect(within(counter).getByText(dateWithYear("2026-09-01"))).toBeInTheDocument();

    // Goods lead with what was given, and no amount is invented for them.
    const goods = rowFor("Rice, 25 Kg");
    expect(within(goods).getByText(dateWithYear("2026-08-15"))).toBeInTheDocument();
    expect(within(goods).queryByText(/₹/)).toBeNull();
  });

  it("offers Download receipt only on a gift that has a receipt", () => {
    render(<MyDonationsPage />);

    // Asserted per row, and the count across the page, so a button moved onto the wrong gift fails
    // as surely as a button missing altogether.
    expect(screen.getAllByRole("button", { name: "Download receipt" })).toHaveLength(1);
    expect(within(rowFor(money(5000, "INR"))).getByRole("button", { name: "Download receipt" })).toBeInTheDocument();
    expect(within(rowFor(money(1200, "INR"))).queryByRole("button")).toBeNull();
    expect(within(rowFor("Rice, 25 Kg")).queryByRole("button")).toBeNull();
  });

  it("downloads the receipt with the person's token and names the file for the receipt", async () => {
    const createObjectURL = vi.fn(() => "blob:receipt");
    const revokeObjectURL = vi.fn();
    Object.assign(URL, { createObjectURL, revokeObjectURL });
    const clicked: string[] = [];
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
      clicked.push(this.download);
    });

    render(<MyDonationsPage />);
    fireEvent.click(screen.getByRole("button", { name: "Download receipt" }));

    await waitFor(() => expect(downloadMock).toHaveBeenCalledWith("g-1", "test-token"));
    await waitFor(() => expect(clicked).toEqual(["receipt-R-2026-0007.pdf"]));
    click.mockRestore();
  });

  it("says plainly when a download is refused", async () => {
    downloadMock.mockReset().mockRejectedValue(
      new ApiError({
        code: "KMS-400030",
        message: "We couldn't find what you were looking for.",
        action: "It may have been removed.",
        fieldErrors: [],
      }),
    );

    render(<MyDonationsPage />);
    fireEvent.click(screen.getByRole("button", { name: "Download receipt" }));

    expect(await screen.findByText("We couldn't find what you were looking for.")).toBeInTheDocument();
  });

  it("has one plain sentence when there are no gifts", () => {
    returnsRef.current = { data: [], error: null, loading: false };
    render(<MyDonationsPage />);

    expect(screen.getByText("Your gifts to the temple will appear here.")).toBeInTheDocument();
    expect(screen.queryByRole("list")).toBeNull();
  });

  it("refuses somebody who is not a volunteer", () => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    render(<MyDonationsPage />);

    expect(screen.getByRole("heading", { name: "Not your page" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "My donations" })).toBeNull();
    expect(screen.queryByText("Online donation")).toBeNull();
  });
});
