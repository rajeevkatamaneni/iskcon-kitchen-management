"use client";

import { useCallback, useEffect, useState } from "react";
import { ApiError } from "./api";
import { useAuth } from "./auth-context";

/**
 * Fetches data from the API for the signed-in user, with the token attached.
 *
 * <p>The pattern every read-only screen uses: it waits until the user is signed in, gets a fresh
 * token, calls the given API method, and hands back {@code data} / {@code error} / {@code loading}
 * plus a {@code reload}. A failed request always surfaces as an {@link ApiError} — the same shape
 * the whole app renders — so screens never have to handle two kinds of failure.
 *
 * <p>Pass a stable fetcher (an {@code api.*} method, or one wrapped in {@code useCallback}); an
 * inline arrow re-created each render would re-fetch in a loop.
 */
export function useAuthedQuery<T>(
  fetcher: (token: string | undefined) => Promise<T>
): { data: T | null; error: ApiError | null; loading: boolean; reload: () => void } {
  const { status, getToken } = useAuth();
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [loading, setLoading] = useState(true);
  const [nonce, setNonce] = useState(0);

  useEffect(() => {
    if (status !== "signed-in") {
      // The route guard handles signed-out / no-account; nothing to fetch until signed in.
      return;
    }

    let cancelled = false;
    setLoading(true);
    setError(null);

    getToken()
      .then(fetcher)
      .then((result) => {
        if (!cancelled) {
          setData(result);
          setLoading(false);
        }
      })
      .catch((caught) => {
        if (cancelled) return;
        setError(
          caught instanceof ApiError
            ? caught
            : new ApiError({
                code: "KMS-0000",
                message: "We couldn't load this.",
                action: "Check your connection and try again.",
                fieldErrors: [],
              })
        );
        setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [status, getToken, fetcher, nonce]);

  const reload = useCallback(() => setNonce((n) => n + 1), []);

  return { data, error, loading, reload };
}
