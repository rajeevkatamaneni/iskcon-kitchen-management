import { beforeEach, describe, expect, it, vi } from "vitest";

/**
 * The temple somebody chose while registering, carried to the screen that asks them for it (T-118).
 *
 * <p><strong>The defect.</strong> `/register` asks which temple you serve at in its first field.
 * Firebase answers "that email already has an account", the screen says to sign in instead — and
 * promises, in T-114's words, that signing in will ask which temple you serve at and you can join
 * from there. It does. It just asks as though nobody had ever said. The answer was collected,
 * carried nowhere, and asked for again a minute later.
 *
 * <p><strong>What is asserted here is the hand-off, end to end, through the real thing.</strong>
 * `lib/auth-context` is not mocked in this file: the register screen writes the note with the real
 * `rememberChosenTemple`, the picker reads it with the real `takeChosenTemple`, and the real
 * `AuthProvider` is what resolves the session in between. Only Firebase and the API are stubs. A
 * version of this test that mocked the storage either side would prove the two screens agree with a
 * fake and nothing about whether they agree with each other.
 *
 * <p><strong>The two properties that are not "it remembers".</strong> First, it is
 * <em>pre-selected and not pre-joined</em>: joining a temple is a real act, so the button is still
 * theirs to press and `joinTemple` is not called by arriving. Second, <em>nothing remembered is
 * ever displayed</em>: the note holds an id and a search term, the record on the screen comes back
 * from the server, and a temple the picker would no longer offer is not offered here either. A
 * remembered value that is silently wrong is worse than asking again, so the tests below check the
 * screen falls back to asking in every case where the answer cannot be confirmed.
 */

const {
  createUserWithEmailAndPassword,
  signInWithPopup,
  signInWithPhoneNumber,
  updateProfile,
  firebaseSignOut,
  whoami,
  temples,
  joinTemple,
  setActiveTempleId,
  replaceMock,
  authState,
} = vi.hoisted(() => ({
  createUserWithEmailAndPassword: vi.fn(),
  signInWithPopup: vi.fn(),
  signInWithPhoneNumber: vi.fn(),
  updateProfile: vi.fn(async () => {}),
  firebaseSignOut: vi.fn(async () => {}),
  whoami: vi.fn(),
  temples: vi.fn(),
  joinTemple: vi.fn(),
  setActiveTempleId: vi.fn(),
  replaceMock: vi.fn(),
  // Firebase's own session: the listener the provider registers, and the currentUser the register
  // screen reaches for when it looks for a credential it stranded.
  authState: { listener: null as null | ((user: unknown) => void), currentUser: null as unknown },
}));

vi.mock("firebase/auth", () => ({
  createUserWithEmailAndPassword,
  signInWithPopup,
  signInWithPhoneNumber,
  updateProfile,
  signOut: firebaseSignOut,
  onAuthStateChanged: (_auth: unknown, callback: (user: unknown) => void) => {
    authState.listener = callback;
    return () => undefined;
  },
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

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, whoami, temples, joinTemple },
    setActiveTempleId,
  };
});

vi.mock("@/lib/nav", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/nav")>();
  return { ...actual, forgetSidebarScroll: vi.fn() };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: replaceMock, push: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
}));

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ApiError } from "@/lib/api";
import {
  AuthProvider,
  rememberChosenTemple,
  takeChosenTemple,
  useAuth,
} from "@/lib/auth-context";
import RegisterPage from "@/app/register/page";
import ChooseTemplePage from "@/app/choose-temple/page";

const MYSORE = { id: "t1", name: "ISKCON Mysore", address: "Jayalakshmipuram", distanceKm: 3 };

/** Half an hour is the window; this is comfortably outside it. */
const LONG_AGO = 31 * 60 * 1000;

function firebaseUser(overrides: Record<string, unknown> = {}) {
  return {
    uid: "fb-1",
    email: "gopal@example.org",
    phoneNumber: null,
    displayName: "Gopal Das",
    getIdToken: async () => "id-token",
    ...overrides,
  };
}

/** Everything on the register form above "How would you like to sign in?", temple first. */
async function fillTheRegisterForm() {
  fireEvent.change(screen.getByLabelText(/which temple do you serve at/i), {
    target: { value: "Mysore" },
  });
  // The picker waits 350ms after the last keystroke before it searches.
  fireEvent.click(await screen.findByRole("button", { name: /ISKCON Mysore/i }, { timeout: 3000 }));

  fireEvent.change(screen.getByLabelText(/first name/i), { target: { value: "Gopal" } });
  fireEvent.change(screen.getByLabelText(/last name/i), { target: { value: "Das" } });
  fireEvent.change(screen.getByLabelText(/^email$/i), { target: { value: "gopal@example.org" } });
  fireEvent.change(screen.getByLabelText(/^phone$/i), { target: { value: "+919876543210" } });
  fireEvent.change(screen.getByLabelText("Create a password"), { target: { value: "hare-krishna-108" } });
  fireEvent.change(screen.getByLabelText(/confirm password/i), { target: { value: "hare-krishna-108" } });
}

/**
 * The picker, reached the way people actually reach it: signed into Firebase, refused by `/whoami`
 * with the code that means no account at any temple, and routed here by the session layer.
 */
function arriveAtThePicker() {
  whoami.mockRejectedValue(
    new ApiError(
      {
        code: "KMS-400020",
        message: "You're signed in, but you don't have an account at any temple yet.",
        action: "Choose your temple to join it.",
        fieldErrors: [],
      },
      401
    )
  );
  render(
    <AuthProvider>
      <ChooseTemplePage />
    </AuthProvider>
  );
  authState.listener?.(firebaseUser());
}

/** A way to reach the real `signOut` without a screen in the way. */
function SignOutProbe() {
  const { signOut } = useAuth();
  return (
    <button type="button" onClick={() => void signOut()}>
      Sign out
    </button>
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  window.sessionStorage.clear();
  authState.listener = null;
  authState.currentUser = null;
  temples.mockResolvedValue([MYSORE]);
  joinTemple.mockResolvedValue({});
  createUserWithEmailAndPassword.mockResolvedValue({ user: firebaseUser() });
});

// ---------------------------------------------------------------------------
// The half that collects the answer.
// ---------------------------------------------------------------------------

describe("registering, when the screen sends somebody away to sign in", () => {
  it("keeps the temple they chose, for the screen that is about to ask", async () => {
    createUserWithEmailAndPassword.mockRejectedValueOnce({ code: "auth/email-already-in-use" });

    render(
      <AuthProvider>
        <RegisterPage />
      </AuthProvider>
    );
    await fillTheRegisterForm();
    fireEvent.click(screen.getByRole("button", { name: /create my account/i }));

    // The sentence that promises the sign-in will ask which temple they serve at (T-114).
    expect(await screen.findByText(/sign in instead\./i)).toBeInTheDocument();

    // The id is the fact, and the name is only what to look it up by again.
    expect(takeChosenTemple()).toEqual({ id: "t1", name: "ISKCON Mysore" });
  });

  it("keeps nothing when the failure leaves them on the form with the temple still in it", async () => {
    createUserWithEmailAndPassword.mockRejectedValueOnce({ code: "auth/weak-password" });

    render(
      <AuthProvider>
        <RegisterPage />
      </AuthProvider>
    );
    await fillTheRegisterForm();
    fireEvent.click(screen.getByRole("button", { name: /create my account/i }));

    expect(await screen.findByText(/choose a longer password/i)).toBeInTheDocument();
    // Nobody has gone anywhere. A note here would be a guess waiting in the next tab.
    expect(takeChosenTemple()).toBeNull();
  });

  it("keeps nothing when the registration works, because the picker is not in their future", async () => {
    render(
      <AuthProvider>
        <RegisterPage />
      </AuthProvider>
    );
    await fillTheRegisterForm();
    fireEvent.click(screen.getByRole("button", { name: /create my account/i }));

    await waitFor(() => expect(joinTemple).toHaveBeenCalledTimes(1));
    expect(takeChosenTemple()).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// The half that would otherwise ask again.
// ---------------------------------------------------------------------------

describe("arriving at the picker having already said which temple", () => {
  it("opens with it chosen, says where that came from, and still waits to be told to join", async () => {
    rememberChosenTemple({ id: "t1", name: "ISKCON Mysore" });
    arriveAtThePicker();

    expect(await screen.findByText("ISKCON Mysore")).toBeInTheDocument();
    expect(screen.getByText(/temple you chose when you registered/i)).toBeInTheDocument();
    // Pre-selected, not pre-decided: the way to a different temple is one press away.
    expect(screen.getByRole("button", { name: /change/i })).toBeInTheDocument();
    // Looked up by the term the note carried, matched on the id.
    expect(temples).toHaveBeenCalledWith({ q: "ISKCON Mysore" });
    // And pre-selected, not pre-joined. Joining a temple stays the person's own act.
    expect(joinTemple).not.toHaveBeenCalled();
  });

  it("joins that temple when they press the button", async () => {
    rememberChosenTemple({ id: "t1", name: "ISKCON Mysore" });
    arriveAtThePicker();

    expect(await screen.findByText("ISKCON Mysore")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/^phone$/i), { target: { value: "+919876543210" } });
    fireEvent.click(screen.getByRole("button", { name: /join this temple/i }));

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
  });

  it("shows what the temple is called now, not what it was called when they picked it", async () => {
    temples.mockResolvedValue([{ ...MYSORE, name: "ISKCON Mysore (Jayalakshmipuram)" }]);
    rememberChosenTemple({ id: "t1", name: "ISKCON Mysore" });
    arriveAtThePicker();

    expect(await screen.findByText("ISKCON Mysore (Jayalakshmipuram)")).toBeInTheDocument();
    // The remembered string was a search term and never a label. Nothing stored is put on a screen.
    expect(screen.queryByText("ISKCON Mysore")).not.toBeInTheDocument();
  });

  it("asks plainly when that temple is no longer one this picker offers", async () => {
    // Closed to new devotees, withdrawn, renamed past finding — the screen cannot tell which, and
    // does not need to: the picker's own list is what may be chosen here.
    temples.mockResolvedValue([]);
    rememberChosenTemple({ id: "t9", name: "ISKCON Nowhere" });
    arriveAtThePicker();

    expect(
      await screen.findByRole("heading", { name: /which temple do you serve at\?/i })
    ).toBeInTheDocument();
    expect(screen.getByLabelText(/search for your temple/i)).toBeInTheDocument();
    expect(screen.queryByText("ISKCON Nowhere")).not.toBeInTheDocument();
    expect(screen.queryByText(/temple you chose when you registered/i)).not.toBeInTheDocument();
  });

  it("asks plainly when the lookup cannot be made at all", async () => {
    temples.mockRejectedValue(new Error("offline"));
    rememberChosenTemple({ id: "t1", name: "ISKCON Mysore" });
    arriveAtThePicker();

    expect(await screen.findByLabelText(/search for your temple/i)).toBeInTheDocument();
    expect(screen.queryByText(/temple you chose when you registered/i)).not.toBeInTheDocument();
  });

  it("ignores a note left more than half an hour ago", async () => {
    rememberChosenTemple({ id: "t1", name: "ISKCON Mysore" }, Date.now() - LONG_AGO);
    arriveAtThePicker();

    expect(await screen.findByLabelText(/search for your temple/i)).toBeInTheDocument();
    // Not even looked up: a decision that old is not an answer to today's question.
    expect(temples).not.toHaveBeenCalled();
  });

  it("reads the note once, so it answers this arrival and not the next", async () => {
    rememberChosenTemple({ id: "t1", name: "ISKCON Mysore" });
    arriveAtThePicker();

    expect(await screen.findByText("ISKCON Mysore")).toBeInTheDocument();
    expect(takeChosenTemple()).toBeNull();
  });

  it("changes nothing for somebody who arrived by another road", async () => {
    arriveAtThePicker();

    expect(
      await screen.findByRole("heading", { name: /which temple do you serve at\?/i })
    ).toBeInTheDocument();
    expect(screen.getByLabelText(/search for your temple/i)).toBeInTheDocument();
    // No note, no lookup. The screen costs a request only where it saves the person a question.
    expect(temples).not.toHaveBeenCalled();
  });
});

// ---------------------------------------------------------------------------
// And it belongs to one person's journey.
// ---------------------------------------------------------------------------

describe("signing out", () => {
  it("forgets the temple, so the next person on this browser is not handed it", async () => {
    // The path this closes: register, get told to sign in, sign in as somebody else who does have
    // a temple, work, sign out. The note would otherwise still be sitting there for whoever is next.
    rememberChosenTemple({ id: "t1", name: "ISKCON Mysore" });

    render(
      <AuthProvider>
        <SignOutProbe />
      </AuthProvider>
    );
    fireEvent.click(screen.getByRole("button", { name: /sign out/i }));

    await waitFor(() => expect(firebaseSignOut).toHaveBeenCalled());
    expect(takeChosenTemple()).toBeNull();
  });
});
