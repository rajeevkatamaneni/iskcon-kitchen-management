"use client";

import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { ButtonLink } from "@/components/ds/ButtonLink";
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

  /*
    How many ingredients an import has created and nobody has saved since (T-119).

    Fetched here rather than counted from the catalogue, because this screen holds no catalogue —
    one integer instead of several hundred rows for a sentence. Null until it has been asked, so
    nothing flashes "0 ingredients" on the way in, and re-asked after every import below, because
    the import is the act that changes the number and the person who just pressed the plus is
    exactly the person the message is for.

    A failure here is swallowed on purpose. It is context beside the real work of the screen, and a
    search that works should not be interrupted by an error about a count.
  */
  const [addedByImport, setAddedByImport] = useState<number | null>(null);
  const refreshAddedByImport = useCallback(async () => {
    try {
      const { count } = await api.countIngredientsAddedByImport(await getToken());
      setAddedByImport(count);
    } catch {
      setAddedByImport(null);
    }
  }, [getToken]);

  useEffect(() => {
    refreshAddedByImport();
  }, [refreshAddedByImport]);

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
      // The import may have just created the ingredients the message counts, so ask again rather
      // than leave the number describing the catalogue as it was before the button was pressed.
      refreshAddedByImport();
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
                className="btn btn-primary flex min-h-touch items-center px-5 transition-colors duration-state"
              >
                New recipe
              </Link>
            </div>
          </header>

          {/*
            Standing context, not a flash: it does not dismiss, is not conditional on anything, and
            says the same thing on every visit — which is why it is an `InlineNotice` with
            `tone="warning"` rather than the confirmation pattern. `InlineNotice` refuses
            `autoDismiss` on `warning` by construction, and this is exactly the case that rule was
            written for: there is something left in it for the reader to do.

            Why it is on Recipes rather than on Ingredients, where the flag is actually set: import
            is the act that creates the unflagged rows, and this is the screen it is started from.
            Somebody who never imports never has the problem.

            It sits above the search box, not between the box and the results, because it is about
            the catalogue rather than about what the box just found — under the field it would read
            as something the search had turned up.

            The words are Rajeev's own, approved 2026-09-08 under D-18. Do not reword them.
          */}
          <div className="mb-6">
            <InlineNotice tone="warning" title="Imported ingredients arrive unflagged for Ekadashi">
              A recipe import adds any ingredient this temple doesn’t have, and can’t tell which are
              restricted on a fast day — so it flags none. Set the Ekadashi flag on each yourself, or
              the meal planner will allow them onto an Ekadashi menu.
            </InlineNotice>
          </div>

          {/*
            The same fact as the ingredients screen carries, said on the screen the import is
            started from (T-119) — and, unlike the warning above it, conditional: it appears only
            while the count is above zero, so somebody who has never imported never sees it and
            somebody who has reviewed everything stops seeing it. That is what "seen after an import
            rather than always" means here, and it is why the number is re-asked after every plus.

            It sits under the Ekadashi warning rather than above it because that one is the graver
            of the two — an unflagged ingredient reaches an Ekadashi menu, while an unreviewed one
            is merely unreviewed — and above the search box for the reason the warning gives: both
            are about the catalogue, not about what the box has just found.

            `info`, not `warning`. `DESIGN_SYSTEM.md:115` reserves the semantic colours for
            something genuinely low, wrong, overdue or complete, and an ingredient an import created
            is none of those.
          */}
          {addedByImport !== null && addedByImport > 0 && (
            <div className="mb-6">
              <InlineNotice
                tone="info"
                title={
                  addedByImport === 1
                    ? "1 ingredient was added by a recipe import"
                    : `${addedByImport} ingredients were added by a recipe import`
                }
                action={
                  <ButtonLink variant="secondary" size="sm" href="/ingredients?show=added-by-import">
                    Review them
                  </ButtonLink>
                }
              >
                An import creates any ingredient a recipe needs that this temple doesn’t already
                have, and picks its category and unit itself. Check each one on the Ingredients
                page — saving an ingredient clears its label.
              </InlineNotice>
            </div>
          )}

          <input
            type="search"
            value={search}
            onChange={(e) => onType(e.target.value)}
            placeholder="Search recipes…"
            aria-label="Search recipes"
            className="mb-6 min-h-touch w-full rounded-control border border-hairline px-4"
          />

          {error && (
            <div className="mb-4">
              <ErrorNotice error={error} />
            </div>
          )}

          {loading && results.length === 0 ? (
            <Loading label="Loading recipes…" />
          ) : results.length === 0 ? (
            <div className="card px-6 py-14 text-center">
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
                /*
                  The tile is the whole cell, so the row of them sits squarely under the search
                  field: flush at the leading edge, flush at the trailing edge, and the grid's own
                  gap — one value, at every width — between them. It used to be the link that
                  carried the card, with the plus reserved beside it in the same cell, which left a
                  hand's width of nothing at the end of every row and made the gaps between tiles
                  read as wider than the gaps within them.
                */
                <li
                  key={`${row.origin}-${row.id}`}
                  className="card flex items-stretch transition-[transform,box-shadow,background-color] duration-state ease-out hover:-translate-y-0.5 hover:bg-sunken hover:shadow-lift"
                >
                  {/* A recipe opens on its own screen, which is where Edit and Delete live — a
                      layer over the results could only ever show the recipe, and reading one is
                      usually the step before changing it. The search rides along in the address so
                      the way back lands on these exact results rather than on all four hundred. */}
                  <Link
                    href={{
                      pathname: row.origin === "MINE" ? `/recipes/${row.id}` : `/recipes/library/${row.id}`,
                      query: search.trim() ? { q: search.trim() } : undefined,
                    }}
                    className="block min-w-0 flex-1 rounded-lg px-5 py-4 text-left"
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
                      {row.subtitle && (
                        <span className="truncate text-xs text-ink-muted">{row.subtitle}</span>
                      )}
                    </div>
                  </Link>

                  {/* The gutter is always here, whether or not it holds a plus, so every tile
                      gives its words the same measure and a list of them reads as one thing.

                      The button itself is a sibling of the link and never nested inside it: its own
                      44px target, its own accessible name, its own focus stop. */}
                  <span className="flex w-touch shrink-0 items-start pe-2 pt-4">
                    {row.origin === "LIBRARY" && !row.alreadyAdded && (
                      <button
                        type="button"
                        onClick={() => add(row)}
                        disabled={adding !== null}
                        aria-label={`Add ${row.name} to your recipes`}
                        className="flex min-h-touch w-full items-center justify-center rounded-lg border border-hairline-strong text-xl transition-colors duration-state hover:bg-canvas disabled:opacity-60"
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
