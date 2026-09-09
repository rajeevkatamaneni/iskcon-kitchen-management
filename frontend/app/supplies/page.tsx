"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { EmptyState } from "@/components/ds/EmptyState";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { splitAliases } from "@/components/IngredientForm";
import { api, toApiError, type ApiError, type IngredientView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";
import { FOOD_UNITS, unitLabel } from "@/lib/format";
import { TABLE, TD_ACTIONS, TD_TEXT, THEAD, TH_ACTIONS, TH_TEXT, TR, ACTIONS_ROW, WRAP } from "@/components/ds/table";
import { Button } from "@/components/ds/Button";

/**
 * Supplies — the half of the ingredient catalogue that is not food (T-089).
 *
 * <p><strong>The rule that decides what belongs here is consumption, and it is Rajeev's</strong>,
 * taken on 2026-09-08 against a proposal of *repaired versus replaced* which he rejected: a plastic
 * stool is not repairable and that does not make it a supply. So — anything **consumed by use**
 * that is not a food ingredient is a supply, and anything not consumed is equipment:
 *
 * <ul>
 *   <li>LPG, kerosene, cleaning liquid, bulbs, brooms — used up by use — <em>here</em>.
 *   <li>Ladders, extension boxes, plastic stools — not consumed — <em>Equipment</em>.
 * </ul>
 *
 * <p><strong>There is no supplies table, and this screen did not need a migration.</strong> D-1
 * settled in V99 that a supply is an `ingredients` row carrying `is_supply`, because a leaf plate
 * is bought from a vendor, received, stored, issued and reordered exactly as rice is — a parallel
 * table would have duplicated the whole inventory chain to express one boolean. What Rajeev asked
 * for is a menu item, which is a fact about navigation rather than about storage. So this screen
 * reads the same `GET /api/v1/ingredients` every other screen reads and takes the other half of it,
 * and every supply a temple has already flagged is on it the moment it deploys, with nothing
 * re-entered and no row rewritten.
 *
 * <p><strong>What is deliberately NOT here</strong>, because it would turn D-1's flag into the
 * second catalogue it refused: nothing filters supplies out of the inventory, ingredient-request,
 * purchase-order, in-kind-donation or vendor-supply pickers. Exactly one picker filters — the
 * recipe one, because a mop is not an ingredient of anything — and `RecipeService` refuses a supply
 * on a recipe line with KMS-400127 for the raw POST that never went through a picker.
 *
 * <p>Simpler than `/ingredients` by two things, both on purpose. There is no Ekadashi column: a
 * fasting rule has nothing to say about hand soap. And there is no "added by an import" filter: an
 * import creates the ingredients a recipe names, and a recipe never names a broom.
 */
export default function SuppliesPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      {/* useSearchParams — for the confirmation a new supply comes back with — needs a boundary. */}
      <Suspense>
        <SuppliesView />
      </Suspense>
    </RequireRole>
  );
}

function SuppliesView() {
  const { getToken } = useAuth();
  const { data, error, loading, reload } = useAuthedQuery(api.listIngredients);
  const supplies = (data ?? []).filter((i) => i.supply);

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [editing, setEditing] = useState<string | null>(null);

  // Adding happens on /supplies/new and ends back here, so the confirmation travels in the URL.
  // Captured behind a ref because setting it re-renders, and a router object that is new on each
  // render would otherwise turn this effect into a loop — the exact fault found on Ingredients.
  const router = useRouter();
  const params = useSearchParams();
  const added = params.get("added");
  const [flash, setFlash] = useState<string | null>(null);
  const captured = useRef(false);
  useEffect(() => {
    if (captured.current || !added) return;
    captured.current = true;
    setFlash(added);
    router.replace("/supplies");
  }, [added, router]);

  // Let the banner stand, then clear itself. Keyed on `flash` so stripping the param above does not
  // cut the timer short.
  useEffect(() => {
    if (!flash) return;
    const timer = setTimeout(() => setFlash(null), 6000);
    return () => clearTimeout(timer);
  }, [flash]);

  async function run(mutation: (token: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    setActionError(null);
    try {
      await mutation(await getToken());
      reload();
      return true;
    } catch (e) {
      setActionError(toApiError(e, failure));
      return false;
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/supplies" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-8 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Supplies</h1>
              <p className="mt-1 text-ink-secondary">
                What the kitchen uses up that nobody eats — LPG, kerosene, cleaning liquid, bulbs,
                brooms. Bought, stored and reordered exactly as food is.
              </p>
            </div>
            <ButtonLink href="/supplies/new">Add a supply</ButtonLink>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

          {flash && (
            <div className="mb-6">
              <InlineNotice tone="success" autoDismiss title={`${flash} was added.`}>
                It can be ordered and stocked now, the same way food is.
              </InlineNotice>
            </div>
          )}

          {loading ? (
            <Loading label="Loading supplies…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : supplies.length === 0 ? (
            /*
              The empty state carries the rule rather than an apology, because this is the screen
              where somebody is deciding whether the thing in their hand belongs here at all, and
              getting that wrong is the only way this screen goes wrong.
            */
            <EmptyState
              title="No supplies yet"
              action={<ButtonLink href="/supplies/new">Add a supply</ButtonLink>}
            >
              Anything the temple uses up that is not food. Things it keeps and re-uses — stools,
              ladders, extension boxes — belong under Equipment.
            </EmptyState>
          ) : (
            <div className="table-wrap overflow-x-auto">
              <table className={TABLE}>
                <thead className={THEAD}>
                  <tr>
                    <th className={`${TH_TEXT} ${WRAP}`}>Name</th>
                    <th className={TH_TEXT}>Category</th>
                    <th className={TH_TEXT}>Unit</th>
                    {/*
                      Aliases are shown here and not on /ingredients, and the difference is what
                      the two lists are for. A temple's food is called what the recipes call it;
                      its supplies are called four things by four people — a cylinder, LPG, gas —
                      and the alias is what makes the shopping list and the vendor agree.
                    */}
                    <th className={TH_TEXT}>Also called</th>
                    <th className={TH_ACTIONS}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {supplies.map((item) =>
                    editing === item.id ? (
                      <EditRow
                        key={item.id}
                        supply={item}
                        busy={busy}
                        onCancel={() => setEditing(null)}
                        onSave={async (input) => {
                          const ok = await run((t) => api.updateIngredient(item.id, input, t), "We couldn’t save that.");
                          if (ok) setEditing(null);
                        }}
                      />
                    ) : (
                      <tr key={item.id} className={TR}>
                        <td className={`${TD_TEXT} ${WRAP}`}>{item.name}</td>
                        <td className={`${TD_TEXT} text-ink-secondary`}>{item.category}</td>
                        <td className={`${TD_TEXT} text-ink-secondary`}>{unitLabel(item.unit)}</td>
                        <td className={`${TD_TEXT} text-ink-secondary`}>
                          {item.aliases.length > 0 ? (
                            item.aliases.join(", ")
                          ) : (
                            <span className="text-ink-muted">—</span>
                          )}
                        </td>
                        <td className={TD_ACTIONS}>
                          <div className={ACTIONS_ROW}>
                            <Button variant="ghost" size="sm" onClick={() => setEditing(item.id)}>Edit</Button>
                            {/*
                              The same endpoint and the same refusal food gets. A supply that is on
                              a purchase order or in the store cannot be deleted, and the message
                              says so in the words of the thing being deleted rather than calling
                              a mop an ingredient.
                            */}
                            <Button variant="danger" size="sm" disabled={busy} onClick={() => run((t) => api.deleteIngredient(item.id, t), "That supply is in use, or couldn’t be removed.")}>Delete</Button>
                          </div>
                        </td>
                      </tr>
                    )
                  )}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </main>
    </div>
  );
}

function EditRow({
  supply,
  busy,
  onSave,
  onCancel,
}: {
  supply: IngredientView;
  busy: boolean;
  onSave: (input: {
    name: string;
    category: string;
    unit: string;
    supply: boolean;
    aliases: string[];
  }) => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState(supply.name);
  const [category, setCategory] = useState(supply.category);
  const [unit, setUnit] = useState(supply.unit);
  /*
    The mirror of the box on /ingredients, and unticked here means "leave it where it is" exactly as
    it does there (T-089). A temple that catalogued its rice as a supply fixes it by ticking this
    and saving, and the row moves to Ingredients on the reload.

    Which is why the payload below sends `supply: !move` rather than a stored value: the flag is
    required on the update body and the server field is a primitive, so every save has to state it,
    and on this screen the true statement is "still a supply unless the box says otherwise".
  */
  const [move, setMove] = useState(false);
  const [aliases, setAliases] = useState(supply.aliases.join(", "));

  return (
    <tr className="border-t border-hairline bg-sunken align-top">
      <td className={`${TD_TEXT} ${WRAP}`}><input aria-label="Name" value={name} onChange={(e) => setName(e.target.value)} className="min-h-touch w-full rounded-control border border-hairline px-2" /></td>
      <td className={TD_TEXT}><input aria-label="Category" value={category} onChange={(e) => setCategory(e.target.value)} className="min-h-touch w-full rounded-control border border-hairline px-2" /></td>
      <td className={TD_TEXT}>
        <select aria-label="Unit" value={unit} onChange={(e) => setUnit(e.target.value)} className="min-h-touch rounded-control border border-hairline px-2">
          {FOOD_UNITS.map((u) => <option key={u} value={u}>{unitLabel(u)}</option>)}
        </select>
      </td>
      <td className={TD_TEXT}>
        <input aria-label="Aliases" value={aliases} onChange={(e) => setAliases(e.target.value)} placeholder="Aliases" className="min-h-touch w-full rounded-control border border-hairline px-2" />
        <label className="mt-2 flex items-center gap-2 text-xs text-ink-secondary">
          <input
            type="checkbox"
            aria-label="Move to Ingredients"
            checked={move}
            onChange={(e) => setMove(e.target.checked)}
            className="h-5 w-5 rounded-sm border-hairline-strong accent-accent"
          />
          Move to Ingredients
        </label>
      </td>
      <td className={TD_ACTIONS}>
        <div className={ACTIONS_ROW}>
          <Button size="sm" disabled={busy} onClick={() => onSave({ name, category, unit, supply: !move, aliases: splitAliases(aliases) })}>Save</Button>
          <Button variant="ghost" size="sm" onClick={onCancel}>Cancel</Button>
        </div>
      </td>
    </tr>
  );
}
