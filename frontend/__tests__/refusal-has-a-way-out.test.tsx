import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

/**
 * A refused page still has a way out of it (T-035).
 *
 * <p>The companion to T-002. That task stopped screens *offering* what the server is right to
 * refuse; this one is about the refusal itself. Opening `/donate` as a cook rendered "Not your
 * page" on a bare white page with no menu and no link anywhere, so the browser's back button was
 * the only exit — Rajeev hit it on four surfaces on staging (2026-09-07).
 *
 * <p>It is fixed in `RequireRole` rather than in the pages, and the tests below are what says that
 * was the right place: the same guard is proved through a route that draws its own sidebar (one of
 * 54) and through a `FocusScreen` route that gets one from the component (one of 27), for both the
 * role it refuses and the role it admits. That second pair is the real risk of moving chrome into
 * the guard — a page that already draws a menu ending up with two.
 *
 * <p>One correction to the brief this was built from, recorded because the next reader will meet
 * the same claim: it said 28 guarded routes "render no sidebar at all". They render one — the grep
 * behind that number looked for `<Sidebar>` in the page file and `FocusScreen` draws it instead
 * (rule 2 of the eight it enforces: *the sidebar stays*). Only the branch a refused reader lands on
 * was ever bare, which is exactly what is being fixed here, so the shape of the fix is untouched.
 *
 * <p>Everything here renders a real route. Calling the refusal branch directly would assert that a
 * component we just wrote renders what we just wrote; it would not say whether a person who types
 * `/donate` sees it.
 */

const { authRef, givingPage, givingWishlist } = vi.hoisted(() => ({
  // One object, replaced only when the role changes: `useAuthedQuery` lists the auth object's
  // members in its effect dependencies, so a mock that builds a fresh closure per render re-fetches
  // for ever and every assertion times out on a screen that never settles.
  authRef: {
    current: {
      status: "signed-in",
      appUser: {
        userId: "u1",
        fullName: "Gopal Das",
        role: "KITCHEN_STAFF",
        tenantName: "Bengaluru Temple",
        tenantSlug: "bengaluru",
        temples: [],
      },
      getToken: async () => "token",
      refresh: () => {},
      signOut: vi.fn(),
      switchTemple: vi.fn(),
    } as Record<string, unknown>,
  },
  givingPage: vi.fn(),
  givingWishlist: vi.fn(),
}));

vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/donate",
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, givingPage, givingWishlist } };
});

// One of the 54 routes that render `<Sidebar>` inside the guard, and the one Rajeev reported.
import DonateRoute from "@/app/donate/page";
// A `FocusScreen` form: the menu it shows comes from the component, not from the page file, and
// like every other route it sits below the guard — so a refused reader had nothing but a sentence.
import NewVendorPage from "@/app/vendors/new/page";

function signedInAs(role: string, name = "Gopal Das") {
  authRef.current = {
    ...authRef.current,
    status: "signed-in",
    appUser: {
      userId: "u1",
      fullName: name,
      role,
      tenantName: "Bengaluru Temple",
      tenantSlug: "bengaluru",
      temples: [],
    },
  };
}

/** Every copy of the application's menu on the screen. Its landmark name is `Main`. */
function menus() {
  return screen.queryAllByRole("navigation", { name: "Main" });
}

beforeEach(() => {
  givingPage.mockReset().mockResolvedValue({
    templeName: "Bengaluru Temple",
    is80gApproved: true,
    presets: [500, 1100],
    platesToday: 1240,
    costPerPlateInr: 32,
    spendShares: [],
  });
  givingWishlist.mockReset().mockResolvedValue([]);
  signedInAs("KITCHEN_STAFF");
});

describe("a reader the guard refuses is not stranded", () => {
  it("shows the refusal inside the app's own chrome, with a link home, on /donate as a cook", async () => {
    render(<DonateRoute />);

    expect(await screen.findByRole("heading", { name: "Not your page" })).toBeInTheDocument();
    // The two halves Rajeev asked for. The menu, so the rest of the app is one press away…
    expect(menus()).toHaveLength(1);
    // …and an explicit door, because on a narrow viewport the menu is not necessarily in front of
    // the reader, and somebody just told they are in the wrong place should not have to find it.
    const out = screen.getByRole("link", { name: /go to today/i });
    expect(out).toHaveAttribute("href", "/today");
    // Still refused, and still without asking the server for the page behind it.
    expect(screen.queryByRole("tab", { name: "Donate money" })).not.toBeInTheDocument();
    expect(givingPage).not.toHaveBeenCalled();
  });

  it("gives a FocusScreen route the same way out, though the page file mentions no chrome", async () => {
    // /vendors/new admits admin, manager and cook. A volunteer typing the URL got a sentence on
    // white and nothing else: the page's own menu comes from FocusScreen, below the guard, so the
    // refused reader never reached it.
    signedInAs("VOLUNTEER", "Radha Devi");
    render(<NewVendorPage />);

    expect(await screen.findByRole("heading", { name: "Not your page" })).toBeInTheDocument();
    expect(menus()).toHaveLength(1);
    // The form itself is gone, header and all — the guard replaced it rather than wrapped it.
    expect(screen.queryByRole("heading", { name: /add a vendor/i })).not.toBeInTheDocument();
  });

  it("sends each reader to their own home, not to a second page that refuses them", async () => {
    // A volunteer has no Today — `/today` admits admin, manager and cook — so a fixed link there
    // would land them on this very screen again. The link is `homeForRole`'s answer, labelled with
    // that destination's own name from nav.ts.
    signedInAs("VOLUNTEER", "Radha Devi");
    render(<NewVendorPage />);

    const out = await screen.findByRole("link", { name: /go to my shifts/i });
    expect(out).toHaveAttribute("href", "/my-shifts");
  });

  it("still gives a way out to a role this build has never heard of", async () => {
    // A role the server sends and this deploy does not know — the shape of every "added a role,
    // deployed the backend first" morning. `homeForRole` has no answer for it, and an undefined
    // href turns the refusal into a blank page: the one screen in the app that must not break is
    // the one people land on when something about them does not fit. So it falls back to `/`, the
    // landing router, which knows what to do with anybody.
    signedInAs("ACCOUNTANT", "Someone New");
    render(<DonateRoute />);

    expect(await screen.findByRole("heading", { name: "Not your page" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /go to the home page/i })).toHaveAttribute("href", "/");
  });
});

describe("no page ends up with two menus", () => {
  // The one way this fix could break a screen that works today: every guarded page that draws its
  // own sidebar draws it *inside* the guard, so a refusal replaces it rather than adding to it. If
  // a page ever moves its `<Sidebar>` above `RequireRole`, this is what catches it.
  it("draws exactly one on /donate when the guard refuses", async () => {
    render(<DonateRoute />);
    await screen.findByRole("heading", { name: "Not your page" });

    expect(menus()).toHaveLength(1);
  });

  it("draws exactly one on /donate when the guard admits, and the page is unchanged", async () => {
    signedInAs("VOLUNTEER", "Radha Devi");
    render(<DonateRoute />);

    // The page's own content, from the page's own sidebar-bearing markup.
    expect(await screen.findByRole("tab", { name: "Donate money" })).toBeInTheDocument();
    expect(menus()).toHaveLength(1);
    expect(screen.queryByRole("heading", { name: "Not your page" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /go to my shifts/i })).not.toBeInTheDocument();
  });

  it("draws exactly one on a FocusScreen route, refused or admitted", async () => {
    signedInAs("VOLUNTEER", "Radha Devi");
    const refused = render(<NewVendorPage />);
    await screen.findByRole("heading", { name: "Not your page" });
    expect(menus()).toHaveLength(1);
    refused.unmount();

    // And the admitted reader sees the form and its own single menu — an allowed role gets no
    // change at all from this, which is the other half of the claim.
    signedInAs("TEMPLE_ADMIN", "Radharani Devi");
    render(<NewVendorPage />);
    expect(await screen.findByRole("heading", { name: /add a vendor/i })).toBeInTheDocument();
    expect(menus()).toHaveLength(1);
    expect(screen.queryByRole("heading", { name: "Not your page" })).not.toBeInTheDocument();
  });
});

/**
 * The two neighbouring branches stay bare, and that is a decision rather than an omission.
 *
 * <p>Asserted on purpose, and this is why: the three refusals in `RequireRole` now look different
 * from one another, and the natural instinct of the next person to read the file will be to make
 * them alike. A disabled person cannot use a single destination the menu offers, and a menu drawn
 * over a server that is not answering is twenty doors that all lead back to this screen. The
 * sign-out is the one thing that is *not* chrome — see `session-failures.test.tsx`, where it is
 * driven through the real provider — and it belongs to the disabled screen alone, because signing
 * out of an application that cannot reach its server helps nobody.
 */
describe("the branches that are bare on purpose", () => {
  it("tells a disabled person plainly, and draws no menu behind it", async () => {
    authRef.current = { ...authRef.current, status: "disabled", appUser: null };
    render(<DonateRoute />);

    expect(await screen.findByText("This account has been disabled")).toBeInTheDocument();
    expect(menus()).toHaveLength(0);
  });

  it("leaves the unreachable screen exactly as it was: no menu, no sign-out, one retry", async () => {
    authRef.current = { ...authRef.current, status: "unreachable", appUser: null };
    render(<DonateRoute />);

    expect(await screen.findByText(/we can’t reach the server/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /try again/i })).toBeInTheDocument();
    expect(menus()).toHaveLength(0);
    expect(screen.queryByRole("button", { name: /different account/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /^go to /i })).not.toBeInTheDocument();
  });
});
