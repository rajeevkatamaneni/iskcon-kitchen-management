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
 * <p>They are characterisation tests over what ships today. The last describe in the file used to
 * document a dead end rather than endorsing it — a refused join left a Firebase account behind that
 * the second press could not get past, and the screen answered it with "Sign in instead", which was
 * wrong for that person. T-037 fixed it, so those tests now assert the resumed join instead. They
 * were rewritten in place rather than replaced: the block is the only record that this was ever
 * wrong, and it is worth keeping.
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
  rememberChosenTemple,
  authState,
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
  rememberChosenTemple: vi.fn(),
  // Firebase's own session, which outlives a page reload — and so is where a credential whose join
  // was refused is still to be found. Mutable, because two tests below turn it into evidence.
  authState: { currentUser: null as Record<string, unknown> | null },
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
  getFirebaseAuth: () => authState,
}));

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ refresh: refreshMock }),
  googleProvider: () => ({ id: "google-provider" }),
  // Where the screen leaves the temple it has already been told, when it sends somebody off to
  // sign in (T-118). Stubbed here because this file is about the form; that the note is written,
  // and read by the picker at the other end, is `temple-carried-from-register.test.tsx`.
  rememberChosenTemple,
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
    authState.currentUser = null;
  });

  /** What the server says when it cannot take the join — the failure this whole block is about. */
  const joinRefused = () =>
    new ApiError({
      code: "KMS-503001",
      message: "We couldn’t reach the temple’s records.",
      action: "Try again in a moment.",
      fieldErrors: [],
    });

  /**
   * THIS WAS A CHARACTERISATION OF A DEFECT, and is now the test that stops it coming back (T-037).
   *
   * <p>What it used to assert, in its own words: "leaves a Firebase account behind that the second
   * attempt cannot get past". `createAccount` made the Firebase credential first and called
   * `api.joinTemple` second, and a refused join left the account behind with nothing to remove it
   * and nothing to remember it. The second press then hit `auth/email-already-in-use` and the
   * screen said "There is already an account with that email. Sign in instead." — advice that was
   * wrong for exactly this person, who had an identity and a membership nowhere, and a dead end,
   * because the join was never retried.
   *
   * <p>The fix Rajeev chose was to remember the credential across attempts and resume at the join.
   * Nothing is deleted to compensate: their identity is fine, and only the membership is missing.
   * So the assertions below are the same story with the ending changed — same refusal, same honest
   * error, and then a second press that asks Firebase for nothing and finishes the join.
   */
  it("remembers the credential it made, and the next press resumes at the join", async () => {
    createUserWithEmailAndPassword.mockResolvedValueOnce({
      user: firebaseUser({ email: "gopal@example.org" }),
    });
    joinTemple.mockRejectedValueOnce(joinRefused());

    render(<RegisterPage />);
    await fillTheDetails();
    fillAPassword();
    fireEvent.click(screen.getByRole("button", { name: /create my account/i }));

    // Still honest about the join, and the code is still there to quote.
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.getByText("KMS-503001")).toBeInTheDocument();
    expect(createUserWithEmailAndPassword).toHaveBeenCalledTimes(1);
    // Nothing is deleted to compensate, and they are not signed out of what was made either —
    // which is what leaves the credential findable at all.
    expect(firebaseSignOut).not.toHaveBeenCalled();

    // Trying again — which is exactly what the error's own "Try again in a moment." invites.
    joinTemple.mockResolvedValueOnce({});
    fireEvent.click(screen.getByRole("button", { name: /create my account/i }));

    await waitFor(() => expect(joinTemple).toHaveBeenCalledTimes(2));
    // No second account is asked for, so there is no `auth/email-already-in-use` to mistranslate.
    expect(createUserWithEmailAndPassword).toHaveBeenCalledTimes(1);
    expect(screen.queryByText(/sign in instead/i)).not.toBeInTheDocument();

    // And they land where a first-time password registration lands, untouched.
    expect(setActiveTempleId).toHaveBeenCalledWith("t1");
    await waitFor(() => expect(firebaseSignOut).toHaveBeenCalled());
    expect(replaceMock).toHaveBeenCalledWith("/sign-in?registered=gopal%40example.org");
  });

  it("does not open a second Google window to retry the join", async () => {
    signInWithPopup.mockResolvedValue({
      user: firebaseUser({ email: "gopal@example.org", displayName: "Gopal Das" }),
    });
    joinTemple.mockRejectedValueOnce(joinRefused());

    render(<RegisterPage />);
    await fillTheDetails();
    fireEvent.click(screen.getByRole("tab", { name: /^google$/i }));
    fireEvent.click(screen.getByRole("button", { name: /create my account/i }));

    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(signInWithPopup).toHaveBeenCalledTimes(1);

    joinTemple.mockResolvedValueOnce({});
    fireEvent.click(screen.getByRole("button", { name: /create my account/i }));

    await waitFor(() => expect(joinTemple).toHaveBeenCalledTimes(2));
    expect(signInWithPopup).toHaveBeenCalledTimes(1);
    // Google keeps them signed in, as it does on a first-time success.
    await waitFor(() => expect(refreshMock).toHaveBeenCalled());
    expect(replaceMock).toHaveBeenCalledWith("/");
    expect(firebaseSignOut).not.toHaveBeenCalled();
  });

  it("does not spend a second OTP to retry the join", async () => {
    // A phone credential carries no email at all, which is the other half of "still the same
    // person": nothing to compare, so nothing to disagree with.
    const confirm = vi.fn(async () => ({ user: firebaseUser({ displayName: "Gopal Das" }) }));
    signInWithPhoneNumber.mockResolvedValue({ confirm });
    joinTemple.mockRejectedValueOnce(joinRefused());

    render(<RegisterPage />);
    await fillTheDetails();
    fireEvent.click(screen.getByRole("tab", { name: /phone and otp/i }));
    fireEvent.click(screen.getByRole("button", { name: /send a code to my phone/i }));
    fireEvent.change(await screen.findByLabelText(/the six-digit code we sent to/i), {
      target: { value: "123456" },
    });
    fireEvent.click(screen.getByRole("button", { name: /create my account/i }));

    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(confirm).toHaveBeenCalledTimes(1);

    // A used verification code is spent; asking Firebase to confirm it twice is its own dead end.
    joinTemple.mockResolvedValueOnce({});
    fireEvent.click(screen.getByRole("button", { name: /create my account/i }));

    await waitFor(() => expect(joinTemple).toHaveBeenCalledTimes(2));
    expect(confirm).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(replaceMock).toHaveBeenCalledWith("/"));
  });

  it("picks the stranded credential back up after a reload, out of Firebase's own session", async () => {
    // A fresh page life: the component remembers nothing. But a refused join does not sign anybody
    // out, and Firebase's session survives a reload, so what was made is still signed in.
    authState.currentUser = firebaseUser({
      email: "gopal@example.org",
      displayName: "Gopal Das",
    });
    createUserWithEmailAndPassword.mockRejectedValueOnce({ code: "auth/email-already-in-use" });
    joinTemple.mockResolvedValue({});

    render(<RegisterPage />);
    await fillTheDetails();
    fillAPassword();
    fireEvent.click(screen.getByRole("button", { name: /create my account/i }));

    await waitFor(() => expect(joinTemple).toHaveBeenCalledTimes(1));
    expect(screen.queryByText(/sign in instead/i)).not.toBeInTheDocument();
    await waitFor(() =>
      expect(replaceMock).toHaveBeenCalledWith("/sign-in?registered=gopal%40example.org")
    );
  });

  it("still tells somebody who genuinely has an account already to sign in instead", async () => {
    // Nobody is signed in, so the email Firebase already holds is not one this screen just made.
    // The advice that is wrong for the stranded person is exactly right for this one, and the fix
    // must not take it away from them.
    //
    // THIS IS THE HALF THAT PROVES T-114 DISCRIMINATES RATHER THAN REPLACES. Without it, a later
    // edit could answer the person with no membership by deleting "Sign in instead." — helping one
    // reader by stranding the other, which is the same defect with the two people swapped round.
    createUserWithEmailAndPassword.mockRejectedValueOnce({ code: "auth/email-already-in-use" });

    render(<RegisterPage />);
    await fillTheDetails();
    fillAPassword();
    fireEvent.click(screen.getByRole("button", { name: /create my account/i }));

    const notice = await screen.findByText(
      /there is already an account with that email\. sign in instead\./i
    );
    expect(notice).toBeInTheDocument();
    // First and unqualified: somebody who has simply forgotten reads their whole answer before any
    // of the "if you aren't on the list" clause, and does not have to work out which half is theirs.
    expect(notice.textContent).toMatch(
      /^There is already an account with that email\. Sign in instead\./
    );
    expect(joinTemple).not.toHaveBeenCalled();
  });

  /**
   * The other person who reaches `auth/email-already-in-use` (T-114), and the reason the sentence
   * above is no longer the whole of it.
   *
   * <p>Firebase holds this email; this temple has no membership row for it. The screen cannot tell
   * that from the Firebase code — and cannot ask, because the credential was refused so there is no
   * token, and an "is this email registered?" lookup from a signed-out screen is the enumeration
   * oracle `SignInPage` refuses to be. So the sentence has to be true for them without knowing they
   * are there: sign in, and if the temple has no record of you, the way in is still that sign-in.
   *
   * <p>What made it a circle was that the old sentence's only instruction was the one they had
   * already tried. `/whoami` refuses them with `KMS-400020`, `auth-context` turns that into
   * `no-account`, and `app/page.tsx` sends `no-account` to `/choose-temple` — so the screen they
   * land on really does ask which temple they serve at and really does let them join, and the
   * sentence now says so rather than leaving them to discover it or give up.
   */
  it("tells somebody Firebase knows but this temple does not what signing in will actually do", async () => {
    createUserWithEmailAndPassword.mockRejectedValueOnce({ code: "auth/email-already-in-use" });

    render(<RegisterPage />);
    await fillTheDetails();
    fillAPassword();
    fireEvent.click(screen.getByRole("button", { name: /create my account/i }));

    const notice = await screen.findByText(/sign in instead\./i);

    // Named, not "this temple": they chose it three fields ago and it is the one they will be
    // looking for on the picker they are about to meet.
    expect(notice.textContent).toMatch(/if you aren’t on ISKCON Mysore’s list yet/i);
    // The screen `no-account` actually lands on, described in its own words.
    expect(notice.textContent).toMatch(/signing in will ask which temple you serve at/i);
    // The end of the circle, said out loud: coming back here is not the answer.
    expect(notice.textContent).toMatch(/join from there without registering again/i);

    // Not KMS-400020's next step, which this screen's own flow contradicts: nobody has to be asked
    // to add them, and telling them to wait for an administrator would be a second dead end.
    expect(notice.textContent).not.toMatch(/administrator/i);

    expect(screen.queryByText(/auth\/email-already-in-use/)).not.toBeInTheDocument();
    expect(joinTemple).not.toHaveBeenCalled();
  });

  it("will not join a temple as whoever else happens to be signed in", async () => {
    // Somebody else's session in the same browser is not evidence of a half-finished registration,
    // so the email has to match before the signed-in account is treated as the stranded one.
    authState.currentUser = firebaseUser({ email: "someone.else@example.org" });
    createUserWithEmailAndPassword.mockRejectedValueOnce({ code: "auth/email-already-in-use" });

    render(<RegisterPage />);
    await fillTheDetails();
    fillAPassword();
    fireEvent.click(screen.getByRole("button", { name: /create my account/i }));

    expect(
      await screen.findByText(/there is already an account with that email\. sign in instead\./i)
    ).toBeInTheDocument();
    expect(joinTemple).not.toHaveBeenCalled();
  });
});
