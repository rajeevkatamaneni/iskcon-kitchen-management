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
import { api, isUnreachable, setActiveTempleId, toApiError, type WhoAmI } from "./api";
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
  | "unreachable";

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
   * <p>A 401 means the server answered and does not know them — a real identity with no account at
   * any temple, which is a normal state and lands on the temple picker. Anything else means the
   * server did not answer, and that is not a fact about the person.
   *
   * <p>Two retries before giving up, a second apart. Most of what this catches is a Cloud Run
   * instance starting up or a phone changing cell, both of which are over in a second or two, and
   * showing somebody a failure they did not need to see is its own small harm. Beyond that it
   * stops: a wall of silent retries is how an application comes to feel broken rather than busy.
   */
  const resolveIdentity = useCallback(async (user: User, attempt = 0): Promise<void> => {
    try {
      const who = await api.whoami(await user.getIdToken());
      setAppUser(who);
      setStatus("signed-in");
    }
    catch (caught) {
      const error = toApiError(caught);
      if (!isUnreachable(error)) {
        setAppUser(null);
        setStatus("no-account");
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
    await signInWithPopup(getFirebaseAuth(), new GoogleAuthProvider());
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
