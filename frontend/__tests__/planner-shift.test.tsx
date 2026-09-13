import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";

/**
 * Asking for volunteers from the planner, and seeing and correcting the shift there afterwards
 * (T-019, rebuilt by T-155).
 *
 * <p>Everything here is asserted against `MealServices`, because that is where the shortfall is
 * drawn and the affordance sits beside it.
 *
 * <p>T-155 replaced T-019's three-field layer with the volunteers' own form, `ShiftFields`, shown
 * in a layer (DESIGN_SYSTEM v1.8 §4). So the layer is identified here by things only that form
 * renders — its `shift-form` id and its reminders box — rather than by a heading any copy could
 * carry. A cut-down copy put back would fail "opens the volunteers' own shift form".
 *
 * <p>The calls are typed like the real ones so the assertions read what was sent rather than
 * casting past an untyped mock — and the meal link is asserted through `Object.keys` and not
 * `objectContaining`, because a missing property and an explicit null read identically to that
 * matcher, and a missing link on a save takes the link off the shift.
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

/** Five of the eight are rostered by default, so the meal is three short. */
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

/** One box of the form inside the layer, by the name `ShiftFields` gives it. */
function field(name: string): HTMLInputElement {
  const input = screen.getByRole("dialog").querySelector(`[name="${name}"]`);
  if (!(input instanceof HTMLInputElement)) throw new Error(`no ${name} field in the layer`);
  return input;
}

const ASK = { name: /ask for volunteers/i };

describe("asking for volunteers from the planner", () => {
  beforeEach(() => {
    authRef.current = { role: "KITCHEN_MANAGER" };
    push.mockClear();
    createShift.mockReset().mockResolvedValue({ id: "s-new" });
    updateShift.mockReset().mockResolvedValue(undefined);
    mealServices.mockReset().mockResolvedValue([lunch()]);
    mealCrew.mockReset().mockResolvedValue([crewOf()]);
    listShifts.mockReset().mockResolvedValue([]);
    jobCardLanguages.mockClear();
  });

  it("opens the volunteers' own shift form over the planner, prefilled from the meal", async () => {
    const day = await openTheDay();
    await settled(day, "5 of 8");
    fireEvent.click(screen.getByRole("button", ASK));

    const layer = screen.getByRole("dialog");
    const form = within(layer).getByRole("form");
    // Only `ShiftFields` renders these: the form named so a header button can submit it, and the
    // reminders box, which the three-field copy this replaced never had.
    expect(form.id).toBe("shift-form");
    expect(within(layer).getByText("Reminder hours before")).toBeTruthy();
    // All eight of the form's fields, by the names `readShiftForm` reads, and nothing cut.
    const names = Array.from(form.querySelectorAll("input")).map((i) => i.getAttribute("name"));
    expect(names.sort()).toEqual(
      ["capacity", "description", "endTime", "location", "reminderHours", "shiftDate", "startTime", "title"]
    );

    // Prefilled from the meal: its derived title, its day, three hands short, wanted up to ready-by.
    expect(field("title").value).toBe("Lunch preparation on Tuesday, 1 September 2026");
    expect(field("shiftDate").value).toBe(DATE);
    expect(field("shiftDate").readOnly).toBe(true);
    expect(field("capacity").value).toBe("3");
    expect(field("endTime").value).toBe("12:00");
    // And what the planner does not know opens as it does on the volunteers screen.
    expect(field("startTime").value).toBe("");
    expect(field("location").value).toBe("");
    expect(field("reminderHours").value).toBe("24");
  });

  it("calls a prefilled new shift a new one, not an edit", async () => {
    await openTheDay();
    fireEvent.click(screen.getByRole("button", ASK));

    const layer = screen.getByRole("dialog");
    // What a screen reader announces for the form. Having values is not the same as existing.
    expect(within(layer).getByRole("form").getAttribute("aria-label")).toBe("Post a shift");
    expect(within(layer).queryByRole("form", { name: /edit a shift/i })).toBeNull();
    expect(within(layer).getByRole("heading", { name: "Post a shift" })).toBeTruthy();
  });

  it("says the shift runs into the next day for 20:00 to 02:00", async () => {
    await openTheDay();
    fireEvent.click(screen.getByRole("button", ASK));
    const layer = screen.getByRole("dialog");

    fireEvent.change(field("startTime"), { target: { value: "20:00" } });
    // Later the same evening first, so the line is shown to appear for the crossing and not for any
    // pair of times at all.
    fireEvent.change(field("endTime"), { target: { value: "22:00" } });
    expect(layer.textContent).not.toMatch(/next day/i);

    fireEvent.change(field("endTime"), { target: { value: "02:00" } });
    expect(within(layer).getByText(/Ends the next day/i)).toBeTruthy();
  });

  it("posts the shift with the meal link and closes onto the same day", async () => {
    await openTheDay();
    fireEvent.click(screen.getByRole("button", ASK));
    fireEvent.change(field("startTime"), { target: { value: "09:00" } });
    // The commit button sits in the layer's header, outside the form, and reaches it by id — as it
    // does on the volunteers screen.
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: /post shift/i }));

    await waitFor(() => expect(createShift).toHaveBeenCalled());
    const input = createShift.mock.calls[0][0];
    expect(input.title).toBe("Lunch preparation on Tuesday, 1 September 2026");
    expect(input.shiftDate).toBe(DATE);
    expect(input.startTime).toBe("09:00");
    expect(input.endTime).toBe("12:00");
    expect(input.capacity).toBe(3);
    expect(input.reminderOffsetsMinutes).toEqual([1440]);
    const keys = Object.keys(input);
    expect(keys).toContain("mealDate");
    expect(keys).toContain("mealKind");
    expect(keys).toContain("mealEventName");
    expect(input.mealDate).toBe(DATE);
    expect(input.mealKind).toBe("Lunch");
    expect(input.mealEventName).toBeNull();
    // Nothing navigated, and the day is still the thing on screen with the layer gone.
    expect(push).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(screen.getByText("Lunch")).toBeTruthy();
  });

  it("links an event's shift by the event's own name", async () => {
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

    fireEvent.click(screen.getByRole("button", ASK));
    fireEvent.change(field("startTime"), { target: { value: "07:00" } });
    fireEvent.submit(within(screen.getByRole("dialog")).getByRole("form"));

    await waitFor(() => expect(createShift).toHaveBeenCalled());
    const input = createShift.mock.calls[0][0];
    const keys = Object.keys(input);
    expect(keys).toContain("mealDate");
    expect(keys).toContain("mealKind");
    expect(keys).toContain("mealEventName");
    expect(input.mealDate).toBe(DATE);
    expect(input.mealKind).toBe("Event");
    expect(input.mealEventName).toBe("Bhagavad Gita Parayanam");
    expect(input.title).toContain("Bhagavad Gita Parayanam preparation on");
  });

  it("replaces the button with the posted shift, which opens in the same layer to edit", async () => {
    // The list re-reads after the save; from then on it has the shift that was just posted.
    let posted = false;
    listShifts.mockImplementation(async () =>
      posted ? [shiftFor({ id: "s-new", description: null, location: null, capacity: 3, signedUpCount: 0, reminderOffsetsMinutes: [1440] })] : []
    );
    createShift.mockImplementation(async () => {
      posted = true;
      return { id: "s-new" };
    });

    await openTheDay();
    fireEvent.click(screen.getByRole("button", ASK));
    fireEvent.change(field("startTime"), { target: { value: "09:00" } });
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: /post shift/i }));

    const opener = await screen.findByRole("button", { name: /0 of 3 signed up/ });
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(screen.queryByRole("button", ASK)).toBeNull();

    fireEvent.click(opener);
    const layer = screen.getByRole("dialog");
    expect(within(layer).getByRole("form").getAttribute("aria-label")).toBe("Edit a shift");
    expect(field("title").value).toBe("Lunch preparation on Tuesday, 1 September 2026");
    expect(field("capacity").value).toBe("3");
    expect(push).not.toHaveBeenCalled();
  });

  it("shows a shift already raised instead of the button, and edits it on the full form", async () => {
    listShifts.mockResolvedValue([shiftFor()]);
    const day = await openTheDay();

    await settled(day, "2 of 5 signed up");
    expect(day.textContent).toContain("5 of 8");
    expect(screen.queryByRole("button", ASK)).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: /2 of 5 signed up/ }));
    const layer = screen.getByRole("dialog");
    // An existing shift is announced as an edit, and headed as one.
    expect(within(layer).getByRole("form").getAttribute("aria-label")).toBe("Edit a shift");
    expect(within(layer).getByRole("heading", { name: "Edit a shift" })).toBeTruthy();
    // The shift's own values, all of them — not the meal's suggestions.
    expect(field("startTime").value).toBe("09:00");
    expect(field("capacity").value).toBe("5");
    expect(field("location").value).toBe("Main kitchen");
    expect(field("description").value).toBe("Bring an apron");
    expect(field("reminderHours").value).toBe("48");

    fireEvent.change(field("capacity"), { target: { value: "7" } });
    fireEvent.change(field("location"), { target: { value: "Prep area" } });
    fireEvent.click(within(layer).getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(updateShift).toHaveBeenCalled());
    const [id, input] = updateShift.mock.calls[0];
    expect(id).toBe("s1");
    expect(input.capacity).toBe(7);
    expect(input.location).toBe("Prep area");
    expect(input.description).toBe("Bring an apron");
    expect(input.reminderOffsetsMinutes).toEqual([2880]);
    expect(input.title).toBe("Lunch preparation on Tuesday, 1 September 2026");
    // A save without the link would take it off the shift, so a correction carries it too.
    const keys = Object.keys(input);
    expect(keys).toContain("mealDate");
    expect(keys).toContain("mealKind");
    expect(keys).toContain("mealEventName");
    expect(input.mealKind).toBe("Lunch");
    expect(push).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(createShift).not.toHaveBeenCalled();
  });

  it("offers the button on a meal that is short of hands", async () => {
    mealCrew.mockResolvedValue([crewOf("Lunch", 7, 8)]);
    const day = await openTheDay();
    await settled(day, "7 of 8");
    expect(screen.getByRole("button", ASK)).toBeTruthy();
  });

  it("offers no button on a meal that is fully crewed", async () => {
    mealCrew.mockResolvedValue([crewOf("Lunch", 8, 8)]);
    const day = await openTheDay();
    await settled(day, "8 of 8");
    expect(screen.queryByRole("button", ASK)).toBeNull();
  });

  it("still shows a shift raised for a meal that has since filled, so it can be opened", async () => {
    mealCrew.mockResolvedValue([crewOf("Lunch", 8, 8)]);
    listShifts.mockResolvedValue([shiftFor({ signedUpCount: 5 })]);
    const day = await openTheDay();
    await settled(day, "5 of 5 signed up");
    expect(screen.queryByRole("button", ASK)).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /5 of 5 signed up/ }));
    expect(within(screen.getByRole("dialog")).getByRole("form", { name: "Edit a shift" })).toBeTruthy();
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
    expect(screen.getByRole("button", ASK)).toBeTruthy();
  });

  it("changes nothing when the layer is opened and cancelled", async () => {
    const day = await openTheDay();
    fireEvent.click(screen.getByRole("button", ASK));
    fireEvent.change(field("capacity"), { target: { value: "9" } });
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: /^cancel$/i }));

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(createShift).not.toHaveBeenCalled();
    expect(updateShift).not.toHaveBeenCalled();
    expect(day.textContent).toContain("5 of 8");
    expect(screen.getByRole("button", ASK)).toBeTruthy();
  });

  it("offers nothing on a day that has been and gone", async () => {
    listShifts.mockResolvedValue([shiftFor()]);
    const day = await openTheDay(true);

    await settled(day, "2 of 5 signed up");
    // Readable, and not pressable: a past day's shift is a record.
    expect(screen.queryByRole("button", { name: /2 of 5 signed up/ })).toBeNull();
    expect(screen.queryByRole("button", ASK)).toBeNull();
  });

  it("fixes a new shift's date to the meal's day: read-only, described, and still sent", async () => {
    await openTheDay();
    fireEvent.click(screen.getByRole("button", ASK));

    const date = field("shiftDate");
    // Read-only and not disabled: a disabled box is left out of the form's data and cannot be
    // focused, so the save would go without a date and a keyboard could not reach it.
    expect(date.readOnly).toBe(true);
    expect(date.disabled).toBe(false);
    expect(date.value).toBe(DATE);
    // Said, not only drawn: the line under the box is its description.
    const described = document.getElementById(date.getAttribute("aria-describedby") ?? "");
    expect(described?.textContent).toMatch(/day of the meal/i);

    // Typing at it changes nothing.
    fireEvent.change(date, { target: { value: "2026-09-05" } });
    expect(field("shiftDate").value).toBe(DATE);

    fireEvent.change(field("startTime"), { target: { value: "09:00" } });
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: /post shift/i }));
    await waitFor(() => expect(createShift).toHaveBeenCalled());
    expect(createShift.mock.calls[0][0].shiftDate).toBe(DATE);
  });

  it("fixes an existing shift's date to the meal's day too", async () => {
    listShifts.mockResolvedValue([shiftFor()]);
    await openTheDay();
    fireEvent.click(await screen.findByRole("button", { name: /2 of 5 signed up/ }));

    expect(field("shiftDate").readOnly).toBe(true);
    expect(field("shiftDate").value).toBe(DATE);
    fireEvent.change(field("shiftDate"), { target: { value: "2026-09-05" } });

    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: /save changes/i }));
    await waitFor(() => expect(updateShift).toHaveBeenCalled());
    expect(updateShift.mock.calls[0][1].shiftDate).toBe(DATE);
  });

  it("warns on the planner, beside the meal, when a save moves a shift people signed up for", async () => {
    listShifts.mockResolvedValue([shiftFor()]);
    await openTheDay();
    fireEvent.click(await screen.findByRole("button", { name: /2 of 5 signed up/ }));
    fireEvent.change(field("startTime"), { target: { value: "10:00" } });
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(updateShift).toHaveBeenCalled());
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

    const notice = (await screen.findByText(/have not been told/i)).closest('[role="status"]') as HTMLElement;
    // The volunteers screen's words exactly, because it is the same component saying them.
    expect(notice.textContent?.replace(/\s+/g, " ").trim()).toBe(
      "That shift moved, and the 2 volunteers already signed up have not been told. " +
        "Their reminders now fire at the new time. Send them an update."
    );
    expect(within(notice).getByRole("link", { name: /send them an update/i }).getAttribute("href")).toBe(
      "/volunteers/s1"
    );
    // In lunch's own block, under its header, where the reader pressed the shift.
    expect(notice.closest("section")?.textContent).toContain("Ready by");
    expect(push).not.toHaveBeenCalled();
  });

  it("does not warn when the moved shift has nobody signed up", async () => {
    listShifts.mockResolvedValue([shiftFor({ signedUpCount: 0 })]);
    await openTheDay();
    fireEvent.click(await screen.findByRole("button", { name: /0 of 5 signed up/ }));
    fireEvent.change(field("startTime"), { target: { value: "10:00" } });
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(updateShift).toHaveBeenCalled());
    expect(updateShift.mock.calls[0][1].startTime).toBe("10:00");
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    await waitFor(() => expect(listShifts.mock.calls.length).toBeGreaterThan(1));
    expect(screen.queryByText(/have not been told/i)).toBeNull();
  });

  it("does not warn when a save leaves the times alone", async () => {
    listShifts.mockResolvedValue([shiftFor()]);
    await openTheDay();
    fireEvent.click(await screen.findByRole("button", { name: /2 of 5 signed up/ }));
    fireEvent.change(field("capacity"), { target: { value: "7" } });
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(updateShift).toHaveBeenCalled());
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    await waitFor(() => expect(listShifts.mock.calls.length).toBeGreaterThan(1));
    expect(screen.queryByText(/have not been told/i)).toBeNull();
  });

  it("offers nothing to a reader who cannot raise a shift", async () => {
    authRef.current = { role: "VOLUNTEER" };
    listShifts.mockResolvedValue([shiftFor({ id: "s1", mealKind: "Breakfast" })]);
    const day = await openTheDay();

    await settled(day, "5 of 8");
    expect(screen.queryByRole("button", ASK)).toBeNull();
  });
});
