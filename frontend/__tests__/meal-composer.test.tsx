import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

// Typed like the real call, so the assertions below can read what was sent rather than casting
// their way past an untyped mock — which is how this file passed locally and failed in CI.
const { createMealPlan } = vi.hoisted(() => ({
  createMealPlan: vi.fn(async (_input: Record<string, unknown>, _token?: string) => ({ id: "m1" })),
}));

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
  { id: "k1", name: "Lunch", defaultReadyTime: "12:00:00", needsClient: false, needsVenue: false,
    needsPurpose: false },
  { id: "k2", name: "Catering", defaultReadyTime: null, needsClient: true, needsVenue: true,
    needsPurpose: false },
  // An outside event goes somewhere and has a reason for going there (B6).
  { id: "k3", name: "Outside event", defaultReadyTime: null, needsClient: false, needsVenue: true,
    needsPurpose: true },
];

function openAndGet(props: Partial<React.ComponentProps<typeof MealComposer>> = {}) {
  return render(
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

    const [first, second] = createMealPlan.mock.calls.map(([input]) => input);
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

  it("asks an outside event what it is for, and asks nothing else the same question", async () => {
    open();
    // Lunch is asked nothing of the sort: the requirement belongs to the kind, not to the app.
    expect(screen.queryByLabelText(/what is it for/i)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Outside event" }));
    fireEvent.click(screen.getByRole("checkbox", { name: /bisi bele bath/i }));
    fireEvent.change(screen.getByLabelText(/ready by/i), { target: { value: "17:00" } });
    fireEvent.change(screen.getByLabelText(/where is it going/i), {
      target: { value: "Jayanagar school hall" },
    });

    // Venue and time are given; the purpose is still missing, and it says which.
    expect(screen.getByRole("button", { name: /save this meal/i })).toBeDisabled();
    expect(screen.getByText(/say what it is for/i)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/what is it for/i), {
      target: { value: "Bhagavad-gita reading" },
    });
    fireEvent.click(screen.getByRole("button", { name: /save this meal/i }));

    await vi.waitFor(() => expect(createMealPlan).toHaveBeenCalledTimes(1));
    expect(createMealPlan.mock.calls[0][0]).toMatchObject({
      mealKind: "Outside event",
      venue: "Jayanagar school hall",
      purpose: "Bhagavad-gita reading",
    });
  });
});

describe("item 23 — the row of fields keeps its shape", () => {
  it("puts every field in step 1 and step 2 into the row's own tracks", () => {
    // The bug this replaces was not a wrong value of align-items but the use of align-items at all:
    // a readout with its label inside its box can never line up with a counter that has one above.
    // So what is asserted is that no field is laying itself out — every one of them is in a track
    // the row owns. jsdom has no layout, and a pixel assertion here would prove nothing.
    const { container } = openAndGet();
    const rows = container.querySelectorAll("[data-field-row]");
    expect(rows.length).toBe(2);
    rows.forEach((row) => {
      const cells = row.querySelectorAll(":scope > *");
      expect(cells.length).toBeGreaterThan(0);
      cells.forEach((cell) => {
        expect(cell.getAttribute("data-field-row-cell")).not.toBeNull();
        expect(cell.className).toContain("grid-rows-subgrid");
        expect(cell.className).toContain("row-span-3");
      });
    });
  });

  it("gives the Scales-to readout a label above its box, not inside it", () => {
    const { container } = openAndGet();
    const label = screen.getByText("Scales to");
    expect(label.className).toContain("pl-field-inset");
    // Its box is its sibling in the row, not its parent.
    expect(label.nextElementSibling?.textContent).toContain("servings");
    expect(container.innerHTML).not.toContain("&nbsp;");
  });
});
