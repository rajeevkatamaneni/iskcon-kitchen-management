"use client";

import { useState } from "react";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { BusyPot } from "@/components/Loading";
import { useRouter } from "next/navigation";
import { RecipeForm } from "@/components/RecipeForm";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type RecipeInput } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";

const RECIPE_FORM_ID = "new-recipe";

export default function NewRecipePage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <NewRecipeView />
    </RequireRole>
  );
}

function NewRecipeView() {
  const { getToken } = useAuth();
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function create(input: RecipeInput) {
    setBusy(true);
    setError(null);
    try {
      const { id } = await api.createRecipe(input, await getToken());
      router.push(`/recipes/${id}`);
    } catch (e) {
      setError(toApiError(e, "We couldn't create that recipe."));
      setBusy(false);
    }
  }

  return (
    <FocusScreen
      task="New recipe"
      who="For this temple"
      activeHref="/recipes"
      actions={
        <>
          <ButtonLink href="/recipes" variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={RECIPE_FORM_ID} disabled={busy}>
            {busy ? (
              <span className="inline-flex items-center gap-2">
                <BusyPot />
                Saving…
              </span>
            ) : (
              "Create recipe"
            )}
          </Button>
        </>
      }
    >
      <RecipeForm formId={RECIPE_FORM_ID} busy={busy} error={error} onSubmit={create} />
    </FocusScreen>
  );
}
