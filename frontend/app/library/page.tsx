"use client";

import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Loading } from "@/components/Loading";
import { api, toApiError, type ApiError, type MasterRecipeSummary } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";

/**
 * The shared recipe library, as its operator sees it (E2-S15).
 *
 * <p>Its own destination rather than a tab inside Operations. Operations is a health dashboard —
 * what is failing and how often — and a catalogue of five thousand recipes is not health; burying
 * one inside the other would make both harder to read.
 *
 * <p>What a temple sees of this library is the search box on their own Recipes page. This screen is
 * the other side: browsing by state, and the two acts only an operator may perform — loading the
 * books, and taking a recipe down.
 */
export default function LibraryPage() {
  return (
    <RequireRole roles={["SUPER_ADMIN"]}>
      <Suspense>
        <LibraryView />
      </Suspense>
    </RequireRole>
  );
}

function LibraryView() {
  const { getToken } = useAuth();

  const [search, setSearch] = useState("");
  const [state, setState] = useState("");
  const [rows, setRows] = useState<MasterRecipeSummary[]>([]);
  const [states, setStates] = useState<{ slug: string; name: string; recipes: number }[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState<string | null>(null);

  const latest = useRef(0);

  const run = useCallback(async () => {
    const mine = ++latest.current;
    try {
      const token = await getToken();
      const found = await api.listLibraryRecipes(
        { q: search || undefined, state: state || undefined, limit: 100 },
        token
      );
      if (latest.current === mine) {
        setRows(found);
        setError(null);
      }
    } catch (e) {
      if (latest.current === mine) setError(toApiError(e, "We couldn’t read the library."));
    } finally {
      if (latest.current === mine) setLoading(false);
    }
  }, [search, state, getToken]);

  useEffect(() => {
    setLoading(true);
    const timer = setTimeout(run, 200);
    return () => clearTimeout(timer);
  }, [run]);

  useEffect(() => {
    getToken()
      .then((t) => api.listLibraryStates(t))
      .then(setStates)
      .catch(() => setStates([]));
  }, [getToken, loaded]);

  async function load() {
    setBusy(true);
    setError(null);
    try {
      const result = await api.loadRecipeLibrary(await getToken());
      setLoaded(`${result.recipes.toLocaleString("en-IN")} recipes from ${result.books} books`);
      await run();
    } catch (e) {
      setError(toApiError(e, "The load didn’t finish."));
    } finally {
      setBusy(false);
    }
  }

  async function remove(row: MasterRecipeSummary) {
    setBusy(true);
    setError(null);
    try {
      await api.deleteLibraryRecipe(row.id, await getToken());
      setRows((list) => list.filter((r) => r.id !== row.id));
    } catch (e) {
      setError(toApiError(e, "We couldn’t take that recipe down."));
    } finally {
      setBusy(false);
    }
  }

  const total = states.reduce((sum, s) => sum + s.recipes, 0);

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/library" />

      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Recipe library</h1>
              {total > 0 && (
                <p className="mt-1 text-sm text-ink-secondary">
                  {total.toLocaleString("en-IN")} recipes · {states.length} states
                </p>
              )}
            </div>
            <button
              type="button"
              onClick={load}
              disabled={busy}
              className="flex min-h-touch items-center rounded border border-hairline-strong px-4 text-sm transition-colors duration-state hover:bg-raised disabled:opacity-60"
            >
              {busy ? "Loading…" : "Load the books"}
            </button>
          </header>

          {loaded && (
            <p className="mb-4 rounded bg-accent-bg px-4 py-3 text-sm text-accent-text">
              Loaded {loaded}.
            </p>
          )}
          {error && (
            <div className="mb-4">
              <ErrorNotice error={error} />
            </div>
          )}

          <div className="mb-6 flex flex-wrap gap-3">
            <input
              type="search"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search the library…"
              aria-label="Search the library"
              className="min-h-touch flex-1 rounded border border-hairline bg-raised px-4"
            />
            <select
              value={state}
              onChange={(e) => setState(e.target.value)}
              aria-label="Filter by state"
              className="min-h-touch rounded border border-hairline bg-raised px-3"
            >
              <option value="">Every state</option>
              {states.map((s) => (
                <option key={s.slug} value={s.slug}>
                  {s.name} ({s.recipes})
                </option>
              ))}
            </select>
          </div>

          {loading && rows.length === 0 ? (
            <Loading label="Loading the library…" />
          ) : rows.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">Nothing here yet</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                {total === 0
                  ? "Press “Load the books” to read the vendored recipe books in."
                  : "Try a different search."}
              </p>
            </div>
          ) : (
            <ul className="grid gap-2">
              {rows.map((row) => (
                <li key={row.id} className="flex items-stretch gap-2">
                  <Link
                    href={`/recipes/library/${row.id}`}
                    className="block min-w-0 flex-1 rounded-lg bg-raised px-5 py-3 transition-colors duration-state hover:bg-sunken"
                  >
                    <div className="flex items-start justify-between gap-3">
                      <span className="min-w-0 font-medium">{row.displayName}</span>
                      <span className="shrink-0 text-sm text-ink-secondary">{row.state}</span>
                    </div>
                    <span className="text-xs text-ink-muted">
                      {row.categoryName} · {row.badge}
                    </span>
                  </Link>
                  <button
                    type="button"
                    onClick={() => remove(row)}
                    disabled={busy}
                    aria-label={`Take ${row.displayName} out of the library`}
                    className="flex min-h-touch min-w-touch shrink-0 items-center justify-center rounded-lg border border-hairline-strong text-sm text-ink-secondary transition-colors duration-state hover:bg-raised disabled:opacity-60"
                  >
                    ×
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </main>
    </div>
  );
}
