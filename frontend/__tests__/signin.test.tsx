import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

// The sign-in page uses the app router; give it a no-op one.
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));

import SignInPage from "@/app/sign-in/page";

describe("sign-in", () => {
  it("offers all three sign-in methods", () => {
    render(<SignInPage />);

    expect(screen.getByRole("button", { name: /continue with google/i })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: /email/i })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: /phone/i })).toBeInTheDocument();
  });

  it("says plainly when the environment has no Firebase configured", () => {
    // In tests there is no NEXT_PUBLIC_FIREBASE_* config, so the page must explain rather than
    // offer sign-in that silently cannot work.
    render(<SignInPage />);
    expect(screen.getByText(/isn.t configured on this environment/i)).toBeInTheDocument();
  });
});
