import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, render, screen } from "@testing-library/react";
import type { IngredientRequestDetail, TodayView } from "@/lib/api";

/**
 * Four screens that offered a control to somebody the API is right to refuse (T-002).
 *
 * <p>Every case here is a screen problem and not a policy one. The permissions behind these four
 * controls are correct: a cook does not issue stock, does not run the staff schedule, does not sign
 * up for volunteer seva, and does not read the salaries on the staff register. What was wrong is
 * that the screens offered all four anyway, and the reader learned the refusal by pressing the
 * button — or, in the work order's case, before pressing anything at all, because the card asks for
 * its language list the moment it mounts.
 *
 * <p>So each test asserts the behaviour by role, not the markup: the same page rendered for the
 * reader who may act and for the reader who may not, and the difference between them. A test that
 * only asserted the absence would still pass on the day somebody deletes the control outright,
 * which would be a different bug and not a fix.
 */

// The whole auth object has to be stable across renders, `getToken` included: useAuthedQuery lists
// it in the dependencies of the effect that fetches, so a mock that builds a fresh closure each
// render re-fetches forever and every assertion below times out waiting for a screen that never
// settles. Hence one object, replaced only when the role changes.
const { authRef, api } = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "KITCHEN_STAFF", userId: "me", fullName: "Gopal Das" },
      getToken: async () => "test-token",
      refresh: () => {},
    } as {
      status: string;
      appUser: { role: string; userId?: string; fullName?: string } | null;
      getToken: () => Promise<string>;
      refresh: () => void;
    },
  },
  api: {
    getIngredientRequest: vi.fn(),
    workOrderLanguages: vi.fn(),
    today: vi.fn(),
    myShifts: vi.fn(),
    myWaitlist: vi.fn(),
    // T-149's "taken off in the last week" list, the page's third query.
    myReleasedShifts: vi.fn(),
    // Only so that section (3) can render the destination /my-shifts sends its reader to, and
    // prove the reader is admitted there. Never asserted on for its own sake.
    availableShifts: vi.fn(),
    staffWeek: vi.fn(),
    crewCoverage: vi.fn(),
  },
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useParams: () => ({ id: "ir1" }),
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
// The real useAuthedQuery runs throughout, deliberately. The whole point of the first case is that
// a request is never made, and a stubbed query hook would make that true whether the fix was there
// or not.
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, ...api } };
});
// Today's notice band fetches its own feed and has its own tests; here it is only in the way.
vi.mock("@/components/PlatformNotices", () => ({
  PlatformNotices: () => <div data-testid="platform-notices" />,
}));

import IngredientRequestPage from "@/app/ingredient-requests/[id]/page";
import MyShiftsPage from "@/app/my-shifts/page";
// Imported by section (3) alone, and not to test it: it is the screen /my-shifts' empty state
// names, so rendering it is how "the reader is not refused where we are sending them" is checked
// against the real guard instead of against a comment about it.
import ShiftsPage from "@/app/shifts/page";
import StaffSchedulePage from "@/app/staff-schedule/page";
import TodayPage from "@/app/today/page";

function signedInAs(role: string) {
  authRef.current = {
    status: "signed-in",
    appUser: { role, userId: "me", fullName: "Somebody" },
    getToken: async () => "test-token",
    refresh: () => {},
  };
}

/**
 * Lets every effect that a render started run to completion.
 *
 * <p>Needed for the negative assertions: "nothing was requested" is only worth anything once the
 * mount that would have requested it has finished. The positive case beside each one calls the same
 * helper and does see the request, which is what makes the window long enough.
 */
async function settle() {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
}

// --- (1) The work order on an ingredient request ------------------------------------------

function requestDetail(status: IngredientRequestDetail["request"]["status"]): IngredientRequestDetail {
  return {
    request: {
      id: "ir1",
      reference: "IR-2026-0041",
      kitchenId: "k1",
      kitchenName: "Prasadam kitchen",
      neededOn: "2026-09-04",
      purpose: "Janmashtami feast",
      status,
      requestedBy: "me",
      requestedByName: "Gopal Das",
      submittedAt: "2026-08-30T09:00:00Z",
      decidedByName: "Radha",
      decidedAt: "2026-08-30T10:00:00Z",
      issuedAt: status === "ISSUED" ? "2026-08-30T11:00:00Z" : null,
      lineCount: 1,
      dishCount: 1,
    },
    lines: [
      {
        id: "l1",
        lineNo: 1,
        ingredientId: "i1",
        ingredientName: "Rice",
        quantity: 40,
        unit: "KG",
        issuedQuantity: null,
        issuedUnit: null,
        note: null,
      },
    ],
    dishes: [{ id: "d1", lineNo: 1, dishName: "Khichdi", quantity: 200, unit: "KG" }],
    events: [
      {
        id: "e1",
        eventType: "CREATED",
        detail: "IR-2026-0041 raised as a draft",
        actorName: "Gopal Das",
        at: "2026-08-30T09:00:00Z",
      },
    ],
  };
}

describe("the work order is offered to whoever may issue, and to nobody else", () => {
  beforeEach(() => {
    signedInAs("KITCHEN_STAFF");
    api.getIngredientRequest.mockReset().mockResolvedValue(requestDetail("APPROVED"));
    api.workOrderLanguages
      .mockReset()
      .mockResolvedValue({ defaultLanguage: "en", languages: [{ code: "en", label: "English" }] });
  });

  it("asks for no language list at all when the reader may not issue", async () => {
    render(<IngredientRequestPage />);
    await screen.findByRole("heading", { name: /IR-2026-0041/ });
    await settle();

    // The refusal this fixes happened before any click: the card's own query 403s on mount,
    // because every work-order endpoint is guarded by ISSUE_INGREDIENTS.
    expect(api.workOrderLanguages).not.toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: /download work order/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^print$/i })).not.toBeInTheDocument();
    // And the explanation a cook already had is still the one they get.
    expect(screen.getByText(/waiting on the store/i)).toBeInTheDocument();
  });

  it("still asks for it, and still offers both ways out, for a kitchen manager", async () => {
    signedInAs("KITCHEN_MANAGER");
    render(<IngredientRequestPage />);
    await screen.findByRole("heading", { name: /IR-2026-0041/ });
    await settle();

    expect(api.workOrderLanguages).toHaveBeenCalled();
    expect(screen.getByRole("button", { name: /download work order/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^print$/i })).toBeInTheDocument();
  });

  it("keeps the sheet away from a cook on a request that has already been issued", async () => {
    api.getIngredientRequest.mockResolvedValue(requestDetail("ISSUED"));
    render(<IngredientRequestPage />);
    await screen.findByRole("heading", { name: /IR-2026-0041/ });
    await settle();

    expect(api.workOrderLanguages).not.toHaveBeenCalled();
    expect(screen.queryByText("The work order")).not.toBeInTheDocument();
  });
});

// --- (2) Today's "Working today" tile ------------------------------------------------------

function todayView(): TodayView {
  return {
    date: "2026-08-14",
    calendar: {
      fastingToday: false,
      fastingTomorrow: false,
      todayName: null,
      tomorrowName: null,
      sunrise: "06:05:00",
      tithi: 16,
      paksa: 1,
      masa: 3,
      naksatra: 9,
      ahead: null,
    },
    meals: [],
    platesToday: 1240,
    itemsBelowThreshold: 0,
    itemsTracked: 40,
    workforce: { staffIn: 4, volunteers: 3, meals: [] },
    materialsCost: { estimatedTotal: 18400, withoutPrice: 0 },
    unrecordedMeals: 0,
    equipmentOverdue: null,
    approvals: {
      ingredientRequests: 0,
      ingredientRequestsSoon: 0,
      leaveRequests: 0,
      leaveRequestsSoon: 0,
    },
    deliveries: [],
  };
}

describe("Today tells a cook who is in without sending them at the schedule", () => {
  beforeEach(() => {
    signedInAs("KITCHEN_STAFF");
    api.today.mockReset().mockResolvedValue(todayView());
  });

  it("keeps the figure and drops the link for a reader the schedule refuses", async () => {
    render(<TodayPage />);

    const label = await screen.findByText(/working today/i);
    // The count is theirs to see — it answers one of the four questions this screen exists for.
    expect(label.closest("div")).toHaveTextContent("4 · 3");
    // The door is not: /staff-schedule admits an admin and a kitchen manager only, and a tile that
    // lands on "Not your page" teaches its reader that the other three tiles lie too.
    expect(label.closest("a")).toBeNull();
    expect(screen.queryByRole("link", { name: /working today/i })).not.toBeInTheDocument();
  });

  it("is still the way into the schedule for a kitchen manager", async () => {
    signedInAs("KITCHEN_MANAGER");
    render(<TodayPage />);

    const tile = await screen.findByRole("link", { name: /working today/i });
    expect(tile).toHaveAttribute("href", "/staff-schedule");
  });
});

// --- (3) The /my-shifts empty state --------------------------------------------------------

/**
 * Rewritten for D-16 (2026-09-07). T-002 made this section about the *wording* a cook saw on
 * /my-shifts, because a cook could open it. That premise is gone: the page guard is now
 * `["VOLUNTEER"]`, seva is not offered to people the temple employs, and the question is no longer
 * "what does the cook read here" but "does the cook get here at all".
 *
 * <p>The defect T-002's own copy carried is settled by the same change and is asserted below rather
 * than argued: its else-branch told the reader that "Who is covering which shift is on the
 * Volunteer shifts screen", and Rajeev, opening /my-shifts as a real cook on staging, followed it
 * to "Not your page". That branch is deleted, and the surviving sentence is held to T-002's rule
 * directly — whoever can read it can open what it names.
 */
describe("my shifts is the volunteer's seva board and refuses everybody else", () => {
  beforeEach(() => {
    signedInAs("KITCHEN_STAFF");
    api.myShifts.mockReset().mockResolvedValue([]);
    api.myWaitlist.mockReset().mockResolvedValue([]);
    api.myReleasedShifts.mockReset().mockResolvedValue([]);
    api.availableShifts.mockReset().mockResolvedValue([]);
  });

  for (const employed of ["KITCHEN_STAFF", "KITCHEN_MANAGER"] as const) {
    it(`refuses ${employed}, and asks the server for nothing on the way`, async () => {
      signedInAs(employed);
      render(<MyShiftsPage />);
      await settle();

      expect(screen.getByRole("heading", { name: /not your page/i })).toBeInTheDocument();
      expect(screen.queryByText(/no upcoming shifts/i)).not.toBeInTheDocument();
      // The guard sits above the view, so the two queries never mount. Worth asserting: a page
      // that refuses politely while still fetching the reader's shifts would be a different bug.
      expect(api.myShifts).not.toHaveBeenCalled();
      expect(api.myWaitlist).not.toHaveBeenCalled();
      expect(api.myReleasedShifts).not.toHaveBeenCalled();
    });
  }

  it("no longer carries the sentence that sent a cook to a page he was refused", async () => {
    signedInAs("VOLUNTEER");
    render(<MyShiftsPage />);
    await settle();

    // Deleted outright rather than reworded — with the guard narrowed there is no reader left for
    // it. Asserted for the one role that can reach the page, which is where it would have to
    // reappear if somebody restored the ternary.
    expect(screen.queryByText(/signed up for by volunteers/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/volunteer shifts screen/i)).not.toBeInTheDocument();
  });

  it("still points a volunteer at the shifts they can take", async () => {
    signedInAs("VOLUNTEER");
    render(<MyShiftsPage />);

    expect(await screen.findByText(/no upcoming shifts/i)).toBeInTheDocument();
    expect(screen.getByText(/browse available shifts to offer seva/i)).toBeInTheDocument();
  });

  // T-002's acceptance criterion, asserted as a rule instead of as a sentence: for every role the
  // app has, if the empty state offers a destination, that role must be admitted to it. It reads
  // the two real page guards — no list of roles is written down here to drift out of date — so it
  // fails if either the seva copy comes back for somebody /shifts refuses, or /shifts narrows
  // beneath /my-shifts. Today exactly one role reaches this page, and it is the one /shifts wants.
  for (const role of ["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF", "VOLUNTEER"] as const) {
    it(`never points ${role} anywhere ${role} is refused`, async () => {
      signedInAs(role);
      const shown = render(<MyShiftsPage />);
      await settle();
      const pointed = screen.queryByText(/browse available shifts to offer seva/i) !== null;
      shown.unmount();

      if (pointed) {
        render(<ShiftsPage />);
        await settle();
        expect(screen.queryByRole("heading", { name: /not your page/i })).not.toBeInTheDocument();
        expect(screen.getByRole("heading", { name: /available shifts/i })).toBeInTheDocument();
      }
    });
  }
});

// --- (4) The staff-schedule empty state ----------------------------------------------------

/** Every href the empty-state card itself offers, sidebar and header excluded. */
function linksInTheEmptyState(heading: HTMLElement): (string | null)[] {
  const card = heading.closest("div");
  return [...(card as HTMLElement).querySelectorAll("a")].map((a) => a.getAttribute("href"));
}

describe("the staff schedule no longer offers the register it says it does not offer", () => {
  beforeEach(() => {
    signedInAs("KITCHEN_MANAGER");
    api.staffWeek.mockReset().mockResolvedValue({ weekStart: "2026-08-31", staff: [], counts: [] });
    api.crewCoverage.mockReset().mockResolvedValue([]);
  });

  it("gives a kitchen manager an empty grid with no way onto the staff register", async () => {
    render(<StaffSchedulePage />);

    // /staff admits a temple admin alone, because salary and PAN live on it. The grid's own note
    // has claimed since A11 that this link had gone; now it has.
    expect(linksInTheEmptyState(await screen.findByText(/no staff yet/i))).toEqual([]);
    expect(screen.getByText(/an administrator adds them/i)).toBeInTheDocument();
  });

  it("does not restore it for a temple admin either, who reaches the register from the nav", async () => {
    signedInAs("TEMPLE_ADMIN");
    render(<StaffSchedulePage />);

    // Scoped to the card, because an admin's sidebar carries /staff quite legitimately — it is the
    // empty state offering it, to a manager who will be refused, that was the defect.
    expect(linksInTheEmptyState(await screen.findByText(/no staff yet/i))).toEqual([]);
  });
});
