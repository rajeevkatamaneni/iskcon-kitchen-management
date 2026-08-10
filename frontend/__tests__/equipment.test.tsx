import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ApiError, Equipment } from "@/lib/api";

const { authRef, queryRef, reloadMock, createMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  queryRef: { current: { data: [] as Equipment[] | null, error: null as ApiError | null, loading: false } },
  reloadMock: vi.fn(),
  createMock: vi.fn(),
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
  return { ...actual, api: { ...actual.api, createEquipment: createMock } };
});

import EquipmentPage from "@/app/equipment/page";

function equipment(o: Partial<Equipment>): Equipment {
  return {
    id: "e1",
    name: "Wet Grinder",
    category: "MACHINE",
    storageLocation: "Main kitchen",
    condition: "NEEDS_REPAIR",
    acquisitionDate: null,
    source: "PURCHASED",
    notes: null,
    createdAt: "2026-08-01T00:00:00Z",
    ...o,
  };
}

describe("equipment register", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    queryRef.current = { data: [equipment({})], error: null, loading: false };
    reloadMock.mockReset();
    createMock.mockReset().mockResolvedValue({ id: "new" });
  });

  it("lists equipment with its condition", () => {
    render(<EquipmentPage />);
    expect(screen.getByRole("heading", { name: /equipment/i })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "Wet Grinder" })).toBeInTheDocument();
    expect(screen.getByText("Needs repair")).toBeInTheDocument();
  });

  it("registers a piece of equipment", async () => {
    render(<EquipmentPage />);
    fireEvent.click(screen.getByRole("button", { name: /register equipment/i }));
    const form = screen.getByRole("form", { name: /register equipment/i });
    fireEvent.change(within(form).getByLabelText(/name/i), { target: { value: "Steam Boiler" } });
    fireEvent.click(within(form).getByRole("button", { name: /^register$/i }));

    await waitFor(() =>
      expect(createMock).toHaveBeenCalledWith(
        expect.objectContaining({ name: "Steam Boiler", category: "MACHINE" }),
        "test-token"
      )
    );
    expect(reloadMock).toHaveBeenCalled();
  });

  it("refuses a role without inventory access", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } };
    render(<EquipmentPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
