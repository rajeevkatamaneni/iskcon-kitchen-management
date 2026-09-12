import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

// The sign-in page uses the app router; give it a no-op one.
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));

// Only the phone call is replaced; everything else in both modules is the real thing, so the
// "not configured" test below still sees this environment exactly as it is.
const { signInWithPhoneNumber } = vi.hoisted(() => ({ signInWithPhoneNumber: vi.fn() }));
vi.mock("firebase/auth", async (importOriginal) => ({
  ...(await importOriginal<typeof import("firebase/auth")>()),
  signInWithPhoneNumber,
  RecaptchaVerifier: class {
    constructor(..._args: unknown[]) {}
  },
}));
vi.mock("@/lib/firebase", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/firebase")>()),
  getFirebaseAuth: () => ({}),
}));

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

  /**
   * Firebase requires E.164 and has no second chance to clean what it is given, so a number typed the
   * way KMS-400003 writes one — "+91 98765 43210" — has to reach it bare (T-157).
   */
  it("hands Firebase the bare number, however it was spaced", async () => {
    signInWithPhoneNumber.mockResolvedValue({ confirm: vi.fn() });
    render(<SignInPage />);

    fireEvent.click(screen.getByRole("tab", { name: /phone/i }));
    const box = screen.getByLabelText(/phone number/i);
    fireEvent.change(box, { target: { value: "+91 98765-43210" } });
    // Submitted directly rather than by the button, which this environment disables because it has
    // no Firebase configuration — the test above. What is under test is the number handed over.
    fireEvent.submit(box.closest("form")!);

    await waitFor(() =>
      expect(signInWithPhoneNumber).toHaveBeenCalledWith(
        expect.anything(),
        "+919876543210",
        expect.anything()
      )
    );
  });
});
