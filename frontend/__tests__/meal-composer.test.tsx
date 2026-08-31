import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

// Typed like the real call, so the assertions below can read what was sent rather than casting
// their way past an untyped mock — which is how this file passed locally and failed in CI.
const {
  createMealPlan, updateMealPlan, cancelMealPlan,
  suggestedCrew, mealCrew, menuHistory, mealDayContext, listOccasions,
} = vi.hoisted(() => ({
  createMealPlan: vi.fn(async (_input: Record<string, unknown>, _token?: string) => ({ id: "m1" })),
  updateMealPlan: vi.fn(async (_id: string, _input: Record<string, unknown>, _token?: string) => undefined),
  cancelMealPlan: vi.fn(async (_id: string, _token?: string) => undefined),
  // What the last three ordinary meals of this kind took (Q11). Null by default: most of these
  // tests are about a temple that has never recorded one, where the field opens empty.
  suggestedCrew: vi.fn(async (_kind: string, _token?: string) => ({ crewRequired: null as number | null })),
  mealCrew: vi.fn(async (_from: string, _to: string, _token?: string) => [] as unknown[]),
  menuHistory: vi.fn(async (_occasion: string, _before: string, _token?: string) => ({
    occasionName: "Janmashtami",
    lastCookedOn: null as string | null,
    mealKind: null as string | null,
    preparationCount: 0,
    missingCount: 0,
    preparations: [] as { recipeId: string; recipeName: string }[],
  })),
  mealDayContext: vi.fn(async (_date: string, _token?: string) => ({
    suggestedDayType: "FESTIVAL",
    occasionName: "Janmashtami" as string | null,
    suggestedServings: null as number | null,
    isEkadashi: false,
  })),
  listOccasions: vi.fn(async (_token?: string) => [
    { id: "o1", name: "Janmashtami", type: "COMPUTED", matchText: null, fixedMonth: null,
      fixedDay: null, defaultServings: null, notes: null, seeded: true },
  ]),
}));

vi.mock("@/lib/auth-context", () => ({ useAuth: () => ({ getToken: async () => "t" }) }));
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      createMealPlan, updateMealPlan, cancelMealPlan,
      suggestedCrew, mealCrew, menuHistory, mealDayContext, listOccasions,
    },
  };
});

import { MealComposer } from "@/components/planner/MealComposer";

const RECIPES = [
  { id: "r1", name: "Bisi Bele Bath", categoryName: "Khichadi", fastingCompatible: false,
    baseYieldQty: 100, baseYieldUnit: "KG", perHeadQty: 1, perHeadUnit: "KG", status: "ACTIVE", sattvicOverridden: false },
  { id: "r2", name: "Kesari Bath", categoryName: "Sweets", fastingCompatible: false,
    baseYieldQty: 100, baseYieldUnit: "KG", perHeadQty: 1, perHeadUnit: "KG", status: "ACTIVE", sattvicOverridden: false },
];

const KINDS = [
  { id: "k1", name: "Lunch", defaultReadyTime: "12:00:00", needsClient: false, needsVenue: false,
    needsPurpose: false },
  { id: "k2", name: "Catering", defaultReadyTime: null, needsClient: true, needsVenue: true,
    needsPurpose: false },
  // An outside event goes somewhere and has a reason for going there (B6).
  { id: "k3", name: "Outside event", defaultReadyTime: null, needsClient: false, needsVenue: true,
    needsPurpose: true },
  // A feast is a kind of meal, not a kind of day (item 26): on Janmashtami the temple serves an
  // ordinary breakfast and then the feast, and only a per-meal fact can say which is which.
  { id: "k4", name: "Festival feast", defaultReadyTime: null, needsClient: false, needsVenue: false,
    needsPurpose: false, needsOccasion: true },
];

function openAndGet(props: Partial<React.ComponentProps<typeof MealComposer>> = {}) {
  return render(
    <MealComposer
      date="2026-08-16"
      recipes={RECIPES as never}
      mealKinds={KINDS as never}
      isEkadashi={false}
      onClose={vi.fn()}
      onPlanned={vi.fn()}
      {...props}
    />
  );
}

function open(props: Partial<React.ComponentProps<typeof MealComposer>> = {}) {
  render(
    <MealComposer
      date="2026-08-16"
      recipes={RECIPES as never}
      mealKinds={KINDS as never}
      isEkadashi={false}
      onClose={vi.fn()}
      onPlanned={vi.fn()}
      {...props}
    />
  );
}

describe("planning a meal", () => {
  beforeEach(() => createMealPlan.mockClear());

  it("works the head count out the way a temple does", () => {
    open();
    // 100 adults to start; children count for 0.6 of a portion and seniors for 0.8.
    fireEvent.change(screen.getByLabelText("Adults"), { target: { value: "200" } });
    fireEvent.change(screen.getByLabelText("Children"), { target: { value: "40" } });
    fireEvent.change(screen.getByLabelText("Seniors"), { target: { value: "30" } });

    expect(screen.getByText("248 people")).toBeInTheDocument();
  });

  it("gives every preparation the head count, and never overwrites one set by hand", () => {
    open();
    fireEvent.change(screen.getByLabelText("Adults"), { target: { value: "200" } });

    fireEvent.click(screen.getByRole("checkbox", { name: /bisi bele bath/i }));
    fireEvent.click(screen.getByRole("checkbox", { name: /kesari bath/i }));

    const sweet = screen.getByLabelText("How much Kesari Bath to make");
    expect(sweet).toHaveValue(200);

    // The sweet always goes first, so the planner raises it deliberately.
    fireEvent.change(sweet, { target: { value: "300" } });

    // More people arrive: the untouched dish follows, the judged one holds.
    fireEvent.change(screen.getByLabelText("Adults"), { target: { value: "250" } });
    expect(screen.getByLabelText("How much Bisi Bele Bath to make")).toHaveValue(250);
    expect(screen.getByLabelText("How much Kesari Bath to make")).toHaveValue(300);
  });

  it("saves one meal per preparation, each with its own servings", async () => {
    open();
    fireEvent.click(screen.getByRole("checkbox", { name: /bisi bele bath/i }));
    fireEvent.click(screen.getByRole("checkbox", { name: /kesari bath/i }));
    fireEvent.change(screen.getByLabelText("How much Kesari Bath to make"), { target: { value: "150" } });
    fireEvent.change(screen.getByLabelText(/notes for the kitchen/i), {
      target: { value: "Cook the kesari thin." },
    });

    fireEvent.click(screen.getByRole("button", { name: /save this meal/i }));
    await vi.waitFor(() => expect(createMealPlan).toHaveBeenCalledTimes(2));

    const [first, second] = createMealPlan.mock.calls.map(([input]) => input);
    expect(first).toMatchObject({ recipeId: "r1", targetYield: 100, mealKind: "Lunch", readyBy: "12:00" });
    expect(second).toMatchObject({ recipeId: "r2", targetYield: 150, kitchenNotes: "Cook the kesari thin." });
  });

  it("will not save until something is being cooked", () => {
    open();
    expect(screen.getByRole("button", { name: /save this meal/i })).toBeDisabled();
    expect(screen.getByText(/pick at least one preparation/i)).toBeInTheDocument();
  });

  it("asks for the things a catering order cannot go out without", () => {
    open();
    fireEvent.click(screen.getByRole("button", { name: "Catering" }));
    fireEvent.click(screen.getByRole("checkbox", { name: /bisi bele bath/i }));

    // Catering carries no default time, and has to say who it is for and where it is going.
    expect(screen.getByRole("button", { name: /save this meal/i })).toBeDisabled();
    expect(screen.getByText(/pick the time it must be ready/i)).toBeInTheDocument();
  });

  it("asks an outside event what it is for, and asks nothing else the same question", async () => {
    open();
    // Lunch is asked nothing of the sort: the requirement belongs to the kind, not to the app.
    expect(screen.queryByLabelText(/what is it for/i)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Outside event" }));
    fireEvent.click(screen.getByRole("checkbox", { name: /bisi bele bath/i }));
    fireEvent.change(screen.getByLabelText(/ready by/i), { target: { value: "17:00" } });
    fireEvent.change(screen.getByLabelText(/where is it going/i), {
      target: { value: "Jayanagar school hall" },
    });

    // Venue and time are given; the purpose is still missing, and it says which.
    expect(screen.getByRole("button", { name: /save this meal/i })).toBeDisabled();
    expect(screen.getByText(/say what it is for/i)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/what is it for/i), {
      target: { value: "Bhagavad-gita reading" },
    });
    fireEvent.click(screen.getByRole("button", { name: /save this meal/i }));

    await vi.waitFor(() => expect(createMealPlan).toHaveBeenCalledTimes(1));
    expect(createMealPlan.mock.calls[0][0]).toMatchObject({
      mealKind: "Outside event",
      venue: "Jayanagar school hall",
      purpose: "Bhagavad-gita reading",
    });
  });
});

describe("item 23 — the row of fields keeps its shape", () => {
  it("puts every field in steps 1, 2 and 4 into the row's own tracks", () => {
    // The bug this replaces was not a wrong value of align-items but the use of align-items at all:
    // a readout with its label inside its box can never line up with a counter that has one above.
    // So what is asserted is that no field is laying itself out — every one of them is in a track
    // the row owns. jsdom has no layout, and a pixel assertion here would prove nothing.
    const { container } = openAndGet();
    const rows = container.querySelectorAll("[data-field-row]");
    // What kind of meal, who is expected, and who will run it.
    expect(rows.length).toBe(3);
    rows.forEach((row) => {
      const cells = row.querySelectorAll(":scope > *");
      expect(cells.length).toBeGreaterThan(0);
      cells.forEach((cell) => {
        expect(cell.getAttribute("data-field-row-cell")).not.toBeNull();
        expect(cell.className).toContain("grid-rows-subgrid");
        expect(cell.className).toContain("row-span-3");
      });
    });
  });

  it("gives the Scales-to readout a label above its box, not inside it", () => {
    const { container } = openAndGet();
    const label = screen.getByText("Cooking for");
    expect(label.className).toContain("pl-field-inset");
    // Its box is its sibling in the row, not its parent.
    expect(label.nextElementSibling?.textContent).toContain("people");
    expect(container.innerHTML).not.toContain("&nbsp;");
  });
});

// --- item 24: how many people it takes to cook the meal -----------------------

describe("who will run it", () => {
  beforeEach(() => {
    createMealPlan.mockClear();
    suggestedCrew.mockClear();
    suggestedCrew.mockResolvedValue({ crewRequired: null });
    mealCrew.mockResolvedValue([]);
  });

  it("opens the counter on the median of the last three, and empty where there is none", async () => {
    suggestedCrew.mockResolvedValue({ crewRequired: 8 });
    open();

    await vi.waitFor(() => expect(screen.getByLabelText("People needed")).toHaveValue(8));
    expect(suggestedCrew.mock.calls[0][0]).toBe("Lunch");
  });

  it("leaves the counter empty when the temple has never recorded a meal of that kind", async () => {
    open();
    await vi.waitFor(() => expect(suggestedCrew).toHaveBeenCalled());
    // Empty is the honest answer. A made-up number would not be.
    expect(screen.getByLabelText("People needed")).toHaveValue(null);
  });

  it("reads out who is rostered, and goes quietly amber when they are short", async () => {
    suggestedCrew.mockResolvedValue({ crewRequired: 8 });
    mealCrew.mockResolvedValue([
      { planDate: "2026-08-16", mealKind: "Lunch", readyBy: "12:00:00", crewRequired: 8,
        staffIn: 3, volunteers: 2, rostered: 5, shortOfCrew: true },
    ]);
    open();

    const readout = await screen.findByText("3 staff · 2 volunteers · 5 of 8");
    expect(readout.className).toContain("text-warning");

    // Never a block: a meal is planned weeks before anybody is rostered.
    fireEvent.click(screen.getByRole("checkbox", { name: /bisi bele bath/i }));
    expect(screen.getByRole("button", { name: /save this meal/i })).not.toBeDisabled();
  });

  it("sends the number with every preparation of the meal", async () => {
    suggestedCrew.mockResolvedValue({ crewRequired: 6 });
    open();
    await vi.waitFor(() => expect(screen.getByLabelText("People needed")).toHaveValue(6));

    fireEvent.click(screen.getByRole("button", { name: /one more people needed/i }));
    fireEvent.click(screen.getByRole("checkbox", { name: /bisi bele bath/i }));
    fireEvent.click(screen.getByRole("button", { name: /save this meal/i }));

    await vi.waitFor(() => expect(createMealPlan).toHaveBeenCalledTimes(1));
    expect(createMealPlan.mock.calls[0][0]).toMatchObject({ crewRequired: 7 });
  });
});

// --- items 26 and 26b: a festival feast, and last year's menu -----------------

describe("a festival feast", () => {
  beforeEach(() => {
    createMealPlan.mockClear();
    menuHistory.mockClear();
    mealDayContext.mockClear();
    mealDayContext.mockResolvedValue({
      suggestedDayType: "FESTIVAL", occasionName: "Janmashtami", suggestedServings: null,
      isEkadashi: false,
    });
    suggestedCrew.mockResolvedValue({ crewRequired: null });
    mealCrew.mockResolvedValue([]);
    menuHistory.mockResolvedValue({
      occasionName: "Janmashtami",
      lastCookedOn: null,
      mealKind: null,
      preparationCount: 0,
      missingCount: 0,
      preparations: [],
    });
  });

  it("asks a feast which festival it is for, and asks nothing else", async () => {
    open();
    expect(screen.queryByLabelText(/what is the occasion/i)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Festival feast" }));
    // The calendar's answer for the date, and still a box: a temple anniversary the calendar has
    // never heard of is a feast the temple takes just as much pride in.
    await vi.waitFor(() =>
      expect(screen.getByLabelText(/what is the occasion/i)).toHaveValue("Janmashtami")
    );
    // Pickable as well as prefilled: the temple's own named occasions are behind the box.
    expect(
      document.querySelector('#meal-occasions option[value="Janmashtami"]')
    ).not.toBeNull();
  });

  it("will not save a feast with no occasion on it", async () => {
    mealDayContext.mockResolvedValue({
      suggestedDayType: "REGULAR", occasionName: null, suggestedServings: null, isEkadashi: false,
    });
    open();
    fireEvent.click(screen.getByRole("button", { name: "Festival feast" }));
    fireEvent.click(screen.getByRole("checkbox", { name: /bisi bele bath/i }));
    fireEvent.change(screen.getByLabelText("Ready by"), { target: { value: "12:00" } });

    expect(await screen.findByText(/name the occasion this feast is for/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /save this meal/i })).toBeDisabled();
  });

  it("offers last year's menu, says what is missing from it, and puts it in with one press", async () => {
    menuHistory.mockResolvedValue({
      occasionName: "Janmashtami",
      lastCookedOn: "2025-08-26",
      mealKind: "Festival feast",
      preparationCount: 4,
      missingCount: 2,
      preparations: [
        { recipeId: "r1", recipeName: "Bisi Bele Bath" },
        { recipeId: "r2", recipeName: "Kesari Bath" },
      ],
    });
    open();
    fireEvent.click(screen.getByRole("button", { name: "Festival feast" }));

    // "Last Janmashtami, 26 August 2025 — 18 preparations", with the date in the reader's locale.
    const offer = await screen.findByText(/^Last Janmashtami, .*2025 — 4 preparations\.$/);
    expect(offer).toBeInTheDocument();
    // Said out loud rather than silently dropped: recipes became removable, and a menu that quietly
    // shrank by two would be wrong in a way nobody would notice.
    expect(
      screen.getByText(/2 of last year’s 4 preparations are no longer in your recipes\./)
    ).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Adults"), { target: { value: "200" } });
    fireEvent.click(screen.getByRole("button", { name: /use this menu/i }));

    // The preparation list carries. The servings do not — they follow this year's head count.
    expect(screen.getByLabelText("How much Bisi Bele Bath to make")).toHaveValue(200);
    expect(screen.getByLabelText("How much Kesari Bath to make")).toHaveValue(200);
  });

  it("offers nothing at all for the first ever Janmashtami", async () => {
    open();
    fireEvent.click(screen.getByRole("button", { name: "Festival feast" }));

    await vi.waitFor(() => expect(menuHistory).toHaveBeenCalled());
    expect(screen.queryByRole("button", { name: /use this menu/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/last janmashtami/i)).not.toBeInTheDocument();
  });
});

// --- item 16: a meal is editable as one --------------------------------------

describe("editing a meal as one thing", () => {
  const MEAL = {
    serviceId: null,
    planDate: "2026-08-16",
    mealKind: "Lunch",
    readyBy: "12:00:00",
    adults: 100, children: 0, seniors: 0,
    plates: 100,
    crewRequired: 6,
    dayType: "REGULAR",
    occasionName: null,
    clientName: null, clientContact: null, venue: null, purpose: null,
    kitchenNotes: null,
    cardNumber: null, cardIssuedAt: null,
    recorded: false, recordedAt: null, recordedByName: null, recordingNote: null,
    dishes: [
      { id: "p1", planDate: "2026-08-16", mealKind: "Lunch", readyBy: "12:00:00",
        recipeId: "r1", recipeName: "Bisi Bele Bath", targetYield: 100, dayType: "REGULAR",
        occasionName: null, status: "PLANNED", clientName: null, clientContact: null, venue: null,
        purpose: null, adults: 100, children: 0, seniors: 0, crewRequired: 6, kitchenNotes: null,
        actualServings: null, notMade: false, cookedAt: null, ekadashiAcknowledged: false,
        createdAt: "2026-08-15T10:00:00Z" },
    ],
  };

  beforeEach(() => {
    createMealPlan.mockClear();
    updateMealPlan.mockClear();
    cancelMealPlan.mockClear();
    suggestedCrew.mockResolvedValue({ crewRequired: null });
    mealCrew.mockResolvedValue([]);
  });

  function openEdit() {
    render(
      <MealComposer
        date="2026-08-16"
        recipes={RECIPES as never}
        mealKinds={KINDS as never}
        isEkadashi={false}
        existing={MEAL as never}
        onClose={vi.fn()}
        onPlanned={vi.fn()}
      />
    );
  }

  it("opens on what the meal already is, down to the crew it takes", () => {
    openEdit();
    expect(screen.getByLabelText("Ready by")).toHaveValue("12:00");
    expect(screen.getByLabelText("Adults")).toHaveValue(100);
    expect(screen.getByLabelText("People needed")).toHaveValue(6);
    expect(screen.getByRole("checkbox", { name: /bisi bele bath/i })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: /kesari bath/i })).not.toBeChecked();
  });

  it("does not offer to move the meal to another kind", () => {
    openEdit();
    // A meal is its date and its kind. Changing the kind would not correct this meal; it would move
    // its preparations into a different one.
    expect(screen.queryByRole("button", { name: "Catering" })).not.toBeInTheDocument();
  });

  it("updates what stayed, adds what was added, and cancels what was taken off", async () => {
    openEdit();
    fireEvent.click(screen.getByRole("checkbox", { name: /kesari bath/i }));
    fireEvent.change(screen.getByLabelText("Adults"), { target: { value: "150" } });
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await vi.waitFor(() => expect(updateMealPlan).toHaveBeenCalledTimes(1));
    // The row that was already there keeps its identity and its history.
    expect(updateMealPlan.mock.calls[0][0]).toBe("p1");
    expect(updateMealPlan.mock.calls[0][1]).toMatchObject({ adults: 150, crewRequired: 6 });
    expect(createMealPlan).toHaveBeenCalledTimes(1);
    expect(createMealPlan.mock.calls[0][0]).toMatchObject({ recipeId: "r2" });
    expect(cancelMealPlan).not.toHaveBeenCalled();
  });

  it("cancels a preparation taken off the meal rather than deleting it", async () => {
    openEdit();
    fireEvent.click(screen.getByRole("checkbox", { name: /bisi bele bath/i }));
    fireEvent.click(screen.getByRole("checkbox", { name: /kesari bath/i }));
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await vi.waitFor(() => expect(cancelMealPlan).toHaveBeenCalledTimes(1));
    expect(cancelMealPlan.mock.calls[0][0]).toBe("p1");
  });
});
