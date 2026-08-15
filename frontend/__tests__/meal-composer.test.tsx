import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

const { createMealPlan } = vi.hoisted(() => ({ createMealPlan: vi.fn(async () => ({ id: "m1" })) }));

vi.mock("@/lib/auth-context", () => ({ useAuth: () => ({ getToken: async () => "t" }) }));
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, createMealPlan } };
});

import { MealComposer } from "@/components/planner/MealComposer";

const RECIPES = [
  { id: "r1", name: "Bisi Bele Bath", categoryName: "Khichadi", fastingCompatible: false,
    baseYieldQty: 100, baseYieldUnit: "SERVINGS", status: "ACTIVE", sattvicOverridden: false },
  { id: "r2", name: "Kesari Bath", categoryName: "Sweets", fastingCompatible: false,
    baseYieldQty: 100, baseYieldUnit: "SERVINGS", status: "ACTIVE", sattvicOverridden: false },
];

const KINDS = [
  { id: "k1", name: "Lunch", defaultReadyTime: "12:00:00", needsClient: false, needsVenue: false },
  { id: "k2", name: "Catering", defaultReadyTime: null, needsClient: true, needsVenue: true },
];

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

    expect(screen.getByText("248 servings")).toBeInTheDocument();
  });

  it("gives every preparation the head count, and never overwrites one set by hand", () => {
    open();
    fireEvent.change(screen.getByLabelText("Adults"), { target: { value: "200" } });

    fireEvent.click(screen.getByRole("checkbox", { name: /bisi bele bath/i }));
    fireEvent.click(screen.getByRole("checkbox", { name: /kesari bath/i }));

    const sweet = screen.getByLabelText("Servings of Kesari Bath");
    expect(sweet).toHaveValue(200);

    // The sweet always goes first, so the planner raises it deliberately.
    fireEvent.change(sweet, { target: { value: "300" } });

    // More people arrive: the untouched dish follows, the judged one holds.
    fireEvent.change(screen.getByLabelText("Adults"), { target: { value: "250" } });
    expect(screen.getByLabelText("Servings of Bisi Bele Bath")).toHaveValue(250);
    expect(screen.getByLabelText("Servings of Kesari Bath")).toHaveValue(300);
  });

  it("saves one meal per preparation, each with its own servings", async () => {
    open();
    fireEvent.click(screen.getByRole("checkbox", { name: /bisi bele bath/i }));
    fireEvent.click(screen.getByRole("checkbox", { name: /kesari bath/i }));
    fireEvent.change(screen.getByLabelText("Servings of Kesari Bath"), { target: { value: "150" } });
    fireEvent.change(screen.getByLabelText(/notes for the kitchen/i), {
      target: { value: "Cook the kesari thin." },
    });

    fireEvent.click(screen.getByRole("button", { name: /save this meal/i }));
    await vi.waitFor(() => expect(createMealPlan).toHaveBeenCalledTimes(2));

    const [first, second] = createMealPlan.mock.calls.map((c) => c[0] as Record<string, unknown>);
    expect(first).toMatchObject({ recipeId: "r1", targetServings: 100, mealKind: "Lunch", readyBy: "12:00" });
    expect(second).toMatchObject({ recipeId: "r2", targetServings: 150, kitchenNotes: "Cook the kesari thin." });
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
});
