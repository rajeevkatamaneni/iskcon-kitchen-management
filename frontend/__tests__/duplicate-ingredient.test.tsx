import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ApiError, type IngredientView } from "@/lib/api";

/**
 * "Did you mean Curd?" (R-DUP-2, T-251): the shared prompt, and the three places it is wired —
 * /ingredients/new, /supplies/new and the inline rename on /ingredients.
 *
 * The server decides what a lookalike is and answers KMS-400156 with the existing ingredient in
 * `fieldErrors`; these tests stand in for that answer and check what each screen does with it.
 */

const { pushMock, replaceMock, paramsRef, queryRef, createMock, updateMock } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  replaceMock: vi.fn(),
  paramsRef: { current: new URLSearchParams() },
  queryRef: { current: { data: [] as IngredientView[] | null } },
  createMock: vi.fn(),
  updateMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
  useSearchParams: () => paramsRef.current,
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({
    status: "signed-in",
    appUser: { role: "TEMPLE_ADMIN", userId: "me" },
    getToken: async () => "test-token",
  }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ data: queryRef.current.data, error: null, loading: false, reload: vi.fn() }),
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, createIngredient: createMock, updateIngredient: updateMock } };
});

import { DuplicateIngredientPrompt, lookalikeFrom } from "@/components/DuplicateIngredientPrompt";
import NewIngredientPage from "@/app/ingredients/new/page";
import NewSupplyPage from "@/app/supplies/new/page";

/** What the server answers when "Curd sour" is typed and "Curd" exists. */
function looksLike(id: string, name: string): ApiError {
  return new ApiError(
    {
      code: "KMS-400156",
      message: "There's already an ingredient with a name very like this one.",
      action: "Use the existing ingredient, or add a preparation note to the recipe line instead.",
      fieldErrors: [
        { field: "existingIngredientId", message: id },
        { field: "existingIngredientName", message: name },
      ],
    },
    409
  );
}

function ingredient(o: Partial<IngredientView>): IngredientView {
  return {
    id: "i1",
    name: "Rice",
    category: "Grains",
    unit: "KG",
    packSizes: [],
    marketRate: null,
    marketRateOn: null,
    marketRateSource: null,
    ekadashiProhibited: false,
    supply: false,
    notBought: false,
    libraryDerived: false,
    aliases: [],
    createdAt: "2026-08-01T00:00:00Z",
    ...o,
  };
}

beforeEach(() => {
  pushMock.mockReset();
  replaceMock.mockReset();
  paramsRef.current = new URLSearchParams();
  queryRef.current = { data: [] };
  createMock.mockReset();
  updateMock.mockReset();
});

describe("lookalikeFrom", () => {
  it("reads the existing ingredient out of a KMS-400156", () => {
    expect(lookalikeFrom(looksLike("curd-id", "Curd"))).toEqual({ id: "curd-id", name: "Curd" });
  });

  it("is null for any other failure, so it falls back to the ordinary error notice", () => {
    expect(
      lookalikeFrom(new ApiError({ code: "KMS-400034", message: "x", action: "y", fieldErrors: [] }, 409))
    ).toBeNull();
    expect(lookalikeFrom(new Error("network"))).toBeNull();
    // A 400156 missing its details cannot say which Curd, so it is not asked as a question.
    expect(
      lookalikeFrom(new ApiError({ code: "KMS-400156", message: "x", action: "y", fieldErrors: [] }, 409))
    ).toBeNull();
  });
});

describe("the prompt", () => {
  function renderPrompt() {
    const handlers = { onUse: vi.fn(), onDifferent: vi.fn(), onDismiss: vi.fn() };
    render(<DuplicateIngredientPrompt candidate="Curd sour" existing={{ id: "c", name: "Curd" }} {...handlers} />);
    return handlers;
  }

  it("asks in the spec's exact words, with its two buttons", () => {
    renderPrompt();
    const dialog = screen.getByRole("alertdialog", { name: "Did you mean Curd?" });
    // The heading and the sentence under it, which together are the spec's line word for word.
    expect(within(dialog).getByRole("heading")).toHaveTextContent(/^Did you mean Curd\?$/);
    expect(dialog).toHaveAccessibleDescription("Use Curd, or add a preparation note instead.");
    const buttons = within(dialog).getAllByRole("button").map((b) => b.textContent);
    expect(buttons).toEqual(["Use Curd", "It’s a different ingredient"]);
  });

  it("styles both answers as the raised secondary, and nothing amber or red", () => {
    renderPrompt();
    const dialog = screen.getByRole("alertdialog");
    for (const b of within(dialog).getAllByRole("button")) {
      // `secondary` is `.btn-quiet`, the style E raised secondary, on `.btn` which carries the press.
      expect(b.className).toContain("btn ");
      expect(b.className).toContain("btn-quiet");
    }
    expect(dialog.innerHTML).not.toMatch(/warning|danger/);
  });

  it("does not save on 'It's a different ingredient' — it asks again first", () => {
    const h = renderPrompt();
    fireEvent.click(screen.getByRole("button", { name: "It’s a different ingredient" }));
    expect(h.onDifferent).not.toHaveBeenCalled();

    const confirm = screen.getByRole("alertdialog", { name: "Keep “Curd sour” separate from Curd?" });
    expect(confirm).toHaveTextContent("recorded in the audit log");
    // The way back is first and takes focus, so a habitual Enter does not save.
    expect(within(confirm).getAllByRole("button")[0]).toHaveTextContent("Go back");
    expect(document.activeElement).toBe(within(confirm).getByRole("button", { name: "Go back" }));

    fireEvent.click(screen.getByRole("button", { name: "Keep it separate" }));
    expect(h.onDifferent).toHaveBeenCalledTimes(1);
  });

  it("goes back one step at a time: Go back, then Escape closes it", () => {
    const h = renderPrompt();
    fireEvent.click(screen.getByRole("button", { name: "It’s a different ingredient" }));
    fireEvent.click(screen.getByRole("button", { name: "Go back" }));
    expect(screen.getByRole("alertdialog", { name: "Did you mean Curd?" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "It’s a different ingredient" }));
    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.getByRole("alertdialog", { name: "Did you mean Curd?" })).toBeInTheDocument();
    expect(h.onDismiss).not.toHaveBeenCalled();
    fireEvent.keyDown(document, { key: "Escape" });
    expect(h.onDismiss).toHaveBeenCalledTimes(1);
  });

  it("calls onUse for 'Use Curd'", () => {
    const h = renderPrompt();
    fireEvent.click(screen.getByRole("button", { name: "Use Curd" }));
    expect(h.onUse).toHaveBeenCalledTimes(1);
  });
});

describe("/ingredients/new", () => {
  function fillAndAdd(name: string) {
    fireEvent.change(screen.getByLabelText(/^name$/i), { target: { value: name } });
    fireEvent.change(screen.getByLabelText(/^category$/i), { target: { value: "Dairy" } });
    fireEvent.click(screen.getByRole("button", { name: /add ingredient/i }));
  }

  it("AC: 'Curd sour' beside Curd is stopped with the prompt, not an error notice", async () => {
    createMock.mockRejectedValueOnce(looksLike("curd-id", "Curd"));
    render(<NewIngredientPage />);
    fillAndAdd("Curd sour");

    expect(await screen.findByRole("alertdialog", { name: "Did you mean Curd?" })).toBeInTheDocument();
    expect(screen.queryByText(/KMS-400156/)).not.toBeInTheDocument();
    expect(pushMock).not.toHaveBeenCalled();
  });

  it("AC: 'Tomatos' beside Tomato, ripe asks about Tomato, ripe", async () => {
    createMock.mockRejectedValueOnce(looksLike("tom-id", "Tomato, ripe"));
    render(<NewIngredientPage />);
    fillAndAdd("Tomatos");

    const dialog = await screen.findByRole("alertdialog", { name: "Did you mean Tomato, ripe?" });
    expect(within(dialog).getByRole("button", { name: "Use Tomato, ripe" })).toBeInTheDocument();
  });

  it("'Use Curd' adds nothing and opens Curd's own edit screen", async () => {
    createMock.mockRejectedValueOnce(looksLike("curd-id", "Curd"));
    render(<NewIngredientPage />);
    fillAndAdd("Curd sour");

    fireEvent.click(await screen.findByRole("button", { name: "Use Curd" }));
    // Straight to the screen since T-441; it used to go through `/ingredients?edit=`, which opened
    // an editing row on the list that no longer exists.
    expect(pushMock).toHaveBeenCalledWith("/ingredients/curd-id/edit");
    expect(createMock).toHaveBeenCalledTimes(1);
  });

  it("confirming a different ingredient re-sends the same thing with confirmDifferent", async () => {
    createMock.mockRejectedValueOnce(looksLike("curd-id", "Curd")).mockResolvedValueOnce({ id: "new" });
    render(<NewIngredientPage />);
    fillAndAdd("Curd sour");

    fireEvent.click(await screen.findByRole("button", { name: "It’s a different ingredient" }));
    expect(createMock).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: "Keep it separate" }));

    await waitFor(() => expect(createMock).toHaveBeenCalledTimes(2));
    expect(createMock.mock.calls[1][0]).toEqual(
      expect.objectContaining({ name: "Curd sour", category: "Dairy", confirmDifferent: true })
    );
    expect(createMock.mock.calls[0][0]).not.toHaveProperty("confirmDifferent");
    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/ingredients?added=Curd%20sour"));
  });

  it("any other failure still shows the ordinary error notice", async () => {
    createMock.mockRejectedValueOnce(
      new ApiError({ code: "KMS-400034", message: "An ingredient with that name already exists.", action: "x", fieldErrors: [] }, 409)
    );
    render(<NewIngredientPage />);
    fillAndAdd("Curd");

    expect(await screen.findByText(/An ingredient with that name already exists/)).toBeInTheDocument();
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });
});

describe("/supplies/new", () => {
  it("asks the same question, and confirming keeps it a supply", async () => {
    createMock.mockRejectedValueOnce(looksLike("lp-id", "Leaf plates")).mockResolvedValueOnce({ id: "new" });
    render(<NewSupplyPage />);
    fireEvent.change(screen.getByLabelText(/^name$/i), { target: { value: "Leaf plate" } });
    fireEvent.change(screen.getByLabelText(/^category$/i), { target: { value: "Disposables" } });
    fireEvent.click(screen.getByRole("button", { name: /add supply/i }));

    expect(await screen.findByRole("alertdialog", { name: "Did you mean Leaf plates?" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "It’s a different ingredient" }));
    fireEvent.click(screen.getByRole("button", { name: "Keep it separate" }));

    await waitFor(() => expect(createMock).toHaveBeenCalledTimes(2));
    expect(createMock.mock.calls[1][0]).toEqual(
      expect.objectContaining({ name: "Leaf plate", supply: true, confirmDifferent: true })
    );
  });

  it("'Use Leaf plates' opens it on the one edit screen both halves share", async () => {
    createMock.mockRejectedValueOnce(looksLike("lp-id", "Leaf plates"));
    render(<NewSupplyPage />);
    fireEvent.change(screen.getByLabelText(/^name$/i), { target: { value: "Leaf plate" } });
    fireEvent.change(screen.getByLabelText(/^category$/i), { target: { value: "Disposables" } });
    fireEvent.click(screen.getByRole("button", { name: /add supply/i }));

    fireEvent.click(await screen.findByRole("button", { name: "Use Leaf plates" }));
    // No forwarding step any more: `/ingredients/[id]/edit` is one screen for food and supplies,
    // so a supply lands on its own form rather than being bounced through a list.
    expect(pushMock).toHaveBeenCalledWith("/ingredients/lp-id/edit");
  });
});

/*
  T-441 — the rename that used to happen in a row on /ingredients happens on the ingredient's own
  edit screen now, so the five tests that were here moved with it: see "renaming onto a lookalike
  (R-DUP-2)" in `__tests__/ingredient-edit.test.tsx`, which asserts the same three things (the
  prompt instead of a save, the confirmed re-send carrying `confirmDifferent`, and "Use Curd"
  saving nothing) against the screen that now owns the form.

  The two that cannot move are the `?edit=` ones, and they are gone rather than rewritten: both add
  screens send "Use Curd" straight to `/ingredients/<id>/edit` now, which is asserted above, so
  there is no parameter left for this list to read and no supply for it to forward.
*/
