import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { ImportCloseMatches, closeMatchesFrom } from "@/components/ImportCloseMatches";
import { ApiError, type ImportCloseMatchView } from "@/lib/api";

/**
 * The "Did you mean …?" screen a library copy shows for close ingredient matches (Q-11, T-287).
 *
 * <p>What is guarded: every close match is listed, "Use <existing>" answers a row at once, "It's a
 * different ingredient" answers only after its confirmation, nothing is sent until every row is
 * answered, and the whole thing works from the keyboard — focus in on open, on to the next row as
 * each is answered, Escape one step back at a time, Tab kept inside, the page behind made inert, and
 * focus handed back on close.
 */

const TOMATO: ImportCloseMatchView = {
  libraryName: "Tomatos",
  note: "chopped",
  existingIngredientId: "ing-t",
  existingIngredientName: "Tomato, ripe",
};
const JAGGERY: ImportCloseMatchView = {
  libraryName: "Jaggary",
  note: null,
  existingIngredientId: "ing-j",
  existingIngredientName: "Jaggery",
};

function open(matches = [TOMATO, JAGGERY]) {
  const onAdd = vi.fn();
  const onCancel = vi.fn();
  const view = render(
    <ImportCloseMatches recipeName="Tomato Saaru" matches={matches} onAdd={onAdd} onCancel={onCancel} />
  );
  return { onAdd, onCancel, dialog: screen.getByRole("dialog"), ...view };
}

function row(name: string) {
  return screen.getByRole("group", { name });
}

describe("ImportCloseMatches", () => {
  it("lists every close match, with its note, as a modal dialog", () => {
    const { dialog } = open();

    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(screen.getByRole("dialog", { name: "Did you mean these ingredients?" })).toBe(dialog);
    expect(dialog).toHaveAccessibleDescription(
      "2 ingredients in Tomato Saaru are close to ones you already have. Choose which each one is before the recipe is added."
    );
    expect(within(row("Tomatos · chopped")).getByText("Did you mean Tomato, ripe?")).toBeInTheDocument();
    expect(within(row("Jaggary")).getByText("Did you mean Jaggery?")).toBeInTheDocument();
    expect(within(row("Tomatos · chopped")).getByRole("button", { name: "Use Tomato, ripe" })).toBeInTheDocument();
    expect(within(row("Jaggary")).getByRole("button", { name: "It’s a different ingredient" })).toBeInTheDocument();
  });

  it("A-N5: shows the note \"Use\" will keep before the person answers — Ginger · peeled — without repeating it in the heading", () => {
    const GINGER: ImportCloseMatchView = {
      libraryName: "Ginger, peeled",
      note: "peeled",
      existingIngredientId: "ing-g",
      existingIngredientName: "Ginger",
    };
    const MUSTARD: ImportCloseMatchView = {
      libraryName: "Mustard, split",
      note: "split",
      existingIngredientId: "ing-m",
      existingIngredientName: "Mustard",
    };
    const { onAdd } = open([GINGER, MUSTARD]);

    const ginger = row("Ginger, peeled");
    // R-DUP-2's question, word for word: the note is not added to it.
    expect(within(ginger).getByText("Did you mean Ginger?")).toBeInTheDocument();
    expect(within(ginger).getByText("The line will read “Ginger · peeled” if you use it.")).toBeInTheDocument();
    expect(within(row("Mustard, split")).getByText("The line will read “Mustard · split” if you use it.")).toBeInTheDocument();

    fireEvent.click(within(ginger).getByRole("button", { name: "Use Ginger" }));
    expect(within(ginger).getByText("Using Ginger · peeled.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Use Mustard" }));
    fireEvent.click(screen.getByRole("button", { name: "Add to my recipes" }));
    expect(onAdd).toHaveBeenCalledWith([
      { libraryName: "Ginger, peeled", useIngredientId: "ing-g", confirmDifferent: false },
      { libraryName: "Mustard, split", useIngredientId: "ing-m", confirmDifferent: false },
    ]);
  });

  it("A-N5: the heading adds only the parts of the note the name doesn't already say, as whole words", () => {
    open([
      { libraryName: "Rice, basmati", note: "basmati, soaked", existingIngredientId: "ing-r", existingIngredientName: "Rice" },
      { libraryName: "Coconut", note: "cut", existingIngredientId: "ing-c", existingIngredientName: "Coconut, dry" },
    ]);
    expect(within(row("Rice, basmati · soaked")).getByText("The line will read “Rice · basmati, soaked” if you use it.")).toBeInTheDocument();
    // "cut" is inside "Coconut" as letters, not as a word, so it is still shown.
    expect(row("Coconut · cut")).toBeInTheDocument();
  });

  it("a row with no note says nothing about the line", () => {
    open([JAGGERY]);
    expect(within(row("Jaggary")).queryByText(/The line will read/)).toBeNull();
  });

  it("says one ingredient in the singular", () => {
    open([JAGGERY]);
    expect(screen.getByRole("dialog", { name: "Did you mean this ingredient?" })).toHaveAccessibleDescription(
      "An ingredient in Tomato Saaru is close to one you already have. Choose which it is before the recipe is added."
    );
  });

  it("\"Use\" answers the row, and \"Change\" takes the answer back", () => {
    open();
    fireEvent.click(screen.getByRole("button", { name: "Use Tomato, ripe" }));

    // The answer reads as the line will: the note goes with it (A-N5, T-297).
    expect(within(row("Tomatos · chopped")).getByText("Using Tomato, ripe · chopped.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Change the answer for Tomatos" }));
    expect(screen.getByRole("button", { name: "Use Tomato, ripe" })).toBeInTheDocument();
  });

  it("\"It's a different ingredient\" needs a deliberate confirmation, and Go back undoes it", () => {
    open();
    const tomato = row("Tomatos · chopped");
    fireEvent.click(within(tomato).getByRole("button", { name: "It’s a different ingredient" }));

    // Not answered yet: only the confirmation's own button answers.
    expect(within(tomato).getByText("Keep “Tomatos” separate from Tomato, ripe?")).toBeInTheDocument();
    expect(
      within(tomato).getByText("It will have its own stock, prices and orders. Your choice is recorded in the audit log.")
    ).toBeInTheDocument();
    fireEvent.click(within(tomato).getByRole("button", { name: "Go back" }));
    expect(within(tomato).getByRole("button", { name: "Use Tomato, ripe" })).toBeInTheDocument();

    fireEvent.click(within(tomato).getByRole("button", { name: "It’s a different ingredient" }));
    fireEvent.click(within(tomato).getByRole("button", { name: "Keep it separate" }));
    expect(
      within(tomato).getByText("Kept separate. “Tomatos” will be added as a new ingredient.")
    ).toBeInTheDocument();
  });

  it("sends nothing until every row is answered, and says so", () => {
    const { onAdd } = open();
    fireEvent.click(screen.getByRole("button", { name: "Use Tomato, ripe" }));
    fireEvent.click(screen.getByRole("button", { name: "Add to my recipes" }));

    expect(onAdd).not.toHaveBeenCalled();
    expect(screen.getByRole("status")).toHaveTextContent("Choose an answer for every ingredient first.");
    // The keyboard is put on the row still waiting.
    expect(screen.getByRole("button", { name: "Use Jaggery" })).toHaveFocus();
  });

  it("sends one answer per close match in the reserved shape", () => {
    const { onAdd } = open();
    fireEvent.click(screen.getByRole("button", { name: "Use Tomato, ripe" }));
    const jaggery = row("Jaggary");
    fireEvent.click(within(jaggery).getByRole("button", { name: "It’s a different ingredient" }));
    fireEvent.click(within(jaggery).getByRole("button", { name: "Keep it separate" }));
    fireEvent.click(screen.getByRole("button", { name: "Add to my recipes" }));

    expect(onAdd).toHaveBeenCalledWith([
      { libraryName: "Tomatos", useIngredientId: "ing-t", confirmDifferent: false },
      { libraryName: "Jaggary", useIngredientId: null, confirmDifferent: true },
    ]);
  });

  it("works from the keyboard: focus in, on to the next row, and to Add once all are answered", () => {
    open();
    expect(screen.getByRole("button", { name: "Use Tomato, ripe" })).toHaveFocus();

    fireEvent.click(screen.getByRole("button", { name: "Use Tomato, ripe" }));
    expect(screen.getByRole("button", { name: "Use Jaggery" })).toHaveFocus();

    // Into the confirmation, focus lands on the way back, never on the act.
    fireEvent.click(within(row("Jaggary")).getByRole("button", { name: "It’s a different ingredient" }));
    expect(within(row("Jaggary")).getByRole("button", { name: "Go back" })).toHaveFocus();

    fireEvent.click(within(row("Jaggary")).getByRole("button", { name: "Keep it separate" }));
    expect(screen.getByRole("button", { name: "Add to my recipes" })).toHaveFocus();
  });

  it("Escape steps back out of a confirmation first, then cancels", () => {
    const { onCancel } = open();
    const tomato = row("Tomatos · chopped");
    fireEvent.click(within(tomato).getByRole("button", { name: "It’s a different ingredient" }));

    fireEvent.keyDown(document, { key: "Escape" });
    expect(onCancel).not.toHaveBeenCalled();
    expect(within(tomato).getByRole("button", { name: "It’s a different ingredient" })).toHaveFocus();

    fireEvent.keyDown(document, { key: "Escape" });
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it("keeps Tab inside the dialog, both ways round", () => {
    open();
    const first = screen.getByRole("button", { name: "Use Tomato, ripe" });
    const last = screen.getByRole("button", { name: "Add to my recipes" });

    last.focus();
    fireEvent.keyDown(document, { key: "Tab" });
    expect(first).toHaveFocus();

    fireEvent.keyDown(document, { key: "Tab", shiftKey: true });
    expect(last).toHaveFocus();
  });

  it("makes the page behind inert while open, and hands focus back on close", () => {
    const opener = document.createElement("button");
    opener.textContent = "Add Majjige to your recipes";
    const page = document.createElement("div");
    page.appendChild(opener);
    document.body.appendChild(page);
    opener.focus();

    const { unmount, dialog } = open();
    expect(page).toHaveAttribute("inert");
    expect(dialog.closest("[inert]")).toBeNull();

    unmount();
    expect(page).not.toHaveAttribute("inert");
    expect(opener).toHaveFocus();
    page.remove();
  });

  it("has a real label on every button, Cancel included", () => {
    const { onCancel } = open();
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(onCancel).toHaveBeenCalled();
  });
});

describe("closeMatchesFrom", () => {
  it("rebuilds the list from a KMS-400156 refusal's flattened details", () => {
    const error = new ApiError({
      code: "KMS-400156",
      message: "m",
      action: "a",
      fieldErrors: [
        { field: "closeMatches[0].libraryName", message: "Tomatos" },
        { field: "closeMatches[0].note", message: "chopped" },
        { field: "closeMatches[0].existingIngredientId", message: "ing-t" },
        { field: "closeMatches[0].existingIngredientName", message: "Tomato, ripe" },
        { field: "closeMatches[1].libraryName", message: "Jaggary" },
        { field: "closeMatches[1].existingIngredientId", message: "ing-j" },
        { field: "closeMatches[1].existingIngredientName", message: "Jaggery" },
      ],
    });
    expect(closeMatchesFrom(error)).toEqual([TOMATO, JAGGERY]);
  });

  it("is null for any other failure, or a 400156 without the list (the ingredient form's)", () => {
    expect(closeMatchesFrom(new Error("x"))).toBeNull();
    expect(
      closeMatchesFrom(new ApiError({ code: "KMS-400001", message: "m", action: "a", fieldErrors: [] }))
    ).toBeNull();
    expect(
      closeMatchesFrom(
        new ApiError({
          code: "KMS-400156",
          message: "m",
          action: "a",
          fieldErrors: [
            { field: "existingIngredientId", message: "ing-c" },
            { field: "existingIngredientName", message: "Curd" },
          ],
        })
      )
    ).toBeNull();
  });
});
