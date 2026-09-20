import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { IngredientView, MergePreviewView, MergeProposalView } from "@/lib/api";

/**
 * T-276 — the merge-duplicates screen (R-DUP-3). Every edit an admin makes is asserted as the exact
 * `MergeGroupInput` the server receives, because that payload is the whole of what the screen does.
 */

const { authRef, getToken, listProposals, listIngredients, previewMerge, mergeIngredients } = vi.hoisted(() => ({
  authRef: { current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } },
  // Stable across renders: useAuthedQuery lists it as an effect dependency.
  getToken: async () => "test-token",
  listProposals: vi.fn(),
  listIngredients: vi.fn(),
  previewMerge: vi.fn(),
  mergeIngredients: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/ingredients/merge",
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken, refresh: vi.fn(), signOut: vi.fn() }),
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      listMergeProposals: listProposals,
      listIngredients,
      previewMerge,
      mergeIngredients,
    },
  };
});

import { ApiError } from "@/lib/api";
import MergeIngredientsPage from "@/app/ingredients/merge/page";

function cand(id: string, name: string, unit: string, note: string | null, lines = 2) {
  return { ingredientId: id, name, unit, preparationNote: note, recipeLineCount: lines, onHand: 1, supplyCount: 1 };
}

const CURD: MergeProposalView = {
  keep: cand("curd", "Curd", "KG", null, 4),
  merge: [cand("fresh", "Curd, fresh", "KG", "fresh"), cand("sour", "Curd, sour", "GM", "sour"), cand("whisked", "Curd, whisked", "KG", "whisked")],
};
const CASHEW: MergeProposalView = {
  keep: cand("cashew", "Cashew", "KG", null),
  merge: [cand("halved", "Cashew, halved", "KG", "halved")],
};

function catalogueItem(id: string, name: string): IngredientView {
  return {
    id, name, category: "Dairy", unit: "KG", packSizes: [], marketRate: null, marketRateOn: null,
    marketRateSource: null, ekadashiProhibited: false, supply: false, notBought: false, libraryDerived: false, aliases: [],
    createdAt: "2026-08-01T00:00:00Z",
  };
}

function previewOf(p: MergeProposalView, extra: Partial<MergePreviewView> = {}): MergePreviewView {
  return { keep: p.keep, merge: p.merge, onHandAfter: 14, conflicts: [], unitProblem: null, ...extra };
}

function group(name: RegExp) {
  return within(screen.getByRole("region", { name }));
}

function curdGroup() {
  return within(screen.getByRole("heading", { level: 2, name: /into Curd$/ }).closest("section")!);
}

async function renderPage() {
  render(<MergeIngredientsPage />);
  await screen.findByRole("heading", { level: 2, name: /into Curd$/ });
}

function refusal(code: string, message: string, fields: Record<string, string> = {}) {
  return new ApiError(
    {
      code,
      message,
      action: "Next step.",
      fieldErrors: Object.entries(fields).map(([field, m]) => ({ field, message: m })),
    },
    409
  );
}

describe("merge duplicate ingredients", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    listProposals.mockReset().mockResolvedValue([CURD, CASHEW]);
    listIngredients.mockReset().mockResolvedValue([catalogueItem("dahi", "Dahi"), catalogueItem("curd", "Curd")]);
    previewMerge.mockReset().mockResolvedValue(previewOf(CURD));
    mergeIngredients.mockReset().mockResolvedValue({
      keptIngredientId: "curd", mergedIngredientIds: ["fresh", "sour", "whisked"], aliasesAdded: [], repointed: {},
    });
  });

  it("lists each proposed group with the note it would move onto recipe lines", async () => {
    await renderPage();
    expect(screen.getByRole("heading", { level: 2, name: "Curd, fresh / Curd, sour / Curd, whisked into Curd" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "Cashew, halved into Cashew" })).toBeInTheDocument();
    const curd = curdGroup();
    expect(curd.getByRole("radio", { name: "Keep Curd" })).toBeChecked();
    expect(curd.getByRole("textbox", { name: "Preparation note for Curd, fresh" })).toHaveValue("fresh");
    expect(curd.getByRole("textbox", { name: "Preparation note for Curd, sour" })).toHaveValue("sour");
    expect(curd.getByRole("textbox", { name: "Preparation note for Curd, whisked" })).toHaveValue("whisked");
    // The kept one has no note to move.
    expect(curd.queryByRole("textbox", { name: "Preparation note for Curd" })).not.toBeInTheDocument();
  });

  it("sends the proposal as it stands: the kept one, and each merged one with its note", async () => {
    await renderPage();
    fireEvent.click(curdGroup().getByRole("button", { name: "Preview merge" }));
    await waitFor(() => expect(previewMerge).toHaveBeenCalledTimes(1));
    expect(previewMerge.mock.calls[0][0]).toStrictEqual({
      keepIngredientId: "curd",
      merge: [
        { ingredientId: "fresh", preparationNote: "fresh" },
        { ingredientId: "sour", preparationNote: "sour" },
        { ingredientId: "whisked", preparationNote: "whisked" },
      ],
    });
  });

  it("sends the admin's edits: another one kept, one taken out, a note cleared and one typed", async () => {
    await renderPage();
    const curd = curdGroup();
    fireEvent.click(curd.getByRole("radio", { name: "Keep Curd, sour" }));
    fireEvent.click(curd.getByRole("button", { name: "Take Curd, whisked out of this group" }));
    fireEvent.change(curd.getByRole("textbox", { name: "Preparation note for Curd, fresh" }), { target: { value: "" } });
    // The one that was kept now merges, and its box opens empty.
    const was = curd.getByRole("textbox", { name: "Preparation note for Curd" });
    expect(was).toHaveValue("");
    fireEvent.click(curd.getByRole("button", { name: "Preview merge" }));
    await waitFor(() => expect(previewMerge).toHaveBeenCalledTimes(1));
    expect(previewMerge.mock.calls[0][0]).toStrictEqual({
      keepIngredientId: "sour",
      merge: [
        // null: the server takes the note from the name, which for "Curd" is none.
        { ingredientId: "curd", preparationNote: null },
        // "": the admin cleared it, so no note.
        { ingredientId: "fresh", preparationNote: "" },
      ],
    });
    expect(curd.queryByText("Curd, whisked")).not.toBeInTheDocument();
  });

  it("sends a typed note in place of the proposed one", async () => {
    await renderPage();
    const curd = curdGroup();
    fireEvent.change(curd.getByRole("textbox", { name: "Preparation note for Curd, sour" }), { target: { value: "sour, thick" } });
    fireEvent.click(curd.getByRole("button", { name: "Preview merge" }));
    await waitFor(() => expect(previewMerge).toHaveBeenCalledTimes(1));
    expect(previewMerge.mock.calls[0][0].merge[1]).toStrictEqual({ ingredientId: "sour", preparationNote: "sour, thick" });
  });

  it("adds an ingredient by hand, sends it with no note of its own, then shows the note the server would use", async () => {
    previewMerge.mockResolvedValue(
      previewOf(CURD, { merge: [...CURD.merge, { ...cand("dahi", "Dahi", "KG", null, 3), preparationNote: null }] })
    );
    await renderPage();
    const curd = curdGroup();
    const picker = curd.getByRole("combobox", { name: "Add an ingredient to this group" });
    // Already in the group, so not offered.
    expect(within(picker).queryByRole("option", { name: "Curd" })).not.toBeInTheDocument();
    fireEvent.change(picker, { target: { value: "dahi" } });
    fireEvent.click(curd.getByRole("button", { name: "Add" }));
    expect(curd.getByRole("textbox", { name: "Preparation note for Dahi" })).toHaveValue("");
    fireEvent.click(curd.getByRole("button", { name: "Preview merge" }));
    await waitFor(() => expect(previewMerge).toHaveBeenCalledTimes(1));
    expect(previewMerge.mock.calls[0][0].merge).toStrictEqual([
      { ingredientId: "fresh", preparationNote: "fresh" },
      { ingredientId: "sour", preparationNote: "sour" },
      { ingredientId: "whisked", preparationNote: "whisked" },
      { ingredientId: "dahi", preparationNote: null },
    ]);
    // The preview's counts fill the row in.
    const row = curd.getByRole("cell", { name: "Dahi" }).closest("tr")!;
    await waitFor(() => expect(within(row).getByText("3")).toBeInTheDocument());
  });

  it("shows the preview: on hand after in the kept unit, and the counts that move", async () => {
    await renderPage();
    fireEvent.click(curdGroup().getByRole("button", { name: "Preview merge" }));
    const preview = await screen.findByRole("region", { name: "Preview of merging into Curd" });
    expect(within(preview).getByText("On hand after").nextSibling).toHaveTextContent("14 Kg");
    expect(within(preview).getByText("Recipe lines moving").nextSibling).toHaveTextContent("6");
    expect(within(preview).getByText("Vendor supplies moving").nextSibling).toHaveTextContent("3");
  });

  it("will not merge until a price is chosen for a vendor that has two, and sends the choice", async () => {
    previewMerge.mockResolvedValue(
      previewOf(CURD, {
        conflicts: [{
          vendorId: "v1",
          vendorName: "Kalasipalya",
          prices: [
            { ingredientId: "curd", ingredientName: "Curd", listPrice: 60, unit: "KG", packLabel: null },
            { ingredientId: "sour", ingredientName: "Curd, sour", listPrice: 0.08, unit: "GM", packLabel: "1 Kg" },
          ],
        }],
      })
    );
    await renderPage();
    fireEvent.click(curdGroup().getByRole("button", { name: "Preview merge" }));
    await screen.findByRole("region", { name: "Preview of merging into Curd" });
    const preview = group(/Preview of merging into Curd/);
    const mergeButton = preview.getByRole("button", { name: "Merge into Curd" });
    expect(mergeButton).toBeDisabled();
    expect(preview.getByText(/Choose a price for the vendor above first/)).toBeInTheDocument();
    const choice = preview.getByRole("group", { name: /Kalasipalya has a different price for two of these/ });
    // Each price said the way the vendor page says it (T-293, VERIFY-A defect 3): ₹0.08 a gram is
    // "₹80 / Kg", with spaces, never "₹0.08/gm".
    const radios = within(choice).getAllByRole("radio").map((r) => r.closest("label")!.textContent);
    expect(radios).toEqual(["₹60 / Kg · Curd", "₹80 / Kg · 1 Kg · Curd, sour"]);
    fireEvent.click(within(choice).getByRole("radio", { name: /Curd, sour/ }));
    expect(mergeButton).toBeEnabled();
    fireEvent.click(mergeButton);
    fireEvent.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "Merge into Curd" }));
    await waitFor(() => expect(mergeIngredients).toHaveBeenCalledTimes(1));
    expect(mergeIngredients.mock.calls[0][0]).toStrictEqual({
      keepIngredientId: "curd",
      merge: [
        { ingredientId: "fresh", preparationNote: "fresh" },
        { ingredientId: "sour", preparationNote: "sour" },
        { ingredientId: "whisked", preparationNote: "whisked" },
      ],
      supplyPriceChoices: [{ vendorId: "v1", keepPriceFromIngredientId: "sour" }],
    });
  });

  it("shows a unit problem in red and will not merge that group", async () => {
    previewMerge.mockResolvedValue(previewOf(CURD, { unitProblem: "Curd is in Kg, Curd pieces is in pieces" }));
    await renderPage();
    fireEvent.click(curdGroup().getByRole("button", { name: "Preview merge" }));
    await screen.findByRole("region", { name: "Preview of merging into Curd" });
    const preview = group(/Preview of merging into Curd/);
    const notice = preview.getByText(/Curd is in Kg, Curd pieces is in pieces/).closest("[role=status]")!;
    expect(notice.className).toContain("bg-danger-bg");
    expect(preview.getByRole("button", { name: "Merge into Curd" })).toBeDisabled();
  });

  it("asks for a deliberate confirmation, and going back merges nothing", async () => {
    await renderPage();
    fireEvent.click(curdGroup().getByRole("button", { name: "Preview merge" }));
    await screen.findByRole("region", { name: "Preview of merging into Curd" });
    fireEvent.click(group(/Preview of merging into Curd/).getByRole("button", { name: "Merge into Curd" }));
    const dialog = screen.getByRole("alertdialog", { name: "Merge 3 ingredients into Curd?" });
    expect(dialog).toHaveTextContent("This can’t be undone.");
    // Focus lands on the way back, not on the act.
    expect(within(dialog).getByRole("button", { name: "Go back" })).toHaveFocus();
    fireEvent.click(within(dialog).getByRole("button", { name: "Go back" }));
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    fireEvent.click(group(/Preview of merging into Curd/).getByRole("button", { name: "Merge into Curd" }));
    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(mergeIngredients).not.toHaveBeenCalled();
  });

  it("confirms a merge in green and takes the group off the list", async () => {
    await renderPage();
    fireEvent.click(curdGroup().getByRole("button", { name: "Preview merge" }));
    await screen.findByRole("region", { name: "Preview of merging into Curd" });
    fireEvent.click(group(/Preview of merging into Curd/).getByRole("button", { name: "Merge into Curd" }));
    fireEvent.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "Merge into Curd" }));
    const done = await screen.findByText("Merged into Curd.");
    expect(done.closest("[role=status]")!.className).toContain("bg-success-bg");
    expect(screen.getByText("Curd, fresh / Curd, sour / Curd, whisked are now aliases of Curd.")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { level: 2, name: /into Curd$/ })).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "Cashew, halved into Cashew" })).toBeInTheDocument();
  });

  it.each([
    ["KMS-400171", "These ingredients are counted in different kinds of unit, so they can’t be merged.",
      { keptIngredient: "Curd", keptUnit: "Kg", otherIngredient: "Curd pieces", otherUnit: "pieces" },
      "Curd is in Kg, Curd pieces is in pieces."],
    ["KMS-400172", "The same vendor has a different list price for two of these ingredients.",
      { vendorId: "v1", vendorName: "Kalasipalya" }, "Vendor: Kalasipalya. Preview the merge again to choose."],
    ["KMS-400173", "This merge group can’t be used as it is.", {}, null],
  ])("shows a %s refusal the app's usual way, and keeps the group", async (code, message, fields, detail) => {
    mergeIngredients.mockRejectedValue(refusal(code, message, fields));
    await renderPage();
    fireEvent.click(curdGroup().getByRole("button", { name: "Preview merge" }));
    await screen.findByRole("region", { name: "Preview of merging into Curd" });
    fireEvent.click(group(/Preview of merging into Curd/).getByRole("button", { name: "Merge into Curd" }));
    fireEvent.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "Merge into Curd" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(message);
    expect(alert).toHaveTextContent(code);
    if (detail) expect(screen.getByText(detail)).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: /into Curd$/ })).toBeInTheDocument();
    expect(screen.queryByText("Merged into Curd.")).not.toBeInTheDocument();
  });

  it("shows a refused preview the app's usual way", async () => {
    previewMerge.mockRejectedValue(refusal("KMS-400173", "This merge group can’t be used as it is."));
    await renderPage();
    fireEvent.click(curdGroup().getByRole("button", { name: "Preview merge" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("KMS-400173");
  });

  it("says so when there is nothing to merge", async () => {
    listProposals.mockResolvedValue([]);
    render(<MergeIngredientsPage />);
    expect(await screen.findByText("No duplicates found")).toBeInTheDocument();
  });

  it("shows a Kitchen Manager the usual not-allowed page and asks the server nothing", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_MANAGER", userId: "me" } };
    render(<MergeIngredientsPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
    expect(listProposals).not.toHaveBeenCalled();
  });
});
