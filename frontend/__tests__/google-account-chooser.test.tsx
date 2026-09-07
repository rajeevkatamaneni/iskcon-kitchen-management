import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * "Continue with Google" must always ask which Google account.
 *
 * <p>Rajeev reported this on 2026-09-07: signed in as the super-admin, signed out, pressed Continue
 * with Google meaning to sign in as kitchen staff — and Google put him straight back in as the
 * super-admin without showing him anything. Signing out clears the *Firebase* session and leaves
 * the *Google* one untouched, and Google's OAuth endpoint skips the account chooser whenever
 * exactly one Google session is live and no `prompt` parameter is given. So the second sign-in
 * silently reuses the first person.
 *
 * <p>It blocked UAT rather than merely annoying: staff added on /staff exist as `pending:` users
 * that bind to a Firebase identity on first Google sign-in (E1-S6, claim-on-match), and binding
 * them means signing in as each address in turn — the one thing the defect prevents. 31 of the 47
 * UAT stories are written for kitchen staff.
 *
 * <p>These tests therefore assert the parameter itself, at both call sites. Asserting only that
 * `signInWithPopup` was called would have passed happily throughout the defect: the popup was
 * always opened, it just never asked anything.
 */

// The provider Firebase is handed, captured. `setCustomParameters` is a spy on every instance, so
// a test can say both *that* it was set and *what* it was set to.
const { providers, signInWithPopup, temples, joinTemple } = vi.hoisted(() => {
  const providers: Array<{ setCustomParameters: ReturnType<typeof vi.fn> }> = [];
  return {
    providers,
    signInWithPopup: vi.fn(async (_auth: unknown, _provider: unknown) => ({
      user: { uid: "u1", displayName: "Gopal Das", getIdToken: async () => "token" },
    })),
    temples: vi.fn(),
    joinTemple: vi.fn(async () => ({})),
  };
});

vi.mock("firebase/auth", () => {
  class GoogleAuthProvider {
    setCustomParameters = vi.fn();
    constructor() {
      providers.push(this);
    }
  }
  return {
    GoogleAuthProvider,
    signInWithPopup,
    onAuthStateChanged: () => () => undefined,
    signOut: vi.fn(),
    createUserWithEmailAndPassword: vi.fn(),
    signInWithPhoneNumber: vi.fn(),
    RecaptchaVerifier: class {},
    updateProfile: vi.fn(),
  };
});

vi.mock("@/lib/firebase", () => ({
  firebaseConfigured: true,
  getFirebaseAuth: () => ({ currentUser: null }),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, temples, joinTemple, whoami: vi.fn() },
    setActiveTempleId: vi.fn(),
  };
});

vi.mock("@/lib/nav", () => ({ forgetSidebarScroll: vi.fn() }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { AuthProvider, useAuth } from "@/lib/auth-context";
import RegisterPage from "@/app/register/page";

/** What every one of these tests is really checking, in one place. */
function expectTheChooserWasAskedFor() {
  expect(signInWithPopup).toHaveBeenCalledTimes(1);

  // The provider actually handed to Firebase — not merely the last one built.
  const handed = signInWithPopup.mock.calls[0][1] as unknown as {
    setCustomParameters: ReturnType<typeof vi.fn>;
  };
  expect(providers).toContain(handed);
  expect(handed.setCustomParameters).toHaveBeenCalledWith({ prompt: "select_account" });

  // Not "consent": that re-asks for scopes already granted, which is a worse experience and is not
  // what was wrong. If someone ever changes this, they should have to change this line too.
  expect(handed.setCustomParameters).not.toHaveBeenCalledWith(
    expect.objectContaining({ prompt: "consent" })
  );
}

function Probe() {
  const { signInWithGoogle } = useAuth();
  return (
    <button type="button" onClick={() => void signInWithGoogle()}>
      Continue with Google
    </button>
  );
}

describe("Continue with Google, on the sign-in screen", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    providers.length = 0;
  });

  it("asks which Google account, rather than reusing the last one", async () => {
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>
    );

    fireEvent.click(screen.getByRole("button", { name: /continue with google/i }));

    await waitFor(() => expect(signInWithPopup).toHaveBeenCalled());
    expectTheChooserWasAskedFor();
  });
});

describe("registering with Google", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    providers.length = 0;
    temples.mockResolvedValue([{ id: "t1", name: "ISKCON Mysore", address: "Jayanagar" }]);
  });

  it("asks which Google account, so the browser's current one is not assumed", async () => {
    render(<RegisterPage />);

    // The temple, which is the one field the form will not proceed without.
    fireEvent.change(screen.getByLabelText(/which temple do you serve at/i), {
      target: { value: "Mysore" },
    });
    // The picker waits 350ms after the last keystroke before it searches.
    const temple = await screen.findByRole(
      "button",
      { name: /ISKCON Mysore/i },
      { timeout: 3000 }
    );
    fireEvent.click(temple);

    fireEvent.change(screen.getByLabelText(/first name/i), { target: { value: "Gopal" } });
    fireEvent.change(screen.getByLabelText(/last name/i), { target: { value: "Das" } });
    fireEvent.change(screen.getByLabelText(/^email$/i), {
      target: { value: "ikms.kitchen-staff.1@trading4good.org" },
    });
    fireEvent.change(screen.getByLabelText(/^phone$/i), { target: { value: "+919876543210" } });

    fireEvent.click(screen.getByRole("tab", { name: /^google$/i }));
    fireEvent.click(screen.getByRole("button", { name: /create my account/i }));

    await waitFor(() => expect(signInWithPopup).toHaveBeenCalled());
    expectTheChooserWasAskedFor();
  });
});
