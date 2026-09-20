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
import { ButtonLink } from "@/components/ds/ButtonLink";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { BusyPot, Loading } from "@/components/Loading";
import {
  api,
  toApiError,
  type ApiError,
  type ImportCloseMatchDecision,
  type ImportCloseMatchView,
} from "@/lib/api";
import { ImportCloseMatches, closeMatchesFrom } from "@/components/ImportCloseMatches";
import { NOT_BOUGHT } from "@/components/ingredient/IngredientFacts";
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
    The copy that worked, and the one thing about it the person has to be told (T-427).

    Null until a copy has withheld something, which is most copies. Set only when the copy came back
    with names in `notBoughtNotApplied`: the temple's own ingredients the library marks "Not bought"
    that this copy deliberately left exactly as the temple has them, because setting a buying policy
    is the Temple Admin's alone and copying a recipe is not. It carries the new recipe's own id, so
    the notice can hand back the navigation this screen is holding — see `add` below.
  */
  const [kept, setKept] = useState<{ recipeId: string; names: string[] } | null>(null);

  /*
    The copy asks first: if any ingredient name here is only close to one the temple has, the dialog
    lists them all and nothing is copied until each is answered. With none it copies at once, as it
    always did, and opens the temple's new recipe.

    Unless it withheld something (T-427), in which case it stops here instead of navigating and the
    notice below says what, with the way on beside it. The reason for holding still: this screen's
    success IS the navigation, so a message shown on the way out is a message nobody reads. The
    alternative considered was carrying the names to `/recipes/[id]` and saying them there; that
    needs a third screen this task does not own, and it would say them one step away from the button
    that caused them. So the person reads it where they pressed, then chooses to move on. It costs
    one click, and only on the copies that have something to say.
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
      const { id: mine, notBoughtNotApplied } = await api.importRecipe(id, token);
      if (notBoughtNotApplied.length === 0) {
        router.push(`/recipes/${mine}`);
        return;
      }
      setKept({ recipeId: mine, names: notBoughtNotApplied });
      setBusy(false);
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
      const { id: mine, notBoughtNotApplied } = await api.importRecipe(id, await getToken(), decisions);
      if (notBoughtNotApplied.length === 0) {
        router.push(`/recipes/${mine}`);
        return;
      }
      // The dialog is closed by hand here, where it used to be unmounted by the navigation.
      setMatches(null);
      setKept({ recipeId: mine, names: notBoughtNotApplied });
      setBusy(false);
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
          {recipe.alreadyAdded || kept ? (
            // `kept` means the copy went through and this screen stayed put to say something about
            // it, so the button has to stop offering a copy the server would now refuse.
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

      {/*
        What the copy left alone, said under the button that made the copy (T-427).

        Neutral, not green and not amber. `InlineNotice`'s `info` is the neutral wash
        (`bg-sunken`/`text-ink`), and the reasoning is the one `IngredientFacts` already writes
        beside this very setting: a temple that buys its own water has nothing wrong with it. Green
        is reserved for the success of the reader's own action and this is not the success, it is
        the footnote to one; amber says take care and there is nothing here to take care about
        today; red says act now and nothing is urgent. It does not auto-dismiss, because there is
        still something in it for the reader to do.

        The action gives back the navigation this screen normally does on a successful copy. It is
        the only difference between this notice and the one on the Recipes list, which needs no such
        button because that screen never navigates.
      */}
      {kept && (
        <div className="mt-4">
          <InlineNotice
            tone="info"
            title={keptTitle(kept.names)}
            action={
              <ButtonLink variant="secondary" size="sm" href={`/recipes/${kept.recipeId}`}>
                Open the recipe
              </ButtonLink>
            }
          >
            {keptBody(kept.names)}
          </InlineNotice>
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

/*
  The words for what a copy left alone (T-427), said the same way on both screens that copy a
  recipe. The Recipes list carries a character-for-character duplicate of these three functions and
  a test renders both screens and compares the two strings, because a shared module for them would
  be a file outside this task's contract. If you are adding a third copy screen, lift them.

  Why these words. The title leads with the consequence, because that is the only part the reader
  has to act on: their water is still going to be bought. The body gives the cause and the next
  step, and names the setting exactly as the Ingredients screen labels it — `NOT_BOUGHT` is that
  screen's own constant, imported rather than retyped, so the two cannot drift. It names the role
  rather than telling the reader to go and do it, because most people copying a recipe are Kitchen
  Managers and cannot: copying is `MANAGE_RECIPES`, the buying policy is `MANAGE_BUYING_POLICY`.
  One sentence that works whichever of the two is reading beats a sentence that is wrong for one.

  There is no "Open Ingredients" button for the same reason: for the commonest reader it would be a
  button to a screen where they cannot do the thing it seems to promise. "Ingredients" as a word is
  enough to find it, and the standing rule is to name the screen, never a path through the menu —
  a temple can rearrange its own menu now.
*/
function keptTitle(names: string[]): string {
  return `${nameList(names)} ${names.length === 1 ? "stays" : "stay"} on your shopping list`;
}

function keptBody(names: string[]): string {
  const it = names.length === 1 ? "it" : "them";
  return `The library marks ${it} “${NOT_BOUGHT}”. A Temple Admin can change that in Ingredients.`;
}

/** "Water" · "Water and Ghee" · "Ghee, Rock salt and Water", in the order the server sent. */
function nameList(names: string[]): string {
  if (names.length < 2) return names.join("");
  return `${names.slice(0, -1).join(", ")} and ${names[names.length - 1]}`;
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
