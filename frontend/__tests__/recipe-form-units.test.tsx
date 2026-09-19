import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import type { IngredientView, RecipeCategory, RecipeDetail } from "@/lib/api";

/*
 * T-218: what a recipe makes, what it is measured in, and what one person eats — and what those
 * three say together.
 *
 * A recipe was saved as 270 L with a portion of 350 ml, and the form said nothing about what that
 * implied. Rajeev approved six guardrails: plain labels, three units for the recipe, a portion
 * locked to the recipe's family, a live "feeds about N people" line, an amber warning outside 5 to
 * 5,000, and a hint in the "i". Each is asserted here against the form itself, rendered on its own
 * with a Save button outside it that reaches it by `form=`, the way both recipe screens do.
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
const eats = () => screen.getByLabelText(/^one person eats$/i);
const portionUnit = () => screen.getByLabelText(/^portion unit$/i) as HTMLSelectElement;
const optionsOf = (select: HTMLSelectElement) => within(select).getAllByRole("option").map((o) => o.textContent);

/** Fill the four boxes the feeds line reads. */
function type(made: string, unit: string, each: string, eachUnit: string) {
  fireEvent.change(amount(), { target: { value: made } });
  fireEvent.change(measuredIn(), { target: { value: unit } });
  fireEvent.change(eats(), { target: { value: each } });
  fireEvent.change(portionUnit(), { target: { value: eachUnit } });
}

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

describe("the labels (T-218)", () => {
  it("says 'This recipe makes' and 'Measured in', and never 'Base yield' or 'Yield unit'", () => {
    renderForm();
    expect(screen.getByText("This recipe makes")).toBeInTheDocument();
    expect(amount()).toBeInTheDocument();
    expect(measuredIn()).toBeInTheDocument();
    // Unchanged on purpose.
    expect(eats()).toBeInTheDocument();
    expect(portionUnit()).toBeInTheDocument();
    expect(screen.queryByText(/base yield/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/yield unit/i)).not.toBeInTheDocument();
  });

  it("puts the hint in the 'i' beside 'This recipe makes', not under the box", () => {
    renderForm();
    const hint = screen.getByRole("button", { name: "More about This recipe makes" });
    expect(screen.queryByText(/planned amounts are scaled from this/i)).not.toBeInTheDocument();
    fireEvent.focus(hint);
    expect(screen.getByRole("tooltip")).toHaveTextContent(
      "How much the ingredients below make. Planned amounts are scaled from this.",
    );
  });
});

describe("the unit lists (T-218)", () => {
  it("offers only Kilos, Litres and Pieces under 'Measured in'", () => {
    renderForm();
    expect(optionsOf(measuredIn())).toEqual(["Kg", "L", "pieces"]);
  });

  it("locks the portion unit to the recipe's family", () => {
    renderForm();
    fireEvent.change(measuredIn(), { target: { value: "KG" } });
    expect(optionsOf(portionUnit())).toEqual(["—", "Kg", "gm"]);
    fireEvent.change(measuredIn(), { target: { value: "L" } });
    expect(optionsOf(portionUnit())).toEqual(["—", "L", "ml"]);
    fireEvent.change(measuredIn(), { target: { value: "PIECES" } });
    expect(optionsOf(portionUnit())).toEqual(["—", "pieces"]);
  });

  it("clears a portion unit that no longer fits when 'Measured in' changes, and keeps one that does", () => {
    renderForm();
    fireEvent.change(measuredIn(), { target: { value: "L" } });
    fireEvent.change(portionUnit(), { target: { value: "ML" } });
    expect(portionUnit().value).toBe("ML");

    fireEvent.change(measuredIn(), { target: { value: "KG" } });
    expect(portionUnit().value).toBe("");

    fireEvent.change(portionUnit(), { target: { value: "GM" } });
    // Kilos to kilos is no change of family, so the portion stays.
    fireEvent.change(measuredIn(), { target: { value: "KG" } });
    expect(portionUnit().value).toBe("GM");
  });

  it("opens and saves an older recipe measured in grams without forcing a change", () => {
    renderForm(recipe({ baseYieldQty: 5000, baseYieldUnit: "GM", perHeadQty: 50, perHeadUnit: "GM" }));
    expect(measuredIn().value).toBe("GM");
    expect(optionsOf(measuredIn())).toEqual(["Kg", "L", "pieces", "gm"]);
    expect(portionUnit().value).toBe("GM");

    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(submitMock).toHaveBeenCalledWith(
      expect.objectContaining({ baseYieldQty: 5000, baseYieldUnit: "GM", perHeadQty: 50, perHeadUnit: "GM" }),
    );
  });
});

describe("what the recipe feeds (T-218)", () => {
  it("270 L at 350 ml each feeds about 771 people", () => {
    renderForm();
    type("270", "L", "350", "ML");
    expect(screen.getByText("This recipe feeds about 771 people at 350 ml each.")).toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("says the same for a saved recipe the moment it opens", () => {
    renderForm(recipe());
    expect(screen.getByText("This recipe feeds about 771 people at 350 ml each.")).toBeInTheDocument();
  });

  it("says nothing until both amounts and both units are filled", () => {
    renderForm();
    fireEvent.change(measuredIn(), { target: { value: "L" } });
    fireEvent.change(eats(), { target: { value: "350" } });
    expect(screen.queryByText(/this recipe feeds/i)).not.toBeInTheDocument();
    fireEvent.change(portionUnit(), { target: { value: "ML" } });
    expect(screen.getByText(/this recipe feeds about/i)).toBeInTheDocument();
    fireEvent.change(amount(), { target: { value: "" } });
    expect(screen.queryByText(/this recipe feeds/i)).not.toBeInTheDocument();
  });

  it("warns in amber under 5 people, and Save still works", () => {
    renderForm();
    fireEvent.change(screen.getByLabelText(/^name$/i), { target: { value: "Rasam" } });
    fireEvent.change(screen.getByLabelText(/^category$/i), { target: { value: "c1" } });
    fireEvent.change(screen.getByLabelText(/^ingredient 1$/i), { target: { value: "i1" } });
    fireEvent.change(screen.getByLabelText(/^quantity 1$/i), { target: { value: "2" } });
    type("1", "L", "350", "ML");

    expect(screen.getByText("This recipe feeds about 3 people at 350 ml each.")).toBeInTheDocument();
    const warning = screen.getByRole("status");
    expect(warning).toHaveTextContent("Very few people for one batch. Check the amounts and units.");
    expect(warning.className).toMatch(/bg-warning-bg/);

    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(submitMock).toHaveBeenCalledWith(
      expect.objectContaining({ baseYieldQty: 1, baseYieldUnit: "L", perHeadQty: 350, perHeadUnit: "ML" }),
    );
  });

  it("warns in amber over 5,000 people, with the count in grouped digits", () => {
    renderForm();
    type("5000", "L", "350", "ML");
    expect(screen.getByText("This recipe feeds about 14,286 people at 350 ml each.")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(
      "A lot of people for one batch. Check the amounts and units.",
    );
  });

  it("does not warn at exactly 5 or exactly 5,000", () => {
    renderForm();
    type("5", "KG", "1", "KG");
    expect(screen.getByText("This recipe feeds about 5 people at 1 Kg each.")).toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    type("5000", "PIECES", "1", "PIECES");
    expect(screen.getByText("This recipe feeds about 5,000 people at 1 piece each.")).toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });
});
