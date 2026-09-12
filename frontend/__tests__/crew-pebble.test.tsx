import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";

/**
 * The crew pebble on a planned meal — "5 of 8" — and the breakdown behind it (T-147).
 *
 * <p>The breakdown, "3 staff and 2 volunteers, of 8 needed", used to be the pebble's native `title`.
 * That is shown only under a resting mouse pointer, so a keyboard user and anyone on a phone never
 * got it. It now sits behind an `InfoHint`, and these tests hold it there.
 *
 * <p>The other `InfoHint` tests open the hint with `mouseOver`. That proves the text is attached,
 * not that it is reachable without a mouse, which is the whole defect here — so the reach is asserted
 * by focus, the way a Tab lands on it. There is no `user-event` in this project to press Tab itself,
 * so the test proves the two halves of it: that the "i" is a real, focusable button in the tab order,
 * and that focus alone, with no pointer event at all, opens the text.
 *
 * <p>Harness copied from `planner-shift.test.tsx`, which drives `MealServices` the same way.
 */

const { mealServices, mealCrew, listShifts, jobCardLanguages } = vi.hoisted(() => ({
  mealServices: vi.fn(async (_from: string, _to: string, _t?: string) => [] as unknown[]),
  mealCrew: vi.fn(async (_from: string, _to: string, _t?: string) => [] as unknown[]),
  listShifts: vi.fn(async (_f: { from?: string; to?: string } = {}, _t?: string) => [] as unknown[]),
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
    api: { ...actual.api, mealServices, mealCrew, listShifts, jobCardLanguages },
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

function lunch(crewRequired: number | null = 8, mealKind = "Lunch", eventName: string | null = null) {
  return {
    serviceId: null, planDate: DATE, mealKind, readyBy: "12:00:00",
    adults: 200, children: 40, seniors: 30, plates: 248,
    crewRequired,
    dayType: "REGULAR", occasionName: null, eventName,
    contactName: null, contactPhone: null, deliveryAddress: null, purpose: null,
    kitchenNotes: null, serverNotes: null, cardNumber: null, cardIssuedAt: null,
    recorded: false, recordedAt: null, recordedByName: null, recordingNote: null,
    corrected: false, correctedAt: null, correctedByName: null, correctionNote: null,
    dishes: [
      {
        id: "m1", planDate: DATE, mealKind: "Lunch", readyBy: "12:00:00",
        recipeId: "r1", recipeName: "Bisi Bele Bath", targetYield: 248,
        dayType: "REGULAR", occasionName: null, status: "PLANNED", eventName: null,
        contactName: null, contactPhone: null, deliveryAddress: null, purpose: null,
        adults: 200, children: 40, seniors: 30, kitchenNotes: null,
        actualServings: null, notMade: false, cookedAt: null, ekadashiAcknowledged: false,
        createdAt: "2026-08-20T10:00:00Z",
      },
    ],
  };
}

function crewOf(staffIn: number, volunteers: number, required: number | null = 8, mealKind = "Lunch") {
  const rostered = staffIn + volunteers;
  return {
    planDate: DATE, mealKind, readyBy: "12:00:00",
    crewRequired: required, staffIn, volunteers, rostered,
    shortOfCrew: required != null && rostered < required,
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

describe("the crew pebble's breakdown", () => {
  beforeEach(() => {
    mealServices.mockReset().mockResolvedValue([lunch()]);
    mealCrew.mockReset().mockResolvedValue([crewOf(3, 2)]);
    listShifts.mockReset().mockResolvedValue([]);
  });

  it("carries no native title, on the pebble or anywhere near it", async () => {
    const day = await openTheDay();
    const pill = await pebble(day, "5 of 8");

    expect(pill).not.toHaveAttribute("title");
    // Not moved onto the wrapper or anything else either: no element on the day holds the
    // breakdown as a hover-only title.
    expect(day.querySelector('[title*="volunteers"]')).toBeNull();
  });

  it("is reached by keyboard: a focusable button that opens the breakdown on focus alone", async () => {
    const day = await openTheDay();
    await pebble(day, "5 of 8");

    const hint = screen.getByRole("button", { name: "More about crew for Lunch" });
    // In the tab order, the way a Tab lands on it: a real button, enabled, not taken out by tabindex.
    expect(hint.tagName).toBe("BUTTON");
    expect(hint).toHaveAttribute("type", "button");
    expect(hint).not.toBeDisabled();
    expect(hint.tabIndex).toBeGreaterThanOrEqual(0);

    // Shut until asked for.
    expect(screen.queryByRole("tooltip")).toBeNull();
    expect(day.textContent).not.toContain("3 staff and 2 volunteers");

    // Focus, with no pointer event at all.
    act(() => hint.focus());
    expect(document.activeElement).toBe(hint);
    expect(screen.getByRole("tooltip")).toHaveTextContent("3 staff and 2 volunteers, of 8 needed");

    // And Escape puts it away again, as every other hint does.
    fireEvent.keyDown(hint, { key: "Escape" });
    expect(screen.queryByRole("tooltip")).toBeNull();
  });

  it("says the breakdown once: the pebble's own sentence does not repeat it", async () => {
    const day = await openTheDay();
    const pill = await pebble(day, "5 of 8");

    act(() => screen.getByRole("button", { name: "More about crew for Lunch" }).focus());
    // Exactly one place in the page holds the sentence, and it is the tip.
    const holders = Array.from(day.querySelectorAll("*")).filter(
      (el) => el.children.length === 0 && el.textContent?.includes("volunteers, of 8 needed")
    );
    expect(holders).toHaveLength(1);
    expect(holders[0]).toHaveAttribute("role", "tooltip");
    // The pebble still says what it said, and no more.
    expect(pill.textContent).toBe("5 of 8 people rostered of the number this meal takes");
  });

  it("names the hint for its meal, so a day of meals is not a row of identical buttons", async () => {
    const day = await openTheDay();
    await pebble(day, "5 of 8");
    // The meal kind for an ordinary meal, as the header reads it.
    expect(screen.getByRole("button", { name: "More about crew for Lunch" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "More about crew" })).toBeNull();
  });

  it("names the hint for an event by the event's own name, as the header does", async () => {
    mealServices.mockResolvedValue([lunch(8, "Event", "Bhagavad Gita Parayanam")]);
    mealCrew.mockResolvedValue([crewOf(3, 2, 8, "Event")]);
    const day = await openTheDay("Bhagavad Gita Parayanam");
    await pebble(day, "5 of 8");
    // Not "crew for Event": two events on one day would share that.
    expect(
      screen.getByRole("button", { name: "More about crew for Bhagavad Gita Parayanam" })
    ).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "More about crew for Event" })).toBeNull();
  });

  it("keeps the count and the warning tone when the meal is short", async () => {
    const day = await openTheDay();
    const pill = await pebble(day, "5 of 8");

    expect(pill.className).toContain("bg-warning-bg");
    expect(pill.className).toContain("text-warning");
  });

  it("drops the warning tone once the meal is fully crewed", async () => {
    mealCrew.mockResolvedValue([crewOf(5, 3)]);
    const day = await openTheDay();
    const pill = await pebble(day, "8 of 8");

    expect(pill.className).toContain("bg-sunken");
    expect(pill.className).not.toContain("bg-warning-bg");
  });

  it("draws no pebble and no hint for a meal nobody has given a crew number", async () => {
    mealServices.mockResolvedValue([lunch(null)]);
    mealCrew.mockResolvedValue([crewOf(3, 2, null)]);
    const day = await openTheDay();
    // Give the crew request its turn to land before asserting absence.
    await waitFor(() => expect(mealCrew).toHaveBeenCalled());
    await act(async () => {});

    expect(day.querySelector(".ti-users")).toBeNull();
    // No crew hint of any name, not merely none named for Lunch.
    expect(screen.queryByRole("button", { name: /more about crew/i })).toBeNull();
  });
});
