import { beforeEach, describe, expect, it, vi } from "vitest";
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
    sattvicOverridden: false,
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

/** The page debounces, so every assertion waits for the search it triggered to land. */
async function settle() {
  await vi.waitFor(() => expect(searchMock).toHaveBeenCalled());
}

describe("recipe browse", () => {
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

  it("links each row at the right screen for where it came from", async () => {
    searchMock.mockResolvedValue([mine(), library()]);
    render(<RecipesPage />);

    expect((await screen.findByText("Khichdi")).closest("a")).toHaveAttribute("href", "/recipes/r1");
    expect(screen.getByText("Majjige").closest("a")).toHaveAttribute("href", "/recipes/library/m1");
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
