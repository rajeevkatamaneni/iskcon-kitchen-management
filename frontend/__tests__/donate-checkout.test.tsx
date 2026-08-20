import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

/**
 * The step that takes the money. The page used to create a PENDING donation, throw away the order it
 * got back, and thank the donor — so a devotee who pressed Give was never charged. What matters here
 * is that the window is opened with what the server created, and that nobody is thanked for a
 * payment that did not happen.
 */

const { giveOnce, startRecurringPlan, giveTowardsItem, donate, openCheckout, assign } = vi.hoisted(() => ({
  giveOnce: vi.fn(),
  startRecurringPlan: vi.fn(),
  giveTowardsItem: vi.fn(),
  donate: vi.fn(),
  openCheckout: vi.fn(),
  assign: vi.fn(),
}));

const CHECKOUT = {
  donationId: "d1",
  orderId: "order_ABC123",
  publicKey: "rzp_test_key",
  amountInr: 1100,
  currency: "INR",
  provider: "razorpay",
};

vi.mock("@/lib/checkout", () => ({ openCheckout }));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      donationPage: async () => ({
        templeName: "Sri Sri Radha Govinda Temple",
        is80gApproved: false,
        presets: [500, 1100],
        platesToday: 1240,
        costPerPlateInr: 32,
        spendShares: [],
      }),
      publicWishlist: async () => [
        {
          id: "item-1",
          title: "Commercial wet grinder",
          description: null,
          imageRef: null,
          priceInr: 42000,
          category: "EQUIPMENT",
          quantityWanted: 1,
          sponsoredQuantity: 0,
          paidInr: 0,
          sortOrder: 1,
          status: "ACTIVE",
          note: null,
          createdAt: "2026-08-15T00:00:00Z",
        },
      ],
      giveOnce,
      startRecurringPlan,
      giveTowardsItem,
      donate,
    },
  };
});

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({
    appUser: { userId: "u1", fullName: "Radha Devi", tenantSlug: "radha-govinda", role: "VOLUNTEER" },
    getToken: async () => "token-abc",
  }),
}));

import { DonatePage } from "@/components/give/DonatePage";

async function giveOnceOfEleven() {
  render(<DonatePage slug="radha-govinda" standalone />);
  await waitFor(() => expect(screen.getByRole("button", { name: /^Give ₹/ })).toBeInTheDocument());
  fireEvent.click(screen.getByRole("button", { name: /^Give ₹/ }));
}

describe("taking the money", () => {
  beforeEach(() => {
    giveOnce.mockReset().mockResolvedValue(CHECKOUT);
    giveTowardsItem.mockReset().mockResolvedValue({ ...CHECKOUT, donationId: "d2", amountInr: 500 });
    donate.mockReset().mockResolvedValue(CHECKOUT);
    startRecurringPlan.mockReset().mockResolvedValue({ id: "p1", shortUrl: null });
    openCheckout.mockReset().mockResolvedValue("paid");
    assign.mockReset();
    Object.defineProperty(window, "location", {
      configurable: true,
      value: { ...window.location, assign },
    });
  });

  it("opens the payment window with the order the server created", async () => {
    await giveOnceOfEleven();

    await waitFor(() => expect(openCheckout).toHaveBeenCalled());
    expect(openCheckout).toHaveBeenCalledWith(
      CHECKOUT,
      expect.objectContaining({ templeName: "Sri Sri Radha Govinda Temple", name: "Radha Devi" })
    );
  });

  it("thanks the donor once the payment has gone through", async () => {
    await giveOnceOfEleven();
    expect(await screen.findByText(/thank you/i)).toBeInTheDocument();
    expect(screen.getByText(/went through/i)).toBeInTheDocument();
  });

  it("thanks nobody when the donor closed the window", async () => {
    openCheckout.mockResolvedValue("dismissed");
    await giveOnceOfEleven();

    expect(await screen.findByText(/no payment was taken/i)).toBeInTheDocument();
    expect(screen.queryByText(/thank you/i)).not.toBeInTheDocument();
    // Still on the form, so the devotee can simply press Give again.
    expect(screen.getByRole("button", { name: /^Give ₹/ })).toBeInTheDocument();
  });

  it("says so plainly when the temple cannot take payments online yet", async () => {
    openCheckout.mockResolvedValue("unavailable");
    await giveOnceOfEleven();

    expect(await screen.findByText(/cannot take online payments/i)).toBeInTheDocument();
    expect(screen.queryByText(/thank you/i)).not.toBeInTheDocument();
  });

  it("sends a monthly gift to the provider's own mandate page, not a window over ours", async () => {
    startRecurringPlan.mockResolvedValue({ id: "p1", shortUrl: "https://rzp.io/i/mandate123" });
    render(<DonatePage slug="radha-govinda" standalone />);
    await waitFor(() => expect(screen.getByLabelText("Every month")).toBeInTheDocument());

    fireEvent.click(screen.getByLabelText("Every month"));
    fireEvent.click(screen.getByRole("button", { name: /a month$/ }));

    await waitFor(() => expect(assign).toHaveBeenCalledWith("https://rzp.io/i/mandate123"));
    expect(openCheckout).not.toHaveBeenCalled();
  });

  it("opens the window for a piece of equipment too", async () => {
    render(<DonatePage slug="radha-govinda" standalone />);
    await waitFor(() => expect(screen.getByRole("tab", { name: /equipment/i })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("tab", { name: /equipment/i }));

    await waitFor(() => expect(screen.getByRole("button", { name: "Give ₹500" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Give ₹500" }));

    await waitFor(() =>
      expect(openCheckout).toHaveBeenCalledWith(
        expect.objectContaining({ donationId: "d2" }),
        expect.objectContaining({ description: "Commercial wet grinder" })
      )
    );
    expect(await screen.findByText(/the kitchen has been told/i)).toBeInTheDocument();
  });

  it("does not tell the kitchen about a gift the donor abandoned", async () => {
    openCheckout.mockResolvedValue("dismissed");
    render(<DonatePage slug="radha-govinda" standalone />);
    await waitFor(() => expect(screen.getByRole("tab", { name: /equipment/i })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("tab", { name: /equipment/i }));

    await waitFor(() => expect(screen.getByRole("button", { name: "Give ₹500" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Give ₹500" }));

    expect(await screen.findByText(/no payment was taken/i)).toBeInTheDocument();
    expect(screen.queryByText(/the kitchen has been told/i)).not.toBeInTheDocument();
  });
});
