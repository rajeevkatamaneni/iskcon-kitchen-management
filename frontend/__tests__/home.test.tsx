import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

const { replaceMock, authRef } = vi.hoisted(() => ({
  replaceMock: vi.fn(),
  authRef: {
    current: { status: "loading", appUser: null, signOut: vi.fn() } as {
      status: string;
      appUser: { role: string } | null;
      signOut: () => void;
    },
  },
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: replaceMock }) }));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));

import Home from "@/app/page";

describe("landing router", () => {
  beforeEach(() => replaceMock.mockClear());

  it("sends a signed-out visitor to sign in", () => {
    authRef.current = { status: "signed-out", appUser: null, signOut: vi.fn() };
    render(<Home />);
    expect(replaceMock).toHaveBeenCalledWith("/sign-in");
  });

  it("sends a signed-in user to the home for their role", () => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN" }, signOut: vi.fn() };
    render(<Home />);
    expect(replaceMock).toHaveBeenCalledWith("/profile");
  });

  it("explains, rather than dead-ends, a Firebase identity with no temple account", () => {
    authRef.current = { status: "no-account", appUser: null, signOut: vi.fn() };
    render(<Home />);
    expect(screen.getByText(/isn.t linked to a temple/i)).toBeInTheDocument();
    expect(replaceMock).not.toHaveBeenCalled();
  });
});
