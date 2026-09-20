import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";

/**
 * Which kitchen is cooking (Epic 12, T-352): a meal's view is one card with a headed section per
 * kitchen, each holding that kitchen's own dishes, its People needed against its own staff, and its
 * own job card. Built to the approved mock's Option 1.
 *
 * <p>The harness is the one `crew-pebble.test.tsx` uses to drive `MealServices`: the api mocked at
 * its calls, and the query hook driven straight off them. The backend for Epic 12 is not built yet
 * (wave E12-2), so every figure here is what the reserved types in `lib/api.ts` promise it will send.
 */

const {
  meals, mealCrew, jobCardLanguages, requestJobCard, getJobCardDocument, downloadJobCardDocument,
  travelEstimate,
} =
  vi.hoisted(() => ({
    meals: vi.fn(async (_from: string, _to: string, _t?: string) => [] as unknown[]),
    mealCrew: vi.fn(async (_from: string, _to: string, _t?: string) => [] as unknown[]),
    jobCardLanguages: vi.fn(async () => ({ languages: ["en"], defaultLanguage: "en" })),
    requestJobCard: vi.fn(
      async (_mealId: string, _language?: string, _token?: string, _kitchenId?: string) => ({
        documentId: "d1",
        cardNumber: "LC-2026-0142",
        status: "PENDING",
      })
    ),
    // Ready at once, so the download goes through without the poll's wait.
    getJobCardDocument: vi.fn(async () => ({ id: "d1", status: "READY" })),
    downloadJobCardDocument: vi.fn(async () => new Blob(["%PDF"])),
    // `TravelLine` asks for this the moment a delivery's card mounts, so the handover cases below
    // need it stubbed or the real `fetch` runs and jsdom rejects the relative URL. Unmocked it was
    // an unhandled rejection rather than a failure, which vitest warns can make a pass meaningless.
    travelEstimate: vi.fn(async () => ({
      available: false, leaveBy: null, optimisticMinutes: null, pessimisticMinutes: null,
      guestsEatAt: null, reason: "No route provider configured",
    })),
  }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn(), refresh: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ getToken: async () => "t", appUser: { role: "KITCHEN_MANAGER" } }),
}));
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      meals,
      mealCrew,
      jobCardLanguages,
      requestJobCard,
      getJobCardDocument,
      downloadJobCardDocument,
      travelEstimate,
    },
  };
});
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

const DATE = "2026-09-25";
const MAIN = "kit-main";
const SWEETS = "kit-sweets";

const RECIPES = [
  { id: "r-rice", name: "Basmati rice", baseYieldUnit: "KG" },
  { id: "r-dal", name: "Toor dal", baseYieldUnit: "L" },
  { id: "r-halva", name: "Rava halva", baseYieldUnit: "KG" },
];

function dish(id: string, recipeId: string, recipeName: string, kitchenId: string, targetYield: number) {
  return {
    id, mealId: "meal-1", kitchenId, recipeId, recipeName, targetYield,
    targetYieldUnit: "KG", status: "PLANNED", actualServings: null, consumedQuantity: null,
    notMade: false, originalActualServings: null, originalConsumedQuantity: null, cookedAt: null,
    ekadashiAcknowledged: false, createdAt: "2026-09-20T10:00:00Z",
  };
}

type Section = { kitchenId: string; kitchenName: string; isMain: boolean; crewRequired: number | null };

/**
 * Friday's Lunch from the mock: the main kitchen cooks rice and dal, the sweets kitchen the halva.
 * The sections are sent in the order the server chose for the reader — here the sweets kitchen
 * first, as it would be for somebody who works in it — to prove the view does not re-sort them.
 */
function lunch(
  kitchens: Section[] = [
    { kitchenId: SWEETS, kitchenName: "Sweets kitchen", isMain: false, crewRequired: 2 },
    { kitchenId: MAIN, kitchenName: "Main kitchen", isMain: true, crewRequired: 6 },
  ],
  dishes = [
    dish("d-rice", "r-rice", "Basmati rice", MAIN, 150),
    dish("d-halva", "r-halva", "Rava halva", SWEETS, 36),
    dish("d-dal", "r-dal", "Toor dal", MAIN, 120),
  ]
) {
  return {
    mealId: "meal-1", mealKindId: "k1", planDate: DATE, mealKind: "Lunch", readyBy: "11:30:00",
    adults: 600, children: null, seniors: null, plates: 600,
    crewRequired: kitchens.reduce((n, k) => n + (k.crewRequired ?? 0), 0),
    kitchens,
    dayType: "REGULAR", occasionName: null, eventName: null,
    isOutside: false, handover: null,
    contactName: null, contactPhone: null, deliveryAddress: null, deliverySubLocation: null,
    deliveryPlaceId: null, deliveryLatitude: null, deliveryLongitude: null, guestsEatAt: null,
    travelMinutes: null, travelMinutesSource: null, purpose: null,
    kitchenNotes: null, serverNotes: null, status: "PLANNED", cardNumber: "LC-2026-0142", cardIssuedAt: null,
    recorded: false, recordedAt: null, recordedByName: null, recordingNote: null,
    corrected: false, correctedAt: null, correctedByName: null, correctionNote: null,
    dishes,
    volunteerShift: null,
  };
}

function kitchenCrew(
  kitchenId: string,
  kitchenName: string,
  crewRequired: number | null,
  staffNames: string[],
  volunteers = 0
) {
  const rostered = staffNames.length + volunteers;
  return {
    kitchenId, kitchenName, crewRequired, staffIn: staffNames.length, staffNames, volunteers, rostered,
    shortOfCrew: crewRequired != null && rostered < crewRequired,
  };
}

/** The meal's crew, per kitchen, in the same order as the sections. */
function crewOf(kitchens: ReturnType<typeof kitchenCrew>[]) {
  const staffIn = kitchens.reduce((n, k) => n + k.staffIn, 0);
  const volunteers = kitchens.reduce((n, k) => n + k.volunteers, 0);
  return {
    mealId: "meal-1", planDate: DATE, mealKind: "Lunch", readyBy: "11:30:00",
    crewRequired: kitchens.reduce((n, k) => n + (k.crewRequired ?? 0), 0),
    staffIn, volunteers, rostered: staffIn + volunteers,
    shortOfCrew: kitchens.some((k) => k.shortOfCrew),
    kitchens,
  };
}

const SWEETS_CREW = kitchenCrew(SWEETS, "Sweets kitchen", 2, ["Radha Devi Dasi", "Lalita Devi Dasi"]);
// Five of six: short, so amber.
const MAIN_CREW = kitchenCrew(MAIN, "Main kitchen", 6, ["Govinda Das", "Madhava Das", "Keshava Das", "Damodara Das"], 1);

async function openTheDay(): Promise<HTMLElement> {
  const { container } = render(
    <MealServices
      date={DATE}
      sufficiency={new Map()}
      recipes={RECIPES as never}
      readOnly={false}
      onChanged={vi.fn()}
      onError={vi.fn()}
    />
  );
  await screen.findByText("Lunch");
  return container;
}

/** The kitchen's section, by the name it is labelled with. */
function section(name: string): HTMLElement {
  return screen.getByRole("region", { name });
}

describe("a meal's sections, one per kitchen", () => {
  beforeEach(() => {
    meals.mockReset().mockResolvedValue([lunch()]);
    mealCrew.mockReset().mockResolvedValue([crewOf([SWEETS_CREW, MAIN_CREW])]);
    requestJobCard.mockClear();
    // jsdom has no object URLs; the download's last step needs one.
    URL.createObjectURL = vi.fn(() => "blob:card");
    URL.revokeObjectURL = vi.fn();
  });

  it("draws a section per kitchen in the order the server sent, never re-sorted", async () => {
    const day = await openTheDay();
    const headings = Array.from(day.querySelectorAll("section h3")).map(
      (h) => h.firstElementChild?.textContent
    );
    // Sweets first because the server put it first, although the main kitchen sorts before it.
    expect(headings).toEqual(["Sweets kitchen", "Main kitchen"]);
  });

  it("puts each dish under its own kitchen and nowhere else, with the count in the heading", async () => {
    await openTheDay();
    const sweets = section("Sweets kitchen");
    const main = section("Main kitchen");

    expect(within(sweets).getByRole("button", { name: "Rava halva" })).toBeInTheDocument();
    expect(within(sweets).queryByRole("button", { name: "Basmati rice" })).toBeNull();
    expect(within(sweets).queryByRole("button", { name: "Toor dal" })).toBeNull();
    expect(within(sweets).getByRole("heading", { level: 3 })).toHaveTextContent("Sweets kitchen1 preparation");

    expect(within(main).getByRole("button", { name: "Basmati rice" })).toBeInTheDocument();
    expect(within(main).getByRole("button", { name: "Toor dal" })).toBeInTheDocument();
    expect(within(main).queryByRole("button", { name: "Rava halva" })).toBeNull();
    expect(within(main).getByRole("heading", { level: 3 })).toHaveTextContent("Main kitchen2 preparations");
  });

  it("names the kitchens in the meal's fact line, in the sections' order", async () => {
    const day = await openTheDay();
    const header = day.querySelector("header")!;
    expect(header.textContent).toContain("600 adults expected·600 servings·Sweets kitchen and Main kitchen");
  });

  it("gives each kitchen its own People needed and rostered names, from its own crew readout", async () => {
    await openTheDay();
    const sweets = section("Sweets kitchen");
    const main = section("Main kitchen");

    await waitFor(() => expect(within(main).getByText("5 of 6 rostered")).toBeInTheDocument());
    expect(within(main).getByText(/^People needed/)).toHaveTextContent("People needed 6");
    // The main kitchen carries the meal's one volunteer, so it says so after its staff.
    expect(
      within(main).getByText("Govinda Das, Madhava Das, Keshava Das, Damodara Das, 1 volunteer")
    ).toBeInTheDocument();

    expect(within(sweets).getByText(/^People needed/)).toHaveTextContent("People needed 2");
    expect(within(sweets).getByText("2 of 2 rostered")).toBeInTheDocument();
    expect(within(sweets).getByText("Radha Devi Dasi, Lalita Devi Dasi")).toBeInTheDocument();
    // Neither kitchen shows the other's people.
    expect(within(sweets).queryByText(/Govinda Das/)).toBeNull();
  });

  it("colours a kitchen's pebble amber only when that kitchen is short", async () => {
    await openTheDay();
    const main = section("Main kitchen");
    const sweets = section("Sweets kitchen");
    await waitFor(() => expect(within(main).getByText("5 of 6 rostered")).toBeInTheDocument());

    const short = within(main).getByText("5 of 6 rostered");
    expect(short.className).toContain("bg-warning-bg");
    expect(short.className).toContain("text-warning");

    const enough = within(sweets).getByText("2 of 2 rostered");
    expect(enough.className).toContain("bg-sunken");
    expect(enough.className).not.toContain("warning");
  });

  it("draws no crew pebble for a kitchen nobody has given a number, and still names its people", async () => {
    meals.mockResolvedValue([
      lunch([
        { kitchenId: SWEETS, kitchenName: "Sweets kitchen", isMain: false, crewRequired: null },
        { kitchenId: MAIN, kitchenName: "Main kitchen", isMain: true, crewRequired: 6 },
      ]),
    ]);
    mealCrew.mockResolvedValue([
      crewOf([kitchenCrew(SWEETS, "Sweets kitchen", null, ["Radha Devi Dasi"]), MAIN_CREW]),
    ]);
    await openTheDay();
    const sweets = section("Sweets kitchen");
    await waitFor(() => expect(within(sweets).getByText("Radha Devi Dasi")).toBeInTheDocument());

    expect(sweets.querySelector(".ti-users")).toBeNull();
    expect(within(sweets).queryByText(/People needed/)).toBeNull();
    // The other kitchen's pebble is unaffected.
    expect(section("Main kitchen").querySelector(".ti-users")).not.toBeNull();
  });

  it("asks for each kitchen's own job card, by that kitchen's id", async () => {
    await openTheDay();

    fireEvent.click(screen.getByRole("button", { name: "Download the Lunch, Sweets kitchen job card" }));
    await waitFor(() => expect(requestJobCard).toHaveBeenCalledTimes(1));
    expect(requestJobCard.mock.calls[0]).toEqual(["meal-1", "none", "t", SWEETS]);

    fireEvent.click(screen.getByRole("button", { name: "Download the Lunch, Main kitchen job card" }));
    await waitFor(() => expect(requestJobCard).toHaveBeenCalledTimes(2));
    expect(requestJobCard.mock.calls[1]).toEqual(["meal-1", "none", "t", MAIN]);
    await act(async () => {});
  });

  it("keeps each kitchen's recipe choice to itself", async () => {
    await openTheDay();
    fireEvent.click(screen.getByLabelText("Include the recipes with the Lunch, Main kitchen card"));
    // Only the main kitchen's picker opens.
    expect(await screen.findByLabelText("Recipe language for Lunch, Main kitchen")).toBeInTheDocument();
    expect(screen.queryByLabelText("Recipe language for Lunch, Sweets kitchen")).toBeNull();
    expect(screen.getByLabelText("Include the recipes with the Lunch, Sweets kitchen card")).not.toBeChecked();
  });

  it("has no meal-level crew pebble or job-card row left outside the sections", async () => {
    const day = await openTheDay();
    await waitFor(() => expect(day.querySelectorAll(".ti-users").length).toBe(2));
    const header = day.querySelector("header")!;
    expect(header.querySelector(".ti-users")).toBeNull();
    // Every Download job card button is inside a kitchen's section.
    const downloads = screen.getAllByRole("button", { name: /job card/i });
    expect(downloads).toHaveLength(2);
    for (const button of downloads) expect(button.closest("section")).not.toBeNull();
  });

  it("gives a one-kitchen meal its one headed section, the same shape as two", async () => {
    meals.mockResolvedValue([
      lunch(
        [{ kitchenId: MAIN, kitchenName: "Main kitchen", isMain: true, crewRequired: 6 }],
        [dish("d-rice", "r-rice", "Basmati rice", MAIN, 150), dish("d-dal", "r-dal", "Toor dal", MAIN, 120)]
      ),
    ]);
    mealCrew.mockResolvedValue([crewOf([MAIN_CREW])]);
    const day = await openTheDay();

    const main = section("Main kitchen");
    expect(within(main).getByRole("heading", { level: 3 })).toHaveTextContent("Main kitchen2 preparations");
    expect(within(main).getByRole("button", { name: "Basmati rice" })).toBeInTheDocument();
    expect(
      within(main).getByRole("button", { name: "Download the Lunch, Main kitchen job card" })
    ).toBeInTheDocument();
    expect(day.querySelector("header")!.textContent).toContain("600 servings·Main kitchen");

    fireEvent.click(within(main).getByRole("button", { name: /job card/i }));
    await waitFor(() => expect(requestJobCard).toHaveBeenCalledTimes(1));
    // The kitchen goes with a one-kitchen meal's card too.
    expect(requestJobCard.mock.calls[0][3]).toBe(MAIN);
    await act(async () => {});
  });
});

/**
 * Who moves the food, on the meal's own card (T-363).
 *
 * <p>The one fact the planner's deleted *Upcoming outside commitments* table carried that the meal
 * card did not. Rajeev asked for it "like an information pill" that catches the eye, and chose blue.
 * Everything else that table held — the contact, where it is going, the hour — was already on the
 * card's facts line, which is why only this moved.
 *
 * <p>The words are asserted exactly, in both places they appear. Rajeev wrote "We deliver it"
 * himself; "They collect it" is its pair and says the same thing about the other direction, so a
 * reader who has learned one has learned both. `today.test.tsx` asserts the same two strings on the
 * heads-up row — the same label in every view, so the same fact is never called two things.
 */
describe("the handover pill on an outside event", () => {
  /** A Lunch that leaves the temple, otherwise the fixture above unchanged. */
  function goingOut(handover: "DELIVERY" | "PICKUP" | null) {
    return {
      ...lunch([{ kitchenId: MAIN, kitchenName: "Main kitchen", isMain: true, crewRequired: 6 }], [
        dish("d-rice", "r-rice", "Basmati rice", MAIN, 150),
      ]),
      mealKind: "Event",
      eventName: "Children's Bhagavad-gita Reading",
      isOutside: true,
      handover,
      contactName: "Mrs Shanta",
      contactPhone: "+91 98862 30011",
      deliveryAddress: handover === "DELIVERY" ? "Vidyaranyapura, Bengaluru" : null,
      guestsEatAt: handover === "DELIVERY" ? "13:00:00" : null,
    };
  }

  beforeEach(() => {
    meals.mockReset();
    mealCrew.mockReset().mockResolvedValue([crewOf([MAIN_CREW])]);
  });

  async function openEvent(handover: "DELIVERY" | "PICKUP" | null) {
    meals.mockResolvedValue([goingOut(handover)]);
    render(
      <MealServices
        date={DATE}
        sufficiency={new Map()}
        recipes={RECIPES as never}
        readOnly={false}
        onChanged={vi.fn()}
        onError={vi.fn()}
      />
    );
    return screen.findByText("Children's Bhagavad-gita Reading");
  }

  it("says we deliver it, in Rajeev's own words and in the info blue he chose", async () => {
    await openEvent("DELIVERY");
    const pill = screen.getByText("We deliver it");
    // The design system's own info tokens, not a colour written on this component. `globals.css`
    // defines them as #326086 on #E5F2FD, which is the blue.
    expect(pill).toHaveClass("bg-info-bg", "text-info");
    expect(screen.queryByText("They collect it")).not.toBeInTheDocument();
  });

  it("says they collect it when the guests come for the food", async () => {
    await openEvent("PICKUP");
    const pill = screen.getByText("They collect it");
    expect(pill).toHaveClass("bg-info-bg", "text-info");
    // Not "Collected", which the deleted table used and which reads as something that has already
    // happened, on a meal nobody has cooked yet.
    expect(screen.queryByText("Collected")).not.toBeInTheDocument();
  });

  it("says nothing at all where the handover was never asked", async () => {
    // V88 carried the old catering and outside-event plans across with `handover` NULL, because
    // nobody was ever asked. A pill reading "Not set" on a day's card is a permanent question mark
    // the reader cannot answer from there; the meal's own form asks it.
    await openEvent(null);
    expect(screen.queryByText("We deliver it")).not.toBeInTheDocument();
    expect(screen.queryByText("They collect it")).not.toBeInTheDocument();
    expect(screen.queryByText(/Not set/)).not.toBeInTheDocument();
  });

  it("says nothing on a meal that never leaves the temple", async () => {
    meals.mockResolvedValue([lunch()]);
    render(
      <MealServices
        date={DATE}
        sufficiency={new Map()}
        recipes={RECIPES as never}
        readOnly={false}
        onChanged={vi.fn()}
        onError={vi.fn()}
      />
    );
    await screen.findByText("Lunch");
    expect(screen.queryByText("We deliver it")).not.toBeInTheDocument();
    expect(screen.queryByText("They collect it")).not.toBeInTheDocument();
  });

  it("still opens for editing and still offers its kitchen's job card", async () => {
    // Rajeev, on the event he could not reach from the deleted table: "I cant open it to adjust it
    // OR view what is in it, cant print a Job card. NOTHING!!" Both are on the card, and were all
    // along — the table was the dead end, not the meal.
    await openEvent("DELIVERY");
    const edit = screen.getByRole("link", { name: /edit/i });
    expect(edit).toHaveAttribute("href", expect.stringContaining("/planner/meal/meal-1"));
    // Named by the event rather than by the kind, the same as the heading above it: a job card for
    // "the Event" would be no use to anybody holding three of them.
    expect(
      screen.getByRole("button", {
        name: "Download the Children's Bhagavad-gita Reading, Main kitchen job card",
      })
    ).toBeInTheDocument();
  });
});
