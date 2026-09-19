"use client";

import type { ReactNode } from "react";
import type { IngredientView } from "@/lib/api";
import { templeDay, unitLabel } from "@/lib/format";

/**
 * What the temple has recorded about the ingredient, including the facts no other screen shows
 * (Rajeev, Q-10: "other information that we collect regarding Ingredients but don't display anywhere
 * else").
 *
 * <p>Surveyed against every screen on 2026-09-19 before this was written, so the list is measured
 * rather than guessed. **Other names** (`aliases`, which since R-DUP-3 also holds every name merged
 * into this one) appear for food only inside `/ingredients`' editing box, never as something to read.
 * **Added on** (`createdAt`) appears nowhere. Category, stock unit, Ekadashi and the import label are on
 * the list, and are repeated here because this is the ingredient's own page and a page that left them
 * out would send the reader back to the list to learn what the thing is.
 *
 * <p>`supply` is deliberately not printed. Rajeev ruled on 2026-09-10 that the food/supply type drives
 * the backend and is not to be displayed; the page uses it only to send the back link to the right list.
 */
export function IngredientFacts({ ingredient }: { ingredient: IngredientView }) {
  return (
    <section className="card px-6 py-5" aria-labelledby="facts-heading">
      <h2 id="facts-heading" className="text-lg font-semibold text-ink">About this ingredient</h2>
      <dl className="mt-4 grid grid-cols-2 gap-x-8 gap-y-4 text-sm lg:grid-cols-3">
        <Detail label="Category">{ingredient.category}</Detail>
        <Detail label="Stock unit">{unitLabel(ingredient.unit)}</Detail>
        <Detail label="Ekadashi">
          {ingredient.ekadashiProhibited ? (
            // Blue, Ekadashi's colour everywhere: a classification, not a warning (T-227).
            <span className="rounded-control bg-info-bg px-2 py-1 text-xs font-semibold text-info">Prohibited</span>
          ) : (
            "Allowed"
          )}
        </Detail>
        <Detail label="Other names">
          {ingredient.aliases.length === 0 ? (
            <span className="text-ink-muted">None</span>
          ) : (
            ingredient.aliases.join(", ")
          )}
        </Detail>
        <Detail label="Added on">
          <span className="tabular-nums">{templeDay(ingredient.createdAt)}</span>
        </Detail>
      </dl>
    </section>
  );
}

function Detail({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-ink-secondary">{label}</dt>
      <dd className="mt-1 break-words text-ink">{children}</dd>
    </div>
  );
}
