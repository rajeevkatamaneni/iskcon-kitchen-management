import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ApiError, GlossaryEntry } from "@/lib/api";

const { authRef, queryRef, reloadMock, addMock, deleteMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", fullName: "Test Person" } } as {
      status: string;
      appUser: { role: string; fullName?: string } | null;
    },
  },
  queryRef: { current: { data: [] as GlossaryEntry[] | null, error: null as ApiError | null, loading: false } },
  reloadMock: vi.fn(),
  addMock: vi.fn(),
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
  return { ...actual, api: { ...actual.api, addGlossaryEntry: addMock, deleteGlossaryEntry: deleteMock } };
});

import GlossaryPage from "@/app/glossary/page";

describe("translation glossary", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", fullName: "Test Person" } };
    queryRef.current = {
      data: [{ id: "g1", language: "hi", sourceTerm: "Toor Dal", targetTerm: "तूर दाल" }],
      error: null,
      loading: false,
    };
    reloadMock.mockReset();
    addMock.mockReset().mockResolvedValue({ id: "g2" });
    deleteMock.mockReset().mockResolvedValue(undefined);
  });

  it("lists glossary terms", () => {
    render(<GlossaryPage />);
    expect(screen.getByRole("heading", { name: /translation glossary/i })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "Toor Dal" })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "तूर दाल" })).toBeInTheDocument();
  });

  it("adds a term and refreshes", async () => {
    render(<GlossaryPage />);
    const form = screen.getByRole("form", { name: /add a glossary term/i });
    fireEvent.change(within(form).getByLabelText(/english term/i), { target: { value: "Ghee" } });
    fireEvent.change(within(form).getByLabelText(/preferred translation/i), { target: { value: "घी" } });
    fireEvent.click(within(form).getByRole("button", { name: /^add$/i }));

    await waitFor(() =>
      expect(addMock).toHaveBeenCalledWith(
        expect.objectContaining({ language: "hi", sourceTerm: "Ghee", targetTerm: "घी" }),
        "test-token"
      )
    );
    expect(reloadMock).toHaveBeenCalled();
  });

  it("deletes a term", async () => {
    render(<GlossaryPage />);
    fireEvent.click(screen.getByRole("button", { name: /delete/i }));
    await waitFor(() => expect(deleteMock).toHaveBeenCalledWith("g1", "test-token"));
  });

  it("refuses a role without recipe access", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", fullName: "Test Person" } };
    render(<GlossaryPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
