import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

// Signing out is an auth-context action; the tests drive it through a spy so no Firebase session
// is needed, and read the signed-in person from a mutable ref.
const { authRef, signOutMock } = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "KITCHEN_STAFF", fullName: "Gopal Das" } as
        | { role: string; fullName?: string }
        | null,
    },
  },
  signOutMock: vi.fn(async () => {}),
}));

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, signOut: signOutMock, signInWithGoogle: vi.fn() }),
}));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

import { Sidebar } from "@/components/Sidebar";
import SignInPage from "@/app/sign-in/page";
import { noteAutomaticSignOut } from "@/lib/session-timeout";

describe("signing out", () => {
  beforeEach(() => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "KITCHEN_STAFF", fullName: "Gopal Das" },
    };
    signOutMock.mockClear();
    window.localStorage.clear();
  });

  it("is offered at the person, on every signed-in screen", async () => {
    render(<Sidebar templeName="ISKCON Bengaluru" activeHref="/planner" />);

    fireEvent.click(screen.getByRole("button", { name: /sign out/i }));

    await waitFor(() => expect(signOutMock).toHaveBeenCalledTimes(1));
  });

  it("is offered to a platform operator too, who has no profile page", () => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "SUPER_ADMIN", fullName: "Ops Person" },
    };
    render(<Sidebar templeName="Platform" activeHref="/tenants" />);

    expect(screen.getByRole("button", { name: /sign out/i })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /ops person/i })).not.toBeInTheDocument();
  });

  it("does not leave a person guessing when the app signed them out", async () => {
    noteAutomaticSignOut(Date.now());
    render(<SignInPage />);

    expect(await screen.findByText(/we signed you out/i)).toBeInTheDocument();
    expect(screen.getByText(/60 minutes without activity/i)).toBeInTheDocument();
  });

  it("says nothing extra to someone who signed out themselves", () => {
    render(<SignInPage />);

    expect(screen.queryByText(/we signed you out/i)).not.toBeInTheDocument();
  });
});
