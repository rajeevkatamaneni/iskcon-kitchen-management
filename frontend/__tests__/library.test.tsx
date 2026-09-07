import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";

/**
 * The shared recipe library as its operator sees it (E2-S15) — the other side of the search box a
 * temple gets on its own Recipes page.
 *
 * <p>It had no test of its own. `/library` appeared in the suite only as an href in the navigation
 * table, which proves the menu row exists and nothing whatever about the screen behind it.
 *
 * <p>What is characterised here is what the screen is for: that only a platform operator reaches it,
 * that the two acts nobody else may perform — loading the books and taking a recipe down — actually
 * call the API and are reflected on screen, and that the empty state tells a first-time operator
 * which button to press. The search box is debounced by 200ms, so every assertion about rows waits
 * rather than reading immediately.
 */

const {
  authRef,
  getToken,
  listLibraryRecipes,
  listLibraryStates,
  loadRecipeLibrary,
  deleteLibraryRecipe,
  replaceMock,
} = vi.hoisted(() => ({
  // One object, mutated in place. A fresh object per render would give `useAuth()` a new `getToken`
  // every time, and the page's `run` callback depends on it — the effect would re-arm for ever.
  authRef: {
    current: {
      status: "signed-in",
      appUser: {
        userId: "u1",
        role: "SUPER_ADMIN",
        fullName: "Platform Operator",
        tenantId: null,
        tenantName: null,
        temples: [] as unknown[],
      } as Record<string, unknown> | null,
      getToken: async () => "token-abc",
      refresh: async () => {},
      switchTemple: async () => {},
      signOut: async () => {},
    },
  },
  getToken: vi.fn(async () => "token-abc"),
  listLibraryRecipes: vi.fn(),
  listLibraryStates: vi.fn(),
  loadRecipeLibrary: vi.fn(),
  deleteLibraryRecipe: vi.fn(),
  replaceMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: replaceMock, push: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/library",
}));

vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      listLibraryRecipes,
      listLibraryStates,
      loadRecipeLibrary,
      deleteLibraryRecipe,
    },
  };
});

import LibraryPage from "@/app/library/page";
import { ApiError, type MasterRecipeSummary } from "@/lib/api";

function recipe(overrides: Partial<MasterRecipeSummary>): MasterRecipeSummary {
  return {
    id: "mr-1",
    displayName: "Bisi Bele Bath",
    subtitle: null,
    categoryName: "Rice",
    state: "Karnataka",
    badge: "Traditional",
    showState: true,
    alreadyAdded: false,
    ...overrides,
  };
}

function asOperator() {
  authRef.current.status = "signed-in";
  authRef.current.appUser = {
    userId: "u1",
    role: "SUPER_ADMIN",
    fullName: "Platform Operator",
    tenantId: null,
    tenantName: null,
    temples: [],
  };
}

describe("the recipe library, for a platform operator", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    asOperator();
    listLibraryRecipes.mockResolvedValue([]);
    listLibraryStates.mockResolvedValue([]);
  });

  it("invites the first load when nothing has been read in yet", async () => {
    render(<LibraryPage />);

    expect(await screen.findByRole("heading", { name: /recipe library/i })).toBeInTheDocument();
    expect(await screen.findByText(/nothing here yet/i)).toBeInTheDocument();
    // The empty state names the button rather than apologising.
    expect(screen.getByText(/press “load the books”/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /load the books/i })).toBeInTheDocument();
  });

  it("counts what is in the library across every state", async () => {
    listLibraryStates.mockResolvedValue([
      { slug: "karnataka", name: "Karnataka", recipes: 1200 },
      { slug: "gujarat", name: "Gujarat", recipes: 800 },
    ]);
    listLibraryRecipes.mockResolvedValue([recipe({})]);
    render(<LibraryPage />);

    // Indian digit grouping, because this is an India-first product and 2,000 is not 2,000 here.
    expect(await screen.findByText(/2,000 recipes · 2 states/)).toBeInTheDocument();
  });

  it("lists each recipe with the way back to it, and a way to take it down", async () => {
    listLibraryRecipes.mockResolvedValue([
      recipe({ id: "mr-1", displayName: "Bisi Bele Bath", state: "Karnataka", categoryName: "Rice" }),
      recipe({ id: "mr-2", displayName: "Undhiyu", state: "Gujarat", categoryName: "Sabji" }),
    ]);
    render(<LibraryPage />);

    const link = await screen.findByRole("link", { name: /bisi bele bath/i });
    expect(link).toHaveAttribute("href", "/recipes/library/mr-1");
    expect(screen.getByRole("link", { name: /undhiyu/i })).toHaveAttribute(
      "href",
      "/recipes/library/mr-2"
    );

    // The × is a bare glyph, so the only thing a screen reader has to go on is its label.
    expect(
      screen.getByRole("button", { name: /take bisi bele bath out of the library/i })
    ).toBeInTheDocument();
  });

  it("searches the library once typing stops, not on every keystroke", async () => {
    listLibraryRecipes.mockResolvedValue([]);
    render(<LibraryPage />);
    await waitFor(() => expect(listLibraryRecipes).toHaveBeenCalled());
    listLibraryRecipes.mockClear();

    fireEvent.change(screen.getByLabelText(/search the library/i), { target: { value: "halva" } });

    await waitFor(() =>
      expect(listLibraryRecipes).toHaveBeenCalledWith(
        expect.objectContaining({ q: "halva", limit: 100 }),
        "token-abc"
      )
    );
  });

  it("narrows to one state when the filter is used", async () => {
    listLibraryStates.mockResolvedValue([{ slug: "karnataka", name: "Karnataka", recipes: 12 }]);
    listLibraryRecipes.mockResolvedValue([]);
    render(<LibraryPage />);

    const filter = await screen.findByLabelText(/filter by state/i);
    await waitFor(() =>
      expect(within(filter).getByRole("option", { name: /karnataka \(12\)/i })).toBeInTheDocument()
    );
    fireEvent.change(filter, { target: { value: "karnataka" } });

    await waitFor(() =>
      expect(listLibraryRecipes).toHaveBeenCalledWith(
        expect.objectContaining({ state: "karnataka" }),
        "token-abc"
      )
    );
  });

  it("reads the books in and says what it found", async () => {
    loadRecipeLibrary.mockResolvedValue({
      books: 4,
      recipes: 5200,
      bare: 0,
      withState: 5200,
      withStateAndCategory: 5000,
    });
    render(<LibraryPage />);

    fireEvent.click(await screen.findByRole("button", { name: /load the books/i }));

    expect(await screen.findByText(/loaded 5,200 recipes from 4 books\./i)).toBeInTheDocument();
    expect(loadRecipeLibrary).toHaveBeenCalledWith("token-abc");
  });

  it("takes a recipe down and stops showing it, without waiting for a re-read", async () => {
    listLibraryRecipes.mockResolvedValue([
      recipe({ id: "mr-1", displayName: "Bisi Bele Bath" }),
      recipe({ id: "mr-2", displayName: "Undhiyu" }),
    ]);
    deleteLibraryRecipe.mockResolvedValue(undefined);
    render(<LibraryPage />);

    fireEvent.click(
      await screen.findByRole("button", { name: /take bisi bele bath out of the library/i })
    );

    await waitFor(() =>
      expect(screen.queryByRole("link", { name: /bisi bele bath/i })).not.toBeInTheDocument()
    );
    expect(deleteLibraryRecipe).toHaveBeenCalledWith("mr-1", "token-abc");
    expect(screen.getByRole("link", { name: /undhiyu/i })).toBeInTheDocument();
  });

  it("shows the error contract, code and all, when the library cannot be read", async () => {
    listLibraryRecipes.mockRejectedValue(
      new ApiError({
        code: "KMS-500002",
        message: "We couldn’t read the library.",
        action: "Try again in a moment.",
        fieldErrors: [],
      })
    );
    render(<LibraryPage />);

    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.getByText("KMS-500002")).toBeInTheDocument();
  });

  it("reports a failed take-down rather than pretending the row is gone", async () => {
    listLibraryRecipes.mockResolvedValue([recipe({ id: "mr-1", displayName: "Bisi Bele Bath" })]);
    deleteLibraryRecipe.mockRejectedValue(
      new ApiError({
        code: "KMS-500003",
        message: "We couldn’t take that recipe down.",
        action: "Try again in a moment.",
        fieldErrors: [],
      })
    );
    render(<LibraryPage />);

    fireEvent.click(
      await screen.findByRole("button", { name: /take bisi bele bath out of the library/i })
    );

    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /bisi bele bath/i })).toBeInTheDocument();
  });
});

describe("the recipe library, for everyone else", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    asOperator();
    listLibraryRecipes.mockResolvedValue([]);
    listLibraryStates.mockResolvedValue([]);
  });

  it("refuses a temple administrator, and does not leak the screen's shape", () => {
    authRef.current.appUser = {
      userId: "u2",
      role: "TEMPLE_ADMIN",
      fullName: "Temple Admin",
      tenantId: "t1",
      tenantName: "ISKCON Mysore",
      temples: [],
    };
    render(<LibraryPage />);

    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /recipe library/i })).not.toBeInTheDocument();
    // Nothing is fetched for somebody who is not allowed to see it.
    expect(listLibraryRecipes).not.toHaveBeenCalled();
  });

  it("sends a signed-out visitor to the front door", () => {
    authRef.current.status = "signed-out";
    authRef.current.appUser = null;
    render(<LibraryPage />);

    expect(replaceMock).toHaveBeenCalledWith("/sign-in");
    expect(screen.queryByRole("heading", { name: /recipe library/i })).not.toBeInTheDocument();
  });

  it("sends somebody with no temple to the temple picker", () => {
    authRef.current.status = "no-account";
    authRef.current.appUser = null;
    render(<LibraryPage />);

    expect(replaceMock).toHaveBeenCalledWith("/choose-temple");
  });
});
