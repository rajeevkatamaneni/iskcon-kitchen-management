"use client";

import { useCallback, useState } from "react";
import { useRouter } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { api, toApiError, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * Track an item — four fields, which is exactly the threshold, so it is a screen.
 *
 * <p>Four is the line settled in Q1: at four fields a form becomes its own URL and at three it stays
 * inline. This form is on the line rather than over it, and it converts, because the rule is what
 * decides — not a judgement made form by form. The next field somebody adds here changes nothing.
 *
 * <p>Only ingredients that are not already tracked are offered. Tracking the same ingredient twice
 * would give the shelf two running totals for one thing, and the list on the other side of this
 * screen is where somebody would go to find out which of them was right.
 */

const FORM = "track-item";
const FIELD = "min-h-touch rounded border border-hairline bg-canvas px-3";

export default function NewInventoryItemPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <NewInventoryItemView />
    </RequireRole>
  );
}

function NewInventoryItemView() {
  const { getToken } = useAuth();
  const router = useRouter();
  const { data: trackedData } = useAuthedQuery(useCallback((t: string | undefined) => api.listInventory({}, t), []));
  const { data: ingredientsData } = useAuthedQuery(api.listIngredients);

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const trackedIds = new Set((trackedData ?? []).map((i) => i.ingredientId));
  const untracked = (ingredientsData ?? []).filter((i) => !trackedIds.has(i.id));

  async function add(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    const ingredientId = String(f.get("ingredientId") ?? "");
    const threshold = String(f.get("reorderThreshold") ?? "").trim();
    setBusy(true);
    setError(null);
    try {
      await api.createInventoryItem(
        {
          ingredientId,
          storageLocation: emptyToNull(String(f.get("storageLocation") ?? "")),
          reorderThreshold: threshold === "" ? null : Number(threshold),
          notes: emptyToNull(String(f.get("notes") ?? "")),
        },
        await getToken()
      );
      const name = untracked.find((i) => i.id === ingredientId)?.name ?? "";
      router.push(`/inventory?tracking=${encodeURIComponent(name)}`);
    } catch (e) {
      setError(toApiError(e, "We couldn't start tracking that item."));
      setBusy(false);
    }
  }

  return (
    <FocusScreen
      task="Track an item"
      who="A consumable this temple keeps on the shelf"
      activeHref="/inventory"
      actions={
        <>
          <ButtonLink href="/inventory" variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={FORM} disabled={busy || untracked.length === 0}>
            Start tracking
          </Button>
        </>
      }
    >
      {error && <ErrorNotice error={error} />}

      {untracked.length === 0 && <InlineNotice title="Every ingredient is already tracked." />}

      <form id={FORM} className="grid grid-cols-2 gap-4" aria-label="Track an item" onSubmit={add}>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Ingredient</span>
          <select name="ingredientId" required className={FIELD}>
            <option value="">Choose an ingredient…</option>
            {untracked.map((i) => (
              <option key={i.id} value={i.id}>
                {i.name}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Storage location</span>
          <input name="storageLocation" placeholder="Main store, cold room…" className={FIELD} />
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Reorder threshold</span>
          <input name="reorderThreshold" type="number" min="0" step="any" placeholder="e.g. 5" className={FIELD} />
          <span className="pl-field-inset text-sm text-ink-secondary">In the ingredient&rsquo;s own unit</span>
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Notes</span>
          <input name="notes" className={FIELD} />
        </label>
      </form>
    </FocusScreen>
  );
}

function emptyToNull(s: string): string | null {
  const t = s.trim();
  return t === "" ? null : t;
}
