"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
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
import { api, type WhoAmI } from "./api";
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

export type AuthStatus = "loading" | "signed-out" | "no-account" | "signed-in";

interface AuthState {
  user: User | null;
  appUser: WhoAmI | null;
  status: AuthStatus;
  getToken: () => Promise<string | undefined>;
  signInWithGoogle: () => Promise<void>;
  signOut: () => Promise<void>;
}

const AuthContext = createContext<AuthState>({
  user: null,
  appUser: null,
  status: "loading",
  getToken: async () => undefined,
  signInWithGoogle: async () => {},
  signOut: async () => {},
});

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [appUser, setAppUser] = useState<WhoAmI | null>(null);
  const [status, setStatus] = useState<AuthStatus>("loading");

  useEffect(() => {
    if (!firebaseConfigured) {
      setStatus("signed-out");
      return;
    }

    return onAuthStateChanged(getFirebaseAuth(), (next) => {
      setUser(next);

      if (!next) {
        setAppUser(null);
        setStatus("signed-out");
        return;
      }

      // Signed into Firebase — now find out who they are here.
      setStatus("loading");
      next
        .getIdToken()
        .then((token) => api.whoami(token))
        .then((who) => {
          setAppUser(who);
          setStatus("signed-in");
        })
        .catch(() => {
          // Most often a 401: a real identity with no account at any temple yet. Either way they
          // cannot enter the app, so surface it as the no-account state.
          setAppUser(null);
          setStatus("no-account");
        });
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

  const signOut = useCallback(async () => {
    if (firebaseConfigured) {
      await firebaseSignOut(getFirebaseAuth());
    }
    setAppUser(null);
    setStatus("signed-out");
  }, []);

  const value = useMemo(
    () => ({ user, appUser, status, getToken, signInWithGoogle, signOut }),
    [user, appUser, status, getToken, signInWithGoogle, signOut]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  return useContext(AuthContext);
}
