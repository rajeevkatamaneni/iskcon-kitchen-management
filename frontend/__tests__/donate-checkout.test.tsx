import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

/**
 * The step that takes the money. The page used to create a PENDING donation, throw away the order it
 * got back, and thank the donor — so a devotee who pressed Give was never charged. What matters here
 * is that the window is opened with what the server created, and that nobody is thanked for a
 * payment that did not happen.
 */

const { giveOnce, giveTowardsItem, donate, openCheckout } = vi.hoisted(() => ({
  giveOnce: vi.fn(),
  giveTowardsItem: vi.fn(),
  donate: vi.fn(),
  openCheckout: vi.fn(),
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
      givingPage: async () => ({
        templeName: "Sri Sri Radha Govinda Temple",
        is80gApproved: false,
        presets: [500, 1100],
        platesToday: 1240,
        costPerPlateInr: 32,
        spendShares: [],
      }),
      givingWishlist: async () => [
        {
          id: "item-1",
          title: "Commercial wet grinder",
          description: null,
          imageRef: null,
          priceInr: 42000,
          category: "EQUIPMENT",
          quantityWanted: 1,
          paidInr: 0,
          sortOrder: 1,
          status: "ACTIVE",
          note: null,
          createdAt: "2026-08-15T00:00:00Z",
        },
      ],
      giveOnce,
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
  render(<DonatePage />);
  await waitFor(() => expect(screen.getByRole("button", { name: /^Give ₹/ })).toBeInTheDocument());
  fireEvent.click(screen.getByRole("button", { name: /^Give ₹/ }));
}

describe("taking the money", () => {
  beforeEach(() => {
    giveOnce.mockReset().mockResolvedValue(CHECKOUT);
    giveTowardsItem.mockReset().mockResolvedValue({ ...CHECKOUT, donationId: "d2", amountInr: 500 });
    donate.mockReset().mockResolvedValue(CHECKOUT);
    openCheckout.mockReset().mockResolvedValue("paid");
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

  // Until 2026-09-10 one of the two gifts this page could start was a standing mandate, which had
  // to be authorised on the provider's own site — so pressing Give could navigate away from the app
  // instead of opening the window. Recurring giving is gone, and with it that second road: every
  // gift now ends in openCheckout, and nothing here should be sending anybody anywhere.
  it("takes every gift in the window over our own page, and navigates nowhere", async () => {
    const assign = vi.fn();
    Object.defineProperty(window, "location", {
      configurable: true,
      value: { ...window.location, assign },
    });

    await giveOnceOfEleven();

    await waitFor(() => expect(openCheckout).toHaveBeenCalledWith(CHECKOUT, expect.anything()));
    expect(assign).not.toHaveBeenCalled();
  });

  it("opens the window for a piece of equipment too", async () => {
    render(<DonatePage />);
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
    render(<DonatePage />);
    await waitFor(() => expect(screen.getByRole("tab", { name: /equipment/i })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("tab", { name: /equipment/i }));

    await waitFor(() => expect(screen.getByRole("button", { name: "Give ₹500" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Give ₹500" }));

    expect(await screen.findByText(/no payment was taken/i)).toBeInTheDocument();
    expect(screen.queryByText(/the kitchen has been told/i)).not.toBeInTheDocument();
  });
});

/*
 * T-166, slice F of the blank-required-fields wave. The money form is now a Form, and nothing about
 * the checkout may change with it. Until T-172 "Or another amount" was a plain text box with no rule,
 * and the Give button was disabled while the amount was not above nothing — the page's only guard.
 * T-172 put the rule on the box instead (a number of whole rupees, at least 1, still not required)
 * and made Give pressable, so a bad amount is refused by name beside the box.
 */
describe("the amount, under Form (T-166)", () => {
  beforeEach(() => {
    giveOnce.mockReset().mockResolvedValue(CHECKOUT);
    openCheckout.mockReset().mockResolvedValue("paid");
  });

  it("gives the chosen preset when the other amount is left blank, and says nothing about the box", async () => {
    render(<DonatePage />);
    await waitFor(() => expect(screen.getByRole("button", { name: /^Give ₹/ })).toBeInTheDocument());
    // A blank number box reads as null, not "" (T-172 made it a number box).
    expect(screen.getByLabelText(/or another amount/i)).toHaveValue(null);

    fireEvent.click(screen.getByRole("button", { name: /^Give ₹/ }));

    await waitFor(() => expect(giveOnce).toHaveBeenCalledTimes(1));
    expect(giveOnce.mock.calls[0][0]).toBe(1100);
    await waitFor(() => expect(openCheckout).toHaveBeenCalledWith(CHECKOUT, expect.anything()));
    expect(screen.queryByText(/is required|must be|can be at most/i)).not.toBeInTheDocument();
  });

  /**
   * T-172. Give is pressable whatever the amount says, and a 0 or a negative amount is refused by name
   * beside the box, with nothing sent. Then a proper amount, pressed once, is given once.
   *
   * "abc" is no longer tried. A number box holds no letters: a browser that lets them be typed
   * reports the box as not a number, which `Form` refuses as "must be a number" (a check `Form`'s own
   * tests cover, since jsdom cannot produce it), and jsdom simply clears the box to blank, which is
   * the preset — the ordinary answer the test above covers.
   */
  it("refuses an amount of 0 or below by name when Give is pressed, and gives a proper one once (T-172)", async () => {
    render(<DonatePage />);
    await waitFor(() => expect(screen.getByRole("button", { name: /^Give/ })).toBeInTheDocument());
    const other = screen.getByLabelText(/or another amount/i);
    expect(other).not.toBeRequired();
    expect(other).toHaveAttribute("type", "number");
    expect(other).toHaveAttribute("min", "1");

    for (const value of ["0", "-5"]) {
      fireEvent.change(other, { target: { value } });
      const give = screen.getByRole("button", { name: /^Give/ });
      expect(give).toBeEnabled();
      fireEvent.click(give);
      expect(screen.getByText("Or another amount must be at least 1")).toBeInTheDocument();
      expect(other).toHaveAttribute("aria-invalid", "true");
    }

    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(giveOnce).not.toHaveBeenCalled();

    fireEvent.change(other, { target: { value: "250" } });
    expect(screen.queryByText("Or another amount must be at least 1")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /^Give/ }));
    await waitFor(() => expect(giveOnce).toHaveBeenCalledTimes(1));
    expect(giveOnce.mock.calls[0][0]).toBe(250);
  });

  /**
   * T-204. The box shows no up/down spinner arrows. jsdom draws nothing and applies no Tailwind, so
   * what can be proved here is that the box carries the three classes that remove them: one per
   * WebKit pseudo-element, and `appearance: textfield` for Firefox. `next build` is what proves the
   * classes compile to real CSS. The point of asserting type, min, step and inputMode alongside is
   * that the easy way to lose the arrows is to stop being a number box, and that would silently undo
   * T-172's "at least 1".
   */
  it("hides the spinner arrows without giving up the number box (T-204)", async () => {
    render(<DonatePage />);
    await waitFor(() => expect(screen.getByRole("button", { name: /^Give/ })).toBeInTheDocument());
    const other = screen.getByLabelText(/or another amount/i);

    expect(other).toHaveClass(
      "[&::-webkit-inner-spin-button]:appearance-none",
      "[&::-webkit-outer-spin-button]:appearance-none",
      "[appearance:textfield]",
    );

    expect(other).toHaveAttribute("type", "number");
    expect(other).toHaveAttribute("min", "1");
    expect(other).toHaveAttribute("step", "1");
    expect(other).toHaveAttribute("inputMode", "numeric");
  });
});
