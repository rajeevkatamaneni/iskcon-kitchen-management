"use client";

import { useCallback, useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RecipeForm } from "@/components/RecipeForm";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type RecipeInput } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";

export default function EditRecipePage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_STAFF"]}>
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
      setSaveError(toApiError(e, "We couldn't save that recipe."));
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/recipes" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-prose">
          <header className="mb-8">
            <Link href={`/recipes/${id}`} className="text-sm text-ink-secondary hover:text-ink">← Back to recipe</Link>
            <h1 className="mt-2">Edit recipe</h1>
          </header>
          {loading ? (
            <Loading />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : recipe ? (
            <RecipeForm initial={recipe} submitLabel="Save changes" busy={busy} error={saveError} onSubmit={save} />
          ) : null}
        </div>
      </main>
    </div>
  );
}
