import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, isUnreachable } from "@/lib/api";

/**
 * Telling "we do not know you" apart from "we could not ask".
 *
 * <p>Both used to arrive as the same thing. `/whoami` answers a 401 with an empty body, so the
 * request layer synthesised `KMS-0000 "We couldn't reach the server"` for it — the identical
 * envelope a dropped connection produces. The session layer could not distinguish them and treated
 * every failure as "this person has no account here", which sent them to the temple picker.
 *
 * <p>That is a lie about the person, and it is worst exactly when it is least true: during a
 * deploy, every signed-in user at every temple is told their account has gone. This is the check
 * that the two stay apart.
 */

describe("what an error says about how it failed", () => {
  it("carries the status the server answered with", () => {
    const refused = new ApiError(
      { code: "KMS-0000", message: "no", action: "no", fieldErrors: [] },
      401
    );
    expect(refused.status).toBe(401);
    expect(isUnreachable(refused)).toBe(false);
  });

  it("treats a request that never got an answer as unreachable", () => {
    // `toApiError` wraps a thrown fetch failure with no status at all.
    const dropped = new ApiError({ code: "KMS-0000", message: "no", action: "no", fieldErrors: [] });
    expect(dropped.status).toBe(0);
    expect(isUnreachable(dropped)).toBe(true);
  });

  it("treats a server that broke as unreachable, not as a verdict on the person", () => {
    for (const status of [500, 502, 503, 504]) {
      const broken = new ApiError(
        { code: "KMS-500001", message: "no", action: "no", fieldErrors: [] },
        status
      );
      expect(isUnreachable(broken), `status ${status}`).toBe(true);
    }
  });

  it("does not call an ordinary refusal unreachable", () => {
    for (const status of [400, 401, 403, 404, 409]) {
      const refused = new ApiError(
        { code: "KMS-400021", message: "no", action: "no", fieldErrors: [] },
        status
      );
      expect(isUnreachable(refused), `status ${status}`).toBe(false);
    }
  });
});

// ---------------------------------------------------------------------------
// The provider itself, driven through a stubbed Firebase.
// ---------------------------------------------------------------------------

const { whoami, authState } = vi.hoisted(() => ({
  whoami: vi.fn(),
  authState: { listener: null as null | ((u: unknown) => void) },
}));

vi.mock("firebase/auth", () => ({
  onAuthStateChanged: (_auth: unknown, cb: (u: unknown) => void) => {
    authState.listener = cb;
    return () => undefined;
  },
  signInWithPopup: vi.fn(),
  signOut: vi.fn(),
  GoogleAuthProvider: class {},
}));

vi.mock("@/lib/firebase", () => ({
  firebaseConfigured: true,
  getFirebaseAuth: () => ({ currentUser: { getIdToken: async () => "token" } }),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, whoami }, setActiveTempleId: vi.fn() };
});

vi.mock("@/lib/nav", () => ({ forgetSidebarScroll: vi.fn() }));

const { replaceMock } = vi.hoisted(() => ({ replaceMock: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: replaceMock }) }));

import { render, screen, waitFor } from "@testing-library/react";
import { AuthProvider, useAuth } from "@/lib/auth-context";
import { RequireRole } from "@/components/RequireRole";
import Home from "@/app/page";

function Probe() {
  const { status } = useAuth();
  return <output>{status}</output>;
}

function signIntoFirebase() {
  authState.listener?.({ uid: "u1", getIdToken: async () => "token" });
}

describe("the session, when whoami fails", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authState.listener = null;
  });

  it("says no-account when the server answered and does not know them", async () => {
    whoami.mockRejectedValue(
      new ApiError({ code: "KMS-0000", message: "no", action: "no", fieldErrors: [] }, 401)
    );
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>
    );
    signIntoFirebase();

    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("no-account"));
    // A refusal is a fact, not a hiccup, so it is not retried.
    expect(whoami).toHaveBeenCalledTimes(1);
  });

  it("retries an unreachable server, and gives up saying so rather than blaming the person", async () => {
    whoami.mockRejectedValue(
      new ApiError({ code: "KMS-0000", message: "no", action: "no", fieldErrors: [] }, 0)
    );
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>
    );
    signIntoFirebase();

    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("unreachable"), {
      timeout: 10000,
    });
    // The first attempt plus two retries. Not "no-account", which is the whole point.
    expect(whoami).toHaveBeenCalledTimes(3);
  }, 15000);

  it("recovers without troubling anybody when a retry succeeds", async () => {
    // The common case this exists for: one Cloud Run instance still starting up.
    whoami
      .mockRejectedValueOnce(
        new ApiError({ code: "KMS-0000", message: "no", action: "no", fieldErrors: [] }, 503)
      )
      .mockResolvedValue({
        userId: "u1",
        tenantId: "t1",
        role: "TEMPLE_ADMIN",
        fullName: "A Person",
        tenantName: "A Temple",
        tenantSlug: "a-temple",
        temples: [],
        themeId: null,
      });
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>
    );
    signIntoFirebase();

    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("signed-in"), {
      timeout: 10000,
    });
  }, 15000);
});

// ---------------------------------------------------------------------------
// Telling the refusals apart — by the code, not by the status number.
// ---------------------------------------------------------------------------

/**
 * A 401 from `/whoami` is three different facts wearing the same number: this account was switched
 * off, this person has no account at any temple, and we would rather not say why the token failed.
 * Only the reference code separates them, which is why nothing below looks at `status`.
 */
function refuses(code: string) {
  whoami.mockRejectedValue(
    new ApiError({ code, message: "no", action: "no", fieldErrors: [] }, 401)
  );
}

describe("what the session makes of a coded refusal", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authState.listener = null;
  });

  it("reads KMS-400019 as an account that was disabled, not as one that never existed", async () => {
    refuses("KMS-400019");
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>
    );
    signIntoFirebase();

    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("disabled"));
    // A withdrawal of access is a decision somebody made, not a hiccup. Asking again cannot change it.
    expect(whoami).toHaveBeenCalledTimes(1);
  });

  it("reads KMS-400020 as no account here, which is what it has always done and is now told so", async () => {
    refuses("KMS-400020");
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>
    );
    signIntoFirebase();

    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("no-account"));
  });

  it("leaves an uncoded 401 exactly where it was", async () => {
    // The bodyless 401 an unverifiable or expired token still produces. `SESSION_EXPIRED`
    // (KMS-400018) is deliberately not sent by the server yet — that is an open question — so this
    // must keep falling through to the old behaviour rather than guessing at something narrower.
    refuses("KMS-0000");
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>
    );
    signIntoFirebase();

    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("no-account"));
  });
});

// ---------------------------------------------------------------------------
// And what a disabled person actually sees. The trap this guards is specific: a status neither
// consumer names falls through to <Loading /> and stays there, so somebody whose access was
// withdrawn would watch a spinner instead of being told.
// ---------------------------------------------------------------------------

describe("what a disabled person is shown", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authState.listener = null;
  });

  it("tells them on the landing route, and does not spin or send them to the temple picker", async () => {
    refuses("KMS-400019");
    render(
      <AuthProvider>
        <Home />
      </AuthProvider>
    );
    signIntoFirebase();

    await waitFor(() =>
      expect(screen.getByText("This account has been disabled")).toBeInTheDocument()
    );
    expect(screen.getByText("Ask your temple administrator to restore access.")).toBeInTheDocument();
    expect(screen.queryByText("Loading…")).not.toBeInTheDocument();
    // The picker would offer to sign them up somewhere. They already belong here.
    expect(replaceMock).not.toHaveBeenCalledWith("/choose-temple");
  });

  it("tells them on a guarded screen too, since a deep link is how most people arrive", async () => {
    refuses("KMS-400019");
    render(
      <AuthProvider>
        <RequireRole roles={["TEMPLE_ADMIN"]}>
          <p>Secret</p>
        </RequireRole>
      </AuthProvider>
    );
    signIntoFirebase();

    await waitFor(() =>
      expect(screen.getByText("This account has been disabled")).toBeInTheDocument()
    );
    expect(screen.queryByText("Loading…")).not.toBeInTheDocument();
    expect(screen.queryByText("Secret")).not.toBeInTheDocument();
  });
});
