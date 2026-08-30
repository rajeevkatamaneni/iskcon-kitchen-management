"use client";

import { useCallback, useEffect } from "react";
import { Badge } from "@/components/ds/Badge";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Loading } from "@/components/Loading";
import { api, type MasterRecipeDetail, type RecipeDetail } from "@/lib/api";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * A recipe read over whatever screen you were on, and closed to land you back on it.
 *
 * <p>Deciding to cook something means reading it first, and reading it used to mean leaving — the
 * planner, or a page of search results you had typed your way to — and finding your way back.
 * Neither of those screens is finished with you: the planner still has a day to build, and search
 * results are a place you are standing rather than a page you arrived at. So the recipe comes to
 * the screen instead.
 *
 * <p>Escape closes it, and so does the backdrop, and so does the X. A layer with one way out is a
 * trap, and this one opens over work in progress.
 */
export function RecipePeek({
  recipeId,
  name,
  origin = "MINE",
  onClose,
}: {
  recipeId: string;
  /** What the screen underneath called it, so the heading is right before the fetch lands. */
  name?: string;
  /** Whose recipe this is. The library's are read the same way and read the same. */
  origin?: "MINE" | "LIBRARY";
  onClose: () => void;
}) {
  const load = useCallback(
    (token?: string) =>
      origin === "LIBRARY" ? api.getLibraryRecipe(recipeId, token) : api.getRecipe(recipeId, token),
    [recipeId, origin]
  );
  const { data: raw, error, loading } = useAuthedQuery<RecipeDetail | MasterRecipeDetail>(load);
  const data = raw ? asReadable(raw) : null;

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") onClose();
    }
    document.addEventListener("keydown", onKey);
    // The page behind must not scroll under the layer — a reader who scrolls the recipe and finds
    // the planner has moved has lost the place they were coming back to.
    const previous = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = previous;
    };
  }, [onClose]);

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={data?.name ?? name ?? "Recipe"}
      className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-ink/40 p-4 sm:p-8"
      onClick={onClose}
    >
      <div
        // The layer itself does not close when it is pressed; only the ground around it does.
        onClick={(event) => event.stopPropagation()}
        className="w-full max-w-3xl rounded-lg bg-canvas shadow-overlay backdrop-blur-surface"
      >
        <header className="flex items-start gap-4 border-b border-hairline px-6 py-4">
          <div className="min-w-0 flex-1">
            <h2 className="truncate text-xl font-semibold text-ink">{data?.name ?? name ?? "Recipe"}</h2>
            {data && (
              <p className="mt-1 text-sm text-ink-secondary">
                {data.categoryName}
                {data.yieldText ? ` · Makes ${data.yieldText}` : ""}
              </p>
            )}
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close the recipe"
            className="flex min-h-touch min-w-touch items-center justify-center rounded text-ink-secondary transition-colors duration-state hover:bg-sunken hover:text-ink"
          >
            <i className="ti ti-x text-lg" aria-hidden="true" />
          </button>
        </header>

        <div className="grid gap-6 px-6 py-5">
          {loading && !data && <Loading label="Loading the recipe…" />}
          {error && <ErrorNotice error={error} />}

          {data && (
            <>
              {(data.badges.length > 0 || data.tags.length > 0) && (
                <div className="flex flex-wrap gap-2">
                  {data.badges.map((badge) => (
                    <Badge key={badge} tone="success">
                      {badge}
                    </Badge>
                  ))}
                  {data.tags.map((tag) => (
                    <Badge key={tag}>{tag}</Badge>
                  ))}
                </div>
              )}

              <section className="grid gap-2">
                <h3 className="text-sm font-semibold uppercase tracking-wide text-ink-secondary">
                  Ingredients
                </h3>
                <ul className="grid gap-1">
                  {data.ingredients.map((line, i) => (
                    <li key={`${line.name}-${i}`} className="flex justify-between gap-4 text-ink">
                      <span>{line.name}</span>
                      <span className="tabular-nums text-ink-secondary">{line.quantity}</span>
                    </li>
                  ))}
                </ul>
              </section>

              {data.method.length > 0 && (
                <section className="grid gap-2">
                  <h3 className="text-sm font-semibold uppercase tracking-wide text-ink-secondary">
                    Method
                  </h3>
                  {data.method.length === 1 ? (
                    <p className="whitespace-pre-wrap text-ink">{data.method[0]}</p>
                  ) : (
                    <ol className="grid list-decimal gap-1 pl-5 text-ink">
                      {data.method.map((step, i) => (
                        <li key={i}>{step}</li>
                      ))}
                    </ol>
                  )}
                </section>
              )}

              {data.notes.length > 0 && (
                <section className="grid gap-2">
                  <h3 className="text-sm font-semibold uppercase tracking-wide text-ink-secondary">
                    Notes
                  </h3>
                  {data.notes.map((note, i) => (
                    <p key={i} className="whitespace-pre-wrap text-ink-secondary">
                      {note}
                    </p>
                  ))}
                </section>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}

/**
 * One shape for two kinds of recipe.
 *
 * <p>A temple's own recipe and a library one are different records — one carries quantities as
 * numbers in a unit, the other as the phrases its book printed — and a person reading either is
 * doing exactly the same thing. So they are flattened to what a reader needs before the layer draws
 * anything, and the layer knows about one of them.
 */
function asReadable(recipe: RecipeDetail | MasterRecipeDetail) {
  if ("ingredients" in recipe && recipe.ingredients.some((line) => "qty" in line)) {
    const master = recipe as MasterRecipeDetail;
    return {
      name: master.displayName || master.name,
      categoryName: master.categoryName,
      yieldText: master.yieldText,
      badges: [master.badge, master.state].filter(Boolean) as string[],
      tags: master.tags,
      ingredients: master.ingredients.map((line) => ({ name: line.name, quantity: line.qty })),
      method: master.method,
      notes: [master.why, master.cateringNote, master.noteStart, master.noteVessel, master.noteSeason]
        .filter(Boolean) as string[],
    };
  }

  const mine = recipe as RecipeDetail;
  return {
    name: mine.name,
    categoryName: mine.categoryName,
    yieldText: `${Number(mine.baseYieldQty).toLocaleString("en-IN")} ${unitLabel(mine.baseYieldUnit)}${
      mine.yieldNote ? ` — ${mine.yieldNote}` : ""
    }`,
    badges: mine.fastingCompatible ? ["Suits a fasting day"] : [],
    tags: [mine.regionTag, ...mine.tags].filter(Boolean) as string[],
    ingredients: mine.ingredients.map((line) => ({
      name: line.ingredientName,
      quantity: `${Number(line.quantity).toLocaleString("en-IN")} ${unitLabel(line.unit)}`,
    })),
    method: mine.method ? [mine.method] : [],
    notes: mine.notes ? [mine.notes] : [],
  };
}

const UNIT_LABEL: Record<string, string> = {
  SERVINGS: "servings",
  LITRES: "L",
  KG: "kg",
  GM: "gm",
  L: "L",
  ML: "ml",
  PIECES: "pieces",
};

/** How a unit is written where a person reads it. */
export function unitLabel(unit: string | null | undefined): string {
  if (!unit) return "";
  return UNIT_LABEL[unit] ?? unit.toLowerCase();
}
