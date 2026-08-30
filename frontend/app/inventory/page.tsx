"use client";

import Link from "next/link";
import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { api, toApiError, type ApiError, type IngredientView, type StockItemView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { expiryWord, quantity } from "@/lib/format";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";

const UNIT_LABEL: Record<string, string> = { KG: "Kg", GM: "gm", L: "L", ML: "ml", PIECES: "pieces" };


/** The units a level may be typed in, for the unit the ingredient is kept in. */
const ENTRY_UNITS: Record<string, { code: string; per: number }[]> = {
  KG: [{ code: "KG", per: 1 }, { code: "GM", per: 0.001 }],
  GM: [{ code: "GM", per: 1 }, { code: "KG", per: 1000 }],
  L: [{ code: "L", per: 1 }, { code: "ML", per: 0.001 }],
  ML: [{ code: "ML", per: 1 }, { code: "L", per: 1000 }],
  PIECES: [{ code: "PIECES", per: 1 }],
};

export default function InventoryPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      {/* useSearchParams — for the confirmation a newly tracked item comes back with. */}
      <Suspense>
        <InventoryView />
      </Suspense>
    </RequireRole>
  );
}

function InventoryView() {
  const { getToken } = useAuth();
  const [nonce, setNonce] = useState(0);
  const fetchInventory = useCallback(
    (token: string | undefined) => {
      void nonce;
      return api.listInventory({}, token);
    },
    [nonce]
  );
  const { data, error, loading } = useAuthedQuery(fetchInventory);
  const items = data ?? [];

  const { data: ingredientsData } = useAuthedQuery(api.listIngredients);
  const ingredients = (ingredientsData ?? []) as IngredientView[];

  const [locationFilter, setLocationFilter] = useState("");
  const [onlyLow, setOnlyLow] = useState(false);
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState<string | null>(null);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [flash, setFlash] = useState<string | null>(null);

  // Adding used to happen on a screen of its own and hand the confirmation back through the URL.
  // It happens here now, in the panel above the list, the way Ingredients has always worked.
  const router = useRouter();
  const tracking = useSearchParams().get("tracking");
  const captured = useRef(false);
  useEffect(() => {
    if (captured.current || !tracking) return;
    captured.current = true;
    setFlash(`${tracking} is now in your inventory.`);
    router.replace("/inventory");
  }, [tracking, router]);

  async function run(fn: (token: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    setActionError(null);
    try {
      await fn(await getToken());
      setNonce((n) => n + 1);
      return true;
    } catch (e) {
      setActionError(toApiError(e, failure));
      return false;
    } finally {
      setBusy(false);
    }
  }

  const locations = useMemo(
    () => [...new Set(items.map((i) => i.storageLocation).filter(Boolean))] as string[],
    [items]
  );

  const visible = items.filter(
    (i) => (!locationFilter || i.storageLocation === locationFilter) && (!onlyLow || i.belowThreshold)
  );
  const lowCount = items.filter((i) => i.belowThreshold).length;
  const expiringCount = items.filter((i) => i.expiringSoon).length;

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/inventory" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          {/*
            One page, shaped exactly like Ingredients: the form in a panel at the top, the list
            underneath. Adding used to be a screen of its own behind a "Track an item" button, which
            meant the same job — add a thing, see the things — was done two different ways in two
            places, and a person had to learn both. Two screens that do the same kind of work look
            the same, so muscle memory forms instead of hunting.
          */}
          <header className="mb-8">
            <h1>Inventory</h1>
            <p className="mt-1 text-ink-secondary">
              What the store holds, counted from every receipt, donation and meal cooked.
            </p>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

          <AddToInventory
            ingredients={ingredients}
            tracked={items}
            busy={busy}
            onAdd={async (input) => {
              const ok = await run(
                async (t) => {
                  const itemId = await api.createInventoryItem(
                    {
                      ingredientId: input.ingredientId,
                      storageLocation: input.storageLocation,
                      reorderThreshold: input.reorderThreshold,
                      notes: input.notes,
                    },
                    t
                  );
                  // The count is the first thing anybody knows about a consumable, so it is asked for
                  // here rather than on a second screen afterwards. It opens the item's first lot.
                  if (input.openingQuantity != null && input.openingQuantity > 0) {
                    await api.adjustStock(
                      String(itemId),
                      {
                        batchId: null,
                        quantity: input.openingQuantity,
                        unit: input.unit,
                        reason: "COUNT_CORRECTION",
                        note: "Opening count, when the item was added to inventory.",
                      },
                      t
                    );
                  }
                },
                "We couldn’t add that to your inventory."
              );
              if (ok) setFlash(`${input.name} is now in your inventory.`);
              return ok;
            }}
          />

          {(lowCount > 0 || expiringCount > 0) && (
            <div className="mb-6 flex flex-wrap gap-3">
              {lowCount > 0 && (
                <button
                  type="button"
                  onClick={() => setOnlyLow((s) => !s)}
                  className={`rounded-md px-4 py-2 text-sm ${onlyLow ? "bg-warning text-ink-inverse" : "bg-warning-bg text-warning"}`}
                >
                  {lowCount} below reorder level{onlyLow ? ", showing only these" : ""}
                </button>
              )}
              {expiringCount > 0 && (
                <span className="rounded-md bg-warning-bg px-4 py-2 text-sm text-warning">
                  {expiringCount} with stock expiring soon
                </span>
              )}
            </div>
          )}

          {flash && (
            <div className="mb-6">
              <InlineNotice tone="success" autoDismiss title={flash}>
                Its stock moves on its own from here — every delivery, donation and meal cooked.
              </InlineNotice>
            </div>
          )}

          {locations.length > 0 && (
            <div className="mb-4">
              <label className="text-sm text-ink-secondary">
                <span className="font-medium text-ink">Location</span>
                <select value={locationFilter} onChange={(e) => setLocationFilter(e.target.value)} className="ml-2 min-h-touch rounded border border-hairline bg-canvas px-3">
                  <option value="">All</option>
                  {locations.map((l) => <option key={l} value={l}>{l}</option>)}
                </select>
              </label>
            </div>
          )}

          {loading ? (
            <Loading label="Loading inventory…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : items.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">Nothing in your inventory yet</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                Add the first consumable above, with what is on the shelf today.
              </p>
            </div>
          ) : (
            <div className="overflow-hidden rounded-lg bg-raised">
              <table className="w-full text-left">
                <thead className="bg-sunken text-sm text-ink-secondary">
                  <tr>
                    <th className="px-5 py-3 font-medium">Item</th>
                    <th className="px-5 py-3 font-medium">Location</th>
                    <th className="px-5 py-3 font-medium text-right">On hand</th>
                    <th className="px-5 py-3 font-medium text-right">Reorder at</th>
                    <th className="px-5 py-3 font-medium">Status</th>
                    <th className="px-5 py-3 font-medium">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {visible.map((i) =>
                    editing === i.itemId ? (
                      <EditRow
                        key={i.itemId}
                        item={i}
                        busy={busy}
                        onCancel={() => setEditing(null)}
                        onSave={async (input) => {
                          const ok = await run(
                            (t) => api.updateInventoryItem(i.itemId, input, t),
                            "We couldn’t save that change."
                          );
                          if (ok) setEditing(null);
                        }}
                      />
                    ) : (
                      <tr key={i.itemId} className="border-t border-hairline align-middle hover:bg-sunken">
                        <td className="px-5 py-3">
                          <Link href={`/inventory/${i.itemId}`} className="font-medium text-accent-text hover:underline">
                            {i.ingredientName}
                          </Link>
                          <span className="ml-2 text-xs text-ink-muted">{i.category}</span>
                        </td>
                        <td className="px-5 py-3 text-ink-secondary">{i.storageLocation ?? "—"}</td>
                        <td className="px-5 py-3 text-right tabular-nums">{quantity(i.onHand, i.unit)}</td>
                        <td className="px-5 py-3 text-right tabular-nums text-ink-secondary">
                          {i.reorderThreshold == null ? "—" : quantity(i.reorderThreshold, i.unit)}
                        </td>
                        <td className="px-5 py-3">
                          <div className="flex flex-wrap gap-1.5">
                            {i.belowThreshold && <span className="rounded-sm bg-warning-bg px-2 py-1 text-xs text-warning font-semibold">Low</span>}
                            {i.expiringSoon && (
                              <span className="rounded-sm bg-warning-bg px-2 py-1 text-xs font-semibold text-warning">
                                {expiryWord(i.soonestExpiry) === "expired" ? "Expired" : "Expiring soon"}
                              </span>
                            )}
                            {!i.belowThreshold && !i.expiringSoon && <span className="text-xs text-ink-muted">Fine</span>}
                          </div>
                        </td>
                        {/* Changing your mind about a level is a one-click job on the row you are
                            looking at. It used to be impossible anywhere in the application: the
                            endpoint existed and no screen called it. */}
                        <td className="px-5 py-3">
                          <button
                            type="button"
                            onClick={() => setEditing(i.itemId)}
                            className="min-h-touch rounded border border-hairline-strong px-3 text-sm transition-colors duration-state hover:bg-sunken"
                          >
                            Edit
                          </button>
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

/**
 * Adding a consumable to the inventory, in the panel above the list.
 *
 * <p>It asks the three things a storekeeper knows standing in front of the shelf: what it is, how
 * much is there, and where it lives. The count is the one that used to be missing — an item could
 * be added and had no way of being told what was on the shelf, so it sat at zero, badged "below
 * reorder level", with nothing on any screen able to answer it.
 */
function AddToInventory({
  ingredients,
  tracked,
  busy,
  onAdd,
}: {
  ingredients: IngredientView[];
  tracked: StockItemView[];
  busy: boolean;
  onAdd: (input: {
    ingredientId: string;
    name: string;
    unit: string;
    openingQuantity: number | null;
    storageLocation: string | null;
    reorderThreshold: number | null;
    notes: string | null;
  }) => Promise<boolean>;
}) {
  const [ingredientId, setIngredientId] = useState("");
  const [levelUnit, setLevelUnit] = useState<string | null>(null);

  const alreadyIn = new Set(tracked.map((i) => i.ingredientId));
  const available = ingredients.filter((i) => !alreadyIn.has(i.id));
  const chosen = available.find((i) => i.id === ingredientId);

  // The unit belongs to the ingredient, so until one is chosen there is no unit to show. It used to
  // default to kilograms, which asserted a unit for an ingredient nobody had named yet.
  const units = chosen ? (ENTRY_UNITS[chosen.unit] ?? [{ code: chosen.unit, per: 1 }]) : [];
  const typedIn = units.find((u) => u.code === levelUnit) ?? units[0] ?? null;

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const f = new FormData(form);
    if (!chosen || !typedIn) return;

    const opening = String(f.get("opening") ?? "").trim();
    const level = String(f.get("reorderThreshold") ?? "").trim();

    const ok = await onAdd({
      ingredientId: chosen.id,
      name: chosen.name,
      unit: typedIn.code,
      openingQuantity: opening === "" ? null : Number(opening),
      storageLocation: emptyToNull(String(f.get("storageLocation") ?? "")),
      // Stored in the ingredient's own unit, whichever one it was typed in.
      reorderThreshold: level === "" ? null : Number(level) * typedIn.per,
      notes: emptyToNull(String(f.get("notes") ?? "")),
    });
    if (ok) {
      form.reset();
      setIngredientId("");
      setLevelUnit(null);
    }
  }

  const FIELD = "min-h-touch rounded border border-hairline bg-canvas px-3";

  return (
    <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="add-heading">
      <h2 id="add-heading" className="text-lg">Add to inventory</h2>
      <p className="mt-1 text-sm text-ink-secondary">
        A consumable this temple keeps on the shelf, and what is on that shelf today.
      </p>

      {available.length === 0 && ingredients.length > 0 && (
        <p className="mt-4 text-sm text-ink-secondary">
          Every ingredient is already in your inventory.
        </p>
      )}

      <form className="mt-4 grid grid-cols-2 gap-4" aria-label="Add to inventory" onSubmit={submit}>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Ingredient</span>
          <select
            name="ingredientId"
            required
            className={FIELD}
            value={ingredientId}
            onChange={(e) => {
              setIngredientId(e.target.value);
              setLevelUnit(null);
            }}
          >
            <option value="">Choose an ingredient…</option>
            {available.map((i) => (
              <option key={i.id} value={i.id}>
                {i.name} — kept in {UNIT_LABEL[i.unit] ?? i.unit}
              </option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">How much is on the shelf now</span>
          <div className="flex gap-2">
            <input
              name="opening"
              type="number"
              min="0"
              step="any"
              placeholder={chosen ? "e.g. 40" : "Choose an ingredient first"}
              disabled={!chosen}
              className={`${FIELD} min-w-0 flex-1 disabled:opacity-60`}
            />
            <UnitControl units={units} typedIn={typedIn} onChange={setLevelUnit} />
          </div>
          <span className="pl-field-inset text-sm text-ink-secondary">
            Counted today. Everything after this — deliveries, donations, meals cooked — moves on its own.
          </span>
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Where it lives</span>
          <input name="storageLocation" placeholder="Main store, cold room…" className={FIELD} />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Tell me when stock drops below</span>
          <input
            name="reorderThreshold"
            type="number"
            min="0"
            step="any"
            placeholder={chosen ? "e.g. 5" : ""}
            disabled={!chosen}
            className={`${FIELD} disabled:opacity-60`}
          />
          <span className="pl-field-inset text-sm text-ink-secondary">
            Leave it blank if you’d rather not be warned. You can change it later.
          </span>
        </label>

        <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Notes</span>
          <input name="notes" className={FIELD} />
        </label>

        <div className="col-span-2">
          <button
            type="submit"
            disabled={busy || !chosen}
            className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60"
          >
            Add to inventory
          </button>
        </div>
      </form>
    </section>
  );
}

/** The unit a level is typed in: a choice where the family has two, a plain label where it has one. */
function UnitControl({
  units,
  typedIn,
  onChange,
}: {
  units: { code: string; per: number }[];
  typedIn: { code: string; per: number } | null;
  onChange: (code: string) => void;
}) {
  const FIELD = "min-h-touch rounded border border-hairline bg-canvas px-3";
  if (!typedIn) {
    return (
      <span className="flex min-h-touch items-center rounded border border-hairline bg-sunken px-3 text-ink-muted">
        —
      </span>
    );
  }
  if (units.length === 1) {
    // Grams convert to kilograms; nothing converts to a coconut.
    return (
      <span className="flex min-h-touch items-center rounded border border-hairline bg-sunken px-3 text-ink-secondary">
        {UNIT_LABEL[typedIn.code] ?? typedIn.code}
      </span>
    );
  }
  return (
    <select aria-label="Unit" className={FIELD} value={typedIn.code} onChange={(e) => onChange(e.target.value)}>
      {units.map((u) => (
        <option key={u.code} value={u.code}>
          {UNIT_LABEL[u.code] ?? u.code}
        </option>
      ))}
    </select>
  );
}

/** Changing a level, a location or a note, in place on the row — the Ingredients pattern. */
function EditRow({
  item,
  busy,
  onCancel,
  onSave,
}: {
  item: StockItemView;
  busy: boolean;
  onCancel: () => void;
  onSave: (input: { storageLocation: string | null; reorderThreshold: number | null; notes: string | null }) => void;
}) {
  const [location, setLocation] = useState(item.storageLocation ?? "");
  const [threshold, setThreshold] = useState(item.reorderThreshold == null ? "" : String(item.reorderThreshold));
  const [notes, setNotes] = useState(item.notes ?? "");
  const FIELD = "min-h-touch w-full rounded border border-hairline bg-canvas px-2";

  return (
    <tr className="border-t border-hairline bg-sunken/40 align-middle">
      <td className="px-5 py-3">
        <span className="font-medium">{item.ingredientName}</span>
        <span className="ml-2 text-xs text-ink-muted">{item.category}</span>
      </td>
      <td className="px-5 py-3">
        <input aria-label="Where it lives" value={location} onChange={(e) => setLocation(e.target.value)} className={FIELD} />
      </td>
      <td className="px-5 py-3 text-right tabular-nums text-ink-secondary">{quantity(item.onHand, item.unit)}</td>
      <td className="px-5 py-3">
        <div className="flex items-center gap-2">
          <input
            aria-label={`Tell me when ${item.ingredientName} drops below`}
            type="number"
            min="0"
            step="any"
            value={threshold}
            onChange={(e) => setThreshold(e.target.value)}
            className={`${FIELD} text-right`}
          />
          <span className="text-xs text-ink-secondary">{UNIT_LABEL[item.unit] ?? item.unit}</span>
        </div>
      </td>
      <td className="px-5 py-3">
        <input aria-label="Notes" value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Notes" className={FIELD} />
      </td>
      <td className="px-5 py-3">
        <div className="flex gap-2">
          <button
            type="button"
            disabled={busy}
            onClick={() =>
              onSave({
                storageLocation: emptyToNull(location),
                reorderThreshold: threshold.trim() === "" ? null : Number(threshold),
                notes: emptyToNull(notes),
              })
            }
            className="min-h-touch rounded bg-accent px-3 text-sm text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60"
          >
            Save
          </button>
          <button type="button" onClick={onCancel} className="min-h-touch rounded px-3 text-sm text-ink-secondary hover:underline">
            Cancel
          </button>
        </div>
      </td>
    </tr>
  );
}

function emptyToNull(value: string): string | null {
  const trimmed = value.trim();
  return trimmed === "" ? null : trimmed;
}
