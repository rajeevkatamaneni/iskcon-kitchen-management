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
import { onAuthStateChanged, signOut as firebaseSignOut, type User } from "firebase/auth";
import { firebaseConfigured, getFirebaseAuth } from "./firebase";

/**
 * Who is signed in, and how to get a token for API calls.
 *
 * <p>Tokens are fetched per request rather than held in state. The Firebase SDK refreshes them
 * roughly hourly, and a cached copy in React state would quietly go stale — producing 401s that
 * look like a permissions bug rather than an expired token.
 */

interface AuthState {
  user: User | null;
  loading: boolean;
  getToken: () => Promise<string | undefined>;
  signOut: () => Promise<void>;
}

const AuthContext = createContext<AuthState>({
  user: null,
  loading: true,
  getToken: async () => undefined,
  signOut: async () => {},
});

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!firebaseConfigured) {
      setLoading(false);
      return;
    }

    return onAuthStateChanged(getFirebaseAuth(), (next) => {
      setUser(next);
      setLoading(false);
    });
  }, []);

  const getToken = useCallback(async () => {
    // Asking the SDK each time means we always get a valid token; it handles refresh.
    return user ? user.getIdToken() : undefined;
  }, [user]);

  const signOut = useCallback(async () => {
    if (firebaseConfigured) {
      await firebaseSignOut(getFirebaseAuth());
    }
  }, []);

  const value = useMemo(
    () => ({ user, loading, getToken, signOut }),
    [user, loading, getToken, signOut]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  return useContext(AuthContext);
}
