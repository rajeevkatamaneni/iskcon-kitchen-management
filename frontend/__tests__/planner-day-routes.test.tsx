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

function preparation(id: string, recipeId: string, recipeName: string) {
  return {
    id, planDate: TOMORROW, mealKind: "Lunch", readyBy: "12:00:00",
    recipeId, recipeName, targetYield: 133, dayType: "REGULAR", occasionName: null,
    status: "PLANNED", clientName: null, clientContact: null, venue: null, purpose: null,
    adults: 120, children: 20, seniors: 0, crewRequired: 8, kitchenNotes: null,
    actualServings: null, notMade: false, cookedAt: null, ekadashiAcknowledged: false,
    createdAt: "2026-08-20T10:00:00Z",
  };
}

function lunch(overrides: Record<string, unknown> = {}) {
  return {
    serviceId: null, planDate: TOMORROW, mealKind: "Lunch", readyBy: "12:00:00",
    adults: 120, children: 20, seniors: 0, plates: 133, crewRequired: 8,
    dayType: "REGULAR", occasionName: null,
    clientName: null, clientContact: null, venue: null, purpose: null, kitchenNotes: null,
    cardNumber: null, cardIssuedAt: null,
    recorded: false, recordedAt: null, recordedByName: null, recordingNote: null,
    dishes: [preparation("p1", "r1", "Bisi Bele Bath"), preparation("p2", "r2", "Kesari Bath")],
    ...overrides,
  };
}

const RECIPES = [
  { id: "r1", name: "Bisi Bele Bath", categoryName: "Khichadi", fastingCompatible: false,
    baseYieldQty: 100, baseYieldUnit: "SERVINGS", status: "ACTIVE", sattvicOverridden: false },
  { id: "r2", name: "Kesari Bath", categoryName: "Sweets", fastingCompatible: false,
    baseYieldQty: 100, baseYieldUnit: "SERVINGS", status: "ACTIVE", sattvicOverridden: false },
];

const KINDS = [
  { id: "k1", name: "Lunch", defaultReadyTime: "12:00:00", needsClient: false, needsVenue: false,
    needsPurpose: false, needsOccasion: false },
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
    expect(screen.getByRole("button", { name: /save changes/i })).toBeInTheDocument();
    // No second copy of the commit button at the foot.
    expect(screen.getAllByRole("button", { name: /save changes/i })).toHaveLength(1);

    // Both of the meal's preparations are on it, and the crew it takes came with them.
    expect(screen.getByRole("checkbox", { name: /bisi bele bath/i })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: /kesari bath/i })).toBeChecked();
    expect(screen.getByLabelText("People needed")).toHaveValue(8);
  });

  it("saves the whole meal and returns to the day with the confirmation waiting", async () => {
    render(<EditMealPage />);
    await screen.findByRole("heading", { level: 1, name: "Edit Lunch" });

    fireEvent.change(screen.getByLabelText("Adults"), { target: { value: "150" } });
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await vi.waitFor(() => expect(api.updateMealPlan).toHaveBeenCalledTimes(2));
    await vi.waitFor(() =>
      expect(push).toHaveBeenCalledWith(`/planner/${TOMORROW}?saved=Lunch`)
    );
  });

  it("will not reopen a meal that has already been recorded", async () => {
    api.mealServices.mockResolvedValue([lunch({ recorded: true, recordedByName: "Gopal Das" })]);
    render(<EditMealPage />);

    expect(await screen.findByText(/has been recorded/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /save changes/i })).not.toBeInTheDocument();
  });

  it("says so plainly when nothing of that kind is planned", async () => {
    api.mealServices.mockResolvedValue([]);
    render(<EditMealPage />);
    expect(
      await screen.findByText(/nothing of that kind is planned for this day/i)
    ).toBeInTheDocument();
  });
});

/** A date `days` from now, as the API writes them. Tomorrow, so nothing is read-only. */
function isoIn(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return [d.getFullYear(), String(d.getMonth() + 1).padStart(2, "0"), String(d.getDate()).padStart(2, "0")].join("-");
}
