"use client";

import { useCallback, useMemo, useState } from "react";
import Link from "next/link";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api } from "@/lib/api";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";

/**
 * Recipe browse & search (E2-S7) — the Recipes tab. Category chips + a name search over the temple's
 * recipes, each linking to its detail. Behind MANAGE_RECIPES (the two kitchen roles).
 */
export default function RecipesPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <RecipesView />
    </RequireRole>
  );
}

function RecipesView() {
  const categories = useAuthedQuery(api.listRecipeCategories);
  const [categoryId, setCategoryId] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  // Archiving would otherwise be a disappearance: an archived recipe is off this list by design,
  // and without a way to see one there is no way back to it to restore it.
  const [includeArchived, setIncludeArchived] = useState(false);

  // Fetch by category (server-side); name filtering is instant, client-side, over that set.
  const fetchRecipes = useCallback(
    (token: string | undefined) =>
      api.listRecipes(
        { ...(categoryId ? { categoryId } : {}), ...(includeArchived ? { includeArchived: true } : {}) },
        token
      ),
    [categoryId, includeArchived]
  );
  const recipes = useAuthedQuery(fetchRecipes);

  const shown = useMemo(() => {
    const all = recipes.data ?? [];
    const q = search.trim().toLowerCase();
    return q ? all.filter((r) => r.name.toLowerCase().includes(q)) : all;
  }, [recipes.data, search]);

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/recipes" />

      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Recipes</h1>
              <p className="mt-1 text-ink-secondary">Your temple&rsquo;s recipes, ready to scale and print.</p>
            </div>
            <div className="flex flex-wrap gap-2">
              <Link href="/glossary" className="flex min-h-touch items-center rounded border border-hairline-strong px-4 text-sm transition-colors duration-state hover:bg-raised">
                Glossary
              </Link>
              <Link href="/recipes/new" className="flex min-h-touch items-center rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover">
                New recipe
              </Link>
            </div>
          </header>

          <input
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search recipes by name…"
            aria-label="Search recipes by name"
            className="mb-4 min-h-touch w-full rounded border border-hairline bg-raised px-4"
          />

          <label className="mb-4 flex items-center gap-2 text-sm text-ink-secondary">
            <input
              type="checkbox"
              checked={includeArchived}
              onChange={(e) => setIncludeArchived(e.target.checked)}
              className="size-4"
            />
            Show archived recipes
          </label>

          <div className="mb-6 flex flex-wrap gap-2" role="group" aria-label="Filter by category">
            <Chip active={categoryId === null} onClick={() => setCategoryId(null)}>All</Chip>
            {(categories.data ?? []).map((c) => (
              <Chip key={c.id} active={categoryId === c.id} onClick={() => setCategoryId(c.id)}>
                {c.name}
                {c.fastingCompatible ? " ·⃝" : ""}
              </Chip>
            ))}
          </div>

          {recipes.loading ? (
            <Loading label="Loading recipes…" />
          ) : recipes.error ? (
            <ErrorNotice error={recipes.error} />
          ) : shown.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">No recipes found</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                {search || categoryId
                  ? "Try a different search or category."
                  : "Recipes added to your temple will appear here."}
              </p>
            </div>
          ) : (
            <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              {shown.map((r) => (
                <li key={r.id}>
                  <Link
                    href={`/recipes/${r.id}`}
                    className="block rounded-lg bg-raised px-5 py-4 transition-colors duration-state hover:bg-sunken"
                  >
                    <div className="flex items-start justify-between gap-3">
                      <span className="font-medium">{r.name}</span>
                      <span className="shrink-0 text-sm text-ink-secondary">{r.categoryName}</span>
                    </div>
                    <div className="mt-2 flex flex-wrap gap-2">
                      {r.status === "ARCHIVED" && (
                        <span className="rounded-sm bg-sunken px-2 py-0.5 text-xs text-ink-secondary font-semibold">
                          Archived
                        </span>
                      )}
                      {r.fastingCompatible && (
                        <span className="rounded-sm bg-accent-bg px-2 py-0.5 text-xs text-accent-text font-semibold">
                          Ekadashi-friendly
                        </span>
                      )}
                      {r.sattvicOverridden && (
                        <span className="rounded-sm bg-warning-bg px-2 py-0.5 text-xs text-warning font-semibold">
                          Sattvic override
                        </span>
                      )}
                      <span className="text-xs text-ink-muted">
                        base {r.baseYieldQty} {r.baseYieldUnit.toLowerCase()}
                      </span>
                    </div>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </div>
      </main>
    </div>
  );
}

function Chip({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={[
        "min-h-touch rounded-full border px-4 text-sm transition-colors duration-state",
        active
          ? "border-accent bg-accent text-ink-inverse"
          : "border-hairline-strong text-ink-secondary hover:bg-raised",
      ].join(" ")}
    >
      {children}
    </button>
  );
}
