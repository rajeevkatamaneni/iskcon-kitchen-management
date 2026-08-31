import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import type { ApiError, TodayView } from "@/lib/api";

// Today is role-gated and reads a single assembled payload. Drive the guard and the query from
// mutable refs so each case states only what it is about.
const { authRef, queryRef } = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", fullName: "Radha Devi", tenantName: "ISKCON Bengaluru" },
    } as { status: string; appUser: { role: string; fullName?: string; tenantName?: string } | null },
  },
  queryRef: {
    current: { data: null as TodayView | null, error: null as ApiError | null, loading: false },
  },
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token", signOut: vi.fn() }),
}));
vi.mock("@/lib/use-authed-query", () => ({ useAuthedQuery: () => queryRef.current }));

// The notice band fetches its own feed, which the single query mock above cannot serve alongside
// Today's payload. It has its own tests (notices.test.tsx); here we only care that Today mounts it,
// and mounts it above everything else on the screen.
vi.mock("@/components/PlatformNotices", () => ({
  PlatformNotices: () => <div data-testid="platform-notices" />,
}));

import TodayPage from "@/app/today/page";

function today(overrides: Partial<TodayView> = {}): TodayView {
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
    meals: [
      {
        mealKind: "Lunch",
        readyBy: "12:00:00",
        plates: 820,
        recorded: false,
        awaitingRecord: true,
        occasionName: null,
        dishes: [
          {
            id: "m1",
            recipeName: "Khichdi",
            targetYield: 820,
            targetYieldUnit: "KG",
            actualServings: null,
            notMade: false,
            status: "PLANNED",
          },
          {
            id: "m1b",
            recipeName: "Kesari",
            targetYield: 820,
            targetYieldUnit: "KG",
            actualServings: null,
            notMade: false,
            status: "PLANNED",
          },
        ],
      },
      {
        mealKind: "Dinner",
        readyBy: "19:30:00",
        plates: 420,
        recorded: true,
        awaitingRecord: false,
        occasionName: null,
        dishes: [
          {
            id: "m2",
            recipeName: "Upma",
            targetYield: 420,
            targetYieldUnit: "KG",
            actualServings: 395,
            notMade: false,
            status: "COOKED",
          },
        ],
      },
    ],
    platesToday: 1240,
    itemsBelowThreshold: 6,
    itemsTracked: 40,
    workforce: { staffIn: 4, volunteers: 3, meals: [] },
    materialsCost: { estimatedTotal: 18400, withoutPrice: 0 },
    unrecordedMeals: 0,
    approvals: { ingredientRequests: 0, ingredientRequestsSoon: 0, leaveRequests: 0, leaveRequestsSoon: 0 },
    deliveries: [
      {
        purchaseOrderId: "po1",
        poNumber: "PO-2026-0001",
        vendorName: "Govind Wholesale",
        neededBy: "2026-08-14",
        state: "AWAITED",
      },
    ],
    ...overrides,
  };
}

describe("today", () => {
  beforeEach(() => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", fullName: "Radha Devi", tenantName: "ISKCON Bengaluru" },
    };
    queryRef.current = { data: today(), error: null, loading: false };
  });

  it("opens on the date, and says what the day holds", () => {
    render(<TodayPage />);

    // The date renders in the reader's own locale ("14 August 2026" in India, "August 14, 2026"
    // in a US-defaulted test runner), so assert the parts rather than one country's order.
    const heading = screen.getByRole("heading", { level: 1 });
    expect(heading).toHaveTextContent(/friday/i);
    expect(heading).toHaveTextContent(/august/i);
    expect(heading).toHaveTextContent(/14/);
    // No year: the morning screen is always about today, and the year on the heading is noise.
    expect(heading).not.toHaveTextContent(/2026/);
    expect(screen.getByText(/1,240 plates across 2 meals/i)).toBeInTheDocument();
  });

  it("answers the four questions, each as a way into the screen that acts on it", () => {
    render(<TodayPage />);

    expect(screen.getByRole("link", { name: /plates today/i })).toHaveAttribute("href", "/planner");
    expect(screen.getByRole("link", { name: /items below par/i })).toHaveAttribute("href", "/inventory");
    // "Working today" replaced "Shifts unfilled", which warned about a shift on an unnamed date and
    // gave an admin nothing to act on; "Cost of materials" replaced "Given this month", which moved
    // to the donations screen where somebody goes to look at money deliberately.
    expect(screen.getByRole("link", { name: /working today/i })).toHaveAttribute(
      "href",
      "/staff-schedule"
    );
    expect(screen.getByRole("link", { name: /cost of materials/i })).toHaveAttribute(
      "href",
      "/planner"
    );
  });

  it("lists the meals in the order the kitchen has to have them ready", () => {
    render(<TodayPage />);

    const meals = screen.getByRole("region", { name: /meals planned for today/i });
    const rows = within(meals).getAllByRole("link");
    expect(rows[0]).toHaveAccessibleName("Lunch at 12:00");
    expect(rows[1]).toHaveAccessibleName("Dinner at 19:30");
    expect(within(meals).getByText("12:00")).toBeInTheDocument();
  });

  it("names the next fast a month out, so there is time to order around it", () => {
    queryRef.current = {
      data: today({
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
          ahead: { date: "2026-08-24", name: "Pavitropana Ekadasi", kind: "FAST", daysAway: 10 },
        },
      }),
      error: null,
      loading: false,
    };
    render(<TodayPage />);

    expect(screen.getByText(/Pavitropana Ekadasi/)).toBeInTheDocument();
    expect(screen.getByText(/in 10 days/)).toBeInTheDocument();
  });

  it("shows a fasting day as a banner, because it changes every menu on it", () => {
    queryRef.current = {
      data: today({
        calendar: {
          fastingToday: false,
          fastingTomorrow: true,
          todayName: null,
          tomorrowName: "Ekadashi",
          sunrise: "06:05:00",
          tithi: 16,
          paksa: 1,
          masa: 3,
          naksatra: 9,
          ahead: null,
        },
      }),
      error: null,
      loading: false,
    };
    render(<TodayPage />);

    expect(screen.getByText(/tomorrow is a fasting day \(ekadashi\)/i)).toBeInTheDocument();
  });

  it("says nothing about fasting when the temple has no calendar computed", () => {
    queryRef.current = { data: today({ calendar: null }), error: null, loading: false };
    render(<TodayPage />);

    expect(screen.queryByText(/fasting day/i)).not.toBeInTheDocument();
  });

  it("no longer carries giving at all — money lives where somebody looks for it deliberately", () => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "KITCHEN_STAFF", fullName: "Gopal Das", tenantName: "ISKCON Bengaluru" },
    };
    queryRef.current = { data: today(), error: null, loading: false };
    render(<TodayPage />);

    expect(screen.queryByText(/given this month/i)).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: /plates today/i })).toBeInTheDocument();
  });

  it("counts staff and volunteers apart, because they are not interchangeable", () => {
    queryRef.current = { data: today(), error: null, loading: false };
    render(<TodayPage />);

    const tile = screen.getByRole("link", { name: /working today/i });
    expect(tile).toHaveTextContent("4 · 3");
    expect(tile).toHaveTextContent(/4 staff/i);
    expect(tile).toHaveTextContent(/3 volunteers/i);
  });

  it("names how many ingredients had no price rather than quietly under-reporting", () => {
    queryRef.current = {
      data: today({ materialsCost: { estimatedTotal: 18400, withoutPrice: 6 } }),
      error: null,
      loading: false,
    };
    render(<TodayPage />);

    const tile = screen.getByRole("link", { name: /cost of materials/i });
    expect(tile).toHaveTextContent("₹18,400");
    expect(tile).toHaveTextContent(/6 ingredients have no known price/i);
  });

  it("counts plates per meal, never by summing the dishes of one", () => {
    // A lunch of two dishes at 820 servings each is 820 plates, not 1,640 (A4, §1d).
    queryRef.current = { data: today(), error: null, loading: false };
    render(<TodayPage />);

    const tile = screen.getByRole("link", { name: /plates today/i });
    expect(tile).toHaveTextContent("Lunch 820");
    expect(tile).toHaveTextContent("Dinner 420");
  });

  it("groups the day's dishes under their meal, and links each meal to that day's planner", () => {
    queryRef.current = { data: today(), error: null, loading: false };
    render(<TodayPage />);

    expect(screen.getByText("Meals planned for today")).toBeInTheDocument();

    const lunch = screen.getByRole("link", { name: "Lunch at 12:00" });
    expect(lunch).toHaveAttribute("href", "/planner?date=2026-08-14");
    expect(within(lunch).getByText("Khichdi")).toBeInTheDocument();
    expect(within(lunch).getByText("Kesari")).toBeInTheDocument();
    // The truth, not a badge.
    expect(within(lunch).getByText(/not yet recorded/i)).toBeInTheDocument();

    const dinner = screen.getByRole("link", { name: "Dinner at 19:30" });
    expect(within(dinner).getByText(/395 Kg served/i)).toBeInTheDocument();
  });

  it("puts the platform notice band above everything else on the screen", () => {
    queryRef.current = { data: today(), error: null, loading: false };
    const { container } = render(<TodayPage />);

    const band = screen.getByTestId("platform-notices");
    const tiles = screen.getByRole("link", { name: /plates today/i });
    // A supplier recall is not a thing to scroll past.
    expect(band.compareDocumentPosition(tiles) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(container).toBeTruthy();
  });

  it("nudges about meals nobody recorded, and says why it matters", () => {
    queryRef.current = { data: today({ unrecordedMeals: 3 }), error: null, loading: false };
    render(<TodayPage />);

    // The count leads, in the heavier weight, because it is the thing to react to.
    const count = screen.getByText("3 meals");
    expect(count).toBeInTheDocument();
    expect(count.className).toContain("font-semibold");
    expect(screen.getByText(/from earlier this week haven’t been recorded yet/i)).toBeInTheDocument();
    expect(screen.getByText(/still shows their ingredients as on hand/i)).toBeInTheDocument();
    // The way out is a control, not a word buried in a sentence.
    // Not the planner on today — the one day that is certainly not the problem. The way out lands
    // on the meals that actually owe a recording (2026-08-23).
    expect(screen.getByRole("link", { name: /record them/i })).toHaveAttribute(
      "href",
      "/planner/catch-up"
    );
  });

  it("nudges about requests waiting for an answer, and says how many cannot wait", () => {
    queryRef.current = {
      data: today({
        approvals: {
          ingredientRequests: 3,
          ingredientRequestsSoon: 1,
          leaveRequests: 0,
          leaveRequestsSoon: 0,
        },
      }),
      error: null,
      loading: false,
    };
    render(<TodayPage />);

    const count = screen.getByText("3 ingredient requests");
    expect(count).toBeInTheDocument();
    expect(count.className).toContain("font-semibold");
    expect(screen.getByText(/1 of them is needed today or tomorrow/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /review them/i })).toHaveAttribute(
      "href",
      "/ingredient-requests?status=SUBMITTED"
    );
  });

  it("says nothing at all to somebody who cannot answer either queue", () => {
    // The server sends zeroes to a kitchen staff member, because they hold neither permission. A
    // nudge about something you cannot do is noise you learn to scroll past — and then you scroll
    // past the ones you can.
    queryRef.current = {
      data: today({
        approvals: {
          ingredientRequests: 0,
          ingredientRequestsSoon: 0,
          leaveRequests: 0,
          leaveRequestsSoon: 0,
        },
      }),
      error: null,
      loading: false,
    };
    render(<TodayPage />);

    expect(screen.queryByText(/waiting for an answer/i)).not.toBeInTheDocument();
  });

  it("nudges about leave nobody has answered, separately from the requests", () => {
    // Two notices rather than one: they are answered on different screens by different acts, and a
    // single line offering two destinations makes the reader choose before they have understood.
    queryRef.current = {
      data: today({
        approvals: {
          ingredientRequests: 1,
          ingredientRequestsSoon: 0,
          leaveRequests: 2,
          leaveRequestsSoon: 2,
        },
      }),
      error: null,
      loading: false,
    };
    render(<TodayPage />);

    expect(screen.getByText("1 ingredient request")).toBeInTheDocument();
    expect(screen.getByText("2 leave requests")).toBeInTheDocument();
    // Two of two are urgent, so the sentence is plural throughout — "they all start …
    // or have already started", never "has".
    expect(screen.getByText(/They all start today or tomorrow, or have already started/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /open the leave queue/i })).toHaveAttribute(
      "href",
      "/leave"
    );
  });

  it("says one meal in the singular, because a nudge that cannot count is not read twice", () => {
    queryRef.current = { data: today({ unrecordedMeals: 1 }), error: null, loading: false };
    render(<TodayPage />);

    expect(screen.getByText("1 meal")).toBeInTheDocument();
    expect(screen.getByText(/from earlier this week hasn’t been recorded yet/i)).toBeInTheDocument();
  });

  it("tells a temple with nothing in it what would fill the screen", () => {
    queryRef.current = {
      data: today({
        meals: [],
        platesToday: 0,
        deliveries: [],
        itemsBelowThreshold: 0,
        itemsTracked: 0,
        workforce: { staffIn: 0, volunteers: 0, meals: [] },
        materialsCost: { estimatedTotal: 0, withoutPrice: 0 },
      }),
      error: null,
      loading: false,
    };
    render(<TodayPage />);

    expect(screen.getByText(/nothing planned for today/i)).toBeInTheDocument();
    expect(screen.getByText(/nothing due today/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /open the planner/i })).toBeInTheDocument();

    // A zero that means "nothing is tracked yet" must not read as "everything is fine".
    expect(screen.getByRole("link", { name: /items below par/i })).toHaveTextContent(
      /nothing is tracked yet/i
    );
    expect(screen.getByRole("link", { name: /working today/i })).toHaveTextContent(
      /nobody is down to work today/i
    );
  });

  it("shows the error contract when the screen cannot load", () => {
    queryRef.current = {
      data: null,
      loading: false,
      error: { code: "KMS-0000", message: "We couldn't load this.", action: "Try again." } as ApiError,
    };
    render(<TodayPage />);

    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.getByText("KMS-0000")).toBeInTheDocument();
  });

  it("refuses a volunteer, who has no temple day to run", () => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "VOLUNTEER", fullName: "A Devotee", tenantName: "ISKCON Bengaluru" },
    };
    render(<TodayPage />);

    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });

  it("says how many hands each meal has against how many it takes", () => {
    // "Working today · 7" could not answer the question. The seven are not all there at midday, and
    // lunch may take eight (item 24).
    queryRef.current = {
      data: today({
        workforce: {
          staffIn: 4,
          volunteers: 3,
          meals: [
            { planDate: "2026-08-14", mealKind: "Breakfast", readyBy: "07:30:00", crewRequired: 4,
              staffIn: 4, volunteers: 0, rostered: 4, shortOfCrew: false },
            { planDate: "2026-08-14", mealKind: "Lunch", readyBy: "12:00:00", crewRequired: 8,
              staffIn: 3, volunteers: 2, rostered: 5, shortOfCrew: true },
            // Nobody has said what the evening takes, so it is left out rather than drawn as short
            // of nothing.
            { planDate: "2026-08-14", mealKind: "Dinner", readyBy: "19:30:00", crewRequired: null,
              staffIn: 2, volunteers: 1, rostered: 3, shortOfCrew: false },
          ],
        },
      }),
      error: null,
      loading: false,
    };
    render(<TodayPage />);

    const tile = screen.getByRole("link", { name: /working today/i });
    expect(tile).toHaveTextContent("Breakfast 4 of 4");
    expect(tile).toHaveTextContent("Lunch 5 of 8");
    expect(tile).not.toHaveTextContent("Dinner");

    // The short one is the only part of the line anybody has to do anything about, so it is the
    // part that stands out.
    expect(screen.getByText("Lunch 5 of 8").className).toContain("text-warning");
    expect(screen.getByText("Breakfast 4 of 4").className).not.toContain("text-warning");
  });

  it("falls back to who is in when no meal has a number on it yet", () => {
    render(<TodayPage />);
    expect(screen.getByRole("link", { name: /working today/i })).toHaveTextContent(
      /4 staff · 3 volunteers/
    );
  });

  it("names the temple in the menu", () => {
    render(<TodayPage />);

    const nav = screen.getByRole("navigation", { name: /main/i });
    expect(within(nav).getByText("ISKCON Bengaluru")).toBeInTheDocument();
  });
});
