import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

const { authRef, createMock, pushMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  createMock: vi.fn(),
  pushMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: pushMock, replace: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ data: null, error: null, loading: false, reload: vi.fn() }),
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, createIngredient: createMock } };
});

import NewIngredientPage from "@/app/ingredients/new/page";

describe("adding an ingredient", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    createMock.mockReset().mockResolvedValue({ id: "ing-new" });
    pushMock.mockReset();
  });

  it("commits from the header and returns to the list with the confirmation", async () => {
    render(<NewIngredientPage />);
    expect(screen.getByRole("heading", { name: "Add an ingredient" })).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/^name$/i), { target: { value: "Ghee" } });
    fireEvent.change(screen.getByLabelText(/^category$/i), { target: { value: "Oils" } });
    fireEvent.change(screen.getByLabelText(/^aliases/i), { target: { value: "Ghrita, Clarified butter" } });

    // The commit button is in the sticky header, outside the form, and reaches it by name.
    fireEvent.click(screen.getByRole("button", { name: /add ingredient/i }));

    await waitFor(() =>
      expect(createMock).toHaveBeenCalledWith(
        expect.objectContaining({
          name: "Ghee",
          category: "Oils",
          unit: "KG",
          aliases: ["Ghrita", "Clarified butter"],
        }),
        "test-token"
      )
    );
    // Rule 8: the confirmation waits on the list, not here.
    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/ingredients?added=Ghee"));
  });

  it("offers Cancel back to the list, and no way out that is not Cancel", () => {
    render(<NewIngredientPage />);
    expect(screen.getByRole("link", { name: /^cancel$/i })).toHaveAttribute("href", "/ingredients");
    expect(screen.queryByRole("button", { name: /close/i })).not.toBeInTheDocument();
  });

  it("offers the sattvic flag to an administrator only", () => {
    render(<NewIngredientPage />);
    expect(screen.getByLabelText(/sattvic-prohibited/i)).toBeInTheDocument();
  });

  it("keeps the sattvic flag from kitchen staff", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<NewIngredientPage />);
    expect(screen.queryByLabelText(/sattvic-prohibited/i)).not.toBeInTheDocument();
    expect(screen.getByRole("form", { name: /add an ingredient/i })).toBeInTheDocument();
  });

  it("refuses a role without ingredient access", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } };
    render(<NewIngredientPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
