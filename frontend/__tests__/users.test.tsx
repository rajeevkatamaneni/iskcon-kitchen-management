import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ApiError, UserSummary } from "@/lib/api";

const { authRef, queryRef, reloadMock, addUserMock, changeRoleMock, setStatusMock } = vi.hoisted(
  () => ({
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
    addUserMock: vi.fn(),
    changeRoleMock: vi.fn(),
    setStatusMock: vi.fn(),
  })
);

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
      addUser: addUserMock,
      changeUserRole: changeRoleMock,
      setUserStatus: setStatusMock,
    },
  };
});

import UsersPage from "@/app/users/page";

function user(overrides: Partial<UserSummary>): UserSummary {
  return {
    id: "u1",
    fullName: "Radha Devi",
    email: "radha@example.com",
    phone: "+919876543210",
    role: "VOLUNTEER",
    status: "ACTIVE",
    createdAt: "2026-08-01T00:00:00Z",
    ...overrides,
  };
}

describe("user management", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    queryRef.current = { data: [], error: null, loading: false };
    reloadMock.mockReset();
    addUserMock.mockReset().mockResolvedValue({ id: "new" });
    changeRoleMock.mockReset().mockResolvedValue(undefined);
    setStatusMock.mockReset().mockResolvedValue(undefined);
  });

  it("offers an add-person form and an inviting empty state", () => {
    render(<UsersPage />);

    expect(screen.getByRole("heading", { name: /people/i })).toBeInTheDocument();

    const form = screen.getByRole("form", { name: /add a person/i });
    expect(within(form).getByLabelText(/full name/i)).toBeInTheDocument();
    expect(within(form).getByLabelText(/email/i)).toBeInTheDocument();
    expect(within(form).getByLabelText(/phone/i)).toBeInTheDocument();
    expect(within(form).getByRole("button", { name: /add person/i })).toBeInTheDocument();

    expect(screen.getByText(/just you so far/i)).toBeInTheDocument();
  });

  it("offers the fixed roles but not platform operator", () => {
    render(<UsersPage />);
    const form = screen.getByRole("form", { name: /add a person/i });
    const role = within(form).getByLabelText(/role/i);
    expect(within(role).getByRole("option", { name: /temple admin/i })).toBeInTheDocument();
    expect(within(role).getByRole("option", { name: /kitchen staff/i })).toBeInTheDocument();
    expect(within(role).getByRole("option", { name: /volunteer/i })).toBeInTheDocument();
    expect(within(role).queryByRole("option", { name: /super|platform/i })).not.toBeInTheDocument();
  });

  it("lists people from live data", () => {
    queryRef.current = {
      data: [user({ id: "u1", fullName: "Radha Devi" }), user({ id: "u2", fullName: "Gopal Das" })],
      error: null,
      loading: false,
    };
    render(<UsersPage />);

    expect(screen.getByText("Radha Devi")).toBeInTheDocument();
    expect(screen.getByText("Gopal Das")).toBeInTheDocument();
    expect(screen.queryByText(/just you so far/i)).not.toBeInTheDocument();
  });

  it("adds a person and refreshes the list", async () => {
    render(<UsersPage />);
    const form = screen.getByRole("form", { name: /add a person/i });

    fireEvent.change(within(form).getByLabelText(/full name/i), { target: { value: "Gopal Das" } });
    fireEvent.change(within(form).getByLabelText(/email/i), { target: { value: "gopal@example.com" } });
    fireEvent.change(within(form).getByLabelText(/phone/i), { target: { value: "+919812345678" } });
    fireEvent.click(within(form).getByRole("button", { name: /add person/i }));

    await waitFor(() =>
      expect(addUserMock).toHaveBeenCalledWith(
        expect.objectContaining({ fullName: "Gopal Das", email: "gopal@example.com", role: "TEMPLE_ADMIN" }),
        "test-token"
      )
    );
    expect(reloadMock).toHaveBeenCalled();
  });

  it("changes another person's role", async () => {
    queryRef.current = { data: [user({ id: "u2", fullName: "Gopal Das", role: "VOLUNTEER" })], error: null, loading: false };
    render(<UsersPage />);

    fireEvent.change(screen.getByLabelText(/role for gopal das/i), {
      target: { value: "KITCHEN_STAFF" },
    });

    await waitFor(() => expect(changeRoleMock).toHaveBeenCalledWith("u2", "KITCHEN_STAFF", "test-token"));
    expect(reloadMock).toHaveBeenCalled();
  });

  it("disables another person", async () => {
    queryRef.current = { data: [user({ id: "u2", fullName: "Gopal Das", status: "ACTIVE" })], error: null, loading: false };
    render(<UsersPage />);

    fireEvent.click(screen.getByRole("button", { name: /disable/i }));

    await waitFor(() => expect(setStatusMock).toHaveBeenCalledWith("u2", "DISABLED", "test-token"));
    expect(reloadMock).toHaveBeenCalled();
  });

  it("won't let an admin change or disable their own account", () => {
    queryRef.current = { data: [user({ id: "me", fullName: "It's Me" })], error: null, loading: false };
    render(<UsersPage />);

    // No role select and no disable button on the self row — the backend refuses it and so does this.
    expect(screen.queryByLabelText(/role for it's me/i)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /disable/i })).not.toBeInTheDocument();
    expect(screen.getByText(/^you$/i)).toBeInTheDocument();
  });

  it("refuses a role without MANAGE_USERS", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<UsersPage />);

    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /people/i })).not.toBeInTheDocument();
  });

  it("marks people as the current page in navigation", () => {
    render(<UsersPage />);
    const nav = screen.getByRole("navigation", { name: /main/i });
    expect(within(nav).getByRole("link", { current: "page" })).toHaveTextContent(/people/i);
  });
});
