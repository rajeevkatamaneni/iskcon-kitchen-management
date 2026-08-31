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

  async function add(input: NewInventoryItem) {
    setBusy(true);
    setError(null);
    try {
      const token = await getToken();
      const itemId = await api.createInventoryItem(
        {
          ingredientId: input.ingredientId,
          storageLocation: input.storageLocation,
          reorderThreshold: input.reorderThreshold,
          notes: input.notes,
        },
        token
      );
      // The count is the first thing anybody knows about a consumable, so it is asked for here
      // rather than on a second screen afterwards. It opens the item's first lot.
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
          token
        );
      }
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
      />
    </FocusScreen>
  );
}
