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

  // T-045. The flag was reachable from nowhere but the provisioning seed, so every ingredient a
  // temple added after being set up read as permitted on a fasting day, and the composer would
  // offer grain dishes on Ekadashi.
  it("carries the Ekadashi flag from the create form", async () => {
    render(<NewIngredientPage />);

    fireEvent.change(screen.getByLabelText(/^name$/i), { target: { value: "Basmati Rice" } });
    fireEvent.change(screen.getByLabelText(/^category$/i), { target: { value: "Grains" } });
    fireEvent.click(screen.getByLabelText(/ekadashi-prohibited/i));
    fireEvent.click(screen.getByRole("button", { name: /add ingredient/i }));

    await waitFor(() =>
      expect(createMock).toHaveBeenCalledWith(
        expect.objectContaining({
          name: "Basmati Rice",
          ekadashiProhibited: true,
          // The two observance rules are independent: ticking one must not set the other.
          sattvicProhibited: false,
        }),
        "test-token"
      )
    );
  });

  // The heart of the defect, in one assertion. An unticked checkbox puts no key in the FormData,
  // and the Java field is a primitive `boolean`, so an omitted key deserialises to `false` — the
  // permissive answer — without anything saying no. `objectContaining` would not catch that,
  // because a missing property and an explicit `false` read the same to it. This asserts the
  // payload literally carries the word.
  it("says so out loud when the flag is not ticked, rather than leaving the key out", async () => {
    render(<NewIngredientPage />);

    fireEvent.change(screen.getByLabelText(/^name$/i), { target: { value: "Ghee" } });
    fireEvent.change(screen.getByLabelText(/^category$/i), { target: { value: "Oils" } });
    fireEvent.click(screen.getByRole("button", { name: /add ingredient/i }));

    await waitFor(() => expect(createMock).toHaveBeenCalled());
    const [payload] = createMock.mock.calls[0];
    expect(Object.keys(payload)).toContain("ekadashiProhibited");
    expect(payload.ekadashiProhibited).toBe(false);
  });

  it("offers the sattvic flag to an administrator only", () => {
    render(<NewIngredientPage />);
    expect(screen.getByLabelText(/sattvic-prohibited/i)).toBeInTheDocument();
  });

  it("offers the Ekadashi flag to an administrator only", () => {
    render(<NewIngredientPage />);
    expect(screen.getByLabelText(/ekadashi-prohibited/i)).toBeInTheDocument();
  });

  it("keeps the Ekadashi flag from kitchen staff, who still get a usable form", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<NewIngredientPage />);
    expect(screen.queryByLabelText(/ekadashi-prohibited/i)).not.toBeInTheDocument();
    expect(screen.getByRole("form", { name: /add an ingredient/i })).toBeInTheDocument();
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
