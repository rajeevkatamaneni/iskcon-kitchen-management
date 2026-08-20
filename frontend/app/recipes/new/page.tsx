"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { RecipeForm } from "@/components/RecipeForm";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type RecipeInput } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";

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
    <div className="flex min-h-screen">
      <Sidebar activeHref="/recipes" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-prose">
          <header className="mb-8">
            <Link href="/recipes" className="text-sm text-ink-secondary hover:text-ink">← Recipes</Link>
            <h1 className="mt-2">New recipe</h1>
          </header>
          <RecipeForm submitLabel="Create recipe" busy={busy} error={error} onSubmit={create} />
        </div>
      </main>
    </div>
  );
}
