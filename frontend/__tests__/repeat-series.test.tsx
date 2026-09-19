import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";

/**
 * Repeating events as a series, on the planner's day (T-308). Rajeev, 2026-09-19:
 *
 * <blockquote>"Let us change for many weeks to 'until' a date and also give them the option to pick
 * the duration between repeats, and a cancel of this repeating event should ask JUST this event OR
 * all events from this point onwards. An example of how it would look on screen: 'Repeat Children's
 * Bhagavad-gita Reading once every [1] week / weeks (if the selected value is more than 1) until 31
 * Dec 2026'."</blockquote>
 *
 * <p>The clock is fixed at 11:30 on Saturday 19 September 2026 in the temple (06:00 UTC, the temple
 * being in India), and the event is on Saturday 26 September, so every date below can be checked by
 * hand against a calendar. Only `Date` is faked: the preview's pause is a real timer, so the order in
 * which answers arrive is the real order.
 *
 * <p>Where the shape of a request matters, the call's own arguments are read rather than matched
 * loosely: "no series argument at all" and "a series argument saying THIS" are different requests.
 */

const { api } = vi.hoisted(() => ({
  api: {
    meals: vi.fn(async (_from: string, _to: string, _t?: string) => [] as unknown[]),
    mealCrew: vi.fn(async (_from: string, _to: string, _t?: string) => [] as unknown[]),
    jobCardLanguages: vi.fn(async () => ({ languages: ["en"], defaultLanguage: "en" })),
    previewRepeat: vi.fn(),
    repeatEvent: vi.fn(),
    laterInSeries: vi.fn(),
    cancelMeal: vi.fn(),
  },
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn(), refresh: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ getToken: async () => "t", appUser: { role: "TEMPLE_ADMIN" } }),
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

import { MealServices, repeatDates, seriesLine } from "@/components/planner/MealServices";
import { ApiError } from "@/lib/api";

const DATE = "2026-09-26";
const NAME = "Children's Bhagavad-gita Reading";

function reading(overrides: Record<string, unknown> = {}) {
  return {
    mealId: "meal-3", mealKindId: "k2", planDate: DATE, mealKind: "Event", readyBy: "17:00:00",
    adults: 0, children: 0, seniors: 0, plates: 30, crewRequired: null,
    dayType: "REGULAR", occasionName: null, eventName: NAME, isOutside: false, handover: null,
    contactName: null, contactPhone: null, deliveryAddress: null, deliverySubLocation: null,
    deliveryPlaceId: null, deliveryLatitude: null, deliveryLongitude: null, guestsEatAt: null,
    travelMinutes: null, travelMinutesSource: null, purpose: null, kitchenNotes: null, serverNotes: null,
    status: "PLANNED", cardNumber: null, cardIssuedAt: null,
    recorded: false, recordedAt: null, recordedByName: null, recordingNote: null,
    corrected: false, correctedAt: null, correctedByName: null, correctionNote: null,
    dishes: [
      { id: "d1", mealId: "meal-3", recipeId: "r1", recipeName: "Kesari Bath", targetYield: 30,
        targetYieldUnit: "KG", status: "PLANNED", actualServings: null, consumedQuantity: null,
        notMade: false, originalActualServings: null, originalConsumedQuantity: null, cookedAt: null,
        ekadashiAcknowledged: false, createdAt: "2026-09-01T10:00:00Z" },
    ],
    volunteerShift: null,
    series: null,
    ...overrides,
  };
}

const SERIES = { seriesId: "s-1", everyWeeks: 2, until: "2026-12-31", position: 3, count: 8 };

function result(overrides: Record<string, unknown> = {}) {
  return {
    copies: 0, preparations: 0, dates: [] as string[], skippedFasting: [] as string[],
    skippedAlreadyPlanned: [] as string[], lastDate: null as string | null, series: null, ...overrides,
  };
}

function refusal(code: string, message: string, action: string, status = 400) {
  return new ApiError({ code, message, action, fieldErrors: [] }, status);
}

function openTheDay() {
  render(
    <MealServices
      date={DATE}
      sufficiency={new Map()}
      recipes={[{ id: "r1", name: "Kesari Bath", baseYieldUnit: "KG" }] as never}
      readOnly={false}
      onChanged={vi.fn()}
      onError={vi.fn()}
    />
  );
}

/**
 * A phrase of the sentence as the reader sees it, less the box's value: the words either side of a
 * box, joined by the space the layout's gap draws between them — "once every week".
 */
function phraseAround(label: string) {
  return Array.from(screen.getByLabelText(label).parentElement!.childNodes)
    .map((n) => n.textContent?.trim())
    .filter(Boolean)
    .join(" ");
}

function gapPhrase() {
  return phraseAround("Weeks between repeats");
}

/** The live answer under the control. */
function answer() {
  return screen.getByText(/^Makes |^No copies fit|^An event can repeat|^The end date/);
}

async function openRepeat() {
  fireEvent.click(await screen.findByRole("button", { name: "Repeat this event" }));
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-09-19T06:00:00Z"));
  window.localStorage.clear();
  for (const fn of Object.values(api)) fn.mockReset();
  api.mealCrew.mockResolvedValue([]);
  api.jobCardLanguages.mockResolvedValue({ languages: ["en"], defaultLanguage: "en" });
  api.meals.mockResolvedValue([reading()]);
  // A preview that never answers unless a test says otherwise, so the arithmetic is what shows.
  api.previewRepeat.mockImplementation(() => new Promise(() => undefined));
});

afterEach(() => {
  vi.useRealTimers();
});

describe("the dates a repeat makes, from the arithmetic alone", () => {
  it("counts every multiple of the gap up to and including the end date", () => {
    expect(repeatDates(DATE, 1, "2026-10-17", "2026-09-19")).toEqual([
      "2026-10-03", "2026-10-10", "2026-10-17",
    ]);
    // A day short of the next copy stops at the one before.
    expect(repeatDates(DATE, 1, "2026-10-16", "2026-09-19")).toEqual(["2026-10-03", "2026-10-10"]);
    expect(repeatDates(DATE, 3, "2026-12-31", "2026-09-19")).toEqual([
      "2026-10-17", "2026-11-07", "2026-11-28", "2026-12-19",
    ]);
  });

  it("never counts a date before today at the temple, and counts today itself", () => {
    // An event three weeks ago, repeated weekly: 5 and 12 September have gone; the 19th is today.
    expect(repeatDates("2026-08-29", 1, "2026-10-03", "2026-09-19")).toEqual([
      "2026-09-19", "2026-09-26", "2026-10-03",
    ]);
  });

  it("makes nothing for an end date before the first copy", () => {
    expect(repeatDates(DATE, 2, "2026-10-09", "2026-09-19")).toEqual([]);
  });
});

describe("the repeat control", () => {
  it("reads as one sentence, with 'week' at 1 and 'weeks' above it", async () => {
    openTheDay();
    await openRepeat();

    expect(screen.getByText(`Repeat ${NAME}`)).toBeInTheDocument();
    expect(gapPhrase()).toBe("once every week");
    expect(phraseAround("Repeat until")).toBe("until");

    fireEvent.change(screen.getByLabelText("Weeks between repeats"), { target: { value: "2" } });
    expect(gapPhrase()).toBe("once every weeks");
    fireEvent.change(screen.getByLabelText("Weeks between repeats"), { target: { value: "1" } });
    expect(gapPhrase()).toBe("once every week");
  });

  it("opens on six repeats, and says how many copies and the last date before anything is pressed", async () => {
    openTheDay();
    await openRepeat();

    // Every week, six repeats: 3 Oct to 7 Nov.
    expect(screen.getByLabelText("Repeat until")).toHaveValue("2026-11-07");
    expect(answer()).toHaveTextContent("Makes 6 copies · last one Sat 7 Nov 2026");

    // Every two weeks, and the end date follows the gap until somebody picks one: 10 Oct to 19 Dec.
    fireEvent.change(screen.getByLabelText("Weeks between repeats"), { target: { value: "2" } });
    expect(screen.getByLabelText("Repeat until")).toHaveValue("2026-12-19");
    expect(answer()).toHaveTextContent("Makes 6 copies · last one Sat 19 Dec 2026");

    // Every twelve weeks, six repeats would be 2028: held to a year from today, which fits four.
    fireEvent.change(screen.getByLabelText("Weeks between repeats"), { target: { value: "12" } });
    expect(screen.getByLabelText("Repeat until")).toHaveValue("2027-09-19");
    expect(screen.getByLabelText("Repeat until")).toHaveAttribute("max", "2027-09-19");
    expect(screen.getByLabelText("Repeat until")).toHaveAttribute("min", "2026-12-19");
    expect(answer()).toHaveTextContent("Makes 4 copies · last one Sat 28 Aug 2027");
  });

  it("counts an end date that falls exactly on a copy, and one that falls a day short of it", async () => {
    openTheDay();
    await openRepeat();

    fireEvent.change(screen.getByLabelText("Repeat until"), { target: { value: "2026-12-26" } });
    expect(answer()).toHaveTextContent("Makes 13 copies · last one Sat 26 Dec 2026");

    fireEvent.change(screen.getByLabelText("Repeat until"), { target: { value: "2026-12-25" } });
    expect(answer()).toHaveTextContent("Makes 12 copies · last one Sat 19 Dec 2026");

    // A picked end date stays picked when the gap changes: every 3 weeks to 25 Dec is four.
    fireEvent.change(screen.getByLabelText("Weeks between repeats"), { target: { value: "3" } });
    expect(screen.getByLabelText("Repeat until")).toHaveValue("2026-12-25");
    expect(answer()).toHaveTextContent("Makes 4 copies · last one Sat 19 Dec 2026");
  });

  it("says '1 copy', not '1 copies'", async () => {
    openTheDay();
    await openRepeat();
    fireEvent.change(screen.getByLabelText("Repeat until"), { target: { value: "2026-10-03" } });
    expect(answer()).toHaveTextContent("Makes 1 copy · last one Sat 3 Oct 2026");
  });

  it("lets the preview correct the count, and names each skipped date and why", async () => {
    api.previewRepeat.mockResolvedValue(
      result({
        copies: 4, lastDate: "2026-11-07",
        skippedFasting: ["2026-10-10"], skippedAlreadyPlanned: ["2026-10-24"],
      })
    );
    openTheDay();
    await openRepeat();

    // The arithmetic first — six — then the server's answer, which knows about the two skipped.
    expect(answer()).toHaveTextContent("Makes 6 copies");
    expect(await screen.findByText("Makes 4 copies · last one Sat 7 Nov 2026")).toBeInTheDocument();
    expect(screen.getByText("Skips Sat 10 Oct 2026: a dish doesn’t suit the fasting day.")).toBeInTheDocument();
    expect(
      screen.getByText("Skips Sat 24 Oct 2026: this event is already planned that day.")
    ).toBeInTheDocument();
    expect(api.previewRepeat).toHaveBeenLastCalledWith("meal-3", 1, "2026-11-07", "t");
  });

  it("ignores an answer to an older question that arrives after the newer one", async () => {
    let answerFirst: (r: unknown) => void = () => undefined;
    api.previewRepeat
      .mockImplementationOnce(() => new Promise((resolve) => (answerFirst = resolve)))
      .mockImplementationOnce(async () =>
        result({ copies: 5, lastDate: "2026-12-19", skippedFasting: ["2026-11-21"] })
      );
    openTheDay();
    await openRepeat();

    // The first question (every week) is on its way; then the gap changes to two.
    await waitFor(() => expect(api.previewRepeat).toHaveBeenCalledTimes(1));
    fireEvent.change(screen.getByLabelText("Weeks between repeats"), { target: { value: "2" } });
    expect(await screen.findByText("Makes 5 copies · last one Sat 19 Dec 2026")).toBeInTheDocument();

    // Now the first answer lands, late. It describes every week, which nobody is asking about any more.
    await act(async () => {
      answerFirst(result({ copies: 99, lastDate: "2026-11-07" }));
    });
    expect(screen.queryByText(/99 copies/)).toBeNull();
    expect(screen.getByText("Makes 5 copies · last one Sat 19 Dec 2026")).toBeInTheDocument();
    expect(screen.getByText("Skips Sat 21 Nov 2026: a dish doesn’t suit the fasting day.")).toBeInTheDocument();
  });

  it("shows a refusal from the preview beside the control, and will not repeat", async () => {
    api.previewRepeat.mockRejectedValue(
      refusal("KMS-400177", "No copies fit before that end date.", "Choose a later end date, or repeat more often.")
    );
    openTheDay();
    await openRepeat();

    expect(
      await screen.findByText("No copies fit before that end date. Choose a later end date, or repeat more often.")
    ).toBeInTheDocument();
    // Inline, not a banner: nothing with role alert, and the page's error handler is never called.
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.getByRole("button", { name: /^repeat$/i })).toBeDisabled();
  });

  it("refuses a gap outside 1 to 12 before asking anybody", async () => {
    openTheDay();
    await openRepeat();
    fireEvent.change(screen.getByLabelText("Weeks between repeats"), { target: { value: "13" } });

    expect(answer()).toHaveTextContent("An event can repeat every 1 to 12 weeks. Choose a number from 1 to 12.");
    expect(screen.getByRole("button", { name: /^repeat$/i })).toBeDisabled();
  });

  it("says the unit for the number in the box, even one it refuses (VERIFY3, A3-2)", async () => {
    openTheDay();
    await openRepeat();
    const box = screen.getByLabelText("Weeks between repeats");
    // The repro: 13 read "once every 13 week" beside its refusal.
    for (const [typed, unit] of [["13", "weeks"], ["0", "weeks"], ["", "weeks"], ["12", "weeks"], ["01", "week"], ["1", "week"]]) {
      fireEvent.change(box, { target: { value: typed } });
      expect(gapPhrase()).toBe(`once every ${unit}`);
    }
    // The box's own limits are the refusal's: 1 to 12.
    expect(box).toHaveAttribute("min", "1");
    expect(box).toHaveAttribute("max", "12");
  });

  it("puts every phrase on the sentence's baseline, as the event's name is (VERIFY3, A3-3)", async () => {
    openTheDay();
    await openRepeat();
    // jsdom has no layout, so this guards the class; the 1.6px it fixes was measured in Chromium
    // (docs/work/proof/T-343.md).
    for (const label of ["Weeks between repeats", "Repeat until"]) {
      const phrase = screen.getByLabelText(label).parentElement!;
      expect(phrase.className.split(" ")).toContain("align-baseline");
      expect(phrase.className).not.toMatch(/align-middle/);
    }
    const controls = screen.getByRole("button", { name: /^repeat$/i }).parentElement!;
    expect(controls.className.split(" ")).toContain("align-baseline");
  });

  it("repeats with the gap and end date, then says what it made and names what it skipped", async () => {
    api.previewRepeat.mockResolvedValue(result({ copies: 4, lastDate: "2026-11-07" }));
    api.repeatEvent.mockResolvedValue(
      result({
        copies: 4, lastDate: "2026-11-07",
        skippedFasting: ["2026-10-10"], skippedAlreadyPlanned: ["2026-10-17", "2026-10-24"],
      })
    );
    openTheDay();
    await openRepeat();
    fireEvent.change(screen.getByLabelText("Weeks between repeats"), { target: { value: "1" } });
    await screen.findByText("Makes 4 copies · last one Sat 7 Nov 2026");
    // What a copy is, in the "i" beside the control.
    expect(screen.getByRole("button", { name: "More about repeating an event" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /^repeat$/i }));

    await waitFor(() => expect(api.repeatEvent).toHaveBeenCalledWith("meal-3", 1, "2026-11-07", "t"));
    expect(await screen.findByText("Made 4 copies · last one Sat 7 Nov 2026.")).toBeInTheDocument();
    expect(screen.getByText("Skipped Sat 10 Oct 2026: a dish doesn’t suit the fasting day.")).toBeInTheDocument();
    expect(
      screen.getByText("Skipped Sat 17 Oct 2026 and Sat 24 Oct 2026: this event is already planned those days.")
    ).toBeInTheDocument();
  });
});

describe("the series line", () => {
  it("says how often, until when, and which one this is — in plain text, not a pill", async () => {
    api.meals.mockResolvedValue([reading({ series: SERIES })]);
    openTheDay();

    const line = await screen.findByText(
      (_, el) => el?.tagName === "P" && el.textContent === "Repeats every 2 weeks until 31 Dec 2026 · event 3 of 8"
    );
    expect(line.className).toContain("text-ink-secondary");
    expect(line.className).not.toMatch(/bg-|success|warning|danger/);
  });

  it("says 'every week' at a gap of one, and leaves the position off a cancelled one", () => {
    expect(seriesLine({ ...SERIES, everyWeeks: 1 })).toBe("Repeats every week until 31 Dec 2026 · event 3 of 8");
    expect(seriesLine({ ...SERIES, position: null })).toBe("Repeats every 2 weeks until 31 Dec 2026");
  });

  it("is absent on a meal that never repeated", async () => {
    openTheDay();
    await screen.findByText(NAME);
    expect(screen.queryByText((_, el) => el?.tagName === "P" && /^Repeats every/.test(el.textContent ?? ""))).toBeNull();
  });
});

describe("cancelling a meal in a series", () => {
  const LATER = {
    seriesId: "s-1",
    later: [
      { mealId: "meal-4", planDate: "2026-10-10", edited: false, volunteersSignedUp: 0 },
      { mealId: "meal-5", planDate: "2026-10-24", edited: true, volunteersSignedUp: 2 },
      { mealId: "meal-6", planDate: "2026-11-07", edited: false, volunteersSignedUp: 1 },
    ],
    lastDate: "2026-11-07",
    volunteersToTell: 3,
  };

  async function pressCancel() {
    fireEvent.click(await screen.findByRole("button", { name: "Cancel this meal" }));
    return screen.findByRole("alertdialog", { name: `Cancel ${NAME}?` });
  }

  it("asks nothing extra of a meal in no series, and sends the cancel exactly as before", async () => {
    api.cancelMeal.mockResolvedValue({ volunteersTold: 0, mealsCancelled: 1, lastDate: null });
    openTheDay();
    const dialog = await pressCancel();

    expect(api.laterInSeries).not.toHaveBeenCalled();
    expect(within(dialog).queryByRole("radio")).toBeNull();
    expect(dialog).toHaveTextContent("Its preparations come off the plan.");
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel this meal" }));
    await waitFor(() => expect(api.cancelMeal).toHaveBeenCalledTimes(1));
    // Three arguments, no fourth: the body is what it was before series existed.
    expect(api.cancelMeal.mock.calls[0]).toEqual(["meal-3", null, "t"]);
  });

  it("asks nothing extra of the last one in a series", async () => {
    api.meals.mockResolvedValue([reading({ series: { ...SERIES, position: 8 } })]);
    openTheDay();
    const dialog = await pressCancel();
    expect(api.laterInSeries).not.toHaveBeenCalled();
    expect(within(dialog).queryByRole("radio")).toBeNull();
  });

  it("asks nothing extra when none of the later ones is still to cook", async () => {
    api.meals.mockResolvedValue([reading({ series: SERIES })]);
    api.laterInSeries.mockResolvedValue({ ...LATER, later: [], lastDate: null, volunteersToTell: 0 });
    openTheDay();
    const dialog = await pressCancel();
    expect(api.laterInSeries).toHaveBeenCalledWith("meal-3", "t");
    expect(within(dialog).queryByRole("radio")).toBeNull();
    expect(dialog).toHaveTextContent("Its preparations come off the plan.");
  });

  it("offers just this one or this and all later ones, starting on just this one", async () => {
    api.meals.mockResolvedValue([reading({ series: SERIES })]);
    api.laterInSeries.mockResolvedValue(LATER);
    api.cancelMeal.mockResolvedValue({ volunteersTold: 0, mealsCancelled: 1, lastDate: "2026-11-07" });
    openTheDay();
    const dialog = await pressCancel();

    const group = within(dialog).getByRole("group", { name: "This event repeats. Which do you want to cancel?" });
    expect(within(group).getByRole("radio", { name: "Just this event" })).toBeChecked();
    expect(within(group).getByRole("radio", { name: "This and all later ones" })).not.toBeChecked();
    // The way back still takes the focus, as on every question in the planner.
    expect(within(dialog).getByRole("button", { name: "Keep it" })).toHaveFocus();

    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel this meal" }));
    await waitFor(() => expect(api.cancelMeal).toHaveBeenCalledTimes(1));
    expect(api.cancelMeal.mock.calls[0]).toEqual(["meal-3", null, "t"]);
    expect(await screen.findByText(`${NAME} was cancelled.`)).toBeInTheDocument();
  });

  it("for all later ones, says how many, the last date, the edited ones and the volunteers, and sends the ids shown", async () => {
    api.meals.mockResolvedValue([reading({ series: SERIES })]);
    api.laterInSeries.mockResolvedValue(LATER);
    api.cancelMeal.mockResolvedValue({ volunteersTold: 3, mealsCancelled: 4, lastDate: "2026-09-12" });
    openTheDay();
    const dialog = await pressCancel();

    fireEvent.click(within(dialog).getByRole("radio", { name: "This and all later ones" }));
    expect(dialog).toHaveTextContent("Cancels this event and 3 later ones, the last on Sat 7 Nov 2026.");
    expect(dialog).toHaveTextContent("Sat 24 Oct 2026 was changed on its own and will be cancelled too.");
    expect(dialog).toHaveTextContent("3 volunteers are signed up or waiting across these events, and will be told.");

    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel 4 events" }));
    await waitFor(() => expect(api.cancelMeal).toHaveBeenCalledTimes(1));
    expect(api.cancelMeal.mock.calls[0]).toEqual([
      "meal-3", null, "t", { scope: "THIS_AND_LATER", expectedMealIds: ["meal-4", "meal-5", "meal-6"] },
    ]);
    expect(await screen.findByText(`${NAME} was cancelled on 4 dates. 3 volunteers were told.`)).toBeInTheDocument();
  });

  it("when the later ones changed meanwhile, reads them again and shows the fresh list with the server's words", async () => {
    api.meals.mockResolvedValue([reading({ series: SERIES })]);
    const fresher = {
      ...LATER,
      later: [...LATER.later, { mealId: "meal-7", planDate: "2026-11-21", edited: true, volunteersSignedUp: 0 }],
      lastDate: "2026-11-21",
    };
    api.laterInSeries.mockResolvedValueOnce(LATER).mockResolvedValueOnce(fresher);
    api.cancelMeal
      .mockRejectedValueOnce(
        refusal(
          "KMS-400179",
          "The later events changed while you were deciding, so nothing was cancelled.",
          "Look at the list again, then confirm.",
          409
        )
      )
      .mockResolvedValueOnce({ volunteersTold: 0, mealsCancelled: 5, lastDate: "2026-09-12" });
    openTheDay();
    const dialog = await pressCancel();
    fireEvent.click(within(dialog).getByRole("radio", { name: "This and all later ones" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel 4 events" }));

    expect(
      await within(dialog).findByText("The later events changed while you were deciding, so nothing was cancelled.")
    ).toBeInTheDocument();
    expect(api.laterInSeries).toHaveBeenCalledTimes(2);
    expect(dialog).toHaveTextContent("Cancels this event and 4 later ones, the last on Sat 21 Nov 2026.");
    expect(dialog).toHaveTextContent(
      "Sat 24 Oct 2026 and Sat 21 Nov 2026 were changed on their own and will be cancelled too."
    );

    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel 5 events" }));
    await waitFor(() => expect(api.cancelMeal).toHaveBeenCalledTimes(2));
    expect(api.cancelMeal.mock.calls[1][3]).toEqual({
      scope: "THIS_AND_LATER", expectedMealIds: ["meal-4", "meal-5", "meal-6", "meal-7"],
    });
  });

  it("keeps the volunteer-shift warning for just this one", async () => {
    api.meals.mockResolvedValue([
      reading({
        series: SERIES,
        volunteerShift: {
          id: "s1", title: "Reading", description: null, shiftDate: DATE, startTime: "15:00:00",
          endTime: "17:00:00", location: null, capacity: 4, reminderOffsetsMinutes: [], status: "OPEN",
          cancelReason: null, signedUpCount: 2, waitlistCount: 0, createdAt: "2026-09-01T00:00:00Z",
          mealId: "meal-3", mealKind: "Event", mealEventName: NAME,
        },
      }),
    ]);
    api.laterInSeries.mockResolvedValue(LATER);
    openTheDay();
    const dialog = await pressCancel();
    expect(dialog).toHaveTextContent("This meal has a volunteer shift. 2 volunteers are signed up.");
  });
});
