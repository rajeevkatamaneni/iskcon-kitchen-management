import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

// The sign-in page uses the app router; give it a no-op one.
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));

// Only the two sign-in calls are replaced; everything else in both modules is the real thing, so the
// "not configured" test below still sees this environment exactly as it is.
const { signInWithPhoneNumber, signInWithEmailAndPassword } = vi.hoisted(() => ({
  signInWithPhoneNumber: vi.fn(),
  signInWithEmailAndPassword: vi.fn(),
}));
vi.mock("firebase/auth", async (importOriginal) => ({
  ...(await importOriginal<typeof import("firebase/auth")>()),
  signInWithPhoneNumber,
  signInWithEmailAndPassword,
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

/*
 * T-166, slice F of the blank-required-fields wave: the three sign-in forms under Form.
 *
 * T-166 found that `Field`'s `required` printed "(required)" beside the label and never reached the
 * input, so a blank submit here was refused by nothing. T-174 passed it through, so the email,
 * password, phone and code boxes are required on the element and Form names a blank one.
 *
 * Form also checks the email box's type. The two email and phone submit buttons are
 * disabled in this environment, which has no Firebase configuration, so those forms are submitted
 * directly; the code form's button is disabled only while busy, so it is clicked.
 */
describe("sign-in forms under Form (T-166)", () => {
  it("names a malformed email address in words, and never asks Firebase", async () => {
    signInWithEmailAndPassword.mockReset();
    render(<SignInPage />);

    const box = screen.getByLabelText(/email address/i);
    fireEvent.change(box, { target: { value: "radha.example.com" } });
    fireEvent.change(screen.getByLabelText(/password/i), { target: { value: "hare-krishna" } });
    fireEvent.submit(box.closest("form")!);

    expectSaidBeside(box, "Enter an email address like name@example.com");
    await settle();
    expect(signInWithEmailAndPassword).not.toHaveBeenCalled();
  });

  it("names both blank boxes in words, and never asks Firebase (T-174)", async () => {
    signInWithEmailAndPassword.mockReset();
    render(<SignInPage />);

    const email = screen.getByLabelText(/email address/i);
    const password = screen.getByLabelText(/password/i);
    expect(email).toBeRequired();
    expect(password).toBeRequired();
    fireEvent.submit(email.closest("form")!);

    expectSaidBeside(email, "Email address is required");
    expectSaidBeside(password, "Password is required");
    await waitFor(() => expect(email).toHaveFocus());
    await settle();
    expect(signInWithEmailAndPassword).not.toHaveBeenCalled();
  });

  it("hands the typed code to Firebase from the code form's own button, and the code box is required", async () => {
    const confirm = vi.fn().mockResolvedValue(undefined);
    signInWithPhoneNumber.mockResolvedValue({ confirm });
    render(<SignInPage />);

    fireEvent.click(screen.getByRole("tab", { name: /phone/i }));
    const phone = screen.getByLabelText(/phone number/i);
    fireEvent.change(phone, { target: { value: "+919876543210" } });
    fireEvent.submit(phone.closest("form")!);

    const code = await screen.findByLabelText(/^code/i);
    expect(code).toBeRequired();
    fireEvent.change(code, { target: { value: "123456" } });
    fireEvent.click(screen.getByRole("button", { name: /^sign in$/i }));

    await waitFor(() => expect(confirm).toHaveBeenCalledWith("123456"));
  });
});

/**
 * The sentence Form puts beside a refused box. Checked three ways so that "beside" means something:
 * the box is marked invalid, it is described by that very sentence, and the sentence's slot sits
 * straight after the box, or after the label wrapping it.
 */
function expectSaidBeside(box: HTMLElement, sentence: string | RegExp) {
  const said = screen.getByText(sentence);
  expect(box).toHaveAttribute("aria-invalid", "true");
  expect(box.getAttribute("aria-describedby")?.split(" ")).toContain(said.id);
  expect((box.closest("label") ?? box).nextElementSibling).toBe(said.parentElement);
}

/** Lets a handler that awaits a token reach its API call, so "not called" is not merely "not yet". */
function settle() {
  return new Promise((resolve) => setTimeout(resolve, 0));
}
