import React from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";

/**
 * Epic 12, T-351: the composer's step 3, "What each kitchen cooks" — a band per kitchen.
 *
 * <p>Built from the approved mock (the "Building the meal" tab of `app/dev-kitchen-meal`, Rajeev
 * 2026-09-19). What is pinned here is what that mock and the Epic 12 brief decided:
 *
 * <ul>
 *   <li>A new meal starts with one band: the signed-in person's kitchen if it plans meals here, else
 *       the main kitchen, else the first kitchen that plans meals.</li>
 *   <li>"+ Add another kitchen" is an ARIA 1.2 combobox offering the kitchens that are active, plan
 *       meals here, and are not on the meal, in Settings order, each with its staff count.</li>
 *   <li>× on a band only while there is more than one. A band with dishes asks inside itself first;
 *       an empty one goes at once. Nothing moves a dish between kitchens.</li>
 *   <li>The save carries the kitchens in band order and every dish with its kitchen.</li>
 *   <li>A meal being edited opens on its kitchens in the order the server sent them.</li>
 *   <li>Each band's Rostered is asked for that kitchen, and only one band counts the volunteers.</li>
 * </ul>
 *
 * <p>Where a request body's shape matters, `Object.keys` is read rather than `objectContaining`: a
 * missing property and an explicit null read identically to that matcher (docs/work/README.md).
 */

const { authRef, api } = vi.hoisted(() => ({
  authRef: { current: { role: "KITCHEN_MANAGER", kitchenId: null as string | null } },
  api: {
    saveMeal: vi.fn(async (_input: Body, _t?: string) => ({ id: "meal-new" })),
    updateMeal: vi.fn(async (_id: string, _input: Body, _t?: string) => ({ id: "meal-lunch" })),
    ekadashiCheck: vi.fn(async () => ({ isEkadashi: false, compatible: true, offendingIngredients: [] as string[] })),
    suggestedCrew: vi.fn(async (_kind: string, _t?: string) => ({ crewRequired: null as number | null })),
    mealCrew: vi.fn(async (_from: string, _to: string, _t?: string) => [] as unknown[]),
    mealCrewAt: vi.fn(async (
      _date: string, _readyBy: string, _t?: string, _kitchenId?: string, _countVolunteers?: boolean
    ) => ({ planDate: "", readyBy: "", staffIn: 0, volunteers: 0, rostered: 0, staffNames: [] as string[] })),
    menuHistory: vi.fn(async () => ({
      occasionName: "", lastCookedOn: null, mealKind: null, preparationCount: 0, missingCount: 0,
      preparations: [] as { recipeId: string; recipeName: string }[],
    })),
    mealDayContext: vi.fn(async () => ({
      suggestedDayType: "REGULAR", occasionName: null, suggestedServings: null, isEkadashi: false,
    })),
    listOccasions: vi.fn(async () => [] as unknown[]),
    listRecipes: vi.fn(async () => [] as unknown[]),
    eventNameSuggestions: vi.fn(async () => [] as unknown[]),
    placesAvailable: vi.fn(async () => ({ available: false })),
    travelEstimateFor: vi.fn(async () => ({
      available: false, leaveBy: null, optimisticMinutes: null, pessimisticMinutes: null,
      guestsEatAt: null, reason: "NO_MAP_SERVICE",
    })),
  },
}));

type Body = Record<string, unknown> & {
  kitchens: Record<string, unknown>[];
  dishes: Record<string, unknown>[];
};

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn(), refresh: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ getToken: async () => "t", appUser: authRef.current }),
}));
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, ...api } };
});

import { MealComposer, type ComposerStatus } from "@/components/planner/MealComposer";

const DATE = "2026-09-25";

const RECIPES = [
  { id: "r-rice", name: "Basmati Rice", categoryName: "Rice", fastingCompatible: false,
    baseYieldQty: 100, baseYieldUnit: "KG", perHeadQty: 1, perHeadUnit: "KG", status: "ACTIVE" },
  { id: "r-dal", name: "Toor Dal", categoryName: "Dal", fastingCompatible: false,
    baseYieldQty: 100, baseYieldUnit: "L", perHeadQty: 1, perHeadUnit: "L", status: "ACTIVE" },
  { id: "r-halva", name: "Rava Halva", categoryName: "Sweets", fastingCompatible: false,
    baseYieldQty: 100, baseYieldUnit: "KG", perHeadQty: 1, perHeadUnit: "KG", status: "ACTIVE" },
];

const KINDS = [{ id: "k1", name: "Lunch", defaultReadyTime: "11:30:00", isEvent: false, needsOccasion: false }];

function kitchen(id: string, name: string, over: Record<string, unknown> = {}) {
  return {
    id, name, description: null, location: null, isMain: false, usesMealPlanner: true,
    inChargeUserId: null, inChargeName: null, staffCount: 2, contactPhone: null, status: "ACTIVE",
    createdAt: "2026-01-01T00:00:00Z", ...over,
  };
}

/** In Settings order, as `listKitchens` sends them: main first, then by name. */
const MAIN = kitchen("kit-main", "Main kitchen", { isMain: true, staffCount: 5 });
const BAKERY = kitchen("kit-bakery", "Bakery", { staffCount: 2 });
// Draws from the store instead of planning here (E10), so it is never offered.
const RESTAURANT = kitchen("kit-rest", "Govinda’s restaurant", { usesMealPlanner: false, staffCount: 4 });
// Closed, so never offered either — listKitchens(false) would not send it, and the composer checks anyway.
const OLD = kitchen("kit-old", "Old kitchen", { status: "ARCHIVED", staffCount: 0 });
const SWEETS = kitchen("kit-sweets", "Sweets kitchen", { staffCount: 3 });
const KITCHENS = [MAIN, BAKERY, RESTAURANT, OLD, SWEETS];

function Harness(props: Partial<React.ComponentProps<typeof MealComposer>>) {
  const [status, setStatus] = React.useState<ComposerStatus>({ busy: false, blocked: true, hint: null });
  return (
    <>
      <MealComposer
        date={DATE}
        recipes={RECIPES as never}
        mealKinds={KINDS as never}
        kitchens={KITCHENS as never}
        isEkadashi={false}
        onClose={vi.fn()}
        onPlanned={vi.fn()}
        formId="kitchens-form"
        onStatus={setStatus}
        {...props}
      />
      {status.hint && <p>{status.hint}</p>}
      <button type="submit" form="kitchens-form" disabled={status.busy || status.blocked}>
        {props.existing ? "Update this meal" : "Save this meal"}
      </button>
    </>
  );
}

/** The bands' names, top to bottom. */
function bandNames(): string[] {
  const step = screen.getByText("What each kitchen cooks").closest("section")!;
  return within(step).queryAllByRole("region").map((r) => r.getAttribute("aria-label") ?? "");
}

function band(name: string) {
  return screen.getByRole("region", { name });
}

function pick(recipe: RegExp, kitchenName: string) {
  fireEvent.change(screen.getByLabelText(`Add a dish to ${kitchenName}`), { target: { value: recipe.source } });
  fireEvent.click(within(band(kitchenName)).getByRole("button", { name: recipe }));
}

function openAddKitchen() {
  fireEvent.click(screen.getByRole("button", { name: "+ Add another kitchen" }));
  return screen.getByRole("combobox", { name: "Add another kitchen" });
}

function options() {
  return within(screen.getByRole("listbox")).queryAllByRole("option");
}

beforeEach(() => {
  authRef.current = { role: "KITCHEN_MANAGER", kitchenId: null };
  for (const fn of Object.values(api)) fn.mockClear();
  api.mealCrew.mockResolvedValue([]);
  api.mealCrewAt.mockReset().mockRejectedValue(new Error("not counted"));
  api.suggestedCrew.mockResolvedValue({ crewRequired: null });
  api.saveMeal.mockResolvedValue({ id: "meal-new" });
});

describe("the band a new meal starts with", () => {
  it("is the planner's own kitchen when it plans meals here", () => {
    authRef.current = { role: "KITCHEN_MANAGER", kitchenId: "kit-sweets" };
    render(<Harness />);
    expect(bandNames()).toEqual(["Sweets kitchen"]);
  });

  it("is the main kitchen for somebody with no kitchen of their own", () => {
    render(<Harness />);
    expect(bandNames()).toEqual(["Main kitchen"]);
  });

  it("is the main kitchen when the planner's own kitchen does not plan meals here", () => {
    authRef.current = { role: "KITCHEN_MANAGER", kitchenId: "kit-rest" };
    render(<Harness />);
    expect(bandNames()).toEqual(["Main kitchen"]);
  });

  it("is the main kitchen when the planner's own kitchen is archived", () => {
    authRef.current = { role: "KITCHEN_MANAGER", kitchenId: "kit-old" };
    render(<Harness />);
    expect(bandNames()).toEqual(["Main kitchen"]);
  });

  it("is the first kitchen that plans meals when the main kitchen does not", () => {
    const storeOnlyMain = { ...MAIN, usesMealPlanner: false };
    render(<Harness kitchens={[storeOnlyMain, RESTAURANT, BAKERY, SWEETS] as never} />);
    expect(bandNames()).toEqual(["Bakery"]);
  });

  it("arrives with the kitchens when they load after the composer", () => {
    const { rerender } = render(<Harness kitchens={[]} />);
    expect(bandNames()).toEqual([]);
    rerender(<Harness kitchens={KITCHENS as never} />);
    expect(bandNames()).toEqual(["Main kitchen"]);
  });
});

describe("+ Add another kitchen", () => {
  it("offers the active kitchens that plan meals and are not on the meal, in Settings order, with their staff", () => {
    render(<Harness />);
    const box = openAddKitchen();
    expect(box).toHaveFocus();
    expect(box).toHaveAttribute("aria-expanded", "true");

    // Not Main kitchen (on the meal), not the restaurant (draws from the store), not the archived one.
    expect(options().map((o) => o.textContent)).toEqual(["Bakery2 staff", "Sweets kitchen3 staff"]);
    expect(within(options()[1]).getByText("3 staff")).toBeInTheDocument();
  });

  it("narrows as it is typed into, and says when nothing matches", () => {
    render(<Harness />);
    const box = openAddKitchen();
    fireEvent.change(box, { target: { value: "swe" } });
    expect(options().map((o) => o.firstElementChild?.textContent)).toEqual(["Sweets kitchen"]);

    fireEvent.change(box, { target: { value: "zzz" } });
    expect(options()).toHaveLength(0);
    expect(screen.getByText("No kitchen matches “zzz”.")).toBeInTheDocument();
  });

  it("moves with the arrow keys and adds the highlighted kitchen on Enter, as a new band at the end", () => {
    render(<Harness />);
    const box = openAddKitchen();
    expect(options()[0]).toHaveAttribute("aria-selected", "true");
    expect(box.getAttribute("aria-activedescendant")).toBe(options()[0].id);

    fireEvent.keyDown(box, { key: "ArrowDown" });
    expect(options()[1]).toHaveAttribute("aria-selected", "true");
    expect(options()[0]).toHaveAttribute("aria-selected", "false");
    expect(box.getAttribute("aria-activedescendant")).toBe(options()[1].id);

    fireEvent.keyDown(box, { key: "Enter" });
    expect(bandNames()).toEqual(["Main kitchen", "Sweets kitchen"]);
    // Closed again, and Enter did not reach the form: nothing was saved.
    expect(screen.queryByRole("listbox")).toBeNull();
    expect(api.saveMeal).not.toHaveBeenCalled();

    // What is on the meal is not offered again.
    openAddKitchen();
    expect(options().map((o) => o.firstElementChild?.textContent)).toEqual(["Bakery"]);
  });

  it("closes on Escape and adds nothing", () => {
    render(<Harness />);
    const box = openAddKitchen();
    fireEvent.keyDown(box, { key: "ArrowDown" });
    fireEvent.keyDown(box, { key: "Escape" });
    expect(screen.queryByRole("listbox")).toBeNull();
    expect(bandNames()).toEqual(["Main kitchen"]);
    expect(screen.getByRole("button", { name: "+ Add another kitchen" })).toBeInTheDocument();
  });

  it("adds the kitchen pressed with the pointer", () => {
    render(<Harness />);
    openAddKitchen();
    fireEvent.pointerDown(options()[0]);
    expect(bandNames()).toEqual(["Main kitchen", "Bakery"]);
  });

  it("is not offered once every kitchen that plans meals is on the meal", () => {
    render(<Harness />);
    openAddKitchen();
    fireEvent.pointerDown(options()[0]);
    openAddKitchen();
    fireEvent.pointerDown(options()[0]);
    expect(bandNames()).toEqual(["Main kitchen", "Bakery", "Sweets kitchen"]);
    expect(screen.queryByRole("button", { name: "+ Add another kitchen" })).toBeNull();
  });
});

describe("a dish belongs to one kitchen", () => {
  it("is not offered to a second band once it is on the first", () => {
    render(<Harness />);
    openAddKitchen();
    fireEvent.pointerDown(options()[1]); // Sweets kitchen
    pick(/rava halva/i, "Sweets kitchen");

    fireEvent.change(screen.getByLabelText("Add a dish to Main kitchen"), { target: { value: "halva" } });
    expect(within(band("Main kitchen")).getByText("No recipe matches “halva”.")).toBeInTheDocument();
    // And the band counts what it cooks.
    expect(within(band("Sweets kitchen")).getByText("1 preparation")).toBeInTheDocument();
    expect(within(band("Main kitchen")).getByText("0 preparations")).toBeInTheDocument();
  });

  it("has no move action anywhere: it is taken off one band and added in another", () => {
    render(<Harness />);
    openAddKitchen();
    fireEvent.pointerDown(options()[1]);
    pick(/rava halva/i, "Main kitchen");
    // A word, not a substring: "Remove Main kitchen" is the band's × and is not a move.
    expect(screen.queryByRole("button", { name: /\bmove\b/i })).toBeNull();
    expect(document.body.textContent).not.toMatch(/\bmove\b/i);

    fireEvent.click(screen.getByRole("button", { name: "Take Rava Halva off Main kitchen" }));
    pick(/rava halva/i, "Sweets kitchen");
    expect(within(band("Sweets kitchen")).getByLabelText("Amount of Rava Halva")).toBeInTheDocument();
    expect(within(band("Main kitchen")).queryByLabelText("Amount of Rava Halva")).toBeNull();
  });
});

describe("removing a kitchen", () => {
  it("offers no × on the only band", () => {
    render(<Harness />);
    expect(within(band("Main kitchen")).queryByRole("button", { name: "Remove Main kitchen" })).toBeNull();
  });

  it("removes an empty band at once, and the last one left loses its ×", () => {
    render(<Harness />);
    openAddKitchen();
    fireEvent.pointerDown(options()[0]); // Bakery
    expect(within(band("Main kitchen")).getByRole("button", { name: "Remove Main kitchen" })).toBeInTheDocument();

    fireEvent.click(within(band("Bakery")).getByRole("button", { name: "Remove Bakery" }));
    expect(bandNames()).toEqual(["Main kitchen"]);
    expect(screen.queryByRole("group", { name: "Remove Bakery" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Remove Main kitchen" })).toBeNull();
  });

  it("asks inside the band before removing one with dishes, and Keep it keeps everything", () => {
    render(<Harness />);
    openAddKitchen();
    fireEvent.pointerDown(options()[1]); // Sweets kitchen
    pick(/rava halva/i, "Sweets kitchen");

    fireEvent.click(within(band("Sweets kitchen")).getByRole("button", { name: "Remove Sweets kitchen" }));
    const ask = within(band("Sweets kitchen")).getByRole("group", { name: "Remove Sweets kitchen" });
    expect(within(ask).getByText("Remove Sweets kitchen and its 1 dish?")).toBeInTheDocument();
    // Not removed yet.
    expect(bandNames()).toEqual(["Main kitchen", "Sweets kitchen"]);

    fireEvent.click(within(ask).getByRole("button", { name: "Keep it" }));
    expect(screen.queryByRole("group", { name: "Remove Sweets kitchen" })).toBeNull();
    expect(bandNames()).toEqual(["Main kitchen", "Sweets kitchen"]);
    expect(screen.getByLabelText("Amount of Rava Halva")).toBeInTheDocument();
  });

  it("removes the band and its dishes on Remove, and the dish can be offered again", () => {
    render(<Harness />);
    openAddKitchen();
    fireEvent.pointerDown(options()[1]);
    pick(/rava halva/i, "Sweets kitchen");
    pick(/toor dal/i, "Sweets kitchen");

    fireEvent.click(within(band("Sweets kitchen")).getByRole("button", { name: "Remove Sweets kitchen" }));
    const ask = within(band("Sweets kitchen")).getByRole("group", { name: "Remove Sweets kitchen" });
    expect(within(ask).getByText("Remove Sweets kitchen and its 2 dishes?")).toBeInTheDocument();
    fireEvent.click(within(ask).getByRole("button", { name: "Remove" }));

    expect(bandNames()).toEqual(["Main kitchen"]);
    expect(screen.queryByLabelText("Amount of Rava Halva")).toBeNull();
    pick(/rava halva/i, "Main kitchen");
    expect(within(band("Main kitchen")).getByLabelText("Amount of Rava Halva")).toBeInTheDocument();
  });
});

describe("what the save sends", () => {
  it("carries the kitchens in band order with their own People needed, and every dish with its kitchen", async () => {
    authRef.current = { role: "KITCHEN_MANAGER", kitchenId: "kit-sweets" };
    render(<Harness />);
    fireEvent.change(screen.getByLabelText("Adults"), { target: { value: "100" } });
    // Sweets kitchen first (the planner's own), Main kitchen added after it.
    openAddKitchen();
    fireEvent.pointerDown(within(screen.getByRole("listbox")).getByRole("option", { name: /Main kitchen/ }));
    expect(bandNames()).toEqual(["Sweets kitchen", "Main kitchen"]);

    // Added in the opposite order to the bands, so the payload's order is the bands', not the clicks'.
    pick(/basmati rice/i, "Main kitchen");
    pick(/rava halva/i, "Sweets kitchen");
    fireEvent.change(within(band("Main kitchen")).getByLabelText("People needed"), { target: { value: "6" } });
    fireEvent.change(within(band("Sweets kitchen")).getByLabelText("People needed"), { target: { value: "2" } });

    fireEvent.click(screen.getByRole("button", { name: "Save this meal" }));
    await waitFor(() => expect(api.saveMeal).toHaveBeenCalledTimes(1));
    const input = api.saveMeal.mock.calls[0][0];

    expect(Object.keys(input)).toContain("kitchens");
    expect(Object.keys(input)).not.toContain("crewRequired");
    expect(input.kitchens).toHaveLength(2);
    for (const k of input.kitchens) expect(Object.keys(k).sort()).toEqual(["crewRequired", "kitchenId"]);
    expect(input.kitchens).toEqual([
      { kitchenId: "kit-sweets", crewRequired: 2 },
      { kitchenId: "kit-main", crewRequired: 6 },
    ]);

    for (const d of input.dishes) expect(Object.keys(d).sort()).toEqual(["id", "kitchenId", "recipeId", "targetYield"]);
    expect(input.dishes).toEqual([
      { id: null, recipeId: "r-halva", targetYield: 100, kitchenId: "kit-sweets" },
      { id: null, recipeId: "r-rice", targetYield: 100, kitchenId: "kit-main" },
    ]);
  });

  it("sends an untouched People needed as null, and a counter pressed down to nought as null too", async () => {
    render(<Harness />);
    fireEvent.change(screen.getByLabelText("Adults"), { target: { value: "100" } });
    pick(/basmati rice/i, "Main kitchen");
    openAddKitchen();
    fireEvent.pointerDown(options()[0]); // Bakery
    fireEvent.change(within(band("Bakery")).getByLabelText("People needed"), { target: { value: "0" } });

    fireEvent.click(screen.getByRole("button", { name: "Save this meal" }));
    await waitFor(() => expect(api.saveMeal).toHaveBeenCalledTimes(1));
    expect(api.saveMeal.mock.calls[0][0].kitchens).toEqual([
      { kitchenId: "kit-main", crewRequired: null },
      { kitchenId: "kit-bakery", crewRequired: null },
    ]);
  });
});

/** A saved Lunch cooked by two kitchens, sent in the order the server chose for this viewer. */
function twoKitchenLunch() {
  const dish = (id: string, kitchenId: string, recipeId: string, recipeName: string) => ({
    id, mealId: "meal-lunch", kitchenId, recipeId, recipeName, targetYield: 100, targetYieldUnit: "KG",
    status: "PLANNED", actualServings: null, consumedQuantity: null, notMade: false,
    originalActualServings: null, originalConsumedQuantity: null, cookedAt: null,
    ekadashiAcknowledged: false, createdAt: "2026-09-20T10:00:00Z",
  });
  return {
    mealId: "meal-lunch", mealKindId: "k1", planDate: DATE, mealKind: "Lunch", readyBy: "11:30:00",
    adults: 100, children: 0, seniors: 0, plates: 100, crewRequired: 8,
    // Sweets kitchen first: the server put the viewer's own kitchen first. Never re-sorted here.
    kitchens: [
      { kitchenId: "kit-sweets", kitchenName: "Sweets kitchen", isMain: false, crewRequired: 2 },
      { kitchenId: "kit-main", kitchenName: "Main kitchen", isMain: true, crewRequired: 6 },
    ],
    dayType: "REGULAR", occasionName: null, eventName: null, isOutside: false, handover: null,
    contactName: null, contactPhone: null, deliveryAddress: null, deliverySubLocation: null,
    deliveryPlaceId: null, deliveryLatitude: null, deliveryLongitude: null, guestsEatAt: null,
    travelMinutes: null, travelMinutesSource: null, purpose: null, kitchenNotes: null, serverNotes: null,
    status: "PLANNED", cardNumber: null, cardIssuedAt: null,
    recorded: false, recordedAt: null, recordedByName: null, recordingNote: null,
    corrected: false, correctedAt: null, correctedByName: null, correctionNote: null,
    dishes: [dish("d1", "kit-main", "r-rice", "Basmati Rice"), dish("d2", "kit-sweets", "r-halva", "Rava Halva")],
    volunteerShift: null,
  };
}

describe("editing a meal two kitchens cook", () => {
  it("opens its bands in the order the server sent them, each with its own dishes and People needed", () => {
    render(<Harness existing={twoKitchenLunch() as never} />);
    expect(bandNames()).toEqual(["Sweets kitchen", "Main kitchen"]);
    expect(within(band("Sweets kitchen")).getByLabelText("Amount of Rava Halva")).toHaveValue(100);
    expect(within(band("Main kitchen")).getByLabelText("Amount of Basmati Rice")).toHaveValue(100);
    expect(within(band("Sweets kitchen")).getByLabelText("People needed")).toHaveValue(2);
    expect(within(band("Main kitchen")).getByLabelText("People needed")).toHaveValue(6);
  });

  it("updates with the kitchens in the same order and each kept dish under its own kitchen", async () => {
    render(<Harness existing={twoKitchenLunch() as never} />);
    fireEvent.click(screen.getByRole("button", { name: "Update this meal" }));
    await waitFor(() => expect(api.updateMeal).toHaveBeenCalledTimes(1));
    const input = api.updateMeal.mock.calls[0][1];
    expect(input.kitchens).toEqual([
      { kitchenId: "kit-sweets", crewRequired: 2 },
      { kitchenId: "kit-main", crewRequired: 6 },
    ]);
    expect(input.dishes).toEqual([
      { id: "d2", recipeId: "r-halva", targetYield: 100, kitchenId: "kit-sweets" },
      { id: "d1", recipeId: "r-rice", targetYield: 100, kitchenId: "kit-main" },
    ]);
  });
});

describe("who is rostered in each kitchen", () => {
  function countFor(kitchenId?: string, withVolunteers?: boolean) {
    const staff: Record<string, string[]> = {
      "kit-main": ["Govinda Das", "Madhava Das", "Keshava Das"],
      "kit-sweets": ["Radha Devi Dasi", "Lalita Devi Dasi"],
      "kit-bakery": ["Nanda Das"],
    };
    const names = staff[kitchenId ?? ""] ?? [];
    const volunteers = withVolunteers ? 2 : 0;
    return {
      planDate: DATE, readyBy: "11:30:00", staffIn: names.length, volunteers,
      rostered: names.length + volunteers, staffNames: names,
    };
  }

  beforeEach(() => {
    api.mealCrewAt.mockReset().mockImplementation(async (_d, _r, _t, kitchenId, countVolunteers) =>
      countFor(kitchenId, countVolunteers));
  });

  it("counts the volunteers in the main kitchen's band only, even when it was added second", async () => {
    authRef.current = { role: "KITCHEN_MANAGER", kitchenId: "kit-sweets" };
    render(<Harness />);
    openAddKitchen();
    fireEvent.pointerDown(within(screen.getByRole("listbox")).getByRole("option", { name: /Main kitchen/ }));

    await waitFor(() =>
      expect(api.mealCrewAt).toHaveBeenCalledWith(DATE, "11:30", "t", "kit-main", true));
    const calls = api.mealCrewAt.mock.calls;
    // Every question about the Sweets kitchen said "no volunteers" once the main kitchen was on the meal.
    const sweetsCalls = calls.filter((c) => c[3] === "kit-sweets");
    expect(sweetsCalls[sweetsCalls.length - 1][4]).toBe(false);
    expect(calls.some((c) => c[3] === "kit-main" && c[4] === false)).toBe(false);

    // The main band says its volunteers, in the composer's words; the other band leaves them out.
    expect(await within(band("Main kitchen")).findByText("3 staff · 2 volunteers")).toBeInTheDocument();
    expect(await within(band("Sweets kitchen")).findByText("2 staff")).toBeInTheDocument();
    expect(within(band("Sweets kitchen")).getByText("Radha Devi Dasi, Lalita Devi Dasi")).toBeInTheDocument();
    expect(within(band("Main kitchen")).getByText("Govinda Das, Madhava Das, Keshava Das")).toBeInTheDocument();
    expect(within(band("Sweets kitchen")).getByText("Rostered in Sweets kitchen")).toBeInTheDocument();
  });

  it("counts them in the first band when the main kitchen is not cooking", async () => {
    authRef.current = { role: "KITCHEN_MANAGER", kitchenId: "kit-sweets" };
    render(<Harness />);
    openAddKitchen();
    fireEvent.pointerDown(within(screen.getByRole("listbox")).getByRole("option", { name: /Bakery/ }));

    await waitFor(() => expect(api.mealCrewAt).toHaveBeenCalledWith(DATE, "11:30", "t", "kit-bakery", false));
    expect(api.mealCrewAt).toHaveBeenCalledWith(DATE, "11:30", "t", "kit-sweets", true);
    expect(api.mealCrewAt.mock.calls.some((c) => c[3] === "kit-bakery" && c[4] === true)).toBe(false);
  });

  it("goes amber and offers Ask for volunteers only in the band that is short", async () => {
    render(<Harness />);
    openAddKitchen();
    fireEvent.pointerDown(within(screen.getByRole("listbox")).getByRole("option", { name: /Sweets kitchen/ }));
    fireEvent.change(within(band("Main kitchen")).getByLabelText("People needed"), { target: { value: "4" } });
    fireEvent.change(within(band("Sweets kitchen")).getByLabelText("People needed"), { target: { value: "3" } });

    // Main: 3 staff + 2 volunteers = 5 of 4, covered. Sweets: 2 staff of 3, short by one.
    const main = await within(band("Main kitchen")).findByText("3 staff · 2 volunteers · 5 of 4");
    const sweets = await within(band("Sweets kitchen")).findByText("2 staff · 2 of 3");
    expect(main.className).not.toContain("text-warning");
    expect(sweets.className).toContain("text-warning");
    expect(within(band("Main kitchen")).queryByRole("button", { name: /ask for volunteers/i })).toBeNull();
    expect(within(band("Sweets kitchen")).getByRole("button", { name: /ask for volunteers/i })).toBeInTheDocument();
  });

  it("opens the meal's one volunteer layer on the whole meal's shortfall", async () => {
    render(<Harness />);
    openAddKitchen();
    fireEvent.pointerDown(within(screen.getByRole("listbox")).getByRole("option", { name: /Sweets kitchen/ }));
    fireEvent.change(within(band("Main kitchen")).getByLabelText("People needed"), { target: { value: "7" } });
    fireEvent.change(within(band("Sweets kitchen")).getByLabelText("People needed"), { target: { value: "3" } });
    await within(band("Main kitchen")).findByText("3 staff · 2 volunteers · 5 of 7");
    await within(band("Sweets kitchen")).findByText("2 staff · 2 of 3");

    // Two short in Main, one in Sweets: the one shift asks for three, whichever band opened it.
    fireEvent.click(within(band("Sweets kitchen")).getByRole("button", { name: /ask for volunteers/i }));
    const capacity = screen.getByRole("dialog").querySelector('[name="capacity"]') as HTMLInputElement;
    expect(capacity.value).toBe("3");
  });
});
