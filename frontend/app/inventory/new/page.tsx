"use client";

import { useCallback, useState } from "react";
import { useRouter } from "next/navigation";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { InventoryItemForm, type NewInventoryItem } from "@/components/InventoryItemForm";
import { api, toApiError, type ApiError, type IngredientView, type StockItemView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * Add a consumable to the inventory — five fields, so a screen rather than a panel over the list.
 *
 * <p>This screen existed once, was replaced by a panel above the list to match Ingredients, and is
 * back because the panel put both pages on the wrong side of the rule in `DESIGN_SYSTEM.md`: four
 * fields or more becomes a screen. Ingredients moved with it (E10-S12), so the two pages still
 * agree with each other, and now agree with Recipes and with the document as well.
 */

/** Named so the header's primary button can submit the form in the body. */
const FORM = "add-inventory-item";

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
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const { data: ingredientsData } = useAuthedQuery(api.listIngredients);
  const fetchTracked = useCallback((token: string | undefined) => api.listInventory({}, token), []);
  const { data: trackedData } = useAuthedQuery(fetchTracked);

  // The pre-fill for "What it would cost to buy today" (R-ING-3). Kept stable so the form's lookup
  // does not re-run on every render.
  const loadStockValue = useCallback(
    async (ingredientId: string) =>
      (await api.getStockValueSuggestion(ingredientId, await getToken())).pricePerUnit,
    [getToken]
  );

  async function add(input: NewInventoryItem) {
    setBusy(true);
    setError(null);
    try {
      // One request, one transaction (T-294). The count is the first thing anybody knows about a
      // consumable, so it is asked for here rather than on a second screen afterwards, and it opens
      // the item's first lot. It used to follow the item as a second request, and when that one
      // failed the item was left behind with no stock and no value (VERIFY-A defect 5). Now the
      // server writes both or neither.
      const addsStock = input.openingQuantity != null && input.openingQuantity > 0;
      await api.createInventoryItem(
        {
          ingredientId: input.ingredientId,
          storageLocation: input.storageLocation,
          reorderThreshold: input.reorderThreshold,
          // The level's own unit, not the count's. The server converts it against the ingredient's
          // canonical unit and refuses a fraction of a counted thing (T-432).
          reorderThresholdUnit: input.reorderThresholdUnit,
          notes: input.notes,
          openingCount: addsStock
            ? {
                quantity: input.openingQuantity as number,
                unit: input.unit,
                // Required by the server for any count that adds stock (KMS-400161), and it becomes
                // the ingredient's market rate. The form has already refused a blank or 0.
                pricePerUnit: input.pricePerUnit as number,
              }
            : null,
        },
        await getToken()
      );
      // Rule 8: back to the list, with the confirmation waiting there rather than here.
      router.push(`/inventory?added=${encodeURIComponent(input.name)}`);
    } catch (e) {
      setError(toApiError(e, "We couldn’t add that to your inventory."));
      setBusy(false);
    }
  }

  return (
    <FocusScreen
      task="Add to inventory"
      who="A consumable this temple keeps on the shelf"
      activeHref="/inventory"
      actions={
        <>
          <ButtonLink href="/inventory" variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={FORM} disabled={busy}>
            Add to inventory
          </Button>
        </>
      }
    >
      <InventoryItemForm
        formId={FORM}
        ingredients={(ingredientsData ?? []) as IngredientView[]}
        tracked={(trackedData ?? []) as StockItemView[]}
        busy={busy}
        error={error}
        onSubmit={add}
        loadStockValue={loadStockValue}
      />
    </FocusScreen>
  );
}
