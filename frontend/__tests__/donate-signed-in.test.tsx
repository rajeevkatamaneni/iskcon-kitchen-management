import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

const { giveOnce, giveTowardsItem, authRef } = vi.hoisted(() => ({
  giveOnce: vi.fn(async () => ({ donationId: "d1", orderId: "o1" })),
  giveTowardsItem: vi.fn(async () => ({ donationId: "d2", orderId: "o2" })),
  // Mutable so the refusal tests below can render the route as a role other than the volunteer
  // every other test in this file exercises. D-8 (2026-09-07): giving narrows to volunteers alone.
  authRef: {
    current: {
      appUser: { userId: "u1", fullName: "Radha Devi", tenantSlug: "radha-govinda", role: "VOLUNTEER" },
      status: "signed-in",
    },
  },
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      givingPage: async () => ({
        templeName: "Bengaluru Temple",
        is80gApproved: true,
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
    },
  };
});

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({
    ...authRef.current,
    getToken: async () => "token-abc",
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
 * asks for neither, and a gift is a gift — recurring giving left the product on 2026-09-10, so
 * there is no frequency to choose and nothing on the page that implies there ever was.
 */
describe("donating as a signed-in devotee", () => {
  beforeEach(() => {
    // Every test in this file signs in as a volunteer unless it says otherwise below.
    authRef.current = {
      appUser: { userId: "u1", fullName: "Radha Devi", tenantSlug: "radha-govinda", role: "VOLUNTEER" },
      status: "signed-in",
    };
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
    render(<DonatePage />);
    await waitFor(() => expect(screen.getByRole("tab", { name: "Donate money" })).toBeInTheDocument());

    // Inside the app this page is a panel, not a site. A second logo and a second temple name a few
    // centimetres from the first make one page look like two stuck together.
    expect(screen.queryByText(/Bengaluru Temple kitchen/)).not.toBeInTheDocument();
    expect(document.querySelector("header")).toBeNull();
    expect(document.querySelector('img[src*="iskcon-icon"]')).toBeNull();
  });

  it("asks for no name, no email and no consent tick", async () => {
    render(<DonatePage />);
    await waitFor(() => expect(screen.getByRole("tab", { name: "Donate money" })).toBeInTheDocument());

    expect(screen.queryByLabelText(/your name/i)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/^email/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/kept to process this gift/i)).not.toBeInTheDocument();
  });

  it("sends a one-time gift as the account, with no donor in the request", async () => {
    render(<DonatePage />);
    await waitFor(() => expect(screen.getByRole("button", { name: /^Give ₹/ })).toBeInTheDocument());

    fireEvent.click(screen.getByRole("button", { name: /^Give ₹/ }));
    await waitFor(() => expect(giveOnce).toHaveBeenCalled());
    expect(giveOnce).toHaveBeenCalledWith(
      1100,
      { wants80g: false, address: undefined, pan: undefined },
      "token-abc"
    );
  });

  // The screen had a "How often" fieldset with One time and Every month in it. With the second
  // answer withdrawn the fieldset had to go whole: a radio group of one is a control that cannot be
  // operated, and a legend asking how often over a single answer reads as a broken page rather than
  // a simplified one. Nothing replaces it — the amount is now the only question the form asks.
  it("asks no question about frequency, because there is no longer a second answer", async () => {
    render(<DonatePage />);
    await waitFor(() => expect(screen.getByRole("button", { name: /^Give ₹/ })).toBeInTheDocument());

    expect(screen.queryByText("How often")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("One time")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Every month")).not.toBeInTheDocument();
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
    // The button carried " a month" on the end while a mandate was possible.
    expect(screen.getByRole("button", { name: /^Give ₹/ }).textContent).not.toMatch(/month/);
  });

  // Deliberately the real module, not the mock above: what is being proved is that the client has
  // no way to reach the withdrawn endpoints at all. `objectContaining` cannot prove an absence —
  // a missing property and one explicitly set to `undefined` read identically through it — so the
  // keys themselves are what is inspected.
  it("keeps no wrapper for an endpoint the backend no longer serves", async () => {
    const actual = await vi.importActual<typeof import("@/lib/api")>("@/lib/api");
    const keys = Object.keys(actual.api);

    expect(keys).not.toContain("startRecurringPlan");
    expect(keys).not.toContain("myRecurringPlans");
    expect(keys).not.toContain("cancelRecurringPlan");
    // The one-time and wish-list wrappers beside them are untouched, so an empty `api` or a failed
    // import cannot pass this test by accident.
    expect(keys).toEqual(expect.arrayContaining(["giveOnce", "giveTowardsItem"]));
  });

  it("puts money towards equipment as the account rather than anonymously", async () => {
    render(<DonatePage />);
    await waitFor(() => expect(screen.getByRole("tab", { name: /equipment/i })).toBeInTheDocument());

    fireEvent.click(screen.getByRole("tab", { name: /equipment/i }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Give ₹500" })).toBeInTheDocument());

    fireEvent.click(screen.getByRole("button", { name: "Give ₹500" }));
    await waitFor(() => expect(giveTowardsItem).toHaveBeenCalledWith("item-1", 500, undefined, "token-abc"));
  });

  it("still asks for the two things an 80G receipt needs and the account cannot supply", async () => {
    render(<DonatePage />);
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
    render(<DonatePage />);
    await waitFor(() => expect(screen.getByRole("tab", { name: /equipment/i })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("tab", { name: /equipment/i }));

    await waitFor(() => expect(screen.getByText(/Commercial wet grinder/)).toBeInTheDocument());
    expect(screen.queryByText(/no half a grinder/i)).not.toBeInTheDocument();
  });

  // D-8 (Rajeev, 2026-09-07): "Admins shouldn't be asked for money by their own admin app… Same
  // rule applies for Temple staff too. They are already serving which is donation enough." The
  // page guard carries the same narrowing as the `/donate` row in nav.ts, so a cook or an admin who
  // types the URL directly is refused rather than shown a screen the menu simply didn't offer them.
  it("refuses a cook — already serving is the donation, so the page is not theirs", async () => {
    authRef.current = {
      appUser: { userId: "u2", fullName: "Gopal Das", tenantSlug: "radha-govinda", role: "KITCHEN_STAFF" },
      status: "signed-in",
    };
    const { default: DonateRoute } = await import("@/app/donate/page");
    render(<DonateRoute />);

    expect(await screen.findByText("Not your page")).toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "Donate money" })).not.toBeInTheDocument();
  });

  it("refuses the temple admin too — an admin app should not ask its own admin for money", async () => {
    authRef.current = {
      appUser: { userId: "u3", fullName: "Radharani Devi", tenantSlug: "radha-govinda", role: "TEMPLE_ADMIN" },
      status: "signed-in",
    };
    const { default: DonateRoute } = await import("@/app/donate/page");
    render(<DonateRoute />);

    expect(await screen.findByText("Not your page")).toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "Donate money" })).not.toBeInTheDocument();
  });
});
