import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";

/**
 * The crew pebble on a planned meal — "5 of 8 rostered" — and the breakdown behind it (T-147, and
 * Epic 12's per-kitchen sections).
 *
 * <p>The breakdown, "3 staff and 2 volunteers, of 8 needed", used to be the pebble's native `title`,
 * which only a resting mouse pointer ever sees; T-147 moved it behind an `InfoHint`. Epic 12 moved the
 * pebble into each kitchen's section, to the approved mock's footer: "People needed 8 · 5 of 8
 * rostered", with the rostered people named on the line under it. The breakdown is now on the page
 * in plain text, reachable by everyone without pressing anything, so there is no hint to open. These
 * tests hold that, and hold the pebble's colour rule where it now lives.
 *
 * <p>Harness copied from `planner-shift.test.tsx`, which drives `MealServices` the same way.
 */

const { meals, mealCrew, jobCardLanguages } = vi.hoisted(() => ({
  meals: vi.fn(async (_from: string, _to: string, _t?: string) => [] as unknown[]),
  mealCrew: vi.fn(async (_from: string, _to: string, _t?: string) => [] as unknown[]),
  jobCardLanguages: vi.fn(async () => ({ languages: ["en"], defaultLanguage: "en" })),
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
    api: { ...actual.api, meals, mealCrew, jobCardLanguages },
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

const DATE = "2026-09-01";

const RECIPES = [
  {
    id: "r1", name: "Bisi Bele Bath", categoryName: "Khichadi", fastingCompatible: false,
    baseYieldQty: 100, baseYieldUnit: "KG", perHeadQty: 1, perHeadUnit: "KG", status: "ACTIVE",
  },
];

/** A meal by its own id (D-27), with the whole-meal facts on it once. */
function lunch(crewRequired: number | null = 8, mealKind = "Lunch", eventName: string | null = null) {
  return {
    mealId: "meal-1", mealKindId: "k1", planDate: DATE, mealKind, readyBy: "12:00:00",
    adults: 200, children: 40, seniors: 30, plates: 248,
    crewRequired,
    // Epic 12: People needed belongs to the kitchen; the meal's figure is the sum of its kitchens'.
    kitchens: [{ kitchenId: "kit-main", kitchenName: "Main kitchen", isMain: true, crewRequired }],
    dayType: "REGULAR", occasionName: null, eventName,
    isOutside: false, handover: null,
    contactName: null, contactPhone: null, deliveryAddress: null, deliverySubLocation: null,
    deliveryPlaceId: null, deliveryLatitude: null, deliveryLongitude: null, guestsEatAt: null,
    travelMinutes: null, travelMinutesSource: null, purpose: null,
    kitchenNotes: null, serverNotes: null, status: "PLANNED", cardNumber: null, cardIssuedAt: null,
    recorded: false, recordedAt: null, recordedByName: null, recordingNote: null,
    corrected: false, correctedAt: null, correctedByName: null, correctionNote: null,
    dishes: [
      {
        id: "m1", mealId: "meal-1", kitchenId: "kit-main", recipeId: "r1", recipeName: "Bisi Bele Bath", targetYield: 248,
        targetYieldUnit: "KG", status: "PLANNED", actualServings: null, consumedQuantity: null,
        notMade: false, originalActualServings: null, originalConsumedQuantity: null, cookedAt: null,
        ekadashiAcknowledged: false, createdAt: "2026-08-20T10:00:00Z",
      },
    ],
    volunteerShift: null,
  };
}

/**
 * The crew readout for that meal, matched to it by the meal's id and never by the kind's name, and
 * to its kitchen's section by the kitchen's id. The staff are named, as the server names them.
 */
function crewOf(staffIn: number, volunteers: number, required: number | null = 8, mealKind = "Lunch") {
  const rostered = staffIn + volunteers;
  const shortOfCrew = required != null && rostered < required;
  const staffNames = ["Govinda Das", "Madhava Das", "Keshava Das", "Damodara Das", "Gopal Das"].slice(0, staffIn);
  return {
    mealId: "meal-1", planDate: DATE, mealKind, readyBy: "12:00:00",
    crewRequired: required, staffIn, volunteers, rostered, shortOfCrew,
    kitchens: [
      {
        kitchenId: "kit-main", kitchenName: "Main kitchen", crewRequired: required,
        staffIn, staffNames, volunteers, rostered, shortOfCrew,
      },
    ],
  };
}

async function openTheDay(heading = "Lunch"): Promise<HTMLElement> {
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
  await screen.findByText(heading);
  return container;
}

/** The pill itself: the element that wears the users icon and the count. */
async function pebble(container: HTMLElement, count: string): Promise<HTMLElement> {
  await waitFor(() => expect(container.textContent).toContain(count));
  const icon = container.querySelector(".ti-users");
  if (!(icon?.parentElement instanceof HTMLElement)) throw new Error("no crew pebble drawn");
  return icon.parentElement;
}

describe("the crew pebble, in its kitchen's section", () => {
  beforeEach(() => {
    meals.mockReset().mockResolvedValue([lunch()]);
    mealCrew.mockReset().mockResolvedValue([crewOf(3, 2)]);
  });

  it("carries no native title, on the pebble or anywhere near it", async () => {
    const day = await openTheDay();
    const pill = await pebble(day, "5 of 8 rostered");

    expect(pill).not.toHaveAttribute("title");
    // Not moved onto the wrapper or anything else either: no element on the day holds the
    // breakdown as a hover-only title.
    expect(day.querySelector('[title*="volunteers"]')).toBeNull();
  });

  it("says the breakdown on the page, where no pointer or keyboard is needed to reach it", async () => {
    const day = await openTheDay();
    await pebble(day, "5 of 8 rostered");
    const section = screen.getByRole("region", { name: "Main kitchen" });

    // People needed, then the pebble, then who they are: the staff by name and the volunteers counted.
    expect(within(section).getByText(/^People needed/)).toHaveTextContent("People needed 8");
    expect(within(section).getByText("Govinda Das, Madhava Das, Keshava Das, 2 volunteers")).toBeInTheDocument();
    // Nothing left to open: the hint that used to hold this is gone rather than repeating it.
    expect(screen.queryByRole("button", { name: /more about crew/i })).toBeNull();
  });

  it("sits in the kitchen's section, not in the meal's header", async () => {
    const day = await openTheDay();
    const pill = await pebble(day, "5 of 8 rostered");
    expect(pill.closest("section")).toHaveAttribute("aria-label", "Main kitchen");
    expect(day.querySelector("header .ti-users")).toBeNull();
  });

  it("names an event's section controls by the event's own name, as the header does", async () => {
    meals.mockResolvedValue([lunch(8, "Event", "Bhagavad Gita Parayanam")]);
    mealCrew.mockResolvedValue([crewOf(3, 2, 8, "Event")]);
    const day = await openTheDay("Bhagavad Gita Parayanam");
    await pebble(day, "5 of 8 rostered");
    // Not "Event, Main kitchen": two events on one day would share that.
    expect(
      screen.getByRole("button", { name: "Download the Bhagavad Gita Parayanam, Main kitchen job card" })
    ).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Download the Event/ })).toBeNull();
  });

  it("keeps the count and the warning tone when the kitchen is short", async () => {
    const day = await openTheDay();
    const pill = await pebble(day, "5 of 8 rostered");

    expect(pill.className).toContain("bg-warning-bg");
    expect(pill.className).toContain("text-warning");
  });

  it("drops the warning tone once the kitchen is fully crewed", async () => {
    mealCrew.mockResolvedValue([crewOf(5, 3)]);
    const day = await openTheDay();
    const pill = await pebble(day, "8 of 8 rostered");

    expect(pill.className).toContain("bg-sunken");
    expect(pill.className).not.toContain("bg-warning-bg");
  });

  it("draws no pebble and no People needed for a kitchen nobody has given a crew number", async () => {
    meals.mockResolvedValue([lunch(null)]);
    mealCrew.mockResolvedValue([crewOf(3, 2, null)]);
    const day = await openTheDay();
    // Give the crew request its turn to land before asserting absence.
    await waitFor(() => expect(mealCrew).toHaveBeenCalled());
    await screen.findByText("Govinda Das, Madhava Das, Keshava Das, 2 volunteers");

    expect(day.querySelector(".ti-users")).toBeNull();
    expect(screen.queryByText(/People needed/)).toBeNull();
    expect(screen.queryByRole("button", { name: /more about crew/i })).toBeNull();
  });
});
