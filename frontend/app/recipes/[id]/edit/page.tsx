"use client";

import { useCallback, useState } from "react";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { useParams, useRouter } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RecipeForm } from "@/components/RecipeForm";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type RecipeInput } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { BusyPot, Loading } from "@/components/Loading";

const RECIPE_FORM_ID = "edit-recipe";

export default function EditRecipePage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <EditRecipeView />
    </RequireRole>
  );
}

function EditRecipeView() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const { getToken } = useAuth();
  const router = useRouter();
  const fetchRecipe = useCallback((t: string | undefined) => api.getRecipe(id, t), [id]);
  const { data: recipe, error, loading } = useAuthedQuery(fetchRecipe);

  const [busy, setBusy] = useState(false);
  const [saveError, setSaveError] = useState<ApiError | null>(null);

  async function save(input: RecipeInput) {
    setBusy(true);
    setSaveError(null);
    try {
      await api.updateRecipe(id, input, await getToken());
      router.push(`/recipes/${id}`);
    } catch (e) {
      setSaveError(toApiError(e, "We couldn’t save that recipe."));
      setBusy(false);
    }
  }

  return (
    <FocusScreen
      task="Edit recipe"
      who={recipe?.name}
      activeHref="/recipes"
      actions={
        <>
          <ButtonLink href={`/recipes/${id}`} variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={RECIPE_FORM_ID} disabled={busy || !recipe} busy={busy}>
            {busy ? (
              <span className="inline-flex items-center gap-2">
                <BusyPot />
                Saving…
              </span>
            ) : (
              "Save changes"
            )}
          </Button>
        </>
      }
    >
      {loading ? (
        <Loading />
      ) : error ? (
        <ErrorNotice error={error} />
      ) : recipe ? (
        <RecipeForm initial={recipe} formId={RECIPE_FORM_ID} busy={busy} error={saveError} onSubmit={save} />
      ) : null}
    </FocusScreen>
  );
}
