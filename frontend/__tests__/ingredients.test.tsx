import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ApiError, IngredientView } from "@/lib/api";

const { authRef, queryRef, reloadMock, createMock, updateMock, flagMock, deleteMock } = vi.hoisted(() => ({
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
  deleteMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));
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
    aliases: [],
    createdAt: "2026-08-01T00:00:00Z",
    ...o,
  };
}

describe("ingredient management", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    queryRef.current = { data: [ingredient({})], error: null, loading: false };
    reloadMock.mockReset();
    createMock.mockReset().mockResolvedValue({ id: "new" });
    updateMock.mockReset().mockResolvedValue(undefined);
    flagMock.mockReset().mockResolvedValue(undefined);
    deleteMock.mockReset().mockResolvedValue(undefined);
  });

  it("lists ingredients and offers an add form", () => {
    render(<IngredientsPage />);
    expect(screen.getByRole("heading", { name: /ingredients/i })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "Rice" })).toBeInTheDocument();
    const form = screen.getByRole("form", { name: /add an ingredient/i });
    expect(within(form).getByLabelText(/name/i)).toBeInTheDocument();
  });

  it("adds an ingredient and refreshes", async () => {
    render(<IngredientsPage />);
    const form = screen.getByRole("form", { name: /add an ingredient/i });
    fireEvent.change(within(form).getByLabelText(/name/i), { target: { value: "Ghee" } });
    fireEvent.change(within(form).getByLabelText(/category/i), { target: { value: "Oils" } });
    fireEvent.click(within(form).getByRole("button", { name: /add ingredient/i }));

    await waitFor(() =>
      expect(createMock).toHaveBeenCalledWith(
        expect.objectContaining({ name: "Ghee", category: "Oils" }),
        "test-token"
      )
    );
    expect(reloadMock).toHaveBeenCalled();
  });

  it("lets an admin toggle the sattvic flag", async () => {
    render(<IngredientsPage />);
    fireEvent.click(screen.getByRole("button", { name: /allowed/i }));
    await waitFor(() => expect(flagMock).toHaveBeenCalledWith("i1", true, "test-token"));
  });

  it("deletes an ingredient", async () => {
    render(<IngredientsPage />);
    fireEvent.click(screen.getByRole("button", { name: /delete/i }));
    await waitFor(() => expect(deleteMock).toHaveBeenCalledWith("i1", "test-token"));
  });

  it("hides the sattvic controls from kitchen staff", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<IngredientsPage />);
    // No prohibited-flag toggle button, and no checkbox in the add form.
    expect(screen.queryByRole("button", { name: /allowed|prohibited/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/sattvic-prohibited/i)).not.toBeInTheDocument();
  });

  it("refuses a role without recipe access", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } };
    render(<IngredientsPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
