import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

/**
 * Raising a seva shift from the planner, and seeing it there afterwards (T-019).
 *
 * <p>Everything here is asserted against `MealServices`, because that is where the shortfall is
 * drawn and the affordance had to go beside it rather than in a second place that talks about crew.
 *
 * <p>The calls are typed like the real ones so the assertions read what was sent rather than
 * casting past an untyped mock — and one of them, the meal link, is asserted through
 * `Object.keys` and not `objectContaining`, because a missing property and an explicit null read
 * identically to that matcher and a missing link is exactly the defect this task closes.
 */

const { authRef, mealServices, mealCrew, listShifts, createShift, updateShift, jobCardLanguages } =
  vi.hoisted(() => ({
    authRef: {
      current: { role: "KITCHEN_MANAGER" } as { role: string } | null,
    },
    mealServices: vi.fn(async (_from: string, _to: string, _t?: string) => [] as unknown[]),
    mealCrew: vi.fn(async (_from: string, _to: string, _t?: string) => [] as unknown[]),
    listShifts: vi.fn(
      async (_f: { from?: string; to?: string } = {}, _t?: string) => [] as unknown[]
    ),
    createShift: vi.fn(async (_input: Record<string, unknown>, _t?: string) => ({ id: "s-new" })),
    updateShift: vi.fn(
      async (_id: string, _input: Record<string, unknown>, _t?: string) => undefined
    ),
    jobCardLanguages: vi.fn(async () => ({ languages: ["en"], defaultLanguage: "en" })),
  }));

// A push spy that must stay untouched: "lands back in the planner exactly where the reader was" is
// only true if nothing navigated at all, and a router that was never called is how that is proved.
const push = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push, replace: vi.fn(), back: vi.fn(), refresh: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ getToken: async () => "t", appUser: authRef.current }),
}));
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, mealServices, mealCrew, listShifts, createShift, updateShift, jobCardLanguages },
  };
});
// The component's own query hook, driven straight off the mocked calls, as the other planner tests
// drive it. Async so React can be imported inside a factory that is hoisted above this file.
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

function dish(id: string, overrides: Record<string, unknown> = {}) {
  return {
    id, planDate: DATE, mealKind: "Lunch", readyBy: "12:00:00",
    recipeId: "r1", recipeName: "Bisi Bele Bath", targetYield: 248,
    dayType: "REGULAR", occasionName: null, status: "PLANNED", eventName: null,
    contactName: null, contactPhone: null, deliveryAddress: null, purpose: null,
    adults: 200, children: 40, seniors: 30, kitchenNotes: null,
    actualServings: null, notMade: false, cookedAt: null, ekadashiAcknowledged: false,
    createdAt: "2026-08-20T10:00:00Z",
    ...overrides,
  };
}

/** Lunch on 1 September, needing eight pairs of hands. */
function lunch(overrides: Record<string, unknown> = {}) {
  return {
    serviceId: null, planDate: DATE, mealKind: "Lunch", readyBy: "12:00:00",
    adults: 200, children: 40, seniors: 30, plates: 248,
    crewRequired: 8,
    dayType: "REGULAR", occasionName: null, eventName: null,
    contactName: null, contactPhone: null, deliveryAddress: null, purpose: null,
    kitchenNotes: null, serverNotes: null, cardNumber: null, cardIssuedAt: null,
    recorded: false, recordedAt: null, recordedByName: null, recordingNote: null,
    corrected: false, correctedAt: null, correctedByName: null, correctionNote: null,
    dishes: [dish("m1")],
    ...overrides,
  };
}

/** Five of the eight are rostered, so the meal is three short. */
function crewOf(kind = "Lunch", rostered = 5, required: number | null = 8) {
  return {
    planDate: DATE, mealKind: kind, readyBy: "12:00:00",
    crewRequired: required, staffIn: 3, volunteers: rostered - 3, rostered,
    shortOfCrew: required != null && rostered < required,
  };
}

/** A shift as the list endpoint returns it, linked to lunch on the day unless told otherwise. */
function shiftFor(overrides: Record<string, unknown> = {}) {
  return {
    id: "s1", title: "Lunch preparation on Tuesday, 1 September 2026", description: "Bring an apron",
    shiftDate: DATE, startTime: "09:00:00", endTime: "12:00:00", location: "Main kitchen",
    capacity: 5, reminderOffsetsMinutes: [2880], status: "OPEN", cancelReason: null,
    signedUpCount: 2, waitlistCount: 0, createdAt: "2026-08-25T06:00:00Z",
    mealDate: DATE, mealKind: "Lunch", mealEventName: null,
    ...overrides,
  };
}

async function openTheDay(readOnly = false) {
  const { container } = render(
    <MealServices
      date={DATE}
      sufficiency={new Map()}
      recipes={RECIPES as never}
      readOnly={readOnly}
      onChanged={vi.fn()}
      onError={vi.fn()}
    />
  );
  await screen.findByText("Lunch");
  return container;
}

/** The day's text, once whatever was asked for has arrived. */
async function settled(container: HTMLElement, contains: string): Promise<HTMLElement> {
  await waitFor(() => expect(container.textContent).toContain(contains));
  return container;
}

function field(name: string): HTMLInputElement {
  const form = screen.getByRole("dialog");
  const input = form.querySelector(`[name="${name}"]`);
  if (!(input instanceof HTMLInputElement)) throw new Error(`no ${name} field in the layer`);
  return input;
}

describe("asking for volunteers from the planner", () => {
  beforeEach(() => {
    authRef.current = { role: "KITCHEN_MANAGER" };
    push.mockClear();
    createShift.mockClear().mockResolvedValue({ id: "s-new" });
    updateShift.mockClear().mockResolvedValue(undefined);
    mealServices.mockReset().mockResolvedValue([lunch()]);
    mealCrew.mockReset().mockResolvedValue([crewOf()]);
    listShifts.mockReset().mockResolvedValue([]);
    jobCardLanguages.mockClear();
  });

  it("offers the layer beside the shortfall, prefilled from the meal", async () => {
    const day = await openTheDay();
    // The shortfall is drawn, and the offer is beside it rather than somewhere else on the screen.
    await settled(day, "5 of 8");
    fireEvent.click(screen.getByRole("button", { name: /ask for volunteers/i }));

    const layer = screen.getByRole("dialog");
    // The title is derived and shown rather than typed, day-first as this application writes dates.
    expect(layer.textContent).toContain("Lunch preparation on Tuesday, 1 September 2026");
    // Three fields, which is what keeps this a layer rather than a screen.
    expect(layer.querySelectorAll("input").length).toBe(3);
    // Prefilled from the meal on the page: three hands short, and wanted up to the ready-by.
    expect(field("capacity").value).toBe("3");
    expect(field("endTime").value).toBe("12:00");
  });

  it("posts the shift and leaves the reader on the same day and meal", async () => {
    await openTheDay();
    fireEvent.click(screen.getByRole("button", { name: /ask for volunteers/i }));
    fireEvent.change(field("startTime"), { target: { value: "09:00" } });
    fireEvent.submit(screen.getByRole("form", { name: /ask for volunteers/i }));

    await waitFor(() => expect(createShift).toHaveBeenCalled());
    const input = createShift.mock.calls[0][0];
    expect(input.title).toBe("Lunch preparation on Tuesday, 1 September 2026");
    expect(input.shiftDate).toBe(DATE);
    expect(input.startTime).toBe("09:00");
    expect(input.endTime).toBe("12:00");
    expect(input.capacity).toBe(3);
    // Nothing navigated, and the day is still the thing on screen with the layer gone.
    expect(push).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(screen.getByText("Lunch")).toBeTruthy();
  });

  it("links the shift to the meal it was raised from", async () => {
    mealServices.mockResolvedValue([
      lunch({ mealKind: "Event", eventName: "Bhagavad Gita Parayanam" }),
    ]);
    mealCrew.mockResolvedValue([crewOf("Event")]);
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
    await screen.findByText("Bhagavad Gita Parayanam");

    fireEvent.click(screen.getByRole("button", { name: /ask for volunteers/i }));
    fireEvent.change(field("startTime"), { target: { value: "07:00" } });
    fireEvent.submit(screen.getByRole("form", { name: /ask for volunteers/i }));

    await waitFor(() => expect(createShift).toHaveBeenCalled());
    const input = createShift.mock.calls[0][0];
    // `objectContaining` cannot tell a missing key from a null one, and a half-filled link is
    // refused by the server — so the keys are inspected first and the values asserted after.
    const keys = Object.keys(input);
    expect(keys).toContain("mealDate");
    expect(keys).toContain("mealKind");
    expect(keys).toContain("mealEventName");
    expect(input.mealDate).toBe(DATE);
    expect(input.mealKind).toBe("Event");
    expect(input.mealEventName).toBe("Bhagavad Gita Parayanam");
    // An event is named by its own name here as it is everywhere else on the planner.
    expect(input.title).toContain("Bhagavad Gita Parayanam preparation on");
  });

  it("shows the raised shift beside the crew count, with its sign-ups", async () => {
    listShifts.mockResolvedValue([shiftFor()]);
    const day = await openTheDay();

    await settled(day, "2 of 5 signed up");
    // Both figures are in the same line as the meal's name: the shortfall and what was asked for.
    expect(day.textContent).toContain("5 of 8");
    // And the offer is gone, because the shortfall has been answered once.
    expect(screen.queryByRole("button", { name: /ask for volunteers/i })).toBeNull();
  });

  it("does not draw a shift raised for another meal against this one", async () => {
    // Same day, hours that span lunch's ready-by — the clock would have matched it, and the link
    // does not. This is the whole of D-14 stated as a test.
    listShifts.mockResolvedValue([
      shiftFor({ id: "s2", mealKind: "Breakfast", title: "Breakfast preparation", startTime: "06:00:00", endTime: "14:00:00" }),
    ]);
    const day = await openTheDay();

    await settled(day, "5 of 8");
    expect(day.textContent).not.toContain("signed up");
    // Lunch is still offered a shift of its own.
    expect(screen.getByRole("button", { name: /ask for volunteers/i })).toBeTruthy();
  });

  it("changes nothing when the layer is opened and closed without saving", async () => {
    const day = await openTheDay();
    fireEvent.click(screen.getByRole("button", { name: /ask for volunteers/i }));
    fireEvent.change(field("capacity"), { target: { value: "9" } });
    fireEvent.click(screen.getByRole("button", { name: /^cancel$/i }));

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(createShift).not.toHaveBeenCalled();
    expect(updateShift).not.toHaveBeenCalled();
    // The day is exactly as it was, offer and all.
    expect(day.textContent).toContain("5 of 8");
    expect(screen.getByRole("button", { name: /ask for volunteers/i })).toBeTruthy();
  });

  it("opens the raised shift in the same layer and keeps what the layer does not ask", async () => {
    listShifts.mockResolvedValue([shiftFor()]);
    await openTheDay();

    fireEvent.click(await screen.findByRole("button", { name: /2 of 5 signed up/ }));
    expect(screen.getByRole("dialog").textContent).toContain("Change this shift");
    // The shift's own hours and size, not the meal's suggestions.
    expect(field("startTime").value).toBe("09:00");
    expect(field("capacity").value).toBe("5");

    fireEvent.change(field("capacity"), { target: { value: "7" } });
    fireEvent.submit(screen.getByRole("form", { name: /change a shift/i }));

    await waitFor(() => expect(updateShift).toHaveBeenCalled());
    const [id, input] = updateShift.mock.calls[0];
    expect(id).toBe("s1");
    expect(input.capacity).toBe(7);
    // `updateShift` replaces the whole shift, so the three things this layer never asked about must
    // come back unchanged rather than as empties.
    expect(input.location).toBe("Main kitchen");
    expect(input.description).toBe("Bring an apron");
    expect(input.reminderOffsetsMinutes).toEqual([2880]);
    // And the title it was given is kept, not re-derived over the top of it.
    expect(input.title).toBe("Lunch preparation on Tuesday, 1 September 2026");
    expect(push).not.toHaveBeenCalled();
  });

  it("offers nothing on a day that has been and gone", async () => {
    listShifts.mockResolvedValue([shiftFor()]);
    const day = await openTheDay(true);

    await settled(day, "2 of 5 signed up");
    // Readable, and not pressable: a past day's shift is a record.
    expect(screen.queryByRole("button", { name: /2 of 5 signed up/ })).toBeNull();
    expect(screen.queryByRole("button", { name: /ask for volunteers/i })).toBeNull();
  });
});
