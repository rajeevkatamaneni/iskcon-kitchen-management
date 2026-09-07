import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

/**
 * Registering as a devotee (E1-S17) — the only screen in the product where somebody with no session
 * at all creates one, and the whole of it is reached signed out.
 *
 * <p>The screen had no test file. `google-account-chooser.test.tsx` renders it, but for one
 * assertion about one OAuth parameter on one of the three methods; the form itself, the order it was
 * drawn in, and what happens after "Create my account" were uncovered.
 *
 * <p>The governing property is the one in the page's own comment: **nothing is created until the
 * whole form is answered.** The first version asked for a credential first and the temple
 * afterwards, which made a Firebase account before the person had finished and left half-made
 * accounts behind when they stopped. So these tests assert both halves — that Firebase is not
 * touched while the form is incomplete, and that when it is answered the join follows the credential
 * immediately.
 *
 * <p>They are characterisation tests over what ships today. One of them documents a dead end rather
 * than endorsing it; it is marked, and written up in the proof.
 */

const {
  createUserWithEmailAndPassword,
  signInWithPopup,
  signInWithPhoneNumber,
  updateProfile,
  firebaseSignOut,
  temples,
  joinTemple,
  setActiveTempleId,
  replaceMock,
  refreshMock,
} = vi.hoisted(() => ({
  createUserWithEmailAndPassword: vi.fn(),
  signInWithPopup: vi.fn(),
  signInWithPhoneNumber: vi.fn(),
  updateProfile: vi.fn(async () => {}),
  firebaseSignOut: vi.fn(async () => {}),
  temples: vi.fn(),
  joinTemple: vi.fn(),
  setActiveTempleId: vi.fn(),
  replaceMock: vi.fn(),
  refreshMock: vi.fn(async () => {}),
}));

vi.mock("firebase/auth", () => ({
  createUserWithEmailAndPassword,
  signInWithPopup,
  signInWithPhoneNumber,
  updateProfile,
  signOut: firebaseSignOut,
  RecaptchaVerifier: class {
    constructor(..._args: unknown[]) {}
  },
  GoogleAuthProvider: class {
    setCustomParameters = vi.fn();
  },
}));

vi.mock("@/lib/firebase", () => ({
  firebaseConfigured: true,
  getFirebaseAuth: () => ({ currentUser: null }),
}));

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ refresh: refreshMock }),
  googleProvider: () => ({ id: "google-provider" }),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: replaceMock, push: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, temples, joinTemple },
    setActiveTempleId,
  };
});

import RegisterPage from "@/app/register/page";
import { ApiError } from "@/lib/api";

const MYSORE = { id: "t1", name: "ISKCON Mysore", address: "Jayalakshmipuram", distanceKm: 3 };

/** A Firebase user, as the three credential paths each return one. */
function firebaseUser(overrides: Record<string, unknown> = {}) {
  return {
    uid: "fb-1",
    displayName: null,
    getIdToken: async () => "id-token",
    ...overrides,
  };
}

/** Everything above "How would you like to sign in?", which is the same for all three methods. */
async function fillTheDetails() {
  fireEvent.change(screen.getByLabelText(/which temple do you serve at/i), {
    target: { value: "Mysore" },
  });
  // The picker waits 350ms after the last keystroke before it searches.
  fireEvent.click(
    await screen.findByRole("button", { name: /ISKCON Mysore/i }, { timeout: 3000 })
  );

  fireEvent.change(screen.getByLabelText(/first name/i), { target: { value: "Gopal" } });
  fireEvent.change(screen.getByLabelText(/last name/i), { target: { value: "Das" } });
  fireEvent.change(screen.getByLabelText(/^email$/i), { target: { value: "gopal@example.org" } });
  fireEvent.change(screen.getByLabelText(/^phone$/i), { target: { value: "+919876543210" } });
}

function fillAPassword(value = "hare-krishna-108") {
  fireEvent.change(screen.getByLabelText("Create a password"), { target: { value } });
  fireEvent.change(screen.getByLabelText(/confirm password/i), { target: { value } });
}

describe("the registration form, signed out", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    temples.mockResolvedValue([MYSORE]);
    joinTemple.mockResolvedValue({});
    createUserWithEmailAndPassword.mockResolvedValue({ user: firebaseUser() });
    signInWithPopup.mockResolvedValue({ user: firebaseUser({ displayName: "Gopal Das" }) });
  });

  it("renders with no session of any kind, in the order it was drawn", () => {
    render(<RegisterPage />);

    expect(screen.getByRole("heading", { name: /^register$/i })).toBeInTheDocument();
    expect(screen.getByText(/offer seva, and give when you can/i)).toBeInTheDocument();

    // Temple first, then who you are, then how you will sign in.
    expect(screen.getByLabelText(/which temple do you serve at/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/first name/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/^phone$/i)).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: /^password$/i })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: /phone and otp/i })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: /^google$/i })).toBeInTheDocument();

    expect(screen.getByRole("link", { name: /sign in/i })).toHaveAttribute("href", "/sign-in");
  });

  it("names the one thing still missing, rather than only greying the button out", async () => {
    render(<RegisterPage />);

    expect(screen.getByText(/choose your temple to continue/i)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/which temple do you serve at/i), {
      target: { value: "Mysore" },
    });
    fireEvent.click(
      await screen.findByRole("button", { name: /ISKCON Mysore/i }, { timeout: 3000 })
    );
    expect(await screen.findByText(/^enter your name\.$/i)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/first name/i), { target: { value: "Gopal" } });
    fireEvent.change(screen.getByLabelText(/last name/i), { target: { value: "Das" } });
    expect(screen.getByText(/enter your email address/i)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/^email$/i), { target: { value: "gopal@example.org" } });
    expect(screen.getByText(/enter your phone number, with the country code/i)).toBeInTheDocument();
  });

  it("creates nothing while the form is incomplete", async () => {
    render(<RegisterPage />);

    fireEvent.click(screen.getByRole("button", { name: /create my account/i }));

    await waitFor(() => expect(screen.getByText(/choose your temple/i)).toBeInTheDocument());
    expect(createUserWithEmailAndPassword).not.toHaveBeenCalled();
    expect(signInWithPopup).not.toHaveBeenCalled();
    expect(joinTemple).not.toHaveBeenCalled();
  });

  it("holds the button until the two passwords match, and says why", async () => {
    render(<RegisterPage />);
    await fillTheDetails();

    fireEvent.change(screen.getByLabelText("Create a password"), {
      target: { value: "hare-krishna-108" },
    });
    fireEvent.change(screen.getByLabelText(/confirm password/i), { target: { value: "hare" } });

    expect(screen.getByText(/those two don’t match yet/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /create my account/i })).toBeDisabled();

    fireEvent.change(screen.getByLabelText(/confirm password/i), {
      target: { value: "hare-krishna-108" },
    });
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /create my account/i })).toBeEnabled()
    );
  });

  it("lets somebody read back the password they are inventing", async () => {
    render(<RegisterPage />);
    await fillTheDetails();

    const box = screen.getByLabelText("Create a password");
    expect(box).toHaveAttribute("type", "password");
    // A password being created, not one being recalled — or the browser offers a saved one.
    expect(box).toHaveAttribute("autocomplete", "new-password");

    fireEvent.click(screen.getByRole("button", { name: /^show$/i }));
    expect(screen.getByLabelText("Create a password")).toHaveAttribute("type", "text");
    fireEvent.click(screen.getByRole("button", { name: /^hide$/i }));
    expect(screen.getByLabelText("Create a password")).toHaveAttribute("type", "password");
  });
});

describe("finishing registration with a password", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    temples.mockResolvedValue([MYSORE]);
    joinTemple.mockResolvedValue({});
    createUserWithEmailAndPassword.mockResolvedValue({ user: firebaseUser() });
  });

  it("makes the credential, joins the temple, then signs them out to prove the password works", async () => {
    render(<RegisterPage />);
    await fillTheDetails();
    fillAPassword();

    fireEvent.click(screen.getByRole("button", { name: /create my account/i }));

    await waitFor(() =>
      expect(createUserWithEmailAndPassword).toHaveBeenCalledWith(
        expect.anything(),
        "gopal@example.org",
        "hare-krishna-108"
      )
    );
    // Firebase knows an email; the temple's list needs a name to recognise somebody by.
    await waitFor(() =>
      expect(updateProfile).toHaveBeenCalledWith(expect.anything(), { displayName: "Gopal Das" })
    );
    await waitFor(() =>
      expect(joinTemple).toHaveBeenCalledWith(
        "t1",
        {
          firstName: "Gopal",
          lastName: "Das",
          phone: "+919876543210",
          email: "gopal@example.org",
        },
        "id-token"
      )
    );
    expect(setActiveTempleId).toHaveBeenCalledWith("t1");

    // A password is the one credential that can be mistyped into existence, so the first thing it
    // ever does is let them back in.
    await waitFor(() => expect(firebaseSignOut).toHaveBeenCalled());
    expect(replaceMock).toHaveBeenCalledWith("/sign-in?registered=gopal%40example.org");
    expect(refreshMock).not.toHaveBeenCalled();
  });

  it("leaves a name Google already gave alone", async () => {
    createUserWithEmailAndPassword.mockResolvedValue({
      user: firebaseUser({ displayName: "Gopal Das" }),
    });
    render(<RegisterPage />);
    await fillTheDetails();
    fillAPassword();

    fireEvent.click(screen.getByRole("button", { name: /create my account/i }));

    await waitFor(() => expect(joinTemple).toHaveBeenCalled());
    expect(updateProfile).not.toHaveBeenCalled();
  });

  it("turns a Firebase code into a sentence, and never shows the code", async () => {
    createUserWithEmailAndPassword.mockRejectedValue({ code: "auth/email-already-in-use" });
    render(<RegisterPage />);
    await fillTheDetails();
    fillAPassword();

    fireEvent.click(screen.getByRole("button", { name: /create my account/i }));

    expect(
      await screen.findByText(/there is already an account with that email\. sign in instead\./i)
    ).toBeInTheDocument();
    expect(screen.queryByText(/auth\/email-already-in-use/)).not.toBeInTheDocument();
    expect(joinTemple).not.toHaveBeenCalled();
  });

  it("has a sentence for every Firebase failure it names, and a plain one for the rest", async () => {
    const cases: Array<[string, RegExp]> = [
      ["auth/weak-password", /choose a longer password/i],
      ["auth/invalid-email", /doesn’t look right/i],
      ["auth/too-many-requests", /too many attempts just now/i],
      ["auth/internal-error", /check what you entered and try again/i],
    ];

    for (const [code, expected] of cases) {
      vi.clearAllMocks();
      temples.mockResolvedValue([MYSORE]);
      createUserWithEmailAndPassword.mockRejectedValue({ code });

      const view = render(<RegisterPage />);
      await fillTheDetails();
      fillAPassword();
      fireEvent.click(screen.getByRole("button", { name: /create my account/i }));

      expect(await screen.findByText(expected)).toBeInTheDocument();
      view.unmount();
    }
  });
});

describe("finishing registration with Google or a phone code", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    temples.mockResolvedValue([MYSORE]);
    joinTemple.mockResolvedValue({});
    signInWithPopup.mockResolvedValue({ user: firebaseUser({ displayName: "Gopal Das" }) });
  });

  it("warns, before the button is pressed, that a Google window is coming", async () => {
    render(<RegisterPage />);
    await fillTheDetails();
    fireEvent.click(screen.getByRole("tab", { name: /^google$/i }));

    expect(screen.getByText(/you’ll be asked to choose your google account/i)).toBeInTheDocument();
    // Google needs no password from us, so the form is complete the moment the details are.
    expect(screen.getByRole("button", { name: /create my account/i })).toBeEnabled();
  });

  it("keeps a Google registration signed in and lands them inside", async () => {
    render(<RegisterPage />);
    await fillTheDetails();
    fireEvent.click(screen.getByRole("tab", { name: /^google$/i }));
    fireEvent.click(screen.getByRole("button", { name: /create my account/i }));

    await waitFor(() => expect(joinTemple).toHaveBeenCalled());
    // No second sign-in: unlike a password, nothing here could have been mistyped into existence.
    expect(firebaseSignOut).not.toHaveBeenCalled();
    await waitFor(() => expect(refreshMock).toHaveBeenCalled());
    expect(replaceMock).toHaveBeenCalledWith("/");
  });

  it("will not send a code to a number that cannot be one", async () => {
    render(<RegisterPage />);
    await fillTheDetails();
    fireEvent.click(screen.getByRole("tab", { name: /phone and otp/i }));

    fireEvent.change(screen.getByLabelText(/^phone$/i), { target: { value: "98765" } });
    expect(screen.getByRole("button", { name: /send a code to my phone/i })).toBeDisabled();

    fireEvent.change(screen.getByLabelText(/^phone$/i), { target: { value: "+919876543210" } });
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /send a code to my phone/i })).toBeEnabled()
    );
  });

  it("asks for the six digits once the code has been sent, and says where they went", async () => {
    signInWithPhoneNumber.mockResolvedValue({ confirm: vi.fn() });
    render(<RegisterPage />);
    await fillTheDetails();
    fireEvent.click(screen.getByRole("tab", { name: /phone and otp/i }));

    // The button is disabled until the details are complete, so this is the last step.
    fireEvent.click(screen.getByRole("button", { name: /send a code to my phone/i }));

    expect(
      await screen.findByLabelText(/the six-digit code we sent to \+919876543210/i)
    ).toBeInTheDocument();
    expect(signInWithPhoneNumber).toHaveBeenCalledWith(
      expect.anything(),
      "+919876543210",
      expect.anything()
    );
  });

  it("explains a code that would not send, in words", async () => {
    signInWithPhoneNumber.mockRejectedValue({ code: "auth/invalid-phone-number" });
    render(<RegisterPage />);
    await fillTheDetails();
    fireEvent.click(screen.getByRole("tab", { name: /phone and otp/i }));
    fireEvent.click(screen.getByRole("button", { name: /send a code to my phone/i }));

    expect(
      await screen.findByText(/that number doesn’t look right\. include the country code\./i)
    ).toBeInTheDocument();
  });
});

describe("when the credential is made but the join is refused", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    temples.mockResolvedValue([MYSORE]);
  });

  /**
   * CHARACTERISATION OF A DEFECT — asserted as it behaves today, deliberately not fixed here.
   *
   * <p>`createAccount` creates the Firebase credential first and calls `api.joinTemple` second. If
   * the join is refused — the server down, the temple closed to new devotees, a validation the form
   * did not anticipate — the Firebase account has already been made and nothing removes it. The
   * screen reports the join failure honestly, which is right; what it cannot do is let the person
   * try again, because the second press hits `auth/email-already-in-use` and answers with "Sign in
   * instead" — advice that leads to a sign-in with no temple behind it.
   *
   * <p>Written up in `docs/work/proof/T-022.md` for someone to raise as its own task. This test
   * exists so that whoever fixes it has to come here and change what it says.
   */
  it("leaves a Firebase account behind that the second attempt cannot get past", async () => {
    createUserWithEmailAndPassword.mockResolvedValueOnce({ user: firebaseUser() });
    joinTemple.mockRejectedValueOnce(
      new ApiError({
        code: "KMS-503001",
        message: "We couldn’t reach the temple’s records.",
        action: "Try again in a moment.",
        fieldErrors: [],
      })
    );

    render(<RegisterPage />);
    await fillTheDetails();
    fillAPassword();
    fireEvent.click(screen.getByRole("button", { name: /create my account/i }));

    // Honest about the join, and the code is there to quote.
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.getByText("KMS-503001")).toBeInTheDocument();
    expect(createUserWithEmailAndPassword).toHaveBeenCalledTimes(1);
    // The orphan is not cleaned up, and they are not signed out of it either.
    expect(firebaseSignOut).not.toHaveBeenCalled();

    // Trying again — which is exactly what the error's own "Try again in a moment." invites.
    createUserWithEmailAndPassword.mockRejectedValueOnce({ code: "auth/email-already-in-use" });
    fireEvent.click(screen.getByRole("button", { name: /create my account/i }));

    expect(
      await screen.findByText(/there is already an account with that email\. sign in instead\./i)
    ).toBeInTheDocument();
    // And they still belong to no temple: the join was never retried.
    expect(joinTemple).toHaveBeenCalledTimes(1);
  });
});
