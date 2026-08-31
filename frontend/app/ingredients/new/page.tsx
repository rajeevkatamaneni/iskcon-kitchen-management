"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { IngredientForm } from "@/components/IngredientForm";
import { api, toApiError, type ApiError, type CreateIngredientInput } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";

/**
 * Add an ingredient — four fields, five for an administrator, so a screen rather than a panel.
 *
 * <p>The threshold in `DESIGN_SYSTEM.md` is four: a form of four fields or more gets its own URL,
 * and three or fewer stays where it is. This form was inline over the list until E10-S12 and was
 * already over the line; what it gains by moving is that it now works the way adding a recipe and
 * adding a vendor already work, so there is one pattern to learn instead of three.
 *
 * <p>The commit button is in the header and the form is in the body, which is why the button
 * carries `form`: an HTML button can submit a form it is not inside, as long as it names it.
 */

/** Named so the header's primary button can submit the form in the body. */
const FORM = "add-ingredient";

export default function NewIngredientPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <NewIngredientView />
    </RequireRole>
  );
}

function NewIngredientView() {
  const { appUser, getToken } = useAuth();
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function add(input: CreateIngredientInput) {
    setBusy(true);
    setError(null);
    try {
      await api.createIngredient(input, await getToken());
      // Rule 8: back to the list, with the confirmation waiting there rather than here.
      router.push(`/ingredients?added=${encodeURIComponent(input.name)}`);
    } catch (e) {
      setError(toApiError(e, "We couldn’t add that ingredient."));
      setBusy(false);
    }
  }

  return (
    <FocusScreen
      task="Add an ingredient"
      who="A new word in this temple’s shared vocabulary"
      activeHref="/ingredients"
      actions={
        <>
          <ButtonLink href="/ingredients" variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={FORM} disabled={busy}>
            Add ingredient
          </Button>
        </>
      }
    >
      <IngredientForm
        formId={FORM}
        isAdmin={appUser?.role === "TEMPLE_ADMIN"}
        busy={busy}
        error={error}
        onSubmit={add}
      />
    </FocusScreen>
  );
}
