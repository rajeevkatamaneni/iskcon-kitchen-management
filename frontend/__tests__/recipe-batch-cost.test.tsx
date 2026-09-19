import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import type { IngredientView, RecipeCategory, RecipeDetail } from "@/lib/api";
import { batchCost } from "@/lib/format";

/*
 * T-231: a recipe's rough cost is the cost of one batch, the amount "This recipe makes", and it is
 * said with the batch named — "₹8,000 per batch (270 L)" — instead of a bare "₹8,000" that left the
 * reader to guess per what. The form's label says the same, and names the batch beside the box as it
 * is typed. Setup is the same as recipe-form-units.test.tsx: the form alone, Save outside it.
 */
const { catFn, ingFn } = vi.hoisted(() => ({ catFn: () => {}, ingFn: () => {} }));

const CATEGORIES: RecipeCategory[] = [{ id: "c1", name: "Rasam", fastingCompatible: false }];
const INGREDIENTS: IngredientView[] = [
  { id: "i1", name: "Tamarind", category: "Spices", unit: "KG", ekadashiProhibited: false, supply: false, libraryDerived: false, aliases: [], createdAt: "" },
];

vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, listRecipeCategories: catFn, listIngredients: ingFn } };
});
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fetcher: unknown) =>
    fetcher === catFn
      ? { data: CATEGORIES, error: null, loading: false }
      : fetcher === ingFn
        ? { data: INGREDIENTS, error: null, loading: false }
        : { data: null, error: null, loading: false },
}));

import { RecipeForm } from "@/components/RecipeForm";

const submitMock = vi.fn();

function renderForm(initial?: RecipeDetail) {
  return render(
    <>
      <RecipeForm initial={initial} formId="recipe" busy={false} error={null} onSubmit={submitMock} />
      <button type="submit" form="recipe">Save</button>
    </>,
  );
}

const amount = () => screen.getByRole("spinbutton", { name: "How much this recipe makes" });
const measuredIn = () => screen.getByLabelText(/^measured in$/i) as HTMLSelectElement;

function recipe(overrides: Partial<RecipeDetail> = {}): RecipeDetail {
  return {
    id: "r1", name: "Rasam", categoryId: "c1", categoryName: "Rasam", fastingCompatible: false,
    baseYieldQty: 270, baseYieldUnit: "L", method: null, notes: null, regionTag: null, yieldNote: null,
    perHeadQty: 350, perHeadUnit: "ML", subtitle: null, badge: null, indicativeCost: null, why: null,
    cateringNote: null, subRegion: null, noteStart: null, noteVessel: null, noteSeason: null, tags: [],
    serveWith: [], masterRecipeId: null, status: "ACTIVE", version: 1,
    ingredients: [{ ingredientId: "i1", ingredientName: "Tamarind", quantity: 2, unit: "KG" }],
    createdAt: "2026-09-01T00:00:00Z",
    ...overrides,
  };
}

beforeEach(() => submitMock.mockReset());

describe("batchCost (T-231)", () => {
  it("says the cost with the batch it buys, in lakh grouping", () => {
    expect(batchCost(8000, 270, "L")).toBe("₹8,000 per batch (270 L)");
    expect(batchCost(150000, 40, "KG")).toBe("₹1,50,000 per batch (40 Kg)");
  });

  it("uses the app's quantity words for the batch", () => {
    expect(batchCost(500, 0.5, "KG")).toBe("₹500 per batch (500 gm)");
    expect(batchCost(1200, 300, "PIECES")).toBe("₹1,200 per batch (300 pieces)");
  });

  it("drops the bracket when there is no batch size, and says — when there is no cost", () => {
    expect(batchCost(8000, null, "L")).toBe("₹8,000 per batch");
    expect(batchCost(8000, 0, "L")).toBe("₹8,000 per batch");
    expect(batchCost(null, 270, "L")).toBe("—");
  });
});

describe("the form's cost field (T-231)", () => {
  const cost = () => screen.getByLabelText(/rough cost of one batch/i);

  it("is labelled as the cost of one batch, and never 'Indicative cost'", () => {
    renderForm(recipe({ indicativeCost: 8000 }));
    expect(screen.getByText("Rough cost of one batch (₹)")).toBeInTheDocument();
    expect(screen.queryByText(/indicative cost/i)).not.toBeInTheDocument();
    expect(cost()).toHaveValue(8000);
  });

  it("names the batch beside the box, and follows 'This recipe makes' as it changes", () => {
    renderForm(recipe());
    expect(screen.getByText("for 270 L")).toBeInTheDocument();
    fireEvent.change(amount(), { target: { value: "40" } });
    fireEvent.change(measuredIn(), { target: { value: "KG" } });
    expect(screen.getByText("for 40 Kg")).toBeInTheDocument();
    expect(screen.queryByText("for 270 L")).not.toBeInTheDocument();
  });

  it("shows no batch while 'This recipe makes' is empty", () => {
    renderForm(recipe());
    fireEvent.change(amount(), { target: { value: "" } });
    expect(screen.queryByText(/^for /)).not.toBeInTheDocument();
  });

  it("still sends the figure as indicativeCost", () => {
    renderForm(recipe());
    fireEvent.change(cost(), { target: { value: "8000" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(submitMock).toHaveBeenCalledWith(expect.objectContaining({ indicativeCost: 8000 }));
  });
});
