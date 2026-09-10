import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

/**
 * The two screens the planner's overlays became on 2026-08-21 (items 16 and 22).
 *
 * <p>A day was a modal over the calendar grid and a meal could only be corrected one preparation at
 * a time from a strip under it. Both are routes now, so the back button closes them, both reload,
 * and both can be sent to somebody.
 */

const { authRef, routeRef, api } = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", userId: "me", fullName: "Radha Devi", tenantName: "ISKCON Bengaluru" },
    } as { status: string; appUser: Record<string, unknown> | null },
  },
  routeRef: { current: {} as Record<string, string> },
  api: {
    mealServices: vi.fn(async (_from: string, _to: string, _t?: string) => [] as unknown[]),
    calendarRange: vi.fn(async (_from: string, _to: string, _t?: string) => [] as unknown[]),
    mealSufficiency: vi.fn(async (_from: string, _to: string, _t?: string) => [] as unknown[]),
    listRecipes: vi.fn(async (_f?: unknown, _t?: string) => [] as unknown[]),
    listMealKinds: vi.fn(async (_t?: string) => [] as unknown[]),
    mealCrew: vi.fn(async (_from: string, _to: string, _t?: string) => [] as unknown[]),
    suggestedCrew: vi.fn(async (_kind: string, _t?: string) => ({ crewRequired: null })),
    menuHistory: vi.fn(async () => ({
      occasionName: "", lastCookedOn: null, mealKind: null, preparationCount: 0,
      missingCount: 0, preparations: [],
    })),
    jobCardLanguages: vi.fn(async () => ({ languages: ["en"], defaultLanguage: "en" })),
    updateMealPlan: vi.fn(async () => undefined),
    createMealPlan: vi.fn(async () => ({ id: "new" })),
    cancelMealPlan: vi.fn(async () => undefined),
    // E4-S15/S16. A temple with no map service is the ordinary case, so that is the default here.
    travelEstimate: vi.fn(async (_id: string, _t?: string) => ({
      available: false,
      leaveBy: null as string | null,
      optimisticMinutes: null as number | null,
      pessimisticMinutes: null as number | null,
      guestsEatAt: null as string | null,
      reason: "NO_MAP_SERVICE" as string | null,
    })),
    repeatEvent: vi.fn(async (_id: string, _weeks: number, _t?: string) => ({
      copied: 0, weeksCopied: 0, refusedOnFast: 0,
    })),
    eventNameSuggestions: vi.fn(async (_q: string, _t?: string) => [] as unknown[]),
  },
}));

const push = vi.fn();
vi.mock("next/navigation", () => ({
  useParams: () => routeRef.current,
  useRouter: () => ({ push, replace: vi.fn(), back: vi.fn(), refresh: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "t" }),
}));
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, ...api } };
});
// The real hook, near enough: it runs the fetcher and hands back what came out. The screens under
// test each make four or five different calls, so one shared array of stand-in data would have to
// be all of them at once.
vi.mock("@/lib/use-authed-query", async () => {
  const { useEffect, useState } = await import("react");
  return {
    useAuthedQuery: (fn: (t?: string) => Promise<unknown>) => {
      const [data, setData] = useState<unknown>(null);
      useEffect(() => {
        let live = true;
        fn("t").then((d) => live && setData(d));
        return () => {
          live = false;
        };
      }, [fn]);
      return { data, error: null, loading: data === null, reload: vi.fn() };
    },
  };
});

import PlannerDayPage from "@/app/planner/[date]/page";
import EditMealPage from "@/app/planner/[date]/[kind]/page";

const TOMORROW = isoIn(1);

function preparation(
  id: string, recipeId: string, recipeName: string, overrides: Record<string, unknown> = {}
) {
  return {
    id, planDate: TOMORROW, mealKind: "Lunch", readyBy: "12:00:00",
    recipeId, recipeName, targetYield: 133, dayType: "REGULAR", occasionName: null,
    status: "PLANNED",
    eventName: null, isOutside: false, handover: null, contactName: null, contactPhone: null,
    deliveryAddress: null, guestsEatAt: null, purpose: null,
    adults: 120, children: 20, seniors: 0, crewRequired: 8, kitchenNotes: null,
    actualServings: null, notMade: false, cookedAt: null, ekadashiAcknowledged: false,
    createdAt: "2026-08-20T10:00:00Z",
    ...overrides,
  };
}

function lunch(overrides: Record<string, unknown> = {}) {
  return {
    serviceId: null, planDate: TOMORROW, mealKind: "Lunch", readyBy: "12:00:00",
    adults: 120, children: 20, seniors: 0, plates: 133, crewRequired: 8,
    dayType: "REGULAR", occasionName: null,
    eventName: null, contactName: null, contactPhone: null, deliveryAddress: null,
    purpose: null, kitchenNotes: null,
    cardNumber: null, cardIssuedAt: null,
    recorded: false, recordedAt: null, recordedByName: null, recordingNote: null,
    dishes: [preparation("p1", "r1", "Bisi Bele Bath"), preparation("p2", "r2", "Kesari Bath")],
    ...overrides,
  };
}

/**
 * One event, as the day reads it (E4-S15). The whole-meal facts sit on every preparation row, so
 * *going outside*, the handover and the serving time are given to the dish as well as the meal —
 * which is exactly how the server returns them.
 */
function event(dish: Record<string, unknown> = {}, meal: Record<string, unknown> = {}) {
  return lunch({
    mealKind: "Event",
    eventName: "Vidyaranyapura School Gita Reading",
    plates: 30,
    dishes: [preparation("p1", "r1", "Bisi Bele Bath", { mealKind: "Event", ...dish })],
    ...meal,
  });
}

const RECIPES = [
  { id: "r1", name: "Bisi Bele Bath", categoryName: "Khichadi", fastingCompatible: false,
    baseYieldQty: 100, baseYieldUnit: "KG", perHeadQty: 1, perHeadUnit: "KG", status: "ACTIVE" },
  { id: "r2", name: "Kesari Bath", categoryName: "Sweets", fastingCompatible: false,
    baseYieldQty: 100, baseYieldUnit: "KG", perHeadQty: 1, perHeadUnit: "KG", status: "ACTIVE" },
];

const KINDS = [
  { id: "k1", name: "Lunch", defaultReadyTime: "12:00:00", isEvent: false, needsOccasion: false },
  { id: "k2", name: "Event", defaultReadyTime: null, isEvent: true, needsOccasion: false },
];

describe("a day of the plan, at its own address", () => {
  beforeEach(() => {
    routeRef.current = { date: TOMORROW };
    api.mealServices.mockResolvedValue([lunch()]);
    api.calendarRange.mockResolvedValue([]);
    api.listRecipes.mockResolvedValue(RECIPES);
    api.listMealKinds.mockResolvedValue(KINDS);
    authRef.current = {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", userId: "me", fullName: "Radha Devi", tenantName: "ISKCON Bengaluru" },
    };
  });

  it("names the day and draws its meals as blocks", async () => {
    render(<PlannerDayPage />);

    expect(await screen.findByText("Lunch")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(/\d/);
    expect(screen.getAllByText(/133 servings/).length).toBeGreaterThan(0);
    // Not a dialog any more: the back button has something to go back to.
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("refuses a date it cannot read rather than working from NaN", async () => {
    routeRef.current = { date: "yesterday" };
    render(<PlannerDayPage />);
    expect(await screen.findByText(/that is not a date/i)).toBeInTheDocument();
  });
});

/**
 * An event on the day, and the two things only an event has (E4-S15, E4-S16).
 *
 * <p>The estimate says when to <em>leave</em>, and repeating forward makes copies rather than a
 * series. Both hang off the meal's own rows, and neither exists on a Lunch.
 */
describe("an event on the day", () => {
  beforeEach(() => {
    routeRef.current = { date: TOMORROW };
    api.travelEstimate.mockClear();
    api.repeatEvent.mockClear();
    api.calendarRange.mockResolvedValue([]);
    api.listRecipes.mockResolvedValue(RECIPES);
    api.listMealKinds.mockResolvedValue(KINDS);
    api.travelEstimate.mockResolvedValue({
      available: false, leaveBy: null, optimisticMinutes: null, pessimisticMinutes: null,
      guestsEatAt: null, reason: "NO_MAP_SERVICE",
    });
  });

  it("reads under its own name, not as another Event", async () => {
    api.mealServices.mockResolvedValue([event()]);
    render(<PlannerDayPage />);

    expect(await screen.findByText("Vidyaranyapura School Gita Reading")).toBeInTheDocument();
  });

  it("says when to leave the temple, and works it back from when the guests eat", async () => {
    api.mealServices.mockResolvedValue([
      event({
        isOutside: true, handover: "DELIVERY", contactName: "Mrs Latha Rao",
        contactPhone: "+91 98862 30011", deliveryAddress: "Hare Krishna Hill, Rajajinagar",
        guestsEatAt: "13:00:00",
      }, { deliveryAddress: "Hare Krishna Hill, Rajajinagar" }),
    ]);
    api.travelEstimate.mockResolvedValue({
      available: true, leaveBy: "12:15:00", optimisticMinutes: 35, pessimisticMinutes: 45,
      guestsEatAt: "13:00:00", reason: null,
    });
    render(<PlannerDayPage />);

    // "Leave the temple by 12:15" is the sentence a driver can act on; "45 minutes" is not, and
    // nobody should have to do the subtraction in their head against a time they must look up.
    const line = await screen.findByText(/Leave the temple by/);
    expect(line).toHaveTextContent("Leave the temple by 12:15");
    expect(line).toHaveTextContent(/35 to 45 minutes in \w+ traffic/);
    expect(line).toHaveTextContent("to be there before 13:00");
    expect(api.travelEstimate).toHaveBeenCalledWith("p1", "t");
  });

  it("says one quiet sentence, and nothing red, when there is no map service", async () => {
    api.mealServices.mockResolvedValue([
      event({
        isOutside: true, handover: "DELIVERY", contactName: "Mrs Latha Rao",
        contactPhone: "+91 98862 30011", deliveryAddress: "Hare Krishna Hill, Rajajinagar",
        guestsEatAt: "13:00:00",
      }),
    ]);
    render(<PlannerDayPage />);

    // Not an error, not a spinner that never stops, and not a blank where something should be. A
    // temple that has no map service has not been promised one.
    const line = await screen.findByText("No travel estimate for this delivery.");
    expect(line.className).toContain("text-ink-secondary");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("names the address as the thing to fix when the map could not place it", async () => {
    api.mealServices.mockResolvedValue([
      event({
        isOutside: true, handover: "DELIVERY", contactName: "Mrs Latha Rao",
        contactPhone: "+91 98862 30011", deliveryAddress: "Zzzz Qqqq, 999999",
        guestsEatAt: "13:00:00",
      }),
    ]);
    api.travelEstimate.mockResolvedValue({
      available: false, leaveBy: null, optimisticMinutes: null, pessimisticMinutes: null,
      guestsEatAt: null, reason: "ADDRESS_NOT_FOUND",
    });
    render(<PlannerDayPage />);

    expect(
      await screen.findByText("No travel estimate: we couldn’t find that address on the map.")
    ).toBeInTheDocument();
  });

  it("asks nothing of a pickup or an in-house event — there is no drive to describe", async () => {
    api.mealServices.mockResolvedValue([
      event({ isOutside: true, handover: "PICKUP", contactName: "Mrs Latha Rao",
        contactPhone: "+91 98862 30011" }),
    ]);
    render(<PlannerDayPage />);

    await screen.findByText("Vidyaranyapura School Gita Reading");
    expect(screen.queryByText(/travel estimate/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Leave the temple by/)).not.toBeInTheDocument();
    expect(api.travelEstimate).not.toHaveBeenCalled();
  });

  it("repeats an event forward as copies, and says what it declined to copy", async () => {
    api.mealServices.mockResolvedValue([event()]);
    api.repeatEvent.mockResolvedValue({ copied: 5, weeksCopied: 5, refusedOnFast: 1 });
    render(<PlannerDayPage />);

    fireEvent.click(await screen.findByRole("button", { name: /repeat it forward/i }));
    fireEvent.change(screen.getByLabelText(/how many weeks/i), { target: { value: "6" } });
    fireEvent.click(screen.getByRole("button", { name: /copy it forward/i }));

    await vi.waitFor(() => expect(api.repeatEvent).toHaveBeenCalledWith("p1", 6, "t"));
    // A planner who asked for six weeks and got five has to be told which one is missing, or they
    // find out on the day.
    expect(
      await screen.findByText(/5 weeks copied · 5 preparations · 1 skipped/)
    ).toBeInTheDocument();
    // Copies, not a series: nothing here offers to edit or cancel "all of them". That is said in
    // the "i" beside the control since 2026-09-04, so what is asserted is that the panel offers it.
    expect(
      screen.getByRole("button", { name: "More about Repeating it forward" })
    ).toBeInTheDocument();
  });

  it("offers nothing of the sort on a Lunch", async () => {
    api.mealServices.mockResolvedValue([lunch()]);
    render(<PlannerDayPage />);

    await screen.findByText("Lunch");
    expect(screen.queryByRole("button", { name: /repeat it forward/i })).not.toBeInTheDocument();
    expect(api.travelEstimate).not.toHaveBeenCalled();
  });
});

describe("editing one meal", () => {
  beforeEach(() => {
    push.mockClear();
    api.updateMealPlan.mockClear();
    routeRef.current = { date: TOMORROW, kind: "Lunch" };
    api.mealServices.mockResolvedValue([lunch()]);
    api.calendarRange.mockResolvedValue([]);
    api.listRecipes.mockResolvedValue(RECIPES);
    api.listMealKinds.mockResolvedValue(KINDS);
    authRef.current = {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", userId: "me", fullName: "Radha Devi", tenantName: "ISKCON Bengaluru" },
    };
  });

  it("opens that meal and nothing else, with one pair of buttons at the top", async () => {
    render(<EditMealPage />);

    expect(await screen.findByRole("heading", { level: 1, name: "Edit Lunch" })).toBeInTheDocument();
    // Cancel is the way out, and it goes back to the day the meal belongs to.
    expect(screen.getByRole("link", { name: /^cancel$/i })).toHaveAttribute(
      "href",
      `/planner/${TOMORROW}`
    );
    expect(screen.getByRole("button", { name: /update this meal/i })).toBeInTheDocument();
    // No second copy of the commit button at the foot.
    expect(screen.getAllByRole("button", { name: /update this meal/i })).toHaveLength(1);

    // Both of the meal's preparations are on it, and the crew it takes came with them.
    expect(screen.getByRole("checkbox", { name: /bisi bele bath/i })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: /kesari bath/i })).toBeChecked();
    expect(screen.getByLabelText("People needed")).toHaveValue(8);
  });

  it("saves the whole meal and returns to the day with the confirmation waiting", async () => {
    render(<EditMealPage />);
    await screen.findByRole("heading", { level: 1, name: "Edit Lunch" });

    fireEvent.change(screen.getByLabelText("Adults"), { target: { value: "150" } });
    fireEvent.click(screen.getByRole("button", { name: /update this meal/i }));

    await vi.waitFor(() => expect(api.updateMealPlan).toHaveBeenCalledTimes(2));
    await vi.waitFor(() =>
      expect(push).toHaveBeenCalledWith(`/planner/${TOMORROW}?saved=Lunch`)
    );
  });

  it("will not reopen a meal that has already been recorded", async () => {
    api.mealServices.mockResolvedValue([lunch({ recorded: true, recordedByName: "Gopal Das" })]);
    render(<EditMealPage />);

    expect(await screen.findByText(/has been recorded/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /update this meal/i })).not.toBeInTheDocument();
  });

  it("says so plainly when nothing of that kind is planned", async () => {
    api.mealServices.mockResolvedValue([]);
    render(<EditMealPage />);
    expect(
      await screen.findByText(/nothing of that kind is planned for this day/i)
    ).toBeInTheDocument();
  });
});

/**
 * The escalation on the ingredient badge (T-090).
 *
 * <p>Rajeev's rule: amber while there is still slack, red the day you hit the order-by date, and
 * past that a statement of fact rather than a warning. The wording is what carries the third state —
 * these assertions are deliberately on the sentences and not on the colours, because "a darker red"
 * is exactly the way this gets built wrong.
 */
describe("the ingredient badge says how much time is left to order", () => {
  beforeEach(() => {
    routeRef.current = { date: TOMORROW };
    api.mealServices.mockResolvedValue([lunch({ dishes: [preparation("p1", "r1", "Bisi Bele Bath")] })]);
    api.calendarRange.mockResolvedValue([]);
    api.listRecipes.mockResolvedValue(RECIPES);
    api.listMealKinds.mockResolvedValue(KINDS);
  });

  function short(orderBy: string | null, orderUrgency: string | null) {
    return [{
      mealPlanId: "p1", planDate: TOMORROW, mealKind: "Lunch", readyBy: "12:00:00",
      recipeName: "Bisi Bele Bath", status: "SHORT", shortfalls: [], orderBy, orderUrgency,
    }];
  }

  it("names the date while there is still slack", async () => {
    api.mealSufficiency.mockResolvedValue(short(isoIn(4), "IN_TIME"));
    render(<PlannerDayPage />);
    expect(await screen.findByText(/^Short · order by /)).toBeInTheDocument();
  });

  it("asks for the order on the day itself", async () => {
    api.mealSufficiency.mockResolvedValue(short(isoIn(0), "ORDER_TODAY"));
    render(<PlannerDayPage />);
    expect(await screen.findByText("Short · order today")).toBeInTheDocument();
  });

  // The state most likely to be built as one more step up a colour ramp. Once the date has gone,
  // asking somebody to order in time is asking for something that no longer exists, so the badge
  // says what is now true — and this assertion fails if it merely repeats "order today" in a
  // deeper red.
  it("stops advising and states the fact once the date has gone", async () => {
    api.mealSufficiency.mockResolvedValue(short(isoIn(-3), "TOO_LATE"));
    render(<PlannerDayPage />);
    expect(await screen.findByText("Short · won’t arrive in time")).toBeInTheDocument();
    expect(screen.queryByText(/order today/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/order by/i)).not.toBeInTheDocument();
  });

  // Nothing about the escalation may swallow the plain statement that the store cannot cover the
  // dish: a meal with no order-by date still reads short.
  it("falls back to the plain sentence when there is no date to show", async () => {
    api.mealSufficiency.mockResolvedValue(short(null, null));
    render(<PlannerDayPage />);
    expect(await screen.findByText("Short of ingredients")).toBeInTheDocument();
  });
});

/** A date `days` from now, as the API writes them. Tomorrow, so nothing is read-only. */
function isoIn(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return [d.getFullYear(), String(d.getMonth() + 1).padStart(2, "0"), String(d.getDate()).padStart(2, "0")].join("-");
}
