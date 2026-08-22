import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

// Typed like the real calls, so the assertions read what was sent rather than casting past an
// untyped mock.
const { mealServices, recordMeal, updateMealPlan, requestJobCard, jobCardLanguages } = vi.hoisted(
  () => ({
    mealServices: vi.fn(async (_from: string, _to: string, _token?: string) => [] as unknown[]),
    recordMeal: vi.fn(async (_input: Record<string, unknown>, _token?: string) => ({})),
    updateMealPlan: vi.fn(
      async (_id: string, _input: Record<string, unknown>, _token?: string) => undefined
    ),
    requestJobCard: vi.fn(async () => ({
      documentId: "d1",
      cardNumber: "LC-2026-0142",
      status: "PENDING",
    })),
    // The temple works in Kannada and its recipes are translated into it, so the picker opens there.
    jobCardLanguages: vi.fn(async (_date: string, _kind: string, _token?: string) => ({
      languages: ["en", "kn"],
      defaultLanguage: "kn",
    })),
  })
);

vi.mock("@/lib/auth-context", () => ({ useAuth: () => ({ getToken: async () => "t" }) }));
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      mealServices,
      recordMeal,
      updateMealPlan,
      requestJobCard,
      jobCardLanguages,
    },
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
    targetYield: servings,
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
    jobCardLanguages.mockClear();
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
    fireEvent.change(screen.getByLabelText("How much Bisi Bele Bath to make"), {
      target: { value: "300" },
    });
    fireEvent.click(screen.getByRole("button", { name: /^save$/i }));

    await vi.waitFor(() => expect(updateMealPlan).toHaveBeenCalledTimes(1));
    // The same row: id "m1", now carrying a different recipe and a different figure.
    expect(updateMealPlan.mock.calls[0][0]).toBe("m1");
    expect(updateMealPlan.mock.calls[0][1]).toMatchObject({ recipeId: "r2", targetYield: 300 });
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
    expect(screen.getByRole("button", { name: /^job card$/i })).toBeInTheDocument();
    // Nor is there anything to edit: the whole meal is as fixed as its preparations.
    expect(screen.queryByRole("link", { name: /^edit$/i })).not.toBeInTheDocument();
  });

  it("offers only the languages this meal's recipes are actually translated into", async () => {
    await open([lunch()]);

    // English is always there because it is the source text. Kannada is there because a translation
    // exists. The other twenty scheduled languages are not, because printing one would give a cook
    // an English appendix under a Kannada heading.
    const picker = await screen.findByLabelText("Recipe language for Lunch");
    const offered = Array.from(picker.querySelectorAll("option")).map((o) => o.textContent);
    expect(offered).toEqual(["English", "Kannada"]);
  });

  it("prints the recipes in the temple's language by default, and in another when asked", async () => {
    await open([lunch()]);

    // Nobody picked, so the picker opens on the temple's own language — the default, not the rule.
    const picker = await screen.findByLabelText("Recipe language for Lunch");
    expect(picker).toHaveValue("kn");

    fireEvent.change(picker, { target: { value: "en" } });
    fireEvent.click(screen.getByRole("button", { name: /download pdf/i }));

    await vi.waitFor(() => expect(requestJobCard).toHaveBeenCalledTimes(1));
    expect(requestJobCard.mock.calls[0].slice(0, 3)).toEqual(["2026-08-21", "Lunch", "en"]);
  });

  it("asks for the worksheet on its own when the recipes are turned off", async () => {
    await open([lunch()]);

    await screen.findByLabelText("Recipe language for Lunch");
    fireEvent.click(screen.getByLabelText("Include the recipes with the Lunch card"));

    // The language picker goes with them: there is nothing left for it to choose the language of.
    expect(screen.queryByLabelText("Recipe language for Lunch")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /download pdf/i }));
    await vi.waitFor(() => expect(requestJobCard).toHaveBeenCalledTimes(1));
    expect(requestJobCard.mock.calls[0].slice(0, 3)).toEqual(["2026-08-21", "Lunch", "none"]);
  });

  it("opens the print dialogue itself once the card has laid out", async () => {
    await open([lunch()]);

    const printed = vi.fn();
    const w = {
      document: { write: vi.fn(), close: vi.fn(), readyState: "complete" },
      focus: vi.fn(),
      print: printed,
      addEventListener: vi.fn(),
    };
    vi.stubGlobal("open", vi.fn(() => w));
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => ({ ok: true, text: async () => "<html></html>" }))
    );

    fireEvent.click(screen.getByRole("button", { name: /^job card$/i }));

    // A button called "Print job card" that opens a window and stops has asked the person to do the
    // one thing they already said they wanted.
    await vi.waitFor(() => expect(printed).toHaveBeenCalledTimes(1));
    vi.unstubAllGlobals();
  });
});
