import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ApiError } from "@/lib/api";
import type { RecipeDetail, TranslatedRecipe } from "@/lib/api";

const { authRef, recipeRef, translateMock, pdfMock, deleteMock, archiveMock, restoreMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", fullName: "Test Person" } } as {
      status: string;
      // `userId` as well as `fullName`: the test at "what a recipe makes" sets one and not the
      // other, and without it here the whole typecheck fails on a test that passes at run time.
      appUser: { role: string; fullName?: string; userId?: string } | null;
    },
  },
  recipeRef: { current: { data: null as RecipeDetail | null, error: null, loading: false } },
  translateMock: vi.fn(),
  pdfMock: vi.fn(),
  deleteMock: vi.fn(),
  archiveMock: vi.fn(),
  restoreMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn() }),
  useParams: () => ({ id: "r1" }),
  // The back link reads the search out of the address so it can return to it.
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({ useAuthedQuery: () => recipeRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      translateRecipe: translateMock,
      requestRecipePdf: pdfMock,
      deleteRecipe: deleteMock,
      archiveRecipe: archiveMock,
      restoreRecipe: restoreMock,
    },
  };
});

import RecipeDetailPage from "@/app/recipes/[id]/page";

function detail(overrides: Partial<RecipeDetail> = {}): RecipeDetail {
  return {
    id: "r1",
    name: "Khichdi",
    categoryId: "c1",
    categoryName: "Rice",
    fastingCompatible: true,
    baseYieldQty: 100,
    baseYieldUnit: "KG",
    method: "Wash the rice.\nCook until soft.",
    notes: "The default lunch.",
    regionTag: "Karnataka",
    yieldNote: null,
    perHeadQty: null,
    perHeadUnit: null,
    subtitle: null,
    badge: null,
    indicativeCost: null,
    why: null,
    cateringNote: null,
    subRegion: null,
    noteStart: null,
    noteVessel: null,
    noteSeason: null,
    tags: [],
    serveWith: [],
    masterRecipeId: null,
    status: "ACTIVE",
    version: 1,
    ingredients: [
      { ingredientId: "i1", ingredientName: "Rice", quantity: 2, unit: "KG" },
      { ingredientId: "i2", ingredientName: "Toor Dal", quantity: 1, unit: "KG" },
    ],
    createdAt: "2026-08-10T00:00:00Z",
    ...overrides,
  };
}

describe("recipe detail", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", fullName: "Test Person" } };
    recipeRef.current = { data: detail(), error: null, loading: false };
    translateMock.mockReset();
    // The PDF request is all this screen owns; what happens after it is document-download's job.
    pdfMock.mockReset().mockRejectedValue(new Error("not under test"));
    deleteMock.mockReset();
    archiveMock.mockReset();
    restoreMock.mockReset();
    // jsdom refuses a real navigation; the delete path ends in one, and what it navigates *to* is
    // the assertion, so the location is replaced with something writable.
    Object.defineProperty(window, "location", {
      configurable: true,
      writable: true,
      value: { href: "", reload: vi.fn() },
    });
  });

  it("asks before deleting, and says plainly that it cannot be undone", async () => {
    render(<RecipeDetailPage />);

    fireEvent.click(screen.getByRole("button", { name: /^delete$/i }));

    const confirm = screen.getByRole("alertdialog", { name: /delete this recipe/i });
    expect(confirm).toHaveTextContent(/Delete Khichdi\?/);
    expect(confirm).toHaveTextContent(/can’t undo this/i);
    // True to the server: a recipe on any meal is refused and Archive is offered, never archived on its own.
    expect(confirm).toHaveTextContent(/If it is on any meal, planned or cooked, you’ll be offered Archive instead\./);
    expect(deleteMock).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: /delete recipe/i }));
    await waitFor(() => expect(deleteMock).toHaveBeenCalledWith("r1", "test-token"));
    // Nothing to go back to once it is gone.
    await waitFor(() => expect(window.location.href).toBe("/recipes"));
  });

  it("offers archiving when the recipe is on a meal, rather than leaving a refusal", async () => {
    // KMS-400102 is the server saying "archive it instead" — the screen has to carry that through
    // to something the person can press, or they are simply stuck.
    deleteMock.mockRejectedValue(
      new ApiError({
        code: "KMS-400102",
        message: "This recipe is on a meal, so it can't be deleted.",
        action: "Archive it instead.",
        fieldErrors: [],
      })
    );
    render(<RecipeDetailPage />);

    fireEvent.click(screen.getByRole("button", { name: /^delete$/i }));
    fireEvent.click(screen.getByRole("button", { name: /delete recipe/i }));

    const archive = await screen.findByRole("button", { name: /archive it instead/i });
    expect(screen.getByText(/is on a meal, so it can.t be deleted/i)).toBeInTheDocument();

    fireEvent.click(archive);
    await waitFor(() => expect(archiveMock).toHaveBeenCalledWith("r1", "test-token"));
  });

  it("an archived recipe says so, and offers the way back rather than a delete", async () => {
    recipeRef.current = { data: detail({ status: "ARCHIVED" }), error: null, loading: false };
    render(<RecipeDetailPage />);

    expect(screen.getByText(/this recipe is archived/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^delete$/i })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /restore/i }));
    await waitFor(() => expect(restoreMock).toHaveBeenCalledWith("r1", "test-token"));
  });

  it("shows the recipe, ingredients, method, badges, and the actions", () => {
    render(<RecipeDetailPage />);
    expect(screen.getByRole("heading", { name: "Khichdi" })).toBeInTheDocument();
    // "Rice" is both the category and an ingredient; assert the ingredient cell specifically.
    expect(screen.getByRole("cell", { name: "Rice" })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "Toor Dal" })).toBeInTheDocument();
    expect(screen.getByText("Wash the rice.")).toBeInTheDocument();
    expect(screen.getByText(/ekadashi-friendly/i)).toBeInTheDocument();
    // Scale and the Translate button are gone (Rajeev, 2026-09-19); a language picker set to
    // English and a download icon replace them.
    expect(screen.queryByRole("button", { name: /^scale$/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/scale to/i)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^translate$/i })).not.toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Language" })).toHaveValue("en");
    expect(screen.getByRole("button", { name: "Download recipe as PDF" })).toBeInTheDocument();
  });

  it("translates the recipe and shows the translated names", async () => {
    const translated: TranslatedRecipe = {
      recipeId: "r1",
      language: "hi",
      provider: "google",
      name: "खिचड़ी",
      categoryName: "चावल",
      ingredients: [
        { name: "चावल", quantity: 2, unit: "KG" },
        { name: "तूर दाल", quantity: 1, unit: "KG" },
      ],
      method: ["चावल धो लें।"],
    };
    translateMock.mockResolvedValue(translated);

    render(<RecipeDetailPage />);
    expect(translateMock).not.toHaveBeenCalled();
    // Choosing the language is the whole action: there is no button to press after it.
    fireEvent.change(screen.getByRole("combobox", { name: "Language" }), { target: { value: "hi" } });

    expect(await screen.findByText("तूर दाल")).toBeInTheDocument();
    expect(translateMock).toHaveBeenCalledWith("r1", "hi", "test-token");
    expect(screen.getByRole("heading", { name: "खिचड़ी" })).toBeInTheDocument();
    expect(screen.getByText(/Hindi · machine translation/)).toBeInTheDocument();

    // English is the recipe as written, back at once and without asking the server.
    fireEvent.change(screen.getByRole("combobox", { name: "Language" }), { target: { value: "en" } });
    expect(await screen.findByRole("heading", { name: "Khichdi" })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "Toor Dal" })).toBeInTheDocument();
    expect(screen.queryByText(/machine translation/)).not.toBeInTheDocument();
    expect(translateMock).toHaveBeenCalledTimes(1);
  });

  it("shows it is working while a translation loads, and keeps only the latest choice", async () => {
    // Hindi is still on its way when Kannada is chosen; Hindi's late answer must not win.
    let answerHindi: (v: TranslatedRecipe) => void = () => {};
    const tr = (language: string, dal: string): TranslatedRecipe => ({
      recipeId: "r1",
      language,
      provider: "google",
      name: `Khichdi (${language})`,
      categoryName: "Rice",
      ingredients: [
        { name: "Rice", quantity: 2, unit: "KG" },
        { name: dal, quantity: 1, unit: "KG" },
      ],
      method: [],
    });
    translateMock
      .mockImplementationOnce(() => new Promise<TranslatedRecipe>((resolve) => (answerHindi = resolve)))
      .mockResolvedValueOnce(tr("kn", "ತೊಗರಿ ಬೇಳೆ"));
    render(<RecipeDetailPage />);
    const picker = screen.getByRole("combobox", { name: "Language" });

    fireEvent.change(picker, { target: { value: "hi" } });
    expect(await screen.findByRole("status")).toHaveTextContent("Translating…");

    fireEvent.change(picker, { target: { value: "kn" } });
    expect(await screen.findByText("ತೊಗರಿ ಬೇಳೆ")).toBeInTheDocument();
    answerHindi(tr("hi", "तूर दाल"));
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent(""));
    expect(screen.queryByText("तूर दाल")).not.toBeInTheDocument();
    expect(picker).toHaveValue("kn");
  });

  it("goes back to English and says so when a translation fails", async () => {
    translateMock.mockRejectedValue(
      new ApiError({
        code: "KMS-503001",
        message: "Translation isn’t available right now.",
        action: "Try again in a few minutes.",
        fieldErrors: [],
      })
    );
    render(<RecipeDetailPage />);
    fireEvent.change(screen.getByRole("combobox", { name: "Language" }), { target: { value: "kn" } });

    expect(await screen.findByText("The recipe is shown in English.")).toBeInTheDocument();
    expect(screen.getByText(/Translation isn’t available right now/)).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Language" })).toHaveValue("en");
    expect(screen.getByRole("cell", { name: "Toor Dal" })).toBeInTheDocument();
  });

  it("downloads the PDF in the language on screen", async () => {
    translateMock.mockResolvedValue({
      recipeId: "r1",
      language: "hi",
      provider: "google",
      name: "खिचड़ी",
      categoryName: "चावल",
      ingredients: [
        { name: "चावल", quantity: 2, unit: "KG" },
        { name: "तूर दाल", quantity: 1, unit: "KG" },
      ],
      method: [],
    } satisfies TranslatedRecipe);
    render(<RecipeDetailPage />);

    const download = screen.getByRole("button", { name: "Download recipe as PDF" });
    expect(download).toHaveAttribute("title", "Download recipe as PDF");
    fireEvent.click(download);
    await waitFor(() => expect(pdfMock).toHaveBeenCalledWith("r1", { language: undefined }, "test-token"));

    fireEvent.change(screen.getByRole("combobox", { name: "Language" }), { target: { value: "hi" } });
    await screen.findByText("तूर दाल");
    await waitFor(() => expect(download).not.toBeDisabled());
    fireEvent.click(download);
    await waitFor(() => expect(pdfMock).toHaveBeenLastCalledWith("r1", { language: "hi" }, "test-token"));
  });

  /*
    D-18 removed the override badge that sat beside "Ekadashi-friendly" and the marker that used to
    print after a forbidden ingredient\u2019s name in the table.

    Read off the rendered screen rather than off the data, and paired with a positive assertion on
    the badge that survives: an absence query on its own passes just as well against a page that
    renders no badges at all, or none at all, which is not what is being claimed here.
  */
  it("shows the Ekadashi badge and no override badge or forbidden marker", () => {
    render(<RecipeDetailPage />);
    expect(screen.getByText(/ekadashi-friendly/i)).toBeInTheDocument();
    expect(screen.queryByText(/override/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/prohibited/i)).not.toBeInTheDocument();
    // The cell holds the ingredient name and nothing appended to it.
    expect(screen.getByRole("cell", { name: "Rice" })).toHaveTextContent(/^Rice$/);
  });

  it("refuses a role without recipe access", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", fullName: "Test Person" } };
    render(<RecipeDetailPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});

describe("what a recipe makes, and what one person eats", () => {
  it("shows both as labelled figures, with their units", async () => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    recipeRef.current = {
      data: detail({ baseYieldQty: 20, baseYieldUnit: "L", perHeadQty: 0.2, perHeadUnit: "L" }),
      error: null,
      loading: false,
    };
    render(<RecipeDetailPage />);
    // "0.2 litres a head" used to sit at the end of one grey sentence, and nobody found it there
    // (Rajeev, 2026-08-23).
    expect(await screen.findByText("Makes")).toBeInTheDocument();
    expect(screen.getByText("20 L")).toBeInTheDocument();
    expect(screen.getByText("Per person")).toBeInTheDocument();
    // 0.2 of a litre is not how anybody serves rasam — it is 200 ml (Rajeev, 2026-08-23).
    expect(screen.getByText("200 ml")).toBeInTheDocument();
    expect(screen.queryByText("0.2 L")).not.toBeInTheDocument();
  });
});

describe("a temple recipe's batch cost (T-233)", () => {
  // Rajeev, 2026-09-18: one more figure beside Makes and Per person, for everyone who can open the
  // recipe, written the way the library page writes it, and absent when no cost is saved.
  it("shows the saved cost as the library does, with the batch it buys", async () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    recipeRef.current = {
      data: detail({ baseYieldQty: 270, baseYieldUnit: "L", indicativeCost: 8000 }),
      error: null,
      loading: false,
    };
    render(<RecipeDetailPage />);
    expect(await screen.findByText("Rough cost")).toBeInTheDocument();
    expect(screen.getByText("₹8,000 per batch (270 L)")).toBeInTheDocument();
  });

  it("says nothing at all when no cost is saved", async () => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    recipeRef.current = { data: detail({ indicativeCost: null }), error: null, loading: false };
    render(<RecipeDetailPage />);
    expect(await screen.findByText("Makes")).toBeInTheDocument();
    expect(screen.queryByText("Rough cost")).not.toBeInTheDocument();
    expect(screen.queryByText(/per batch/)).not.toBeInTheDocument();
  });
});
