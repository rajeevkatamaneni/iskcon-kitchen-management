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
        { code: "KMS-5001", message: "no", action: "no", fieldErrors: [] },
        status
      );
      expect(isUnreachable(broken), `status ${status}`).toBe(true);
    }
  });

  it("does not call an ordinary refusal unreachable", () => {
    for (const status of [400, 401, 403, 404, 409]) {
      const refused = new ApiError(
        { code: "KMS-4301", message: "no", action: "no", fieldErrors: [] },
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

import { render, screen, waitFor } from "@testing-library/react";
import { AuthProvider, useAuth } from "@/lib/auth-context";

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
