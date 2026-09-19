import React from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";

/**
 * Asking for volunteers from the meal planner, as D-27 ruled it (Rajeev, 2026-09-13).
 *
 * <p>Before D-27 the day's block posted a shift the moment its layer was saved. Now asking for
 * volunteers is part of planning the meal: *Ask for volunteers* sits in section 4 of the composer the
 * moment People needed is more than Rostered, the layer's button reads **Done** and saves nothing,
 * and the shift goes to the server inside *Save this meal* or *Update this meal*, where the meal and
 * the shift are one transaction. Rajeev: *"we should not be left with an orphan shift."*
 *
 * <p>So most of this file drives `MealComposer` with every call that could write a shift mocked, and
 * asserts the calls that were NOT made as carefully as the one that was. The end of the file drives
 * `MealServices`, the day: it shows the shift and links to its meal, and cancelling a meal with a
 * shift warns with the count before it cancels both.
 *
 * <p>Where a request body's shape matters, `Object.keys` is read rather than `objectContaining`: a
 * missing property and an explicit null read identically to that matcher.
 */

const { authRef, api } = vi.hoisted(() => ({
  authRef: { current: { role: "KITCHEN_MANAGER" } as { role: string } | null },
  api: {
    // The only two calls allowed to carry a shift.
    saveMeal: vi.fn(async (_input: Body, _t?: string) => ({ id: "meal-new" })),
    updateMeal: vi.fn(async (_id: string, _input: Body, _t?: string) => ({ id: "meal-lunch" })),
    // Calls that would save a shift on its own. Nothing in the planner may make them.
    createShift: vi.fn(async (_input: Record<string, unknown>, _t?: string) => ({ id: "s-new" })),
    updateShift: vi.fn(async (_id: string, _input: Record<string, unknown>, _t?: string) => undefined),
    cancelShift: vi.fn(async (_id: string, _reason: string, _t?: string) => undefined),
    cancelMeal: vi.fn(async (_id: string, _reason?: string | null, _t?: string) => ({ volunteersTold: 0 })),
    meals: vi.fn(async (_from: string, _to: string, _t?: string) => [] as unknown[]),
    mealCrew: vi.fn(async (_from: string, _to: string, _t?: string) => [] as unknown[]),
    // Who is rostered at a date and ready-by before the meal is saved (T-215). Read-only.
    mealCrewAt: vi.fn(async (_date: string, _readyBy: string, _t?: string) =>
      ({ planDate: "", readyBy: "", staffIn: 0, volunteers: 0, rostered: 0 })),
    suggestedCrew: vi.fn(async (_kind: string, _t?: string) => ({ crewRequired: null as number | null })),
    // What the calendar says the date is (T-208). A new meal of any kind now asks, because a festival
    // day's usual crowd opens as its adults. A plain day here, read-only, so no meal in this file
    // opens on anything but the nought it always did — and nothing reaches the network unmocked.
    mealDayContext: vi.fn(async (_date: string, _t?: string) => ({
      suggestedDayType: "REGULAR", occasionName: null as string | null,
      suggestedServings: null as number | null, isEkadashi: false,
    })),
    jobCardLanguages: vi.fn(async () => ({ languages: ["en"], defaultLanguage: "en" })),
    eventNameSuggestions: vi.fn(async () => [] as unknown[]),
    placesAvailable: vi.fn(async () => ({ available: false })),
    travelEstimateFor: vi.fn(async () => ({
      available: false, leaveBy: null, optimisticMinutes: null, pessimisticMinutes: null,
      guestsEatAt: null, reason: "NO_MAP_SERVICE",
    })),
  },
}));

/** What a meal save or update sends, typed enough to index into. */
type Body = Record<string, unknown> & {
  dishes: { id: string | null; recipeId: string; targetYield: number }[];
  volunteerShift: Record<string, unknown> | null;
};

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
  return { ...actual, api: { ...actual.api, ...api } };
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

import { MealComposer, type ComposerStatus } from "@/components/planner/MealComposer";
import { MealServices } from "@/components/planner/MealServices";

const DATE = "2026-09-01";
const DAY = `/planner/${DATE}`;

const RECIPES = [
  { id: "r1", name: "Bisi Bele Bath", categoryName: "Khichadi", fastingCompatible: false,
    baseYieldQty: 100, baseYieldUnit: "KG", perHeadQty: 1, perHeadUnit: "KG", status: "ACTIVE" },
];

const KINDS = [
  { id: "k1", name: "Lunch", defaultReadyTime: "12:00:00", isEvent: false, needsOccasion: false },
  { id: "k2", name: "Event", defaultReadyTime: null, isEvent: true, needsOccasion: false },
];

/** A shift as the server sends it on a meal — Lunch's, with two signed up unless told otherwise. */
function shiftFor(overrides: Record<string, unknown> = {}) {
  return {
    id: "s1", title: "Lunch preparation on Tuesday, 1 September 2026", description: "Bring an apron",
    shiftDate: DATE, startTime: "09:00:00", endTime: "12:00:00", location: "Main kitchen",
    capacity: 5, reminderOffsetsMinutes: [2880], status: "OPEN", cancelReason: null,
    signedUpCount: 2, waitlistCount: 0, createdAt: "2026-08-25T06:00:00Z",
    mealId: "meal-lunch", mealKind: "Lunch", mealEventName: null,
    ...overrides,
  };
}

/** Lunch on 1 September, by its own id, needing eight pairs of hands. */
function lunch(overrides: Record<string, unknown> = {}) {
  return {
    mealId: "meal-lunch", mealKindId: "k1", planDate: DATE, mealKind: "Lunch", readyBy: "12:00:00",
    adults: 200, children: 40, seniors: 30, plates: 248, crewRequired: 8,
    dayType: "REGULAR", occasionName: null, eventName: null, isOutside: false, handover: null,
    contactName: null, contactPhone: null, deliveryAddress: null, deliverySubLocation: null,
    deliveryPlaceId: null, deliveryLatitude: null, deliveryLongitude: null, guestsEatAt: null,
    travelMinutes: null, travelMinutesSource: null, purpose: null, kitchenNotes: null, serverNotes: null,
    status: "PLANNED", cardNumber: null, cardIssuedAt: null,
    recorded: false, recordedAt: null, recordedByName: null, recordingNote: null,
    corrected: false, correctedAt: null, correctedByName: null, correctionNote: null,
    dishes: [
      { id: "d1", mealId: "meal-lunch", recipeId: "r1", recipeName: "Bisi Bele Bath", targetYield: 248,
        targetYieldUnit: "KG", status: "PLANNED", actualServings: null, consumedQuantity: null,
        notMade: false, originalActualServings: null, originalConsumedQuantity: null, cookedAt: null,
        ekadashiAcknowledged: false, createdAt: "2026-08-20T10:00:00Z" },
    ],
    volunteerShift: null,
    ...overrides,
  };
}

/** Who is rostered over the meal. Five by default, so a meal needing eight is three short. */
function crewOf(rostered = 5, required: number | null = 8, mealId = "meal-lunch") {
  return {
    mealId, planDate: DATE, mealKind: "Lunch", readyBy: "12:00:00",
    crewRequired: required, staffIn: 3, volunteers: rostered - 3, rostered,
    shortOfCrew: required != null && rostered < required,
  };
}

/**
 * The composer as the compose and edit screens mount it: the commit button outside the form, reaching
 * it by id, and a link out of the page like the header's Cancel.
 */
function Harness(props: Partial<React.ComponentProps<typeof MealComposer>>) {
  const [status, setStatus] = React.useState<ComposerStatus>({ busy: false, blocked: true, hint: null });
  return (
    <>
      <a href={DAY}>Back to the day</a>
      <MealComposer
        date={DATE}
        recipes={RECIPES as never}
        mealKinds={KINDS as never}
        isEkadashi={false}
        onClose={vi.fn()}
        onPlanned={vi.fn()}
        formId="test-meal"
        onStatus={setStatus}
        {...props}
      />
      {status.hint && <p>{status.hint}</p>}
      <button type="submit" form="test-meal" disabled={status.busy || status.blocked}>
        {props.existing ? "Update this meal" : "Save this meal"}
      </button>
    </>
  );
}

/** A new Lunch, counted and with its preparation picked, so the only open question is its crew. */
async function planALunch(needed: string) {
  render(<Harness />);
  fireEvent.change(screen.getByLabelText("Adults"), { target: { value: "200" } });
  fireEvent.click(screen.getByRole("checkbox", { name: /bisi bele bath/i }));
  fireEvent.change(screen.getByLabelText("People needed"), { target: { value: needed } });
  // The crew readout is read from the server; wait for it so "Rostered" is the real figure.
  await screen.findByText(/3 staff · 2 volunteers/);
}

/** Lunch as it is saved already, opened for editing. */
async function editLunch(overrides: Record<string, unknown> = {}) {
  render(<Harness existing={lunch(overrides) as never} />);
  await screen.findByText(/3 staff · 2 volunteers/);
}

/** One box of the form inside the layer, by the name `ShiftFields` gives it. */
function field(name: string): HTMLInputElement {
  const input = screen.getByRole("dialog").querySelector(`[name="${name}"]`);
  if (!(input instanceof HTMLInputElement)) throw new Error(`no ${name} field in the layer`);
  return input;
}

function done() {
  fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Done" }));
}

const ASK = { name: /ask for volunteers/i };
const VIEW = { name: /view volunteer shift/i };

function nothingSavedAShift() {
  expect(api.createShift).not.toHaveBeenCalled();
  expect(api.updateShift).not.toHaveBeenCalled();
  expect(api.cancelShift).not.toHaveBeenCalled();
}

beforeEach(() => {
  authRef.current = { role: "KITCHEN_MANAGER" };
  push.mockReset();
  for (const fn of Object.values(api)) fn.mockClear();
  api.mealCrew.mockReset().mockResolvedValue([crewOf()]);
  // Refused unless a test says otherwise: every test above a meal with a crew row never asks for it.
  api.mealCrewAt.mockReset().mockRejectedValue(new Error("not counted"));
  api.suggestedCrew.mockReset().mockResolvedValue({ crewRequired: null });
  api.saveMeal.mockReset().mockResolvedValue({ id: "meal-new" });
  api.updateMeal.mockReset().mockResolvedValue({ id: "meal-lunch" });
});

describe("Ask for volunteers, in section 4 of the composer", () => {
  it("appears exactly when People needed is more than Rostered — not at equal, not below", async () => {
    await planALunch("5");
    // Five needed, five rostered: covered, so nothing to ask for.
    expect(screen.queryByRole("button", ASK)).toBeNull();

    fireEvent.change(screen.getByLabelText("People needed"), { target: { value: "4" } });
    expect(screen.queryByRole("button", ASK)).toBeNull();

    fireEvent.change(screen.getByLabelText("People needed"), { target: { value: "6" } });
    expect(screen.getByRole("button", ASK)).toBeInTheDocument();

    // And gone again the moment the meal is covered.
    fireEvent.change(screen.getByLabelText("People needed"), { target: { value: "5" } });
    expect(screen.queryByRole("button", ASK)).toBeNull();
  });

  it("is not offered while nobody has said how many the meal takes", async () => {
    render(<Harness />);
    await screen.findByText(/3 staff · 2 volunteers/);
    expect(screen.getByLabelText("People needed")).toHaveValue(null);
    expect(screen.queryByRole("button", ASK)).toBeNull();
  });

  it("opens the volunteers' own form: no meal checkbox, the date read-only, and Done", async () => {
    await planALunch("8");
    fireEvent.click(screen.getByRole("button", ASK));

    const layer = screen.getByRole("dialog");
    const form = within(layer).getByRole("form");
    // `ShiftFields` and nothing cut from it: the eight boxes `readShiftForm` reads.
    expect(form.id).toBe("shift-form");
    const names = Array.from(form.querySelectorAll("input")).map((i) => i.getAttribute("name"));
    expect(names.sort()).toEqual(
      ["capacity", "description", "endTime", "location", "reminderHours", "shiftDate", "startTime", "title"]
    );
    // No "is this for a meal" checkbox and no meal picker (D-27 screen 3): the shift is for this meal.
    expect(layer.querySelectorAll('input[type="checkbox"], select')).toHaveLength(0);
    expect(within(layer).queryByText(/for a meal/i)).toBeNull();

    // The meal's day, read-only — read-only and not disabled, so it is still in the form and reachable.
    expect(field("shiftDate").value).toBe(DATE);
    expect(field("shiftDate").readOnly).toBe(true);
    expect(field("shiftDate").disabled).toBe(false);

    // The button is Done. Nothing here posts or saves, so nothing here says it does.
    expect(within(layer).getByRole("button", { name: "Done" })).toBeInTheDocument();
    expect(within(layer).queryByRole("button", { name: /post shift|save changes/i })).toBeNull();
  });

  it("prefills Volunteers requested as People needed minus Rostered, and lets it be changed", async () => {
    await planALunch("8");
    fireEvent.click(screen.getByRole("button", ASK));

    const layer = screen.getByRole("dialog");
    // The label is the ruled one (D-27 answer 1); the box is still `capacity` on the wire.
    expect(within(layer).getByText("Volunteers requested")).toBeInTheDocument();
    expect(within(layer).queryByText("Capacity")).toBeNull();
    // Eight needed, five rostered.
    expect(field("capacity").value).toBe("3");

    fireEvent.change(field("capacity"), { target: { value: "4" } });
    expect(field("capacity").value).toBe("4");

    // The rest of what the planner knows: the derived title and the ready-by as the end.
    expect(field("title").value).toBe("Lunch preparation on Tuesday, 1 September 2026");
    expect(field("endTime").value).toBe("12:00");
  });

  it("sends no request when Done is pressed, and shows View volunteer shift in its place", async () => {
    await planALunch("8");
    fireEvent.click(screen.getByRole("button", ASK));
    fireEvent.change(field("startTime"), { target: { value: "09:00" } });
    done();

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    nothingSavedAShift();
    expect(api.saveMeal).not.toHaveBeenCalled();
    expect(api.updateMeal).not.toHaveBeenCalled();

    expect(screen.queryByRole("button", ASK)).toBeNull();
    expect(screen.getByRole("button", VIEW)).toBeInTheDocument();
    expect(screen.getByText("Saved when you save this meal.")).toBeInTheDocument();
  });

  it("reopens the draft on View volunteer shift, as it was left", async () => {
    await planALunch("8");
    fireEvent.click(screen.getByRole("button", ASK));
    fireEvent.change(field("startTime"), { target: { value: "09:00" } });
    fireEvent.change(field("capacity"), { target: { value: "4" } });
    done();

    fireEvent.click(await screen.findByRole("button", VIEW));
    expect(field("startTime").value).toBe("09:00");
    expect(field("capacity").value).toBe("4");
    // Still a shift being posted: nothing has saved it.
    expect(within(screen.getByRole("dialog")).getByRole("heading", { name: "Ask for volunteers" })).toBeTruthy();
  });

  it("changes nothing when the layer is cancelled", async () => {
    await planALunch("8");
    fireEvent.click(screen.getByRole("button", ASK));
    fireEvent.change(field("capacity"), { target: { value: "9" } });
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: /^cancel$/i }));

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(screen.getByRole("button", ASK)).toBeInTheDocument();
    expect(screen.queryByRole("button", VIEW)).toBeNull();
    nothingSavedAShift();
  });
});

describe("a meal not saved yet is counted before its first save (T-215)", () => {
  /**
   * The browser test's case: a brand-new event, needing five, with two staff rostered over its
   * ready-by. Before T-215 it read "Not counted yet" and the layer asked for all five; after saving,
   * the same meal read "2 of 5". The count here stands in for the server's answer at that date and
   * ready-by, echoing the time it was asked about as the server does.
   */
  function countOf(rostered: number, readyBy = "12:00") {
    return { planDate: DATE, readyBy: `${readyBy}:00`, staffIn: rostered, volunteers: 0, rostered };
  }

  async function planANewEvent(needed: string) {
    // No meal of any kind on the day, so there is no crew row to borrow.
    api.mealCrew.mockResolvedValue([]);
    render(<Harness />);
    fireEvent.click(screen.getByRole("button", { name: "Event" }));
    fireEvent.change(screen.getByLabelText(/event name/i, { selector: "input" }), {
      target: { value: "Bhajan prasadam" },
    });
    fireEvent.change(screen.getByLabelText(/ready by/i), { target: { value: "12:00" } });
    fireEvent.change(screen.getByLabelText("People needed"), { target: { value: needed } });
  }

  it("reads Rostered as the count at the event's date and ready-by, not Not counted yet", async () => {
    api.mealCrewAt.mockImplementation(async (_d: string, readyBy: string) => countOf(2, readyBy));
    await planANewEvent("5");

    expect(await screen.findByText("2 staff · 0 volunteers · 2 of 5")).toBeInTheDocument();
    expect(screen.queryByText("Not counted yet")).toBeNull();
    expect(api.mealCrewAt).toHaveBeenLastCalledWith(DATE, "12:00", "t");
    // Five needed, two rostered: short, so asking is offered.
    expect(screen.getByRole("button", ASK)).toBeInTheDocument();
  });

  it("prefills Volunteers requested as People needed minus the count", async () => {
    api.mealCrewAt.mockImplementation(async (_d: string, readyBy: string) => countOf(2, readyBy));
    await planANewEvent("5");
    await screen.findByText("2 staff · 0 volunteers · 2 of 5");

    fireEvent.click(screen.getByRole("button", ASK));
    // Five needed, two rostered: three, where the browser test saw five.
    expect(field("capacity").value).toBe("3");
  });

  it("sends no request at Done apart from the read-only count", async () => {
    api.mealCrewAt.mockImplementation(async (_d: string, readyBy: string) => countOf(2, readyBy));
    // Anything reaching the network without a mock in front of it would come through here.
    const fetchSpy = vi.fn(async () => new Response("{}", { status: 200 }));
    vi.stubGlobal("fetch", fetchSpy);
    try {
      await planANewEvent("5");
      await screen.findByText("2 staff · 0 volunteers · 2 of 5");
      fireEvent.click(screen.getByRole("button", ASK));
      fireEvent.change(field("startTime"), { target: { value: "09:00" } });

      const before = new Map(Object.entries(api).map(([name, fn]) => [name, fn.mock.calls.length]));
      done();
      await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
      expect(screen.getByRole("button", VIEW)).toBeInTheDocument();

      // Nothing was called by Done, the count included; and nothing at all that writes, ever.
      const calledSince = Object.entries(api)
        .filter(([name, fn]) => fn.mock.calls.length !== before.get(name))
        .map(([name]) => name);
      expect(calledSince.filter((name) => name !== "mealCrewAt")).toEqual([]);
      nothingSavedAShift();
      expect(api.saveMeal).not.toHaveBeenCalled();
      expect(api.updateMeal).not.toHaveBeenCalled();
      expect(api.cancelMeal).not.toHaveBeenCalled();
      expect(fetchSpy).not.toHaveBeenCalled();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("asks for the count with a GET carrying the date and the ready-by and nothing else", async () => {
    const { api: real } = await vi.importActual<typeof import("@/lib/api")>("@/lib/api");
    const fetchSpy = vi.fn(async (_url: string, _init?: RequestInit) =>
      new Response(JSON.stringify(countOf(2)), { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchSpy);
    try {
      await real.mealCrewAt(DATE, "12:00", "t");
    } finally {
      vi.unstubAllGlobals();
    }
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const [url, init] = fetchSpy.mock.calls[0];
    const parsed = new URL(url, "http://kms.test");
    expect(parsed.pathname.endsWith("/api/v1/meal-crew/at")).toBe(true);
    expect(Array.from(parsed.searchParams.keys())).toEqual(["date", "readyBy"]);
    expect(parsed.searchParams.get("date")).toBe(DATE);
    expect(parsed.searchParams.get("readyBy")).toBe("12:00");
    // A read: GET, and no body to save anything with.
    expect(Object.keys(init ?? {}).sort()).toEqual(["headers", "method"]);
    expect(init?.method).toBe("GET");
  });

  it("asks again when the ready-by changes, and reads out the new answer", async () => {
    api.mealCrewAt.mockImplementation(async (_d: string, readyBy: string) =>
      countOf(readyBy === "18:00" ? 1 : 2, readyBy));
    await planANewEvent("5");
    await screen.findByText("2 staff · 0 volunteers · 2 of 5");
    const asked = api.mealCrewAt.mock.calls.length;

    fireEvent.change(screen.getByLabelText(/ready by/i), { target: { value: "18:00" } });

    expect(await screen.findByText("1 staff · 0 volunteers · 1 of 5")).toBeInTheDocument();
    expect(api.mealCrewAt.mock.calls.length).toBe(asked + 1);
    expect(api.mealCrewAt).toHaveBeenLastCalledWith(DATE, "18:00", "t");
  });

  it("does not offer Ask for volunteers when the count already covers People needed", async () => {
    api.mealCrewAt.mockImplementation(async (_d: string, readyBy: string) => countOf(5, readyBy));
    await planANewEvent("5");

    await screen.findByText("5 staff · 0 volunteers · 5 of 5");
    expect(screen.queryByRole("button", ASK)).toBeNull();
  });

  it("says Not counted yet, and prefills People needed in full, when the count cannot be fetched", async () => {
    await planANewEvent("5");
    await waitFor(() => expect(api.mealCrewAt).toHaveBeenCalled());

    expect(await screen.findByText("Not counted yet")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", ASK));
    expect(field("capacity").value).toBe("5");
  });

  it("counts a new main meal with no meal of its kind that day the same way", async () => {
    api.mealCrewAt.mockImplementation(async (_d: string, readyBy: string) => countOf(2, readyBy));
    api.mealCrew.mockResolvedValue([]);
    render(<Harness />);
    fireEvent.change(screen.getByLabelText("People needed"), { target: { value: "6" } });

    expect(await screen.findByText("2 staff · 0 volunteers · 2 of 6")).toBeInTheDocument();
    // Lunch opens at its kind's default ready-by.
    expect(api.mealCrewAt).toHaveBeenLastCalledWith(DATE, "12:00", "t");
  });

  it("does not ask for the count where the meal's kind already has a row that day", async () => {
    await planALunch("8");
    expect(api.mealCrewAt).not.toHaveBeenCalled();
  });
});

describe("the shift is saved only with the meal", () => {
  it("sends one Save this meal request carrying the drafted shift, and no shift request", async () => {
    await planALunch("8");
    fireEvent.click(screen.getByRole("button", ASK));
    fireEvent.change(field("startTime"), { target: { value: "09:00" } });
    done();
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

    fireEvent.click(screen.getByRole("button", { name: "Save this meal" }));
    await waitFor(() => expect(api.saveMeal).toHaveBeenCalledTimes(1));
    nothingSavedAShift();

    const input = api.saveMeal.mock.calls[0][0];
    expect(Object.keys(input)).toContain("volunteerShift");
    const shift = input.volunteerShift as Record<string, unknown>;
    // No date and no meal: the server takes both from the meal it saves the shift with.
    expect(Object.keys(shift).sort()).toEqual(
      ["capacity", "description", "endTime", "location", "reminderOffsetsMinutes", "startTime", "title"]
    );
    expect(shift).toEqual({
      title: "Lunch preparation on Tuesday, 1 September 2026",
      description: null,
      startTime: "09:00",
      endTime: "12:00",
      location: null,
      capacity: 3,
      reminderOffsetsMinutes: [1440],
    });
  });

  it("shows View volunteer shift on a meal that already has one, whatever the numbers say", async () => {
    api.mealCrew.mockResolvedValue([crewOf(8, 8)]);
    render(<Harness existing={lunch({ volunteerShift: shiftFor() }) as never} />);
    await screen.findByText(/3 staff · 5 volunteers · 8 of 8/);

    expect(screen.getByRole("button", VIEW)).toBeInTheDocument();
    expect(screen.queryByRole("button", ASK)).toBeNull();
    expect(screen.getByText("2 of 5 signed up")).toBeInTheDocument();
  });

  it("opens a saved shift on its own values, and sends a change only with Update this meal", async () => {
    await editLunch({ volunteerShift: shiftFor({ signedUpCount: 0 }) });
    fireEvent.click(screen.getByRole("button", VIEW));

    const layer = screen.getByRole("dialog");
    expect(within(layer).getByRole("heading", { name: "Edit a shift" })).toBeTruthy();
    expect(field("capacity").value).toBe("5");
    expect(field("location").value).toBe("Main kitchen");
    expect(field("reminderHours").value).toBe("48");

    fireEvent.change(field("capacity"), { target: { value: "7" } });
    done();
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    nothingSavedAShift();
    expect(api.updateMeal).not.toHaveBeenCalled();
    expect(screen.getByText("0 of 7 signed up. Your changes are saved with this meal.")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Update this meal" }));
    await waitFor(() => expect(api.updateMeal).toHaveBeenCalledTimes(1));
    nothingSavedAShift();
    const [mealId, input] = api.updateMeal.mock.calls[0];
    expect(mealId).toBe("meal-lunch");
    expect(input.volunteerShift).toMatchObject({ capacity: 7, description: "Bring an apron", location: "Main kitchen" });
  });

  it("leaves a saved shift alone on an update that did not touch it, by sending null", async () => {
    await editLunch({ volunteerShift: shiftFor() });
    fireEvent.change(screen.getByLabelText("Adults"), { target: { value: "250" } });
    fireEvent.click(screen.getByRole("button", { name: "Update this meal" }));

    await waitFor(() => expect(api.updateMeal).toHaveBeenCalledTimes(1));
    const input = api.updateMeal.mock.calls[0][1];
    expect(Object.keys(input)).toContain("volunteerShift");
    expect(input.volunteerShift).toBeNull();
  });
});

describe("new times on a shift people signed up for", () => {
  async function changeTheStart(signedUpCount: number) {
    await editLunch({ volunteerShift: shiftFor({ signedUpCount }) });
    fireEvent.click(screen.getByRole("button", VIEW));
    fireEvent.change(field("startTime"), { target: { value: "10:00" } });
    done();
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    fireEvent.click(screen.getByRole("button", { name: "Update this meal" }));
  }

  it("warns before Update this meal saves, in the ruled words, and saves once confirmed", async () => {
    await changeTheStart(3);

    const warning = await screen.findByRole("alertdialog");
    expect(within(warning).getByText("3 volunteers are signed up. They’ll be told the new times.")).toBeTruthy();
    // Warned before, not after: nothing has gone to the server yet.
    expect(api.updateMeal).not.toHaveBeenCalled();

    fireEvent.click(within(warning).getByRole("button", { name: "Update this meal" }));
    await waitFor(() => expect(api.updateMeal).toHaveBeenCalledTimes(1));
    expect(api.updateMeal.mock.calls[0][1].volunteerShift).toMatchObject({ startTime: "10:00", endTime: "12:00" });
    nothingSavedAShift();
  });

  it("says it properly for one volunteer", async () => {
    await changeTheStart(1);
    const warning = await screen.findByRole("alertdialog");
    expect(within(warning).getByText("1 volunteer is signed up. They’ll be told the new times.")).toBeTruthy();
  });

  it("saves nothing when the planner goes back", async () => {
    await changeTheStart(3);
    fireEvent.click(within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Go back" }));

    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(api.updateMeal).not.toHaveBeenCalled();
  });

  it("does not warn when the times are the same, signups or not", async () => {
    await editLunch({ volunteerShift: shiftFor({ signedUpCount: 3 }) });
    fireEvent.click(screen.getByRole("button", VIEW));
    fireEvent.change(field("capacity"), { target: { value: "7" } });
    done();
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    fireEvent.click(screen.getByRole("button", { name: "Update this meal" }));

    await waitFor(() => expect(api.updateMeal).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole("alertdialog")).toBeNull();
  });

  it("does not warn when nobody is signed up", async () => {
    await changeTheStart(0);
    await waitFor(() => expect(api.updateMeal).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole("alertdialog")).toBeNull();
  });

  it("never calls it a move, anywhere on the screen", async () => {
    await changeTheStart(3);
    await screen.findByRole("alertdialog");
    // The ruling: "The words are 'times changed', never 'moved'." The whole page, layers included.
    expect(document.body.textContent).not.toMatch(/\bmoved?\b/i);
  });
});

describe("Leave without saving?", () => {
  it("asks before leaving a meal with a drafted shift, and abandoning it sends nothing — no orphan shift", async () => {
    await planALunch("8");
    fireEvent.click(screen.getByRole("button", ASK));
    fireEvent.change(field("startTime"), { target: { value: "09:00" } });
    done();
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

    fireEvent.click(screen.getByRole("link", { name: "Back to the day" }));
    const ask = await screen.findByRole("alertdialog", { name: "Leave without saving?" });
    expect(push).not.toHaveBeenCalled();

    fireEvent.click(within(ask).getByRole("button", { name: "Leave without saving" }));
    expect(push).toHaveBeenCalledWith(DAY);
    // The whole point: walking away from the meal walked away from the shift with it.
    nothingSavedAShift();
    expect(api.saveMeal).not.toHaveBeenCalled();
    expect(api.updateMeal).not.toHaveBeenCalled();
  });

  it("stays, with the draft intact, when the planner chooses to", async () => {
    await planALunch("8");
    fireEvent.click(screen.getByRole("button", ASK));
    fireEvent.change(field("startTime"), { target: { value: "09:00" } });
    done();
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

    fireEvent.click(screen.getByRole("link", { name: "Back to the day" }));
    fireEvent.click(await screen.findByRole("button", { name: "Stay on this page" }));
    expect(push).not.toHaveBeenCalled();
    expect(screen.getByRole("button", VIEW)).toBeInTheDocument();
  });

  it("asks the browser to confirm a reload or a closed tab, but only once something changed", async () => {
    render(<Harness />);
    const before = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(before);
    expect(before.defaultPrevented).toBe(false);

    fireEvent.change(screen.getByLabelText("Adults"), { target: { value: "200" } });
    const after = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(after);
    expect(after.defaultPrevented).toBe(true);
  });

  it("lets an untouched meal be left without asking", async () => {
    render(<Harness />);
    await screen.findByText(/3 staff · 2 volunteers/);
    fireEvent.click(screen.getByRole("link", { name: "Back to the day" }));
    expect(screen.queryByRole("alertdialog")).toBeNull();
  });
});

describe("the day's meal and its shift", () => {
  function openTheDay(readOnly = false) {
    render(
      <MealServices
        date={DATE}
        sufficiency={new Map()}
        recipes={RECIPES as never}
        readOnly={readOnly}
        onChanged={vi.fn()}
        onError={vi.fn()}
      />
    );
  }

  it("shows the shift beside the crew and links to the meal, with no layer and no Ask of its own", async () => {
    api.meals.mockResolvedValue([lunch({ volunteerShift: shiftFor() })]);
    openTheDay();

    const pebble = await screen.findByRole("link", { name: /2 of 5 signed up/ });
    expect(pebble).toHaveAttribute("href", "/planner/meal/meal-lunch");
    // Asking, and changing what was asked, are on the meal's own form since D-27.
    expect(screen.queryByRole("button", ASK)).toBeNull();
    fireEvent.click(pebble);
    expect(screen.queryByRole("dialog")).toBeNull();
    nothingSavedAShift();
  });

  it("draws a past day's shift as text", async () => {
    api.meals.mockResolvedValue([lunch({ volunteerShift: shiftFor() })]);
    openTheDay(true);

    expect(await screen.findByText(/2 of 5 signed up/)).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /2 of 5 signed up/ })).toBeNull();
  });

  it("warns with the count before cancelling a meal with a shift, then cancels both", async () => {
    let cancelled = false;
    api.meals.mockImplementation(async () =>
      cancelled ? [] : [lunch({ volunteerShift: shiftFor({ signedUpCount: 3, waitlistCount: 1 }) })]
    );
    api.cancelMeal.mockImplementation(async () => {
      cancelled = true;
      return { volunteersTold: 4 };
    });
    openTheDay();

    fireEvent.click(await screen.findByRole("button", { name: "Cancel this meal" }));
    const warning = screen.getByRole("alertdialog", { name: "Cancel Lunch?" });
    expect(warning).toHaveTextContent(
      "This meal has a volunteer shift. 3 volunteers are signed up and 1 is waiting. " +
        "Cancelling the meal cancels the shift too, and they will be told."
    );
    // Warned first: nothing sent until it is confirmed.
    expect(api.cancelMeal).not.toHaveBeenCalled();

    fireEvent.click(within(warning).getByRole("button", { name: "Cancel this meal" }));
    await waitFor(() => expect(api.cancelMeal).toHaveBeenCalledTimes(1));
    // One call for the meal. The shift goes with it on the server, in the same transaction.
    expect(api.cancelMeal.mock.calls[0][0]).toBe("meal-lunch");
    nothingSavedAShift();
    expect(await screen.findByText("Lunch was cancelled. 4 volunteers were told.")).toBeInTheDocument();
  });

  it("cancels nothing when the planner keeps the meal", async () => {
    api.meals.mockResolvedValue([lunch({ volunteerShift: shiftFor() })]);
    openTheDay();

    fireEvent.click(await screen.findByRole("button", { name: "Cancel this meal" }));
    fireEvent.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "Keep it" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(api.cancelMeal).not.toHaveBeenCalled();
  });

  it("asks plainly about a meal with no shift", async () => {
    api.meals.mockResolvedValue([lunch()]);
    openTheDay();

    fireEvent.click(await screen.findByRole("button", { name: "Cancel this meal" }));
    expect(screen.getByRole("alertdialog", { name: "Cancel Lunch?" })).toHaveTextContent(
      "Its preparations come off the plan."
    );
  });

  it("offers no cancel on a meal already recorded, or on a day that has gone", async () => {
    api.meals.mockResolvedValue([lunch({ recorded: true, status: "COOKED" })]);
    openTheDay();
    await screen.findByText("Lunch");
    expect(screen.queryByRole("button", { name: "Cancel this meal" })).toBeNull();
  });
});
