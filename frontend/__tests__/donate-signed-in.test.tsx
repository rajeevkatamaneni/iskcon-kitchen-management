import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

const { giveOnce, startRecurringPlan, giveTowardsItem } = vi.hoisted(() => ({
  giveOnce: vi.fn(async () => ({ donationId: "d1", orderId: "o1" })),
  startRecurringPlan: vi.fn(async () => ({ id: "p1", shortUrl: "https://rzp.io/i/mandate123" })),
  giveTowardsItem: vi.fn(async () => ({ donationId: "d2", orderId: "o2" })),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      donationPage: async () => ({
        templeName: "Bengaluru Temple",
        is80gApproved: true,
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
    },
  };
});

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({
    appUser: { userId: "u1", fullName: "Radha Devi", tenantSlug: "radha-govinda", role: "VOLUNTEER" },
    getToken: async () => "token-abc",
    status: "signed-in",
    signOut: vi.fn(),
    switchTemple: vi.fn(),
  }),
}));

// The route mounts the real guard and the real menu, both of which reach for the app router.
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/donate",
}));

import { DonatePage } from "@/components/give/DonatePage";

/**
 * Giving from inside the app. The temple already holds this devotee's name and email, so the page
 * asks for neither — and "every month" has to be a mandate rather than a gift that quietly happens
 * once.
 */
describe("donating as a signed-in devotee", () => {
  beforeEach(() => {
    // A monthly gift leaves for the provider's mandate page; jsdom will not navigate for us.
    Object.defineProperty(window, "location", {
      configurable: true,
      value: { ...window.location, assign: vi.fn() },
    });
  });

  it("the /donate route itself shows exactly one lotus, and it is the menu's", async () => {
    // The component test below proves the banner is suppressed when it is told to suppress it.
    // This proves the route actually tells it to — which is the half that can regress, because a
    // call site is where the prop gets forgotten. Reported as "the logo is back" on 2026-08-20.
    const { default: DonateRoute } = await import("@/app/donate/page");
    render(<DonateRoute />);
    await waitFor(() => expect(screen.getByRole("tab", { name: "Donate money" })).toBeInTheDocument());

    expect(document.querySelectorAll('img[src*="iskcon-icon"]')).toHaveLength(1);
    expect(document.querySelector('nav[aria-label="Main"] img[src*="iskcon-icon"]')).not.toBeNull();
  });

  it("carries no banner of its own — the menu beside it already names the temple", async () => {
    render(<DonatePage slug="radha-govinda" standalone={false} />);
    await waitFor(() => expect(screen.getByRole("tab", { name: "Donate money" })).toBeInTheDocument());

    // Inside the app this page is a panel, not a site. A second logo and a second temple name a few
    // centimetres from the first make one page look like two stuck together.
    expect(screen.queryByText(/Bengaluru Temple kitchen/)).not.toBeInTheDocument();
    expect(document.querySelector("header")).toBeNull();
    expect(document.querySelector('img[src*="iskcon-icon"]')).toBeNull();
  });

  it("asks for no name, no email and no consent tick", async () => {
    render(<DonatePage slug="radha-govinda" standalone />);
    await waitFor(() => expect(screen.getByRole("tab", { name: "Donate money" })).toBeInTheDocument());

    expect(screen.queryByLabelText(/your name/i)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/^email/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/kept to process this gift/i)).not.toBeInTheDocument();
  });

  it("sends a one-time gift as the account, with no donor in the request", async () => {
    render(<DonatePage slug="radha-govinda" standalone />);
    await waitFor(() => expect(screen.getByRole("button", { name: /^Give ₹/ })).toBeInTheDocument());

    fireEvent.click(screen.getByRole("button", { name: /^Give ₹/ }));
    await waitFor(() => expect(giveOnce).toHaveBeenCalled());
    expect(giveOnce).toHaveBeenCalledWith(
      1100,
      { wants80g: false, address: undefined, pan: undefined },
      "token-abc"
    );
    expect(startRecurringPlan).not.toHaveBeenCalled();
  });

  it("sets up a real plan when the gift is every month", async () => {
    render(<DonatePage slug="radha-govinda" standalone />);
    await waitFor(() => expect(screen.getByLabelText("Every month")).toBeInTheDocument());

    fireEvent.click(screen.getByLabelText("Every month"));
    fireEvent.click(screen.getByRole("button", { name: /a month$/ }));

    await waitFor(() =>
      expect(startRecurringPlan).toHaveBeenCalledWith(
        1100,
        { wants80g: false, address: undefined, pan: undefined },
        "token-abc"
      )
    );
  });

  it("puts money towards equipment as the account rather than anonymously", async () => {
    render(<DonatePage slug="radha-govinda" standalone />);
    await waitFor(() => expect(screen.getByRole("tab", { name: /equipment/i })).toBeInTheDocument());

    fireEvent.click(screen.getByRole("tab", { name: /equipment/i }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Give ₹500" })).toBeInTheDocument());

    fireEvent.click(screen.getByRole("button", { name: "Give ₹500" }));
    await waitFor(() => expect(giveTowardsItem).toHaveBeenCalledWith("item-1", 500, undefined, "token-abc"));
  });

  it("still asks for the two things an 80G receipt needs and the account cannot supply", async () => {
    render(<DonatePage slug="radha-govinda" standalone />);
    await waitFor(() =>
      expect(screen.getByLabelText(/80G receipt/i)).toBeInTheDocument()
    );

    fireEvent.click(screen.getByLabelText(/80G receipt/i));
    fireEvent.change(screen.getByLabelText("Address"), { target: { value: "12 Temple Road" } });
    fireEvent.change(screen.getByLabelText("PAN"), { target: { value: "ABCDE1234F" } });
    fireEvent.click(screen.getByRole("button", { name: /^Give ₹/ }));

    await waitFor(() =>
      expect(giveOnce).toHaveBeenCalledWith(
        1100,
        { wants80g: true, address: "12 Temple Road", pan: "ABCDE1234F" },
        "token-abc"
      )
    );
  });

  it("does not tell the kitchen it is bought whole", async () => {
    render(<DonatePage slug="radha-govinda" standalone />);
    await waitFor(() => expect(screen.getByRole("tab", { name: /equipment/i })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("tab", { name: /equipment/i }));

    await waitFor(() => expect(screen.getByText(/Commercial wet grinder/)).toBeInTheDocument());
    expect(screen.queryByText(/no half a grinder/i)).not.toBeInTheDocument();
  });
});
