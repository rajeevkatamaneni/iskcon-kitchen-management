import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

// Typed like the real calls, so the assertions read what was sent rather than casting past an
// untyped mock.
const { mealServices, recordMeal, updateMealPlan, requestJobCard } = vi.hoisted(() => ({
  mealServices: vi.fn(async (_from: string, _to: string, _token?: string) => [] as unknown[]),
  recordMeal: vi.fn(async (_input: Record<string, unknown>, _token?: string) => ({})),
  updateMealPlan: vi.fn(async (_id: string, _input: Record<string, unknown>, _token?: string) => undefined),
  requestJobCard: vi.fn(async () => ({ documentId: "d1", cardNumber: "LC-2026-0142", status: "PENDING" })),
}));

vi.mock("@/lib/auth-context", () => ({ useAuth: () => ({ getToken: async () => "t" }) }));
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, mealServices, recordMeal, updateMealPlan, requestJobCard },
  };
});
// The component's own query hook, driven straight off the mocked list call. The factory is async so
// React can be imported inside it — it is hoisted above this file's imports, so a top-level binding
// would not exist yet when it runs.
vi.mock("@/lib/use-authed-query", async () => {
  const { useEffect, useState } = await import("react");
  return {
    useAuthedQuery: (fn: (t?: string) => Promise<unknown>) => {
      const [data, setData] = useState<unknown>(null);
      useEffect(() => {
        let live = true;
        fn("t").then((d: unknown) => {
          if (live) setData(d);
        });
        return () => {
          live = false;
        };
      }, [fn]);
      return { data, error: null, loading: data === null, reload: vi.fn() };
    },
  };
});

import { MealServices } from "@/components/planner/MealServices";

const RECIPES = [
  { id: "r1", name: "Bisi Bele Bath", categoryName: "Khichadi", fastingCompatible: false,
    baseYieldQty: 100, baseYieldUnit: "SERVINGS", status: "ACTIVE", sattvicOverridden: false },
  { id: "r2", name: "Kesari Bath", categoryName: "Sweets", fastingCompatible: false,
    baseYieldQty: 100, baseYieldUnit: "SERVINGS", status: "ACTIVE", sattvicOverridden: false },
];

function dish(id: string, recipeId: string, recipeName: string, servings: number) {
  return {
    id,
    planDate: "2026-08-21",
    mealKind: "Lunch",
    readyBy: "12:00:00",
    recipeId,
    recipeName,
    targetServings: servings,
    dayType: "REGULAR",
    occasionName: null,
    status: "PLANNED",
    clientName: null,
    clientContact: null,
    venue: null,
    purpose: null,
    adults: 200,
    children: 40,
    seniors: 30,
    kitchenNotes: null,
    actualServings: null,
    notMade: false,
    cookedAt: null,
    ekadashiAcknowledged: false,
    createdAt: "2026-08-20T10:00:00Z",
  };
}

function lunch(overrides: Record<string, unknown> = {}) {
  return {
    serviceId: null,
    planDate: "2026-08-21",
    mealKind: "Lunch",
    readyBy: "12:00:00",
    adults: 200,
    children: 40,
    seniors: 30,
    plates: 248,
    dayType: "REGULAR",
    occasionName: null,
    clientName: null,
    clientContact: null,
    venue: null,
    purpose: null,
    kitchenNotes: null,
    cardNumber: null,
    cardIssuedAt: null,
    recorded: false,
    recordedAt: null,
    recordedByName: null,
    recordingNote: null,
    dishes: [dish("m1", "r1", "Bisi Bele Bath", 248), dish("m2", "r2", "Kesari Bath", 300)],
    ...overrides,
  };
}

async function open(meals: unknown[]) {
  mealServices.mockResolvedValue(meals);
  render(
    <MealServices
      date="2026-08-21"
      sufficiency={new Map()}
      recipes={RECIPES as never}
      readOnly={false}
      onChanged={vi.fn()}
      onError={vi.fn()}
    />
  );
  await screen.findByText("Lunch");
}

describe("the day's meals", () => {
  beforeEach(() => {
    recordMeal.mockClear();
    updateMealPlan.mockClear();
    requestJobCard.mockClear();
  });

  it("groups the day into meals and counts plates per meal, never per dish", async () => {
    await open([lunch()]);

    // Two dishes at 248 and 300 is 248 plates, not 548 — the head count, not a sum.
    expect(screen.getByText(/248 plates/)).toBeInTheDocument();
    expect(screen.getByText("Bisi Bele Bath")).toBeInTheDocument();
    expect(screen.getByText("Kesari Bath")).toBeInTheDocument();
  });

  it("has no per-dish mark-cooked button; recording is one form for the whole meal", async () => {
    await open([lunch()]);

    expect(screen.queryByRole("button", { name: /mark cooked/i })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /record what went out/i }));

    // Every dish is listed, prefilled with what was planned and editable to what went out.
    const first = screen.getByLabelText("Servings of Bisi Bele Bath");
    expect(first).toHaveValue(248);
    fireEvent.change(first, { target: { value: "220" } });
    fireEvent.click(screen.getByLabelText("Kesari Bath was not made"));

    fireEvent.click(screen.getByRole("button", { name: /record this meal/i }));
    await vi.waitFor(() => expect(recordMeal).toHaveBeenCalledTimes(1));

    expect(recordMeal.mock.calls[0][0]).toMatchObject({
      planDate: "2026-08-21",
      mealKind: "Lunch",
      dishes: [
        { mealPlanId: "m1", actualServings: 220, notMade: false },
        { mealPlanId: "m2", actualServings: null, notMade: true },
      ],
    });
  });

  it("swaps a dish in place rather than cancelling and re-adding it", async () => {
    await open([lunch()]);

    fireEvent.click(screen.getAllByRole("button", { name: /swap or edit/i })[0]);
    fireEvent.change(screen.getByLabelText("Recipe for Bisi Bele Bath"), { target: { value: "r2" } });
    fireEvent.change(screen.getByLabelText("Planned servings of Bisi Bele Bath"), {
      target: { value: "300" },
    });
    fireEvent.click(screen.getByRole("button", { name: /^save$/i }));

    await vi.waitFor(() => expect(updateMealPlan).toHaveBeenCalledTimes(1));
    // The same row: id "m1", now carrying a different recipe and a different figure.
    expect(updateMealPlan.mock.calls[0][0]).toBe("m1");
    expect(updateMealPlan.mock.calls[0][1]).toMatchObject({ recipeId: "r2", targetServings: 300 });
  });

  it("offers no way to change or record a meal that has already been recorded", async () => {
    await open([
      lunch({
        recorded: true,
        recordedByName: "Gopal Das",
        cardNumber: "LC-2026-0142",
        dishes: [{ ...dish("m1", "r1", "Bisi Bele Bath", 248), status: "COOKED", actualServings: 220 }],
      }),
    ]);

    expect(screen.getByText("LC-2026-0142")).toBeInTheDocument();
    expect(screen.getByText(/220 went out/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /record what went out/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /swap or edit/i })).not.toBeInTheDocument();

    // The card is still printable — a signed sheet is filed against it long after the meal.
    expect(screen.getByRole("button", { name: /print job card/i })).toBeInTheDocument();
  });

  it("prints the job card in the temple's language by default, and in another when asked", async () => {
    await open([lunch()]);

    const picker = screen.getByLabelText("Job card language for Lunch");
    expect(picker).toHaveValue("");

    fireEvent.change(picker, { target: { value: "kn" } });
    fireEvent.click(screen.getByRole("button", { name: /download pdf/i }));

    await vi.waitFor(() => expect(requestJobCard).toHaveBeenCalledTimes(1));
    expect(requestJobCard.mock.calls[0].slice(0, 3)).toEqual(["2026-08-21", "Lunch", "kn"]);
  });
});
