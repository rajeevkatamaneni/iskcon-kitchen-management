"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import {
  GoogleAuthProvider,
  onAuthStateChanged,
  signInWithPopup,
  signOut as firebaseSignOut,
  type User,
} from "firebase/auth";
import { api, isUnreachable, setActiveTempleId, setTempleTimeZone, toApiError, type WhoAmI } from "./api";
import { forgetSidebarScroll } from "./nav";
import { firebaseConfigured, getFirebaseAuth } from "./firebase";

/**
 * Who is signed in — both as Firebase understands it and as our application does.
 *
 * <p>Firebase authenticates; it does not authorise. So being signed in with Firebase is not the
 * same as having an account here: {@code status} tells the two apart. On a Firebase sign-in we call
 * {@code /whoami} to learn the person's role and tenant from our own records; a 401 there means a
 * valid identity with no temple account yet, which is a normal state to handle, not an error.
 *
 * <p>Tokens are fetched per request rather than held in state. The SDK refreshes them roughly
 * hourly, and a cached copy would quietly go stale — producing 401s that look like a permissions
 * bug rather than an expired token.
 */

/**
 * The Google provider to hand to {@code signInWithPopup} — anywhere in the application.
 *
 * <p>Always build it through here, never as a bare {@code new GoogleAuthProvider()}. The whole
 * point of the function is the one parameter it sets.
 *
 * <p>Signing out of this application signs the person out of <em>Firebase</em>. It does not sign
 * their browser out of <em>Google</em>, and it cannot: that session belongs to accounts.google.com
 * and we have no reach into it. So after signing out, the browser still holds a live Google
 * session — and Google's OAuth endpoint, asked to authorise with no {@code prompt} parameter,
 * takes exactly one live session as an unambiguous answer and skips the account chooser entirely.
 * The next press of "Continue with Google" then puts the person straight back in as whoever signed
 * in last, with no screen in between and nothing to click. It reads as the sign-out having failed.
 *
 * <p>Reported by Rajeev on 2026-09-07 after signing out of the super-admin account and being
 * returned to it while trying to sign in as kitchen staff. It also blocked UAT outright: staff
 * added on /staff exist as {@code pending:} users who bind to a Firebase identity on their first
 * Google sign-in (E1-S6, claim-on-match), so binding them means signing in as each address in
 * turn — the one thing this defect makes impossible.
 *
 * <p>{@code prompt: "select_account"} forces the chooser every time, which is what a person
 * pressing a sign-in button is asking for. Deliberately not {@code "consent"}: that would also
 * re-ask for scopes already granted, which is a worse experience and not what was wrong.
 */
export function googleProvider(): GoogleAuthProvider {
  const provider = new GoogleAuthProvider();
  provider.setCustomParameters({ prompt: "select_account" });
  return provider;
}

export type AuthStatus =
  | "loading"
  | "signed-out"
  | "no-account"
  | "signed-in"
  /**
   * Firebase knows this person, and we could not ask our own server who they are.
   *
   * <p>Its own state since 2026-08-30. Every failure of {@code /whoami} used to become
   * {@code no-account}, which is true of a 401 and a lie about everything else — a deploy, a cold
   * start, a dropped connection. During any release every person with the application open was
   * told their account had gone, and sent to the temple picker to find another.
   */
  | "unreachable"
  /**
   * Firebase knows this person, we know them too, and their account here has been switched off.
   *
   * <p>Its own state for the same reason {@code unreachable} is. Until the server started sending
   * `KMS-400019` this arrived as `no-account`, so a volunteer whose access an administrator had
   * just withdrawn was told they belonged to no temple and offered the chance to sign up for one —
   * which is both untrue and, for somebody who has been serving there for a year, unkind. The two
   * facts are opposite: one person has never had an account, the other had one taken away, and only
   * the second has somebody specific to go and ask.
   */
  | "disabled";

interface AuthState {
  user: User | null;
  appUser: WhoAmI | null;
  status: AuthStatus;
  getToken: () => Promise<string | undefined>;
  signInWithGoogle: () => Promise<void>;
  signOut: () => Promise<void>;
  /** Re-reads who we are — after joining a temple, or switching to another one. */
  refresh: () => Promise<void>;
  /** Switch which temple the app speaks for. Only ever one this person actually belongs to. */
  switchTemple: (tenantId: string) => Promise<void>;
}

const AuthContext = createContext<AuthState>({
  user: null,
  appUser: null,
  status: "loading",
  getToken: async () => undefined,
  signInWithGoogle: async () => {},
  signOut: async () => {},
  refresh: async () => {},
  switchTemple: async () => {},
});

/** How many times a failure to reach our own server is retried before it is reported. */
const RETRIES = 2;
const RETRY_DELAY_MS = 1000;

/**
 * The two refusals `/whoami` can make that mean something different to the person reading them.
 *
 * <p>Named here rather than matched on a status number, because 401 is all three of "your account
 * was disabled", "you have no account at this temple" and "we would rather not say why your token
 * failed" — and `api.ts` says as much beside `ApiError.status`: a screen branching on a status
 * instead of a code is a screen drifting away from the error contract. These are permanent
 * (`ErrorCode.java`) and safe to compare against.
 */
const ACCOUNT_DISABLED = "KMS-400019";
const NO_ACCOUNT_AT_TEMPLE = "KMS-400020";

/**
 * What each of them means about the session, as a table rather than a chain of conditions — so the
 * whole mapping is one thing to read, and adding the next code is adding one line.
 *
 * <p>Anything absent falls through to `no-account`, which is what every refusal produced before the
 * server carried codes at all. A 401 with no body is the live case: `TokenVerifier` deliberately
 * refuses to say why a token failed, so an expired session still lands here and is still reported
 * as having no account. That is known, and it is the half of this held for Rajeev's decision.
 */
const REFUSALS: Record<string, AuthStatus> = {
  [ACCOUNT_DISABLED]: "disabled",
  [NO_ACCOUNT_AT_TEMPLE]: "no-account",
};

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [appUser, setAppUser] = useState<WhoAmI | null>(null);
  const [status, setStatus] = useState<AuthStatus>("loading");

  // Telling a sign-in apart from the first answer about who was already signed in.
  const resolved = useRef(false);
  const previousUid = useRef<string | null>(null);

  /**
   * Asks our own records who this Firebase identity is, and tells the two kinds of failure apart.
   *
   * <p>A refusal means the server answered, and what it says is read from its reference code rather
   * than inferred from the absence of a network error. `KMS-400019` is an account that was switched
   * off and `KMS-400020` is an identity with no account at any temple; the first has an
   * administrator to go and ask, the second lands on the temple picker. Anything else the server
   * refuses — including a 401 it declined to explain, which is what an expired or forged token still
   * produces — keeps the old behaviour and is treated as no account, because guessing anything
   * narrower from a bodyless answer is what this exists to stop.
   *
   * <p>Anything the server did not answer at all is not a fact about the person, and is retried.
   *
   * <p>Two retries before giving up, a second apart. Most of what this catches is a Cloud Run
   * instance starting up or a phone changing cell, both of which are over in a second or two, and
   * showing somebody a failure they did not need to see is its own small harm. Beyond that it
   * stops: a wall of silent retries is how an application comes to feel broken rather than busy.
   */
  const resolveIdentity = useCallback(async (user: User, attempt = 0): Promise<void> => {
    try {
      const who = await api.whoami(await user.getIdToken());
      // The temple's clock, written before anything renders. Every date and time on every screen is
      // formatted in it (see `templeTimeZone`), so it has to be in place before the first screen
      // asks what day it is — and it is refreshed on a temple switch, because the next temple may
      // keep a different one.
      setTempleTimeZone(who.timezone);
      setAppUser(who);
      setStatus("signed-in");
    }
    catch (caught) {
      const error = toApiError(caught);
      if (!isUnreachable(error)) {
        setAppUser(null);
        setStatus(REFUSALS[error.code] ?? "no-account");
        return;
      }
      if (attempt < RETRIES) {
        await new Promise((resolve) => setTimeout(resolve, RETRY_DELAY_MS * (attempt + 1)));
        return resolveIdentity(user, attempt + 1);
      }
      setAppUser(null);
      setStatus("unreachable");
    }
  }, []);

  useEffect(() => {
    if (!firebaseConfigured) {
      setStatus("signed-out");
      return;
    }

    return onAuthStateChanged(getFirebaseAuth(), (next) => {
      setUser(next);

      // Signing in sends the menu back to the top, where Today is. Only signing in: this fires
      // again for the same person on every reload and token refresh, and clearing it then would
      // throw away the position they scrolled to, which is the half that was already right.
      const signingIn = resolved.current && previousUid.current !== (next?.uid ?? null);
      resolved.current = true;
      previousUid.current = next?.uid ?? null;
      if (signingIn) {
        forgetSidebarScroll();
      }

      if (!next) {
        setAppUser(null);
        setStatus("signed-out");
        return;
      }

      // Signed into Firebase — now find out who they are here.
      setStatus("loading");
      resolveIdentity(next);
    });
  }, []);

  const getToken = useCallback(async () => {
    // Ask the SDK each time so we always get a valid, freshly-refreshed token.
    return user ? user.getIdToken() : undefined;
  }, [user]);

  const signInWithGoogle = useCallback(async () => {
    await signInWithPopup(getFirebaseAuth(), googleProvider());
    // onAuthStateChanged does the rest — sets the user and resolves whoami.
  }, []);

  const refresh = useCallback(async () => {
    const current = getFirebaseAuth().currentUser;
    if (!current) return;
    setStatus("loading");
    await resolveIdentity(current);
  }, [resolveIdentity]);

  const switchTemple = useCallback(
    async (tenantId: string) => {
      // The header decides which membership the next request speaks for; the server accepts it only
      // after matching it against this person's own, so this selects rather than grants.
      setActiveTempleId(tenantId);
      await refresh();
    },
    [refresh]
  );

  const signOut = useCallback(async () => {
    if (firebaseConfigured) {
      await firebaseSignOut(getFirebaseAuth());
    }
    setAppUser(null);
    setActiveTempleId(null);
    // Signing out leaves no temple, so it leaves no clock either — the next person to sign in on
    // this browser must not inherit the last one's.
    setTempleTimeZone(null);
    setStatus("signed-out");
  }, []);

  const value = useMemo(
    () => ({ user, appUser, status, getToken, signInWithGoogle, signOut, refresh, switchTemple }),
    [user, appUser, status, getToken, signInWithGoogle, signOut, refresh, switchTemple]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  return useContext(AuthContext);
}
