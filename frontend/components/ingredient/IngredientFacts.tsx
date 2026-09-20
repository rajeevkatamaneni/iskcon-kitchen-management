"use client";

import { useState, type ReactNode } from "react";
import { ErrorNotice } from "@/components/ErrorNotice";
import { api, toApiError, type ApiError, type IngredientView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { templeDay, unitLabel } from "@/lib/format";

/**
 * The one wording for the mark, used on every screen that shows it (T-402).
 *
 * <p>Consistency is the point of the constant. The same two words appear on the ingredients list as
 * a badge, here as the value of the Buying row, and inside the checkbox label below and on
 * `/ingredients/new` — so somebody who learns what "Not bought" means on one screen has learned it
 * everywhere. Nothing shortens it: two words fit in the narrowest place it appears.
 */
export const NOT_BOUGHT = "Not bought";

/**
 * The checkbox's own label, shared with `IngredientForm` so the create screen and this one ask the
 * same question in the same words. The examples are Rajeev's (water, ice) and the clause after the
 * dash is the consequence rather than a restatement — what a person needs to predict is that the
 * thing will never be suggested for buying again.
 */
export const NOT_BOUGHT_LABEL = "Not bought (water, ice — never on a shopping list)";

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
 *
 * <p><strong>`notBought` IS printed, and that is not a reversal of that ruling</strong> (T-402). The
 * two flags answer different questions and only one of them is visible in the product: the food/supply
 * split shows itself by which menu item the row lives under, so printing it repeats what the screen
 * already says. Whether the temple buys a thing shows itself nowhere — the consequence is a shopping
 * list line that is simply absent, with nothing on the list to say why — so the ingredient's own page
 * is the only place a person can find out.
 *
 * <p><strong>This component became editable for that one field.</strong> It is the only fact here that
 * has a route of its own to change it (`PATCH /ingredients/{id}/not-bought`), and it does not ride on
 * the PUT the list's editing row sends, so the ingredient's own page is where it is set. `canEdit` and
 * `onChanged` follow `PackSizes` and `MarketRate` beside it.
 */
export function IngredientFacts({
  ingredient,
  canEdit = false,
  onChanged,
}: {
  ingredient: IngredientView;
  /** Whether this role holds `MANAGE_BUYING_POLICY` — a Temple Admin. The API is still the guard. */
  canEdit?: boolean;
  onChanged?: () => void;
}) {
  const { getToken } = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function set(notBought: boolean) {
    setBusy(true);
    setError(null);
    try {
      await api.setIngredientNotBought(ingredient.id, notBought, await getToken());
      onChanged?.();
    } catch (e) {
      setError(toApiError(e, "We couldn’t save that."));
    } finally {
      setBusy(false);
    }
  }

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
        {/*
          Plain text in both states, in the ink the other values use, because this is a neutral fact
          about the ingredient and not a warning about it. Amber means something is low, wrong or
          overdue; red means act now; green is the success of the reader's own action. A temple that
          has decided it does not buy water has nothing wrong with it. The Ekadashi cell above is
          badged because blue is that flag's own colour across the whole product, which is a
          consistency argument rather than a severity one, so it does not carry over to this row.
        */}
        <Detail label="Buying">
          {ingredient.notBought ? NOT_BOUGHT : "Bought when needed"}
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

      {/*
        Under the grid rather than inside a cell of it, so the six facts stay six even cells — two
        rows of three on a wide screen, three rows of two on a narrow one — and a control's height
        does not stretch the row it sits in.

        It saves on the tick, with no separate Save. Rajeev's 2026-09-10 ruling against a one-click
        flag ("Ingredients don't go in and out of Ekadashi restriction EVER… there is no reason to
        change it") was about a button hidden in a table somebody is scanning; this is a labelled
        checkbox on the ingredient's own page, which is the deliberate place, and a single boolean
        behind an editor with its own Save would be three clicks to change one bit. If he wants a
        confirm step here, it is the same argument as his and worth making again.
      */}
      {canEdit && (
        <div className="mt-5 border-t border-hairline pt-4">
          <label className="flex items-center gap-2 text-sm text-ink">
            <input
              type="checkbox"
              checked={ingredient.notBought}
              disabled={busy}
              onChange={(e) => set(e.target.checked)}
              className="h-5 w-5 rounded-sm border-hairline-strong accent-accent"
            />
            <span>{NOT_BOUGHT_LABEL}</span>
          </label>
        </div>
      )}

      {error && <div className="mt-4"><ErrorNotice error={error} /></div>}
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
