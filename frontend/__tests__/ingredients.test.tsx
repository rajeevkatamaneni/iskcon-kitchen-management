import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ApiError, IngredientView } from "@/lib/api";

const { pushMock, replaceMock, paramsRef } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  replaceMock: vi.fn(),
  paramsRef: { current: new URLSearchParams() },
}));

const { authRef, queryRef, reloadMock, createMock, updateMock, ekadashiFlagMock, deleteMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  queryRef: { current: { data: [] as IngredientView[] | null, error: null as ApiError | null, loading: false } },
  reloadMock: vi.fn(),
  createMock: vi.fn(),
  updateMock: vi.fn(),
  ekadashiFlagMock: vi.fn(),
  deleteMock: vi.fn(),
}));

// The list reads its own address bar now (E10-S12): adding happens on /ingredients/new and the
// confirmation travels back in the URL, so the stub answers both halves of next/navigation.
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
  useSearchParams: () => paramsRef.current,
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ ...queryRef.current, reload: reloadMock }),
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      createIngredient: createMock,
      updateIngredient: updateMock,
      setIngredientEkadashiFlag: ekadashiFlagMock,
      deleteIngredient: deleteMock,
    },
  };
});

import IngredientsPage from "@/app/ingredients/page";

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

/**
 * The row carries one flag column now — D-18 deleted the second — but every assertion still goes
 * through this rather than querying "Allowed" or "Prohibited" by name across the whole row.
 *
 * <p>It was written when there were two columns that read identically, so a query by button name
 * matched whichever came first and would have passed just as happily against the wrong rule. It is
 * kept because the reason it existed can come back: it finds the column by its own header rather
 * than by a fixed index, so inserting a column later moves the tests with it instead of silently
 * pointing them at the neighbour.
 */
function flagCell(column: "Ekadashi") {
  const headers = screen.getAllByRole("columnheader").map((h) => h.textContent);
  const index = headers.indexOf(column);
  expect(index, `no "${column}" column on the ingredients table`).toBeGreaterThan(-1);
  return within((screen.getAllByRole("row")[1] as HTMLTableRowElement).cells[index]);
}

describe("ingredient management", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    queryRef.current = { data: [ingredient({})], error: null, loading: false };
    reloadMock.mockReset();
    paramsRef.current = new URLSearchParams();
    pushMock.mockReset();
    replaceMock.mockReset();
    createMock.mockReset().mockResolvedValue({ id: "new" });
    updateMock.mockReset().mockResolvedValue(undefined);
    ekadashiFlagMock.mockReset().mockResolvedValue(undefined);
    deleteMock.mockReset().mockResolvedValue(undefined);
  });

  it("links each name to the ingredient's own page (Q-10, T-286)", () => {
    render(<IngredientsPage />);
    const cell = screen.getByRole("cell", { name: "Rice" });
    expect(within(cell).getByRole("link", { name: "Rice" })).toHaveAttribute("href", "/ingredients/i1");
  });

  it("lists ingredients and sends adding to a screen of its own", () => {
    render(<IngredientsPage />);
    expect(screen.getByRole("heading", { name: /ingredients/i })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "Rice" })).toBeInTheDocument();

    // Four fields is over the threshold in DESIGN_SYSTEM.md, so the form is not on this page.
    expect(screen.queryByRole("form", { name: /add an ingredient/i })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: /add an ingredient/i })).toHaveAttribute(
      "href",
      "/ingredients/new"
    );
  });

  it("shows the confirmation a new ingredient comes back with, and strips the param", () => {
    paramsRef.current = new URLSearchParams("added=Ghee");
    render(<IngredientsPage />);
    expect(screen.getByText(/Ghee was added/i)).toBeInTheDocument();
    expect(replaceMock).toHaveBeenCalledWith("/ingredients");
  });

  it("points an empty list at the add screen rather than at a panel above it", () => {
    queryRef.current = { data: [], error: null, loading: false };
    render(<IngredientsPage />);
    expect(screen.getByText(/no ingredients yet/i)).toBeInTheDocument();
    expect(screen.queryByText(/above/i)).not.toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: /add an ingredient/i })[0]).toHaveAttribute(
      "href",
      "/ingredients/new"
    );
  });

  /*
    T-121. The flag was a button in this cell from T-045 until 2026-09-10, when Rajeev removed the
    click: "Ingredients don't go in and out of Ekadashi restriction EVER. They are either IN or
    OUT. Once set CORRECTLY, there is no reason to change it."

    Asserted on the RENDERED ELEMENT rather than on the absence of a handler, which is the brief's
    own instruction and the only assertion worth having. `queryByRole("button")` returning null
    proves nothing about a `<div onClick>` or a `<span role="button">`, and the defect being guarded
    against is "it still behaves like a control", not "it is still a <button> tag". So: what the
    cell contains is checked by tag name, and clicking it is checked to reach nothing.
  */
  it("shows Ekadashi as a label in view mode, with nothing to click", async () => {
    queryRef.current = { data: [ingredient({ ekadashiProhibited: true })], error: null, loading: false };
    render(<IngredientsPage />);
    const cell = (screen.getAllByRole("row")[1] as HTMLTableRowElement).cells[
      screen.getAllByRole("columnheader").map((h) => h.textContent).indexOf("Ekadashi")
    ];

    expect(cell.textContent).toBe("Prohibited");
    // Nothing interactive of any kind, however it is spelled.
    expect(cell.querySelector("button, a, input, select, textarea, [role='button'], [onclick]")).toBeNull();
    expect(cell.firstElementChild?.tagName).toBe("SPAN");

    // And pressing it does nothing — the old cell would have fired here.
    fireEvent.click(cell.firstElementChild as HTMLElement);
    await waitFor(() => expect(updateMock).not.toHaveBeenCalled());
    expect(ekadashiFlagMock).not.toHaveBeenCalled();
  });

  it("shows Allowed as a label too, so neither state is a control", () => {
    render(<IngredientsPage />);
    const cell = flagCell("Ekadashi");
    expect(cell.getByText("Allowed")).toBeInTheDocument();
    expect(cell.queryByRole("button")).not.toBeInTheDocument();
    expect(cell.queryByRole("checkbox")).not.toBeInTheDocument();
  });

  /*
    T-441. Where the flag is set is no longer here at all.

    Rajeev, 2026-09-20: "remove the edit button and move the functionality the current edit button
    provides into the edit screen. When the user clicks on the Ingrident Name, it open in the view
    mode, then they see the edit button, Click on that and it goes to the edit screen wchi shows
    save and cancel." So this list has no editing row, and the four fields it carried — name and
    category, aliases, unit and the Ekadashi box — are asserted on `/ingredients/[id]/edit` in
    `__tests__/ingredient-edit.test.tsx`, including the two rules that are easy to lose in a move:
    the box opens holding the flag the row already has, and a Kitchen Manager's save leaves the key
    off the payload rather than sending `false`.

    What is asserted here is what this screen must no longer offer.
  */
  it("has no Edit button on a row — the name is the way in (T-441)", () => {
    render(<IngredientsPage />);
    const row = screen.getAllByRole("row")[1] as HTMLTableRowElement;
    expect(within(row).queryByRole("button", { name: /^edit$/i })).not.toBeInTheDocument();
    // The name, and it opens the ingredient's own page, where Edit now lives.
    expect(within(row).getByRole("link", { name: "Rice" }).getAttribute("href")).toBe("/ingredients/i1");
  });

  it("keeps Delete on the row, which is a removal rather than a change", () => {
    render(<IngredientsPage />);
    const actions = within(screen.getAllByRole("row")[1] as HTMLTableRowElement).getAllByRole("button");
    expect(actions.map((b) => b.textContent)).toEqual(["Delete"]);
  });

  it("offers no way to change anything from the table itself", () => {
    queryRef.current = { data: [ingredient({ ekadashiProhibited: true })], error: null, loading: false };
    render(<IngredientsPage />);
    // No box, no select, no text field: the whole table is read-only now.
    expect(screen.queryAllByRole("checkbox")).toHaveLength(0);
    expect(screen.queryAllByRole("textbox")).toHaveLength(0);
    expect(screen.queryAllByRole("combobox")).toHaveLength(0);
    expect(updateMock).not.toHaveBeenCalled();
  });

  // Somebody needs to be able to see which ingredients a fasting day rules out without touching
  // anything — the flag is read far more often than it is set.
  it("shows the state of the flag without changing it", () => {
    queryRef.current = {
      data: [ingredient({ ekadashiProhibited: true })],
      error: null,
      loading: false,
    };
    render(<IngredientsPage />);
    expect(flagCell("Ekadashi").getByText("Prohibited")).toBeInTheDocument();
    expect(ekadashiFlagMock).not.toHaveBeenCalled();
  });

  /*
    D-18 removed the other dietary flag entirely — column, toggle, badge and endpoint.

    Asserted by what the screen offers rather than by the shape of a prop, because a prop that is
    no longer passed proves nothing about what a person sees: the column header list is read whole,
    and the count of flag toggles in the row is exactly one. If the removed column ever came back,
    both halves of this would fail rather than one of them quietly matching the survivor.
  */
  it("offers one dietary flag and no second one, and no Type column", () => {
    render(<IngredientsPage />);
    /*
      "Type" joined this list in T-023 and left it again in T-089, when supplies got a screen of
      their own and every row here became food — a column printing one word all the way down.
      Rajeev asked for its removal again on 2026-09-10 against the deployed build, which is a
      version behind: "There is no reason for it be PROUDLY displayed on UI."

      This is the assertion that says it is gone, and it is an equality rather than a
      `not.toContain("Type")` on purpose: an exact list also fails if a column is *added* back under
      another name.
    */
    expect(screen.getAllByRole("columnheader").map((h) => h.textContent)).toEqual([
      "Name",
      "Category",
      "Unit",
      "Ekadashi",
      "Actions",
    ]);
    // Nothing in the table body offers a dietary toggle at all now (T-121) — the flag is read here
    // and set on the ingredient's own edit screen (T-441). One row, one Delete button, and nothing
    // else that can be pressed.
    expect(screen.queryAllByRole("button", { name: /^(allowed|prohibited)$/i })).toHaveLength(0);
    expect(screen.queryAllByRole("checkbox")).toHaveLength(0);
  });

  it("shows kitchen staff the Ekadashi state but gives them no way to change it", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    queryRef.current = { data: [ingredient({ ekadashiProhibited: true })], error: null, loading: false };
    render(<IngredientsPage />);
    const cell = flagCell("Ekadashi");
    expect(cell.getByText("Prohibited")).toBeInTheDocument();
    expect(cell.queryByRole("button")).not.toBeInTheDocument();
  });

  /*
    The same rule for the box itself — a Kitchen Manager may rename a prohibited ingredient and may
    not decide what is prohibited — is asserted where the box now is, on the edit screen: see
    "leaves the key off a kitchen-staff save rather than sending false" in
    `__tests__/ingredient-edit.test.tsx`. It cannot be asserted here any more, because this screen
    sends no update at all (T-441).
  */

  it("deletes an ingredient", async () => {
    render(<IngredientsPage />);
    fireEvent.click(screen.getByRole("button", { name: /delete/i }));
    await waitFor(() => expect(deleteMock).toHaveBeenCalledWith("i1", "test-token"));
  });

  /*
    T-119. The column has existed since V69 and nothing read it: a recipe import creates every
    ingredient the temple does not already have, silently and on purpose, and until now no screen
    said which rows those were.

    Every assertion here is on the words a person sees rather than on a prop, because the words are
    the specification — Rajeev chose "Added by a Recipe Import" over a shorter label on 2026-09-10,
    and a test matching /import/i would go on passing through a reword.
  */
  describe("ingredients a recipe import created", () => {
    it("labels the row it created, in the exact words", () => {
      queryRef.current = { data: [ingredient({ libraryDerived: true })], error: null, loading: false };
      render(<IngredientsPage />);
      expect(screen.getByText("Added by a Recipe Import")).toBeInTheDocument();
    });

    it("says nothing at all on a row a person typed", () => {
      render(<IngredientsPage />);
      expect(screen.queryByText("Added by a Recipe Import")).not.toBeInTheDocument();
      // Not a column either: no header appears and no row prints the negative of the fact.
      expect(screen.queryByText(/added by/i)).not.toBeInTheDocument();
    });

    /*
      T-402. A second badge under the name, in the same place and the same neutral style, and the
      header contract is deliberately unchanged: Name, Category, Unit, Ekadashi, Actions. The
      cell-count test above is what holds that, and it is untouched by this.
    */
    it("labels a row the temple never buys, in the same two words every screen uses", () => {
      queryRef.current = { data: [ingredient({ notBought: true })], error: null, loading: false };
      render(<IngredientsPage />);
      expect(screen.getByText("Not bought")).toBeInTheDocument();
      // Still five columns. A sixth would have been the easy way to show this and the wrong one:
      // it would print something on every row, including the negative of the fact.
      expect(screen.getAllByRole("columnheader")).toHaveLength(5);
    });

    it("says nothing on a row the temple does buy", () => {
      render(<IngredientsPage />);
      expect(screen.queryByText("Not bought")).not.toBeInTheDocument();
      expect(screen.queryByText(/bought/i)).not.toBeInTheDocument();
    });

    it("shows both labels on a row that carries both, without widening the row", () => {
      queryRef.current = {
        data: [ingredient({ libraryDerived: true, notBought: true })],
        error: null,
        loading: false,
      };
      render(<IngredientsPage />);
      expect(screen.getByText("Added by a Recipe Import")).toBeInTheDocument();
      expect(screen.getByText("Not bought")).toBeInTheDocument();
      expect(screen.getAllByRole("columnheader")).toHaveLength(5);
    });

    it("keeps the not-bought label out of the semantic colours", () => {
      // Amber is low, wrong or overdue; red is act now; green is the success of the reader's own
      // action. A temple that has decided it does not buy water has nothing wrong with it.
      queryRef.current = { data: [ingredient({ notBought: true })], error: null, loading: false };
      render(<IngredientsPage />);
      expect(screen.getByText("Not bought").className).not.toMatch(/warning|danger|success/);
    });

    it("keeps the label out of the semantic colours", () => {
      // DESIGN_SYSTEM.md:115 — warning means low, wrong or overdue. An unreviewed ingredient is
      // none of those, and sixty amber rows would be wallpaper rather than a signal.
      queryRef.current = { data: [ingredient({ libraryDerived: true })], error: null, loading: false };
      render(<IngredientsPage />);
      expect(screen.getByText("Added by a Recipe Import").className).not.toMatch(/warning|danger/);
    });

    it("counts them above the list", () => {
      queryRef.current = {
        data: [
          ingredient({ id: "i1", name: "Rice", libraryDerived: true }),
          ingredient({ id: "i2", name: "Jaggery", libraryDerived: true }),
          ingredient({ id: "i3", name: "Ghee", libraryDerived: false }),
        ],
        error: null,
        loading: false,
      };
      render(<IngredientsPage />);
      expect(screen.getByText("2 ingredients were added by a recipe import")).toBeInTheDocument();
    });

    it("counts one of them as one", () => {
      queryRef.current = { data: [ingredient({ libraryDerived: true })], error: null, loading: false };
      render(<IngredientsPage />);
      expect(screen.getByText("1 ingredient was added by a recipe import")).toBeInTheDocument();
    });

    /*
      The count falling is the whole of part 4: without it the message and the filter describe a
      list that only grows, and within a month nobody opens it.

      Asserted by re-rendering on the list the server sends back after a save, which is what
      `reload()` fetches — the same shape the screen would really receive, rather than a prop poked
      by hand.
    */
    it("falls as ingredients are reviewed, and goes away entirely at zero", () => {
      queryRef.current = {
        data: [
          ingredient({ id: "i1", name: "Rice", libraryDerived: true }),
          ingredient({ id: "i2", name: "Jaggery", libraryDerived: true }),
        ],
        error: null,
        loading: false,
      };
      const view = render(<IngredientsPage />);
      expect(screen.getByText("2 ingredients were added by a recipe import")).toBeInTheDocument();

      queryRef.current = {
        data: [
          ingredient({ id: "i1", name: "Rice", libraryDerived: false }),
          ingredient({ id: "i2", name: "Jaggery", libraryDerived: true }),
        ],
        error: null,
        loading: false,
      };
      view.rerender(<IngredientsPage />);
      expect(screen.getByText("1 ingredient was added by a recipe import")).toBeInTheDocument();

      queryRef.current = {
        data: [
          ingredient({ id: "i1", name: "Rice", libraryDerived: false }),
          ingredient({ id: "i2", name: "Jaggery", libraryDerived: false }),
        ],
        error: null,
        loading: false,
      };
      view.rerender(<IngredientsPage />);
      expect(screen.queryByText(/added by a recipe import/i)).not.toBeInTheDocument();
      expect(screen.queryByRole("tab", { name: /added by an import/i })).not.toBeInTheDocument();
    });

    it("offers no message and no filter to a temple that has never imported", () => {
      render(<IngredientsPage />);
      expect(screen.queryByText(/added by a recipe import/i)).not.toBeInTheDocument();
      expect(screen.queryByRole("tablist")).not.toBeInTheDocument();
    });

    it("puts the filter in the address bar, so the message on Recipes can link to it", () => {
      queryRef.current = { data: [ingredient({ libraryDerived: true })], error: null, loading: false };
      render(<IngredientsPage />);
      fireEvent.click(screen.getByRole("tab", { name: "Added by an import" }));
      expect(replaceMock).toHaveBeenCalledWith("/ingredients?show=added-by-import");
    });

    it("shows only the ones an import created while the filter is on", () => {
      paramsRef.current = new URLSearchParams("show=added-by-import");
      queryRef.current = {
        data: [
          ingredient({ id: "i1", name: "Rice", libraryDerived: true }),
          ingredient({ id: "i2", name: "Ghee", libraryDerived: false }),
        ],
        error: null,
        loading: false,
      };
      render(<IngredientsPage />);
      expect(screen.getByRole("cell", { name: /Rice/ })).toBeInTheDocument();
      expect(screen.queryByRole("cell", { name: /Ghee/ })).not.toBeInTheDocument();
    });

    /*
      The state the whole task is aiming at. The catalogue is not empty, so "No ingredients yet"
      with an Add button beside it would be a lie and would send somebody off to type a row they do
      not need.
    */
    it("says the queue emptied rather than that the catalogue did", () => {
      paramsRef.current = new URLSearchParams("show=added-by-import");
      queryRef.current = { data: [ingredient({ libraryDerived: false })], error: null, loading: false };
      render(<IngredientsPage />);
      expect(screen.getByText(/nothing left from an import/i)).toBeInTheDocument();
      expect(screen.queryByText(/no ingredients yet/i)).not.toBeInTheDocument();
      expect(screen.getByRole("link", { name: /show all ingredients/i })).toHaveAttribute(
        "href",
        "/ingredients"
      );
    });

    it("keeps the filter when it strips the added-confirmation from the address", () => {
      // Adding an ingredient while filtered used to be the way back to the whole catalogue: the
      // capture effect replaced with a bare "/ingredients" and took the filter with it.
      paramsRef.current = new URLSearchParams("added=Ghee&show=added-by-import");
      queryRef.current = { data: [ingredient({ libraryDerived: true })], error: null, loading: false };
      render(<IngredientsPage />);
      expect(replaceMock).toHaveBeenCalledWith("/ingredients?show=added-by-import");
    });
  });

  // T-276: the one way to the merge tool, for those who hold MERGE_INGREDIENTS (the Temple Admin).
  it("links a Temple Admin to the merge tool", () => {
    render(<IngredientsPage />);
    expect(screen.getByRole("link", { name: "Merge duplicates" })).toHaveAttribute("href", "/ingredients/merge");
  });

  it("does not show a Kitchen Manager or Kitchen Staff the merge link", () => {
    for (const role of ["KITCHEN_MANAGER", "KITCHEN_STAFF"]) {
      authRef.current = { status: "signed-in", appUser: { role, userId: "me" } };
      const { unmount } = render(<IngredientsPage />);
      expect(screen.queryByRole("link", { name: /merge/i })).not.toBeInTheDocument();
      unmount();
    }
  });

  it("refuses a role without recipe access", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } };
    render(<IngredientsPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
