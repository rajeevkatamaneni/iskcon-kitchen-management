import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ApiError, UserSummary } from "@/lib/api";

const { authRef, queryRef, reloadMock, setStatusMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  queryRef: {
    current: { data: [] as UserSummary[] | null, error: null as ApiError | null, loading: false },
  },
  reloadMock: vi.fn(),
  setStatusMock: vi.fn(),
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
  return { ...actual, api: { ...actual.api, setUserStatus: setStatusMock } };
});

import DevoteesPage from "@/app/users/page";

function devotee(o: Partial<UserSummary> = {}): UserSummary {
  return {
    id: "u1",
    fullName: "Radha Devi",
    email: "radha@example.com",
    phone: "+919876500001",
    role: "VOLUNTEER",
    status: "ACTIVE",
    createdAt: "2026-07-04T09:00:00Z",
    ...o,
  };
}

describe("the devotee register", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    queryRef.current = { data: [devotee()], error: null, loading: false };
    reloadMock.mockReset();
    setStatusMock.mockReset().mockResolvedValue(undefined);
  });

  it("lists devotees with how to reach them and when they registered", () => {
    render(<DevoteesPage />);
    expect(screen.getByRole("heading", { name: "Devotees" })).toBeInTheDocument();
    expect(screen.getByText("Radha Devi")).toBeInTheDocument();
    expect(screen.getByText("radha@example.com")).toBeInTheDocument();
    expect(screen.getByText("+919876500001")).toBeInTheDocument();
  });

  it("offers no way to create a devotee — registration is theirs to do", () => {
    render(<DevoteesPage />);
    expect(screen.queryByRole("form", { name: /add a person/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /add person/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/add someone/i)).not.toBeInTheDocument();
  });

  it("offers no role control — a devotee holds one role", () => {
    render(<DevoteesPage />);
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
  });

  it("disables a devotee, and offers to enable them again", async () => {
    render(<DevoteesPage />);
    fireEvent.click(screen.getByRole("button", { name: "Disable" }));
    await waitFor(() => expect(setStatusMock).toHaveBeenCalledWith("u1", "DISABLED", "test-token"));
    expect(reloadMock).toHaveBeenCalled();

    queryRef.current = { data: [devotee({ status: "DISABLED" })], error: null, loading: false };
    render(<DevoteesPage />);
    expect(screen.getAllByRole("button", { name: "Enable" }).length).toBeGreaterThan(0);
  });

  it("counts the active and the disabled apart", () => {
    queryRef.current = {
      data: [devotee(), devotee({ id: "u2", fullName: "Gopal Das", status: "DISABLED" })],
      error: null,
      loading: false,
    };
    render(<DevoteesPage />);
    expect(screen.getByText(/1 active/)).toBeInTheDocument();
    expect(screen.getByText(/1 disabled/)).toBeInTheDocument();
  });

  it("searches by name, email or phone", () => {
    queryRef.current = {
      data: [
        devotee(),
        devotee({ id: "u2", fullName: "Gopal Das", email: "gopal@example.com", phone: "+919000000002" }),
      ],
      error: null,
      loading: false,
    };
    render(<DevoteesPage />);
    const box = screen.getByRole("searchbox");

    fireEvent.change(box, { target: { value: "gopal" } });
    expect(screen.getByText("Gopal Das")).toBeInTheDocument();
    expect(screen.queryByText("Radha Devi")).not.toBeInTheDocument();

    fireEvent.change(box, { target: { value: "9876500001" } });
    expect(screen.getByText("Radha Devi")).toBeInTheDocument();

    fireEvent.change(box, { target: { value: "nobody" } });
    expect(screen.getByText(/nobody matches/i)).toBeInTheDocument();
  });

  it("says how devotees arrive when there are none", () => {
    queryRef.current = { data: [], error: null, loading: false };
    render(<DevoteesPage />);
    expect(screen.getByText(/no devotees yet/i)).toBeInTheDocument();
    expect(screen.getByText(/register themselves/i)).toBeInTheDocument();
  });

  it("refuses kitchen staff", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<DevoteesPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
