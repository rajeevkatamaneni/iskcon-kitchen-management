import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ApiError, IngredientView } from "@/lib/api";

const { pushMock, replaceMock, paramsRef } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  replaceMock: vi.fn(),
  paramsRef: { current: new URLSearchParams() },
}));

const { authRef, queryRef, reloadMock, createMock, updateMock, flagMock, ekadashiFlagMock, deleteMock } = vi.hoisted(() => ({
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
  flagMock: vi.fn(),
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
      setIngredientSattvicFlag: flagMock,
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
    sattvicProhibited: false,
    ekadashiProhibited: false,
    aliases: [],
    createdAt: "2026-08-01T00:00:00Z",
    ...o,
  };
}

/**
 * The row now carries two flag columns that look identical — Sattvic and Ekadashi both read
 * "Allowed" or "Prohibited" — so a query by button name alone matches whichever comes first and
 * would pass just as happily against the wrong rule. Every assertion below goes through this,
 * which finds the column by its own header rather than by a fixed index, so inserting a column
 * later moves the tests with it instead of silently pointing them at the neighbour.
 */
function flagCell(column: "Sattvic" | "Ekadashi") {
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
    flagMock.mockReset().mockResolvedValue(undefined);
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

  it("lets an admin toggle the sattvic flag", async () => {
    render(<IngredientsPage />);
    fireEvent.click(flagCell("Sattvic").getByRole("button", { name: /allowed/i }));
    await waitFor(() => expect(flagMock).toHaveBeenCalledWith("i1", true, "test-token"));
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
    // The two flags are separate rules and one must never be written through the other's endpoint.
    expect(flagMock).not.toHaveBeenCalled();
  });

  it("lets an admin un-mark one, so a mistake is recoverable", async () => {
    queryRef.current = { data: [ingredient({ ekadashiProhibited: true })], error: null, loading: false };
    render(<IngredientsPage />);
    fireEvent.click(flagCell("Ekadashi").getByRole("button", { name: /prohibited/i }));
    await waitFor(() => expect(ekadashiFlagMock).toHaveBeenCalledWith("i1", false, "test-token"));
  });

  // Somebody needs to be able to see which ingredients a fasting day rules out without touching
  // anything — the flag is read far more often than it is set.
  it("shows the state of both flags independently, changing neither", () => {
    queryRef.current = {
      data: [ingredient({ sattvicProhibited: false, ekadashiProhibited: true })],
      error: null,
      loading: false,
    };
    render(<IngredientsPage />);
    expect(flagCell("Ekadashi").getByText("Prohibited")).toBeInTheDocument();
    expect(flagCell("Sattvic").getByText("Allowed")).toBeInTheDocument();
    expect(ekadashiFlagMock).not.toHaveBeenCalled();
    expect(flagMock).not.toHaveBeenCalled();
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

  it("hides the sattvic toggle from kitchen staff", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<IngredientsPage />);
    expect(screen.queryByRole("button", { name: /allowed|prohibited/i })).not.toBeInTheDocument();
  });

  it("refuses a role without recipe access", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } };
    render(<IngredientsPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
