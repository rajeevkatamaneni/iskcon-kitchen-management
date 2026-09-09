import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

/**
 * Correcting a recorded meal from the day's planner (T-007, docket S5/M2).
 *
 * <p>Three things are worth proving here and they are different in kind. That the door is shown to
 * the Temple Admin and to nobody else (D-4). That the form opens on **what was recorded** rather
 * than on what was planned — the difference between correcting a recording and quietly discarding
 * it. And that a corrected meal reads back as *"640, corrected from 400 by Anand on 8 September"*
 * rather than silently showing a different number than it showed yesterday, which is the whole
 * argument for a compensating entry over a reopening.
 */

const { mealServices, correctRecordedMeal, jobCardLanguages, mealCrew } = vi.hoisted(() => ({
  mealServices: vi.fn(async (_from: string, _to: string, _token?: string) => [] as unknown[]),
  correctRecordedMeal: vi.fn(
    async (_id: string, _input: Record<string, unknown>, _token?: string) => ({})
  ),
  jobCardLanguages: vi.fn(async () => ({ languages: ["en"], defaultLanguage: "en" })),
  mealCrew: vi.fn(async () => [] as unknown[]),
}));

/**
 * The signed-in person's role is what the screen gates the button on, so it is a variable rather
 * than a constant: there is no permission list on the client to test against, and D-4 gave
 * `CORRECT_RECORDED_MEAL` to the Temple Admin alone.
 */
let role = "TEMPLE_ADMIN";

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ getToken: async () => "t", appUser: { role } }),
}));
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, mealServices, correctRecordedMeal, jobCardLanguages, mealCrew },
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
import { ApiError } from "@/lib/api";

const RECIPES = [
  {
    id: "r1", name: "Bisi Bele Bath", categoryName: "Khichadi", fastingCompatible: false,
    baseYieldQty: 100, baseYieldUnit: "KG", perHeadQty: 1, perHeadUnit: "KG", status: "ACTIVE",
  },
  {
    id: "r2", name: "Kesari Bath", categoryName: "Sweets", fastingCompatible: false,
    baseYieldQty: 100, baseYieldUnit: "KG", perHeadQty: 1, perHeadUnit: "KG", status: "ACTIVE",
  },
];

/** One dish of a meal that has been recorded: cooked, with the figure the office typed on it. */
function cooked(
  id: string,
  recipeId: string,
  recipeName: string,
  overrides: Record<string, unknown> = {}
) {
  return {
    id,
    planDate: "2026-08-21",
    mealKind: "Lunch",
    readyBy: "12:00:00",
    recipeId,
    recipeName,
    targetYield: 500,
    dayType: "REGULAR",
    occasionName: null,
    status: "COOKED",
    eventName: null,
    contactName: null,
    contactPhone: null,
    deliveryAddress: null,
    purpose: null,
    adults: 400,
    children: 0,
    seniors: 0,
    kitchenNotes: null,
    actualServings: 400,
    consumedQuantity: null,
    notMade: false,
    originalActualServings: null,
    originalConsumedQuantity: null,
    cookedAt: "2026-08-21T07:00:00Z",
    ekadashiAcknowledged: false,
    createdAt: "2026-08-20T10:00:00Z",
    ...overrides,
  };
}

/** The lunch, already written down. Every correction test starts from one of these. */
function recordedLunch(overrides: Record<string, unknown> = {}) {
  return {
    serviceId: "svc-1",
    planDate: "2026-08-21",
    mealKind: "Lunch",
    readyBy: "12:00:00",
    adults: 400,
    children: 0,
    seniors: 0,
    plates: 400,
    crewRequired: null,
    dayType: "REGULAR",
    occasionName: null,
    eventName: null,
    contactName: null,
    contactPhone: null,
    deliveryAddress: null,
    purpose: null,
    kitchenNotes: null,
    serverNotes: null,
    cardNumber: "LC-2026-0142",
    cardIssuedAt: "2026-08-21T05:00:00Z",
    recorded: true,
    recordedAt: "2026-08-21T09:00:00Z",
    recordedByName: "Gopi",
    recordingNote: "As read off the card",
    corrected: false,
    correctedAt: null,
    correctedByName: null,
    correctionNote: null,
    dishes: [cooked("m1", "r1", "Bisi Bele Bath")],
    ...overrides,
  };
}

async function open(meals: unknown[], heading = "Lunch") {
  mealServices.mockResolvedValue(meals);
  render(
    <MealServices
      date="2026-08-21"
      sufficiency={new Map()}
      recipes={RECIPES as never}
      readOnly={false}
      onChanged={vi.fn()}
      onError={vi.fn()}
    />
  );
  await screen.findByText(heading);
}

function openTheCorrectionForm() {
  fireEvent.click(screen.getByRole("button", { name: /correct the figures/i }));
}

describe("correcting a recorded meal", () => {
  beforeEach(() => {
    role = "TEMPLE_ADMIN";
    correctRecordedMeal.mockClear();
    correctRecordedMeal.mockResolvedValue({});
  });

  it("offers the correction only on a meal that has been recorded", async () => {
    await open([recordedLunch({ recorded: false, recordedAt: null, recordedByName: null })]);

    expect(
      screen.queryByRole("button", { name: /correct the figures/i })
    ).not.toBeInTheDocument();
  });

  it("offers it to a Temple Admin", async () => {
    await open([recordedLunch()]);

    expect(screen.getByRole("button", { name: /correct the figures/i })).toBeInTheDocument();
  });

  /**
   * D-4: recording is everyday kitchen work and correcting is not. The API refuses either way — it
   * is the boundary — but a cook should not be shown a button that exists only to refuse them.
   */
  it("does not offer it to kitchen staff, who may record but not correct", async () => {
    role = "KITCHEN_STAFF";
    await open([recordedLunch()]);

    expect(
      screen.queryByRole("button", { name: /correct the figures/i })
    ).not.toBeInTheDocument();
  });

  it("does not offer it twice — a meal is correctable once", async () => {
    await open([
      recordedLunch({
        corrected: true,
        correctedAt: "2026-09-08T04:30:00Z",
        correctedByName: "Anand",
        correctionNote: "640 went out",
      }),
    ]);

    expect(
      screen.queryByRole("button", { name: /correct the figures/i })
    ).not.toBeInTheDocument();
  });

  /**
   * The difference between correcting a recording and discarding it. The box opens on 400 — what is
   * on file — not on 500, which is what the plan asked for. Prefilling from the plan would silently
   * throw away the recorded figure on every dish the office did not retype.
   */
  it("opens on what was recorded, not on what was planned", async () => {
    await open([recordedLunch()]);
    openTheCorrectionForm();

    const cookedBox = screen.getByLabelText(/how much Bisi Bele Bath was actually cooked/i);
    expect((cookedBox as HTMLInputElement).value).toBe("400");
  });

  it("refuses to send until a reason has been given", async () => {
    await open([recordedLunch()]);
    openTheCorrectionForm();

    const submit = screen.getByRole("button", { name: /record this correction/i });
    expect(submit).toBeDisabled();

    fireEvent.change(screen.getByLabelText(/why the figures are being changed/i), {
      target: { value: "The card was read as 400" },
    });
    expect(submit).not.toBeDisabled();
  });

  /**
   * The whole meal is restated, dish by dish, and the server works out what moved. A dish left out
   * is refused rather than assumed unchanged — the same rule as recording — so the unchanged Kesari
   * Bath has to be in the payload too.
   */
  it("sends every dish, changed or not, against the meal's own row", async () => {
    await open([
      recordedLunch({
        dishes: [
          cooked("m1", "r1", "Bisi Bele Bath"),
          cooked("m2", "r2", "Kesari Bath", { actualServings: 300 }),
        ],
      }),
    ]);
    openTheCorrectionForm();

    fireEvent.change(screen.getByLabelText(/how much Bisi Bele Bath was actually cooked/i), {
      target: { value: "640" },
    });
    fireEvent.change(screen.getByLabelText(/why the figures are being changed/i), {
      target: { value: "The card was read as 400; the kitchen confirms 640" },
    });
    fireEvent.click(screen.getByRole("button", { name: /record this correction/i }));

    await vi.waitFor(() => expect(correctRecordedMeal).toHaveBeenCalled());
    const [serviceId, input] = correctRecordedMeal.mock.calls[0];
    expect(serviceId).toBe("svc-1");
    expect(input).toMatchObject({
      note: "The card was read as 400; the kitchen confirms 640",
      dishes: [
        { mealPlanId: "m1", actualServings: 640, notMade: false },
        { mealPlanId: "m2", actualServings: 300, notMade: false },
      ],
    });

    // Required-and-nullable, not optional. "I am not saying what came back" and "nothing came back"
    // have to stay distinguishable, and an omitted key cannot do it — so the key must be PRESENT and
    // null rather than absent. `toMatchObject` above cannot tell those two apart, which is exactly
    // the trap wave 4c wrote up: inspect the keys, then assert the value.
    const first = (input as { dishes: Record<string, unknown>[] }).dishes[0];
    expect(Object.keys(first)).toContain("consumedQuantity");
    expect(first.consumedQuantity).toBeNull();
  });

  /** A dish corrected to not-made sends no figures at all, and the boxes stop accepting them. */
  it("sends nulls for a dish corrected to not-made", async () => {
    await open([recordedLunch()]);
    openTheCorrectionForm();

    fireEvent.click(screen.getByLabelText(/Bisi Bele Bath was not made after all/i));
    fireEvent.change(screen.getByLabelText(/why the figures are being changed/i), {
      target: { value: "The pot went to Tuesday's event" },
    });
    fireEvent.click(screen.getByRole("button", { name: /record this correction/i }));

    await vi.waitFor(() => expect(correctRecordedMeal).toHaveBeenCalled());
    const [, input] = correctRecordedMeal.mock.calls[0];
    expect((input as { dishes: Record<string, unknown>[] }).dishes[0]).toEqual({
      mealPlanId: "m1",
      actualServings: null,
      consumedQuantity: null,
      notMade: true,
    });
  });

  /**
   * The sentence the whole feature exists to be able to write. The figure that is true now, the
   * figure it used to be, and who changed it when — so nobody has to remember yesterday's screen to
   * know that a number moved.
   */
  it("reads back as the new figure, corrected from the old, by whom and when", async () => {
    await open([
      recordedLunch({
        corrected: true,
        correctedAt: "2026-09-08T04:30:00Z",
        correctedByName: "Anand Das",
        correctionNote: "The card was read as 400",
        dishes: [
          cooked("m1", "r1", "Bisi Bele Bath", {
            actualServings: 640,
            originalActualServings: 400,
          }),
        ],
      }),
    ]);

    expect(screen.getByText(/640 kg cooked/i)).toBeInTheDocument();
    // `Sept?` rather than `Sep`, and deliberately not a hardcoded "8 Sept 2026". The date comes from
    // `templeDay`, which is `toLocaleDateString("en-GB", { month: "short" })` — and ICU abbreviates
    // September to FOUR letters in en-GB while every other month gets three. Pinning either spelling
    // would make this test a hostage to the Node build it runs on, in a feature that has nothing to
    // do with dates. What is being asserted is the sentence: the figure, who changed it, and when.
    expect(
      screen.getByText(/corrected from 400 kg by Anand Das on 8 Sept? 2026/i)
    ).toBeInTheDocument();

    // The recording is not replaced by the correction. Both lines stand, in the order they happened
    // — losing the first would lose the fact that there was ever an earlier answer, which is the one
    // thing this feature exists to keep.
    expect(screen.getByText(/Recorded by Gopi/)).toBeInTheDocument();
    expect(screen.getByText(/Corrected by Anand Das on 8 Sept? 2026/)).toBeInTheDocument();
  });

  /**
   * A dish nobody corrected says nothing extra, even on a corrected meal. Restating a figure is not
   * changing it, so the server leaves `originalActualServings` null there — and if the screen showed
   * the line anyway it would read "300 kg cooked, corrected from 300 kg".
   */
  it("says nothing about a dish the correction did not move", async () => {
    await open([
      recordedLunch({
        corrected: true,
        correctedAt: "2026-09-08T04:30:00Z",
        correctedByName: "Anand Das",
        correctionNote: "Only the khichadi was wrong",
        dishes: [
          cooked("m1", "r1", "Bisi Bele Bath", {
            actualServings: 640,
            originalActualServings: 400,
          }),
          cooked("m2", "r2", "Kesari Bath", { actualServings: 300 }),
        ],
      }),
    ]);

    expect(screen.getByText(/corrected from 400 kg/i)).toBeInTheDocument();
    expect(screen.queryByText(/corrected from 300 kg/i)).not.toBeInTheDocument();
  });

  /**
   * A refusal is shown in the form, at the point of action — not handed to the page banner above
   * every meal on the day, where a correction made from the fourth meal down answers off-screen.
   * That is the defect T-043 traced on staging, and this form must not reintroduce it.
   */
  it("shows a refusal inside the form, with its code", async () => {
    correctRecordedMeal.mockRejectedValue(
      new ApiError(
        {
          code: "KMS-400137",
          message: "This meal has already been corrected.",
          action: "Look at the correction that was recorded against it.",
          fieldErrors: [],
        },
        409
      )
    );
    await open([recordedLunch()]);
    openTheCorrectionForm();

    fireEvent.change(screen.getByLabelText(/why the figures are being changed/i), {
      target: { value: "640 went out" },
    });
    fireEvent.click(screen.getByRole("button", { name: /record this correction/i }));

    expect(await screen.findByText(/This meal has already been corrected/i)).toBeInTheDocument();
    expect(screen.getByText(/KMS-400137/)).toBeInTheDocument();
  });
});
