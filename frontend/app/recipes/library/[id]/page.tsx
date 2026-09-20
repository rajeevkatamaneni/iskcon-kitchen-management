"use client";

import { Screen } from "@/components/ds/Screen";
import { Suspense, useCallback, useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { BackToRecipes } from "@/components/BackToRecipes";
import { Tooltip } from "@/components/ds/Tooltip";
import { BusyPot, Loading } from "@/components/Loading";
import {
  api,
  toApiError,
  type ApiError,
  type ImportCloseMatchDecision,
  type ImportCloseMatchView,
} from "@/lib/api";
import { ImportCloseMatches, closeMatchesFrom } from "@/components/ImportCloseMatches";
import { withPreparation } from "@/components/RecipePeek";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { batchCost } from "@/lib/format";
import { recipeTagLabels } from "@/lib/vaishnava-day";
import { RULED_TABLE, THEAD, TR, TH_PRIMARY, TD_PRIMARY, TH_FIXED, TD_FIXED_NUM } from "@/components/ds/table";

/**
 * A library recipe in full (E2-S14).
 *
 * <p>The same screen as a temple's own recipe with two differences, both about what may be done to
 * it. There is an Add button, because this one is not yet theirs. And Edit is unavailable until it
 * is — not `disabled`, which would take no focus and fire no pointer events, but `aria-disabled`
 * inside a tooltip, so the reason reaches a mouse, a keyboard and a thumb.
 */
export default function LibraryRecipePage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF", "SUPER_ADMIN"]}>
      {/* The back link reads the search out of the address, and that needs a boundary. */}
      <Suspense>
        <LibraryRecipeView />
      </Suspense>
    </RequireRole>
  );
}

function LibraryRecipeView() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const { getToken } = useAuth();

  const fetchRecipe = useCallback((t: string | undefined) => api.getLibraryRecipe(id, t), [id]);
  const { data: recipe, error, loading } = useAuthedQuery(fetchRecipe);

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);

  // "Did you mean …?" answers the copy is waiting on (Q-11, T-287), and a refusal of the answered
  // copy that is not about a close match, shown inside the dialog.
  const [matches, setMatches] = useState<ImportCloseMatchView[] | null>(null);
  const [askingError, setAskingError] = useState<ApiError | null>(null);

  /*
    The copy asks first: if any ingredient name here is only close to one the temple has, the dialog
    lists them all and nothing is copied until each is answered. With none it copies at once, as it
    always did, and opens the temple's new recipe.
  */
  async function add() {
    setBusy(true);
    setActionError(null);
    try {
      const token = await getToken();
      const close = await api.importCloseMatches(id, token);
      if (close.length > 0) {
        setAskingError(null);
        setMatches(close);
        setBusy(false);
        return;
      }
      const { id: mine } = await api.importRecipe(id, token);
      router.push(`/recipes/${mine}`);
    } catch (e) {
      // The catalogue can change between asking and copying; a refusal naming close matches opens
      // the dialog on them.
      const close = closeMatchesFrom(e);
      if (close) setMatches(close);
      else setActionError(toApiError(e, "We couldn’t add that recipe."));
      setBusy(false);
    }
  }

  async function addAnswered(decisions: ImportCloseMatchDecision[]) {
    setBusy(true);
    setAskingError(null);
    try {
      const { id: mine } = await api.importRecipe(id, await getToken(), decisions);
      router.push(`/recipes/${mine}`);
    } catch (e) {
      const close = closeMatchesFrom(e);
      if (close) setMatches(close);
      else setAskingError(toApiError(e, "We couldn’t add that recipe."));
      setBusy(false);
    }
  }

  if (loading) return <Chrome><Loading /></Chrome>;
  if (error) return <Chrome><ErrorNotice error={error} /></Chrome>;
  if (!recipe) return null;

  return (
    <Chrome>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <BackToRecipes />

        <div className="flex items-center gap-2">
          {recipe.alreadyAdded ? (
            <span className="text-sm text-ink-secondary">Already in your recipes</span>
          ) : (
            <button
              type="button"
              onClick={add}
              disabled={busy}
              className="btn btn-primary flex min-h-touch items-center px-5 transition-colors duration-state disabled:opacity-60"
            >
              {busy ? (
                <span className="inline-flex items-center gap-2">
                  <BusyPot />
                  Adding…
                </span>
              ) : (
                "Add to my recipes"
              )}
            </button>
          )}

          <Tooltip text="Add this recipe to your temple before editing it.">
            <span
              role="button"
              aria-disabled="true"
              tabIndex={0}
              className="flex min-h-touch cursor-not-allowed items-center rounded-control border border-hairline-strong px-4 text-sm text-ink-muted"
            >
              Edit
            </span>
          </Tooltip>
        </div>
      </div>

      {actionError && (
        <div className="mt-4">
          <ErrorNotice error={actionError} />
        </div>
      )}

      <header className="mt-6">
        <h1>{recipe.displayName}</h1>
        {recipe.subtitle && <p className="mt-1 text-ink-secondary">{recipe.subtitle}</p>}
        <p className="mt-2 text-sm text-ink-muted">
          {[recipe.categoryName, recipe.state, recipe.region, recipe.badge]
            .filter(Boolean)
            .join(" · ")}
        </p>
      </header>

      <dl className="mt-6 grid grid-cols-2 gap-4 sm:grid-cols-4">
        <Fact label="Makes" value={recipe.yieldText} />
        {recipe.perHeadText && <Fact label="Per person" value={recipe.perHeadText} />}
        {recipe.indicativeCost != null && (
          // The cost of one batch, and the batch named beside it (T-231): "₹8,000 per batch (270 L)".
          <Fact label="Rough cost" value={batchCost(recipe.indicativeCost, recipe.yieldQty, recipe.yieldUnit)} />
        )}
      </dl>

      {recipe.tags.length > 0 && (
        <ul className="mt-5 flex flex-wrap gap-2">
          {recipeTagLabels(recipe.tags).map((tag) => (
            <li key={tag} className="rounded-control bg-sunken px-2 py-0.5 text-xs text-ink-secondary">
              {tag}
            </li>
          ))}
        </ul>
      )}

      <section className="mt-8" aria-labelledby="ingredients">
        <h2 id="ingredients" className="text-lg">Ingredients</h2>
        {/* The same ruled table a temple's own recipe uses (app/recipes/[id]), column for column:
            Ingredient as the primary flexible column, Quantity as a fixed figure (reading left, like every column since T-236).
            It was a plain list, so the same dish read two different ways depending on whether the
            temple had added it yet (Rajeev, Decisions Desk, 2026-09-18). The library's quantity
            arrives as text already written ("2 Kg"), so it is printed as it comes. The name carries
            its preparation the way a temple's own recipe does, "Green chilli · slit" — the book has
            always had one and this screen printed the bare name until T-401. */}
        <div className="table-wrap mt-3 overflow-x-auto">
          <table className={RULED_TABLE}>
            <thead className={THEAD}>
              <tr>
                <th className={TH_PRIMARY}>Ingredient</th>
                <th className={TH_FIXED}>Quantity</th>
              </tr>
            </thead>
            <tbody>
              {recipe.ingredients.map((line, i) => (
                <tr key={`${line.name}-${i}`} className={TR}>
                  <td className={TD_PRIMARY}>{withPreparation(line.name, line.prep)}</td>
                  <td className={TD_FIXED_NUM}>{line.qty}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="mt-8" aria-labelledby="method">
        <h2 id="method" className="text-lg">Method</h2>
        <ol className="mt-3 grid gap-3">
          {recipe.method.map((step, i) => (
            <li key={i} className="flex gap-3">
              <span className="shrink-0 tabular-nums text-ink-muted">{i + 1}</span>
              <span className="max-w-prose">{step}</span>
            </li>
          ))}
        </ol>
      </section>

      {recipe.why && <Note heading="Why this dish" body={recipe.why} />}
      {recipe.noteStart && <Note heading="Start" body={recipe.noteStart} />}
      {recipe.noteVessel && <Note heading="Vessel" body={recipe.noteVessel} />}
      {recipe.noteSeason && <Note heading="Season" body={recipe.noteSeason} />}
      {/* No "Catering" note: catering is out of the product (E4-S15), removed from every recipe
          screen on 2026-09-18 at Rajeev's say. The library keeps the value; nobody is shown it. */}

      {recipe.serveWith.length > 0 && (
        <Note heading="Serve with" body={recipe.serveWith.join(" · ")} />
      )}

      {matches && (
        <ImportCloseMatches
          recipeName={recipe.displayName}
          matches={matches}
          busy={busy}
          error={askingError}
          onAdd={addAnswered}
          onCancel={() => setMatches(null)}
        />
      )}
    </Chrome>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs uppercase tracking-eyebrow text-ink-muted">{label}</dt>
      <dd className="mt-1">{value}</dd>
    </div>
  );
}

function Note({ heading, body }: { heading: string; body: string }) {
  return (
    <section className="mt-6">
      <h2 className="text-xs uppercase tracking-eyebrow text-ink-muted">{heading}</h2>
      <p className="mt-1 max-w-prose text-ink-secondary">{body}</p>
    </section>
  );
}

function Chrome({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/recipes" />
      {/* The shared page frame, so this page starts where every other screen does. One wrapper
          inside it keeps this page's own spacing between its blocks. */}
      <main className="min-w-0 flex-1">
        <Screen>
          <div>{children}</div>
        </Screen>
      </main>
    </div>
  );
}
