"use client";

import { Suspense, useCallback, useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { BackToRecipes } from "@/components/BackToRecipes";
import { Tooltip } from "@/components/ds/Tooltip";
import { BusyPot, Loading } from "@/components/Loading";
import { api, toApiError, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

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

  async function add() {
    setBusy(true);
    setActionError(null);
    try {
      const { id: mine } = await api.importRecipe(id, await getToken());
      router.push(`/recipes/${mine}`);
    } catch (e) {
      setActionError(toApiError(e, "We couldn’t add that recipe."));
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
              className="flex min-h-touch items-center rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60"
            >
              {busy ? (
                <span className="inline-flex items-center gap-2">
                  <BusyPot />
                  Adding…
                </span>
              ) : (
                "Add"
              )}
            </button>
          )}

          <Tooltip text="Add this recipe to your temple before editing it.">
            <span
              role="button"
              aria-disabled="true"
              tabIndex={0}
              className="flex min-h-touch cursor-not-allowed items-center rounded border border-hairline-strong px-4 text-sm text-ink-muted"
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
          <Fact label="Indicative cost" value={`₹${recipe.indicativeCost.toLocaleString("en-IN")}`} />
        )}
      </dl>

      {recipe.tags.length > 0 && (
        <ul className="mt-5 flex flex-wrap gap-2">
          {recipe.tags.map((tag) => (
            <li key={tag} className="rounded-sm bg-sunken px-2 py-0.5 text-xs text-ink-secondary">
              {tag}
            </li>
          ))}
        </ul>
      )}

      <section className="mt-8" aria-labelledby="ingredients">
        <h2 id="ingredients" className="text-lg">Ingredients</h2>
        <ul className="mt-3 grid gap-1">
          {recipe.ingredients.map((line, i) => (
            <li key={`${line.name}-${i}`} className="flex justify-between gap-4 border-b border-hairline py-2">
              <span>{line.name}</span>
              <span className="shrink-0 tabular-nums text-ink-secondary">{line.qty}</span>
            </li>
          ))}
        </ul>
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
      {recipe.cateringNote && <Note heading="Catering" body={recipe.cateringNote} />}

      {recipe.serveWith.length > 0 && (
        <Note heading="Serve with" body={recipe.serveWith.join(" · ")} />
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
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">{children}</div>
      </main>
    </div>
  );
}
