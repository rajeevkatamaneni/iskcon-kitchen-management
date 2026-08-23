"use client";

import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type RecipeSearchResult } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { Loading } from "@/components/Loading";

/**
 * Recipe browse and search — the Recipes tab (E2-S13).
 *
 * <p>One box, over the temple's own recipes and the shared library together. A cook looking for a
 * dish does not know or care which of the two it is in, so the box does not ask and the page does
 * not explain itself. What differs is what a row offers: a library recipe the temple has not taken
 * carries a plus, one it already holds does not.
 *
 * <p>The category chips and the "show archived" tick that used to live here are gone. Archiving
 * would have become a disappearance — an archived recipe is off the default list by design, and its
 * Restore button lives on its own screen — so a search now returns archived recipes too, badged.
 * The capability stays; the control that explained it does not.
 */
export default function RecipesPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      {/* useSearchParams — what the list is showing lives in the address bar. */}
      <Suspense>
        <RecipesView />
      </Suspense>
    </RequireRole>
  );
}

/**
 * Long enough that a search is not fired at every keystroke of a word, short enough that the list
 * has settled by the time a person has stopped typing and looked up.
 */
const DEBOUNCE_MS = 200;

function RecipesView() {
  const router = useRouter();
  const params = useSearchParams();
  const { getToken } = useAuth();

  // The box is uncontrolled by the URL after the first render: typing writes to both, and the URL
  // entry is replaced rather than pushed, so nothing can drive the caret from outside and back does
  // not walk letter by letter through a word.
  const [search, setSearch] = useState(params.get("q") ?? "");
  const [results, setResults] = useState<RecipeSearchResult[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ApiError | null>(null);
  const [adding, setAdding] = useState<string | null>(null);

  // Every search is numbered, and a late answer to an earlier one is dropped. Without this a slow
  // response to "ma" can land after a fast one to "majjige" and repopulate the list with the wider
  // set, which reads as the filter running backwards.
  const latest = useRef(0);

  const run = useCallback(
    async (query: string) => {
      const mine = ++latest.current;
      try {
        const rows = await api.searchRecipes(query, await getToken());
        if (latest.current === mine) {
          setResults(rows);
          setError(null);
        }
      } catch (e) {
        if (latest.current === mine) setError(toApiError(e, "We couldn’t search your recipes."));
      } finally {
        if (latest.current === mine) setLoading(false);
      }
    },
    [getToken]
  );

  useEffect(() => {
    setLoading(true);
    const timer = setTimeout(() => run(search), DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [search, run]);

  function onType(value: string) {
    setSearch(value);
    const q = new URLSearchParams();
    if (value.trim()) q.set("q", value);
    router.replace(q.toString() ? `/recipes?${q}` : "/recipes");
  }

  async function add(row: RecipeSearchResult) {
    setAdding(row.id);
    setError(null);
    try {
      await api.importRecipe(row.id, await getToken());
      // The row keeps its place and loses its plus; nothing navigates, because a person adding three
      // recipes should not be thrown out of their search after the first.
      setResults((rows) =>
        rows.map((r) => (r.id === row.id ? { ...r, alreadyAdded: true } : r))
      );
    } catch (e) {
      setError(toApiError(e, "We couldn’t add that recipe."));
    } finally {
      setAdding(null);
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/recipes" />

      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <h1>Recipes</h1>
            <div className="flex flex-wrap gap-2">
              <Link
                href="/glossary"
                className="flex min-h-touch items-center rounded border border-hairline-strong px-4 text-sm transition-colors duration-state hover:bg-raised"
              >
                Glossary
              </Link>
              <Link
                href="/recipes/new"
                className="flex min-h-touch items-center rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover"
              >
                New recipe
              </Link>
            </div>
          </header>

          <input
            type="search"
            value={search}
            onChange={(e) => onType(e.target.value)}
            placeholder="Search recipes…"
            aria-label="Search recipes"
            className="mb-6 min-h-touch w-full rounded border border-hairline bg-raised px-4"
          />

          {error && (
            <div className="mb-4">
              <ErrorNotice error={error} />
            </div>
          )}

          {loading && results.length === 0 ? (
            <Loading label="Loading recipes…" />
          ) : results.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">No recipes found</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                {search ? "Try a different search." : "Recipes added to your temple will appear here."}
              </p>
            </div>
          ) : (
            /*
              Three across on a laptop and four on a wide screen, instead of two. At two columns a
              card was wider than anything in it: the name sat at one end and its category at the
              other with a hand's width of nothing between them, and a search for "palya" filled
              the screen with eight rows and a lot of paper. The name and its category now read as
              one stacked pair, which is what let the columns narrow.
            */
            <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4">
              {results.map((row) => (
                <li key={`${row.origin}-${row.id}`} className="flex items-stretch gap-2">
                  {/* A recipe opens on its own screen, which is where Edit and Delete live — a
                      layer over the results could only ever show the recipe, and reading one is
                      usually the step before changing it. The search rides along in the address so
                      the way back lands on these exact results rather than on all four hundred. */}
                  <Link
                    href={{
                      pathname: row.origin === "MINE" ? `/recipes/${row.id}` : `/recipes/library/${row.id}`,
                      query: search.trim() ? { q: search.trim() } : undefined,
                    }}
                    className="block min-w-0 flex-1 rounded-lg bg-raised px-5 py-4 text-left transition-colors duration-state hover:bg-sunken"
                  >
                    <div className="grid gap-0.5">
                      <span className="min-w-0 font-medium">{row.name}</span>
                      <span className="text-sm text-ink-secondary">
                        {/* The state, but only where the name does not already carry it — a row
                            reading "Sabudana Khichdi (Maharashtra) · Maharashtra" says it twice. */}
                        {row.origin === "LIBRARY" && row.showState ? row.state : row.categoryName}
                      </span>
                    </div>
                    <div className="mt-2 flex flex-wrap items-center gap-2">
                      {row.status === "ARCHIVED" && (
                        <span className="rounded-sm bg-sunken px-2 py-0.5 text-xs font-semibold text-ink-secondary">
                          Archived
                        </span>
                      )}
                      {row.sattvicOverridden && (
                        <span className="rounded-sm bg-warning-bg px-2 py-0.5 text-xs font-semibold text-warning">
                          Sattvic override
                        </span>
                      )}
                      {row.subtitle && (
                        <span className="truncate text-xs text-ink-muted">{row.subtitle}</span>
                      )}
                    </div>
                  </Link>

                  {/* The slot is always here, whether or not it holds a plus. A row that dropped
                      the button would otherwise stretch to fill the cell, and a list where some
                      cards are wider than others reads as broken rather than as informative.

                      The button itself is a sibling of the link and never nested inside it: its own
                      44px target, its own accessible name, its own focus stop. */}
                  <span className="flex w-touch shrink-0">
                    {row.origin === "LIBRARY" && !row.alreadyAdded && (
                      <button
                        type="button"
                        onClick={() => add(row)}
                        disabled={adding !== null}
                        aria-label={`Add ${row.name} to your recipes`}
                        className="flex min-h-touch w-full items-center justify-center rounded-lg border border-hairline-strong text-xl transition-colors duration-state hover:bg-raised disabled:opacity-60"
                      >
                        {adding === row.id ? "…" : "+"}
                      </button>
                    )}
                  </span>
                </li>
              ))}
            </ul>
          )}

        </div>
      </main>
    </div>
  );
}
