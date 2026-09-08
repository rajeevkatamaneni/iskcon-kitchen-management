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
// The real class, not a stand-in: `toApiError` passes an ApiError straight through only if it is
// this one, and a refusal that arrives as anything else is shown as a connection failure instead.
import { ApiError } from "@/lib/api";

const RECIPES = [
  { id: "r1", name: "Bisi Bele Bath", categoryName: "Khichadi", fastingCompatible: false,
    baseYieldQty: 100, baseYieldUnit: "KG", perHeadQty: 1, perHeadUnit: "KG", status: "ACTIVE", sattvicOverridden: false },
  { id: "r2", name: "Kesari Bath", categoryName: "Sweets", fastingCompatible: false,
    baseYieldQty: 100, baseYieldUnit: "KG", perHeadQty: 1, perHeadUnit: "KG", status: "ACTIVE", sattvicOverridden: false },
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
    eventName: null,
    contactName: null,
    contactPhone: null,
    deliveryAddress: null,
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
    eventName: null,
    contactName: null,
    contactPhone: null,
    deliveryAddress: null,
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

/**
 * The Saturday reading, as the planner hands it to the recording form: kind "Event", and the only
 * thing that says which of the day's events this is sitting in `eventName`.
 */
function event(name = "Bhagavad Gita Parayanam", overrides: Record<string, unknown> = {}) {
  return lunch({
    mealKind: "Event",
    eventName: name,
    // An event is planned by how much to make, not by how many people (E4-S15 D2).
    adults: 0,
    children: 0,
    seniors: 0,
    plates: 0,
    dishes: [{ ...dish("m1", "r1", "Bisi Bele Bath", 248), mealKind: "Event", eventName: name }],
    ...overrides,
  });
}

/**
 * Renders the day and waits for it to arrive.
 *
 * <p>`heading` is what the meal calls itself on screen — its kind for the three main meals, and its
 * own name for an event, which is the whole point of splitting events out.
 */
async function open(meals: unknown[], heading = "Lunch", onError = vi.fn()) {
  mealServices.mockResolvedValue(meals);
  render(
    <MealServices
      date="2026-08-21"
      sufficiency={new Map()}
      recipes={RECIPES as never}
      readOnly={false}
      onChanged={vi.fn()}
      onError={onError}
    />
  );
  await screen.findByText(heading);
  return { onError };
}

/** Opens the recording form on the meal on screen. */
function openTheRecordingForm() {
  fireEvent.click(screen.getByRole("button", { name: /record actuals/i }));
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

    // Two dishes at 248 and 300 is 248 servings, not 548 — the head count, not a sum.
    expect(screen.getAllByText(/248 servings/).length).toBeGreaterThan(0);
    expect(screen.getByText("Bisi Bele Bath")).toBeInTheDocument();
    expect(screen.getByText("Kesari Bath")).toBeInTheDocument();
  });

  it("has no per-dish mark-cooked button; recording is one form for the whole meal", async () => {
    await open([lunch()]);

    expect(screen.queryByRole("button", { name: /mark cooked/i })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /record actuals/i }));

    // Every dish is listed with the three figures the returned job card carries: what was planned,
    // what was cooked, and what was actually eaten. Both editable ones start at the plan.
    const cooked = screen.getByLabelText("How much Bisi Bele Bath was cooked");
    const eaten = screen.getByLabelText("How much Bisi Bele Bath was eaten");
    expect(cooked).toHaveValue(248);
    expect(eaten).toHaveValue(248);

    fireEvent.change(cooked, { target: { value: "220" } });
    // Nothing can be eaten that was never made, so the figure below follows the one above down.
    expect(eaten).toHaveValue(220);
    fireEvent.change(eaten, { target: { value: "190" } });
    fireEvent.click(screen.getByLabelText("Kesari Bath was not made"));

    fireEvent.click(screen.getByRole("button", { name: /record this meal/i }));
    await vi.waitFor(() => expect(recordMeal).toHaveBeenCalledTimes(1));

    expect(recordMeal.mock.calls[0][0]).toMatchObject({
      planDate: "2026-08-21",
      mealKind: "Lunch",
      dishes: [
        { mealPlanId: "m1", actualServings: 220, consumedQuantity: 190, notMade: false },
        { mealPlanId: "m2", actualServings: null, consumedQuantity: null, notMade: true },
      ],
    });
  });

  it("names the event it is recording, so the server can tell which meal is being written down", async () => {
    // The regression this exists for, and it was live on staging (T-043). The server has always
    // resolved a recording with (date, kind, event name), because every event of every temple
    // carries the kind "Event" — a Saturday with a morning reading and an evening bhajan is two
    // preparations, two cards and two recordings. The browser sent four fields and no name, so the
    // server resolved nothing and answered 404 to every event recording ever attempted, from all
    // three screens that record through this form. Ordinary Lunch went on working, which is why
    // nobody saw it: a main meal has no event name and is correctly identified without one.
    await open([event()], "Bhagavad Gita Parayanam");

    openTheRecordingForm();
    fireEvent.click(screen.getByRole("button", { name: /record this meal/i }));

    await vi.waitFor(() => expect(recordMeal).toHaveBeenCalledTimes(1));
    expect(recordMeal.mock.calls[0][0]).toMatchObject({
      planDate: "2026-08-21",
      mealKind: "Event",
      eventName: "Bhagavad Gita Parayanam",
    });
  });

  it("says null for an everyday meal, out loud rather than by leaving the field out", async () => {
    // The other half of the same contract. `eventName` is required and nullable on the request
    // type on purpose: optional would let the omission above happen again and still compile, so
    // every caller has to say which case it is in. `toHaveProperty` rather than `toMatchObject`
    // because only the former tells an absent field from one that is genuinely null — and an
    // absent field is exactly what the defect was.
    await open([lunch()]);

    openTheRecordingForm();
    fireEvent.click(screen.getByRole("button", { name: /record this meal/i }));

    await vi.waitFor(() => expect(recordMeal).toHaveBeenCalledTimes(1));
    expect(recordMeal.mock.calls[0][0]).toHaveProperty("eventName", null);
  });

  it("shows the server's refusal inside the form that was submitted, beside the button pressed", async () => {
    // The defect that hid the one above, and the worse of the two. The refusal was complete — code,
    // message and next step — and was handed to the screen around this component, which renders it
    // at the top of the page above every day on it. Pressed from the fourth day down on the
    // catching-up screen, the answer appeared off-screen, with no scroll and no focus move, so four
    // presses of Record this meal looked exactly like nothing happening at all.
    recordMeal.mockRejectedValueOnce(
      new ApiError(
        {
          code: "KMS-400030",
          message: "We couldn’t find what you were looking for.",
          action: "It may have been removed.",
          fieldErrors: [],
        },
        404
      )
    );

    const { onError } = await open([event()], "Bhagavad Gita Parayanam");

    openTheRecordingForm();
    const button = screen.getByRole("button", { name: /record this meal/i });
    fireEvent.click(button);

    const alert = await screen.findByRole("alert");
    // The server's own words and its own next step, with the code to quote — not a sentence this
    // screen made up about them.
    expect(alert).toHaveTextContent("We couldn’t find what you were looking for.");
    expect(alert).toHaveTextContent("It may have been removed.");
    expect(alert).toHaveTextContent("KMS-400030");

    // At the point of action: the same region as the button that caused it, so it is on screen for
    // whoever pressed it wherever that form happens to sit on the page.
    const form = screen.getByRole("region", { name: "Record Event" });
    expect(form).toContainElement(alert);
    expect(form).toContainElement(button);

    // And not *also* handed upwards. The page banner has no way to clear itself, so bubbling it
    // would leave a red notice at the top of the screen contradicting the green one after a
    // successful retry.
    expect(onError).not.toHaveBeenCalled();

    // The form stays open on its figures, which is what a person needs in order to try again.
    expect(screen.getByLabelText("How much Bisi Bele Bath was cooked")).toHaveValue(248);
  });

  it("puts no swap and no cancel on a preparation row", async () => {
    await open([lunch()]);

    // Both are gone at Rajeev's direction (2026-08-23). Cancel was the dangerous one: it deleted a
    // preparation on a single press, with no confirmation, sitting inches from where the pointer
    // rests to read the row. Changing a meal is done on the meal, through Edit.
    expect(screen.queryByRole("button", { name: /swap or edit/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^cancel$/i })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: /^edit$/i })).toBeInTheDocument();
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
    // With its unit, like every other quantity on the screen (E11-S4): the recorded figure used to
    // read "220" beside a target that read "248 servings", so the two did not read as comparable.
    expect(screen.getByText(/220 Kg cooked/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /record actuals/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /swap or edit/i })).not.toBeInTheDocument();

    // The card is still available — a signed sheet is filed against it long after the meal.
    expect(screen.getByRole("button", { name: /download job card/i })).toBeInTheDocument();
    // Nor is there anything to edit: the whole meal is as fixed as its preparations.
    expect(screen.queryByRole("link", { name: /^edit$/i })).not.toBeInTheDocument();
  });

  it("offers every language, because which one a cook reads is not a fact about the temple", async () => {
    await open([lunch()]);
    fireEvent.click(screen.getByLabelText("Include the recipes with the Lunch card"));

    // This list used to be narrowed to the languages a translation already existed for, which made
    // a fresh temple's picker hold one entry and look broken. There is no rule that a cook in a
    // Kannada temple reads Kannada (Rajeev, 2026-08-23), so the choice is offered in full and the
    // translation is produced when the card is asked for.
    const picker = await screen.findByLabelText("Recipe language for Lunch");
    const offered = Array.from(picker.querySelectorAll("option")).map((o) => o.textContent);
    expect(offered).toContain("English");
    expect(offered).toContain("Kannada");
    expect(offered).toContain("Assamese");
    expect(offered.length).toBe(23);
  });

  it("prints the recipes in the temple's language by default, and in another when asked", async () => {
    await open([lunch()]);
    fireEvent.click(screen.getByLabelText("Include the recipes with the Lunch card"));

    // Nobody picked, so the picker opens on the temple's own language — the default, not the rule.
    const picker = await screen.findByLabelText("Recipe language for Lunch");
    expect(picker).toHaveValue("kn");

    fireEvent.change(picker, { target: { value: "en" } });
    fireEvent.click(screen.getByRole("button", { name: /download job card/i }));

    await vi.waitFor(() => expect(requestJobCard).toHaveBeenCalledTimes(1));
    // The event name is the third argument and part of the card's key since V89 — null for a
    // main meal, which is what a Lunch is.
    expect(requestJobCard.mock.calls[0].slice(0, 4)).toEqual(["2026-08-21", "Lunch", null, "en"]);
  });

  it("names the event when it asks for that event's card, so two on one day are two cards", async () => {
    // The regression this exists for. V89 re-keyed a job card on (date, kind, event name), because
    // every event carries the kind "Event" and a Saturday with a morning reading and an evening
    // bhajan would otherwise share one card. The endpoint took the new parameter and the browser
    // went on not sending it, so Download job card silently did nothing for every event and only
    // the server log said why. Neither component was wrong on its own; the seam between them was
    // untested, which is exactly the kind of gap that has no owner.
    await open([
      lunch({
        mealKind: "Event",
        eventName: "School Bhagavad-gita Reading Prasadam",
        dishes: [{ ...dish("m1", "r1", "Bisi Bele Bath", 248), mealKind: "Event" }],
      }),
    ], "School Bhagavad-gita Reading Prasadam");

    fireEvent.click(screen.getByRole("button", { name: /download job card/i }));

    await vi.waitFor(() => expect(requestJobCard).toHaveBeenCalledTimes(1));
    expect(requestJobCard.mock.calls[0].slice(0, 3)).toEqual([
      "2026-08-21",
      "Event",
      "School Bhagavad-gita Reading Prasadam",
    ]);
  });

  it("asks for the worksheet on its own, because that is what most prints are", async () => {
    await open([lunch()]);

    // Unchecked by default since 2026-09-05. The recipes are pages a cook works from and throws
    // away; attaching five of them to every card by default wastes paper on most of them.
    expect(screen.getByLabelText("Include the recipes with the Lunch card")).not.toBeChecked();
    // And the language picker is not there either: there is nothing for it to choose the language of.
    expect(screen.queryByLabelText("Recipe language for Lunch")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /download job card/i }));
    await vi.waitFor(() => expect(requestJobCard).toHaveBeenCalledTimes(1));
    expect(requestJobCard.mock.calls[0].slice(0, 4)).toEqual(["2026-08-21", "Lunch", null, "none"]);
  });

  it("asks for the recipes, in a language, once somebody ticks the box", async () => {
    await open([lunch()]);
    fireEvent.click(screen.getByLabelText("Include the recipes with the Lunch card"));

    // The picker appears with them, opening on the temple's own language.
    expect(await screen.findByLabelText("Recipe language for Lunch")).toHaveValue("kn");

    fireEvent.click(screen.getByRole("button", { name: /download job card/i }));
    await vi.waitFor(() => expect(requestJobCard).toHaveBeenCalledTimes(1));
    expect(requestJobCard.mock.calls[0].slice(0, 4)).toEqual(["2026-08-21", "Lunch", null, "kn"]);
  });

  it("offers the job card once, as a card to download", async () => {
    await open([lunch()]);

    // There were two of it: a "Job card" button on the header that opened a printable copy in a new
    // tab, and a "Download PDF" link at the foot, with the choices that shape the card attached to
    // only one of them. One control now. The fast HTML rendering is still there on the server and
    // is where the outstanding performance question lives.
    expect(screen.queryByRole("button", { name: /^job card$/i })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /download job card/i })).toBeInTheDocument();
  });
});
