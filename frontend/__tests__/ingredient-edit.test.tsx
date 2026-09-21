import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ApiError, type IngredientView } from "@/lib/api";

/**
 * The ingredient's (and the supply's) edit screen — T-441.
 *
 * <p>Rajeev, 2026-09-20: "Inventory, remove the edit button and move the functionality the current
 * edit button provides into the edit screen. When the user clicks on the Ingrident Name, it open in
 * the view mode, then they see the edit button, Click on that and it goes to the edit screen wchi
 * shows save and cancel." And the day before, about this catalogue in particular: "that page is
 * missing what the Edit button provides directly in the table view. ie. Aliases, Unit, Ekadashi
 * Prohibited flag, Category. We dont need 'Move to Supplies' remove that option."
 *
 * <p>So the four fields have to be HERE, seeded with what the row holds, and the fifth thing has to
 * be gone. Those are the first two describes below, and they fail against the tree as it was: there
 * was no `/ingredients/[id]/edit` at all.
 *
 * <p>The rest of the file is the behaviour that moved off the list's editing row rather than being
 * invented here, and each test is the list's own assertion re-pointed at this screen: the Ekadashi
 * box belongs to a Temple Admin and the key is absent for everybody else (not `false`, which would
 * un-prohibit a grain); a rename onto a lookalike asks R-DUP-2's question instead of saving.
 */

const { authRef, pushMock } = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", userId: "me" } as { role: string; userId: string },
      getToken: async () => "test-token",
      refresh: () => {},
    },
  },
  pushMock: vi.fn(),
}));

const mocks = vi.hoisted(() => ({
  getIngredient: vi.fn(),
  updateIngredient: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({ id: "rice" }),
  usePathname: () => "/ingredients/rice/edit",
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, ...mocks } };
});

import EditIngredientPage from "@/app/ingredients/[id]/edit/page";

function rice(o: Partial<IngredientView> = {}): IngredientView {
  return {
    id: "rice",
    name: "Sona Masoori rice",
    category: "Grains",
    unit: "KG",
    packSizes: [],
    marketRate: 60,
    marketRateOn: "2026-09-12",
    marketRateSource: "MANUAL",
    ekadashiProhibited: true,
    supply: false,
    notBought: false,
    libraryDerived: true,
    aliases: ["Sona masuri", "Ponni rice"],
    createdAt: "2026-08-20T09:00:00Z",
    ...o,
  };
}

function lpg(): IngredientView {
  return rice({
    id: "lpg",
    name: "LPG cylinder",
    category: "Fuel",
    unit: "PIECE",
    ekadashiProhibited: false,
    supply: true,
    libraryDerived: false,
    aliases: ["Gas cylinder"],
  });
}

function setRole(role: string) {
  authRef.current = { ...authRef.current, appUser: { role, userId: "me" } };
}

/** The screen, once the ingredient has arrived. */
async function open(ingredient: IngredientView = rice()) {
  mocks.getIngredient.mockResolvedValue(ingredient);
  render(<EditIngredientPage />);
  await screen.findByDisplayValue(ingredient.name);
}

function box(label: string | RegExp): HTMLInputElement {
  return screen.getByLabelText(label) as HTMLInputElement;
}

beforeEach(() => {
  vi.clearAllMocks();
  setRole("TEMPLE_ADMIN");
  mocks.updateIngredient.mockResolvedValue(undefined);
});

describe("the four fields the editing row used to hold", () => {
  it("offers all four, each holding what the ingredient already has", async () => {
    await open();
    expect(box("Name").value).toBe("Sona Masoori rice");
    expect(box("Category").value).toBe("Grains");
    expect((screen.getByLabelText("Unit") as HTMLSelectElement).value).toBe("KG");
    // The stored list back as the line it was typed as, so saving it untouched is a no-op.
    expect(box(/Aliases/).value).toBe("Sona masuri, Ponni rice");
    expect(box(/Ekadashi/).checked).toBe(true);
  });

  it("shows Save and Cancel, and Cancel goes back to the page Edit was pressed on", async () => {
    await open();
    expect(screen.getByRole("button", { name: "Save changes" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "Cancel" }).getAttribute("href")).toBe("/ingredients/rice");
  });

  it("saves all four and returns to the ingredient's own page", async () => {
    await open();
    fireEvent.change(box("Name"), { target: { value: "Sona Masoori" } });
    fireEvent.change(box("Category"), { target: { value: "Rice" } });
    fireEvent.change(screen.getByLabelText("Unit"), { target: { value: "GM" } });
    fireEvent.change(box(/Aliases/), { target: { value: "Sona masuri, Ponni" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(mocks.updateIngredient).toHaveBeenCalled());
    expect(mocks.updateIngredient.mock.calls[0][1]).toEqual({
      name: "Sona Masoori",
      category: "Rice",
      unit: "GM",
      supply: false,
      ekadashiProhibited: true,
      aliases: ["Sona masuri", "Ponni"],
    });
    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/ingredients/rice"));
  });
});

describe("Move to Supplies", () => {
  it("is not offered on an ingredient — Rajeev: delete and recreate as a supply", async () => {
    await open();
    expect(screen.queryByLabelText(/Move to Supplies/i)).toBeNull();
  });

  it("is not offered in the other direction either, on a supply", async () => {
    await open(lpg());
    expect(screen.queryByLabelText(/Move to Ingredients/i)).toBeNull();
  });

  it("keeps an ingredient food on save, although there is no control saying so", async () => {
    await open();
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(mocks.updateIngredient).toHaveBeenCalled());
    // Stated, never omitted: the server field is a primitive and an absent key means food.
    expect(mocks.updateIngredient.mock.calls[0][1].supply).toBe(false);
  });

  it("keeps a supply a supply on save, for the same reason", async () => {
    await open(lpg());
    fireEvent.change(box("Category"), { target: { value: "Gas" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(mocks.updateIngredient).toHaveBeenCalled());
    expect(mocks.updateIngredient.mock.calls[0][1].supply).toBe(true);
  });
});

describe("one screen for both halves of the catalogue", () => {
  it("heads a supply's form as a supply and offers it no Ekadashi box", async () => {
    await open(lpg());
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("Update supply");
    expect(screen.queryByLabelText(/Ekadashi/)).toBeNull();
  });

  it("heads an ingredient's as an ingredient", async () => {
    await open();
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("Update ingredient");
  });

  it("sends a supply back to its own page too", async () => {
    await open(lpg());
    expect(screen.getByRole("link", { name: "Cancel" }).getAttribute("href")).toBe("/ingredients/rice");
  });
});

describe("who may set the Ekadashi flag", () => {
  it("offers the box to a Temple Admin, holding the flag the ingredient already has", async () => {
    await open();
    expect(box(/Ekadashi/).checked).toBe(true);
    fireEvent.click(box(/Ekadashi/));
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(mocks.updateIngredient).toHaveBeenCalled());
    expect(mocks.updateIngredient.mock.calls[0][1].ekadashiProhibited).toBe(false);
  });

  it("gives kitchen staff no box at all", async () => {
    setRole("KITCHEN_STAFF");
    await open();
    expect(screen.queryByLabelText(/Ekadashi/)).toBeNull();
  });

  it("leaves the key off a kitchen-staff save rather than sending false", async () => {
    setRole("KITCHEN_STAFF");
    await open();
    fireEvent.change(box("Name"), { target: { value: "Sona rice" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(mocks.updateIngredient).toHaveBeenCalled());
    // Absent, not `false`: false would ask to un-prohibit a grain they were shown no control for.
    expect("ekadashiProhibited" in mocks.updateIngredient.mock.calls[0][1]).toBe(false);
  });
});

describe("renaming onto a lookalike (R-DUP-2)", () => {
  /** What the server answers when "Curd sour" is typed and "Curd" exists — the add screens' own. */
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

  it("asks the question instead of saving, and stays on the form", async () => {
    await open();
    mocks.updateIngredient.mockRejectedValueOnce(looksLike("curd-id", "Curd"));
    fireEvent.change(box("Name"), { target: { value: "Curd sour" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByRole("button", { name: "Use Curd" })).toBeTruthy();
    expect(pushMock).not.toHaveBeenCalled();
  });

  it("re-sends the same edit with confirmDifferent when it is a different thing", async () => {
    await open();
    mocks.updateIngredient.mockRejectedValueOnce(looksLike("curd-id", "Curd"));
    fireEvent.change(box("Name"), { target: { value: "Curd sour" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    // Two steps on purpose: the prompt asks again before it lets a near-duplicate through.
    fireEvent.click(await screen.findByRole("button", { name: "It’s a different ingredient" }));
    fireEvent.click(screen.getByRole("button", { name: "Keep it separate" }));
    await waitFor(() => expect(mocks.updateIngredient).toHaveBeenCalledTimes(2));
    expect(mocks.updateIngredient.mock.calls[1][1]).toEqual({
      ...mocks.updateIngredient.mock.calls[0][1],
      confirmDifferent: true,
    });
  });

  it("'Use Curd' saves nothing and opens Curd's own edit screen", async () => {
    await open();
    mocks.updateIngredient.mockRejectedValueOnce(looksLike("curd-id", "Curd"));
    fireEvent.change(box("Name"), { target: { value: "Curd sour" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    fireEvent.click(await screen.findByRole("button", { name: "Use Curd" }));
    expect(mocks.updateIngredient).toHaveBeenCalledTimes(1);
    expect(pushMock).toHaveBeenCalledWith("/ingredients/curd-id/edit");
  });
});
