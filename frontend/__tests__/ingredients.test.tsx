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
    ekadashiProhibited: false,
    supply: false,
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

  // T-045. Until this column existed the flag could be set nowhere but the provisioning seed, so
  // every ingredient a temple added afterwards read as permitted on a fasting day. The assertions
  // are on the wrapper rather than on the pixels: what matters is that the change reaches the
  // server's `PATCH /ingredients/{id}/ekadashi-flag`, which is what `EkadashiPolicy.of()` and
  // `RecipeService` later read to decide which recipes Ekadashi allows.
  it("lets an admin mark an ingredient Ekadashi-prohibited", async () => {
    render(<IngredientsPage />);
    fireEvent.click(flagCell("Ekadashi").getByRole("button", { name: /allowed/i }));
    await waitFor(() => expect(ekadashiFlagMock).toHaveBeenCalledWith("i1", true, "test-token"));
  });

  it("lets an admin un-mark one, so a mistake is recoverable", async () => {
    queryRef.current = { data: [ingredient({ ekadashiProhibited: true })], error: null, loading: false };
    render(<IngredientsPage />);
    fireEvent.click(flagCell("Ekadashi").getByRole("button", { name: /prohibited/i }));
    await waitFor(() => expect(ekadashiFlagMock).toHaveBeenCalledWith("i1", false, "test-token"));
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
  it("offers one dietary flag and no second one", () => {
    render(<IngredientsPage />);
    // "Type" joined the list in T-023 and left it again in T-089, when supplies got a screen of
    // their own and every row here became food — a column printing one word all the way down. The
    // assertion that matters is the second one: whatever columns the table grows, exactly one of
    // them offers the allowed/prohibited toggle.
    expect(screen.getAllByRole("columnheader").map((h) => h.textContent)).toEqual([
      "Name",
      "Category",
      "Unit",
      "Ekadashi",
      "Actions",
    ]);
    expect(screen.getAllByRole("button", { name: /^(allowed|prohibited)$/i })).toHaveLength(1);
  });

  /*
    The editing row used to span two cells across the two flag columns; with one column left, a
    span of two would push Actions past the end of the table and misalign every row being edited.
    Counting cells is the only thing that catches that — jsdom has no layout.
  */
  it("keeps the editing row the same width as the header", () => {
    render(<IngredientsPage />);
    fireEvent.click(screen.getByRole("button", { name: /^edit$/i }));
    const headers = screen.getAllByRole("columnheader").length;
    const row = screen.getAllByRole("row")[1] as HTMLTableRowElement;
    expect([...row.cells].reduce((n, c) => n + c.colSpan, 0)).toBe(headers);
  });

  it("shows kitchen staff the Ekadashi state but gives them no way to change it", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    queryRef.current = { data: [ingredient({ ekadashiProhibited: true })], error: null, loading: false };
    render(<IngredientsPage />);
    const cell = flagCell("Ekadashi");
    expect(cell.getByText("Prohibited")).toBeInTheDocument();
    expect(cell.queryByRole("button")).not.toBeInTheDocument();
  });

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

  it("refuses a role without recipe access", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } };
    render(<IngredientsPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
