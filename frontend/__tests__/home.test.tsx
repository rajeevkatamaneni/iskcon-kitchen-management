import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

const { replaceMock, authRef } = vi.hoisted(() => ({
  replaceMock: vi.fn(),
  authRef: {
    current: { status: "loading", appUser: null, signOut: vi.fn() } as {
      status: string;
      appUser: { role: string; fullName?: string } | null;
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
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", fullName: "Test Person" }, signOut: vi.fn() };
    render(<Home />);
    expect(replaceMock).toHaveBeenCalledWith("/today");
  });

  it("sends a Firebase identity with no temple to the choice that gives it one", () => {
    // This used to be a dead end explaining that an administrator would have to add you. A devotee
    // arrives before anyone has heard of them, so it is now a question they can answer themselves.
    authRef.current = { status: "no-account", appUser: null, signOut: vi.fn() };
    render(<Home />);
    expect(replaceMock).toHaveBeenCalledWith("/choose-temple");
  });
});
