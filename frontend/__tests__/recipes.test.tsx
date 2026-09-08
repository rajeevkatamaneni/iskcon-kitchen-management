import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import type { RecipeSearchResult } from "@/lib/api";

const { authRef, searchMock, importMock } = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "KITCHEN_STAFF", fullName: "Test Person" },
      getToken: async () => "token",
    } as {
      status: string;
      appUser: { role: string; fullName?: string } | null;
      getToken: () => Promise<string>;
    },
  },
  searchMock: vi.fn(),
  importMock: vi.fn(),
}));

// The screen reads its own address bar, so the stub answers both halves of next/navigation.
const { pushMock, replaceMock, paramsRef } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  replaceMock: vi.fn(),
  paramsRef: { current: new URLSearchParams() },
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
  useSearchParams: () => paramsRef.current,
  useParams: () => ({ id: "id-1" }),
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, searchRecipes: searchMock, importRecipe: importMock },
  };
});

import RecipesPage from "@/app/recipes/page";

function mine(overrides: Partial<RecipeSearchResult> = {}): RecipeSearchResult {
  return {
    origin: "MINE",
    id: "r1",
    name: "Khichdi",
    subtitle: null,
    categoryName: "Rice",
    state: null,
    showState: false,
    badge: null,
    alreadyAdded: false,
    status: "ACTIVE",
    ...overrides,
  };
}

function library(overrides: Partial<RecipeSearchResult> = {}): RecipeSearchResult {
  return {
    ...mine(),
    origin: "LIBRARY",
    id: "m1",
    name: "Majjige",
    categoryName: "Beverages",
    state: "Karnataka",
    showState: true,
    badge: "Everyday",
    status: null,
    ...overrides,
  };
}

/*
  Rajeev's own words, approved 2026-09-08 under D-18, and asserted here character for character so
  that a later edit to the page has to come back through this test rather than quietly rewording
  him. The heading is the notice's `title` and the rest is its body.
*/
const WARNING_TITLE = "Imported ingredients arrive unflagged for Ekadashi";
const WARNING_BODY =
  "A recipe import adds any ingredient this temple doesn\u2019t have, and can\u2019t tell which are " +
  "restricted on a fast day \u2014 so it flags none. Set the Ekadashi flag on each yourself, or the " +
  "meal planner will allow them onto an Ekadashi menu.";

/** True when `first` comes before `second` in the rendered document. */
function precedes(first: Element, second: Element): boolean {
  return Boolean(first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING);
}

/** The page debounces, so every assertion waits for the search it triggered to land. */
async function settle() {
  await vi.waitFor(() => expect(searchMock).toHaveBeenCalled());
}

describe("recipe browse", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  beforeEach(() => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "KITCHEN_STAFF", fullName: "Test Person" },
      getToken: async () => "token",
    };
    paramsRef.current = new URLSearchParams();
    pushMock.mockReset();
    replaceMock.mockReset();
    importMock
      .mockReset()
      .mockResolvedValue({ id: "new", name: "Majjige", ingredientsCreated: 8, categoryCreated: false });
    searchMock.mockReset().mockResolvedValue([mine(), library()]);
  });

  it("shows the temple's own recipes and the library's in one list", async () => {
    render(<RecipesPage />);
    await settle();

    expect(screen.getByRole("heading", { name: /recipes/i })).toBeInTheDocument();
    expect(await screen.findByText("Khichdi")).toBeInTheDocument();
    expect(screen.getByText("Majjige")).toBeInTheDocument();
  });

  it("says nothing about what it searches", async () => {
    render(<RecipesPage />);
    await settle();

    // The brief is explicit: no line under the box explaining that it covers both sources. A person
    // types and sees results; that is the explanation.
    expect(screen.queryByText(/master recipe/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/shared library/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/searches both/i)).not.toBeInTheDocument();
  });

  it("has no category chips and no archived tick", async () => {
    render(<RecipesPage />);
    await settle();

    expect(screen.queryByRole("group", { name: /filter by category/i })).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/show archived recipes/i)).not.toBeInTheDocument();
  });

  it("offers a plus on a library recipe, and none on one already taken", async () => {
    searchMock.mockResolvedValue([library(), library({ id: "m2", name: "Panaka", alreadyAdded: true })]);
    render(<RecipesPage />);

    expect(await screen.findByRole("button", { name: /add majjige to your recipes/i })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /add panaka to your recipes/i })).not.toBeInTheDocument();
  });

  it("never offers a plus on the temple's own", async () => {
    searchMock.mockResolvedValue([mine()]);
    render(<RecipesPage />);
    await screen.findByText("Khichdi");

    expect(screen.queryByRole("button", { name: /add khichdi/i })).not.toBeInTheDocument();
  });

  it("adds in place: the row stays, the plus goes, and nothing navigates", async () => {
    searchMock.mockResolvedValue([library()]);
    render(<RecipesPage />);

    fireEvent.click(await screen.findByRole("button", { name: /add majjige to your recipes/i }));

    await vi.waitFor(() => expect(importMock).toHaveBeenCalledWith("m1", "token"));
    await vi.waitFor(() =>
      expect(screen.queryByRole("button", { name: /add majjige to your recipes/i })).not.toBeInTheDocument()
    );
    // Somebody adding three recipes should not be thrown out of their search after the first.
    expect(screen.getByText("Majjige")).toBeInTheDocument();
    expect(pushMock).not.toHaveBeenCalled();
  });

  it("opens a recipe on its own screen, and carries the search back with it", async () => {
    searchMock.mockResolvedValue([mine(), library()]);
    render(<RecipesPage />);

    // A layer could only ever show the recipe; Edit and Delete live on the screen itself, and
    // reading a recipe is usually the step before changing it (Rajeev, 2026-08-23).
    expect((await screen.findByText("Khichdi")).closest("a")).toHaveAttribute("href", "/recipes/r1");
    expect(screen.getByText("Majjige").closest("a")).toHaveAttribute("href", "/recipes/library/m1");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    // With something typed, the term rides along so the way back lands on these same results.
    fireEvent.change(screen.getByLabelText(/search recipes/i), { target: { value: "majjige" } });
    await vi.waitFor(() =>
      expect(screen.getByText("Majjige").closest("a")).toHaveAttribute(
        "href",
        "/recipes/library/m1?q=majjige"
      )
    );
  });

  it("prints the state only where the name does not already carry it", async () => {
    searchMock.mockResolvedValue([
      library({ id: "m1", name: "Majjige", showState: true }),
      library({
        id: "m2",
        name: "Sabudana Khichdi (Bihar)",
        state: "Bihar",
        showState: false,
        categoryName: "Khichadi",
      }),
    ]);
    render(<RecipesPage />);
    await screen.findByText("Majjige");

    // "Sabudana Khichdi (Bihar) · Bihar" would say it twice, so the row shows its category instead.
    expect(screen.getByText("Karnataka")).toBeInTheDocument();
    expect(screen.getByText("Khichadi")).toBeInTheDocument();
  });

  it("shows an archived recipe of the temple's own, badged — it is the only way back to one", async () => {
    searchMock.mockResolvedValue([mine({ status: "ARCHIVED" })]);
    render(<RecipesPage />);

    expect(await screen.findByText("Khichdi")).toBeInTheDocument();
    expect(screen.getByText("Archived")).toBeInTheDocument();
  });

  it("replaces rather than pushes as the search is typed", async () => {
    render(<RecipesPage />);
    await settle();

    fireEvent.change(screen.getByLabelText(/search recipes/i), { target: { value: "aam" } });
    expect(replaceMock).toHaveBeenCalledWith("/recipes?q=aam");
    expect(pushMock).not.toHaveBeenCalled();
  });

  it("opens on the search a deep link names", async () => {
    paramsRef.current = new URLSearchParams("q=majjige");
    render(<RecipesPage />);

    expect(screen.getByLabelText(/search recipes/i)).toHaveValue("majjige");
    await vi.waitFor(() => expect(searchMock).toHaveBeenCalledWith("majjige", "token"));
  });

  it("shows an empty state when a search matches nothing", async () => {
    searchMock.mockResolvedValue([]);
    render(<RecipesPage />);

    expect(await screen.findByText(/no recipes found/i)).toBeInTheDocument();
  });

  /*
    D-18. Recipe import creates any ingredient the temple lacks and flags none of them, and the
    provisioning seed that used to flag a handful is gone too, so a temple's whole catalogue now
    reads as permitted on a fast day until an admin says otherwise.

    Rajeev asked for it "in a warning box so it grabs the user\u2019s attention" rather than as text
    under the heading, which is what these three assertions are: it is there, it is the design
    system\u2019s warning treatment rather than a paragraph, and it stands where the eye reaches it
    before the results.
  */
  it("warns that imported ingredients arrive unflagged, in Rajeev\u2019s exact words", async () => {
    render(<RecipesPage />);
    await settle();

    expect(screen.getByText(WARNING_TITLE)).toBeInTheDocument();
    expect(screen.getByText(WARNING_BODY)).toBeInTheDocument();
  });

  it("puts the warning in the warning box rather than under the heading", async () => {
    render(<RecipesPage />);
    await settle();

    // `bg-warning-bg text-warning` is what `InlineNotice tone="warning"` paints, and `role=status`
    // is the element it paints them on — so this fails if the words are ever moved into a bare <p>.
    const notice = screen.getByText(WARNING_TITLE).closest("[role='status']");
    expect(notice).not.toBeNull();
    expect(notice).toHaveClass("bg-warning-bg", "text-warning");
  });

  it("stands between the heading and the list, not after the results", async () => {
    render(<RecipesPage />);
    await settle();
    const list = (await screen.findByText("Khichdi")).closest("ul")!;

    const notice = screen.getByText(WARNING_TITLE).closest("[role='status']")!;
    expect(precedes(screen.getByRole("heading", { name: /^recipes$/i }), notice)).toBe(true);
    expect(precedes(notice, list)).toBe(true);
  });

  /*
    It is standing context, not a confirmation, so it has to be there on the hundredth visit as
    well as the first. `InlineNotice` refuses `autoDismiss` on `warning` by construction — this
    asserts the page gets the benefit of that rather than trusting the prop is absent, since a
    prop that is not passed is invisible to a rendered-output test.
  */
  it("does not fade, however long the page is left open", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    render(<RecipesPage />);
    await settle();

    await vi.advanceTimersByTimeAsync(60_000);
    expect(screen.getByText(WARNING_BODY)).toBeInTheDocument();
    expect(screen.getByText(WARNING_TITLE).closest("[role='status']")).not.toHaveClass("opacity-0");
  });

  it("refuses a role without recipe access", () => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "VOLUNTEER", fullName: "Test Person" },
      getToken: async () => "token",
    };
    render(<RecipesPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /^recipes$/i })).not.toBeInTheDocument();
  });
});
