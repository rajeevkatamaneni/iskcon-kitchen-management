"use client";

import { Suspense, useCallback, useMemo, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
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
      {/* useSearchParams — the search and the filters live in the URL. */}
      <Suspense>
        <RecipesView />
      </Suspense>
    </RequireRole>
  );
}

function RecipesView() {
  const categories = useAuthedQuery(api.listRecipeCategories);
  const router = useRouter();
  const params = useSearchParams();

  // Item 22: what this list is showing goes in the address bar, so a search can be sent to somebody
  // and survives a reload. A category and the archived tick are single deliberate choices and are
  // pushed, so back undoes the filter rather than leaving the page. The search box is replaced,
  // because pushing on every keystroke would leave a whole word to press back through.
  const categoryId = params.get("category");
  // Archiving would otherwise be a disappearance: an archived recipe is off this list by design,
  // and without a way to see one there is no way back to it to restore it.
  const includeArchived = params.get("archived") === "1";
  // The box itself is uncontrolled by the URL after the first render: typing writes to both, and a
  // replaced entry never comes back through history, so nothing can drive the caret from outside.
  const [search, setSearch] = useState(params.get("q") ?? "");

  function go(next: { category?: string | null; archived?: boolean; q?: string }, how: "push" | "replace") {
    const q = new URLSearchParams();
    const cat = next.category === undefined ? categoryId : next.category;
    const arch = next.archived === undefined ? includeArchived : next.archived;
    const text = next.q === undefined ? search : next.q;
    if (cat) q.set("category", cat);
    if (arch) q.set("archived", "1");
    if (text.trim()) q.set("q", text);
    const url = q.toString() ? `/recipes?${q}` : "/recipes";
    if (how === "push") router.push(url);
    else router.replace(url);
  }

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
            onChange={(e) => {
              setSearch(e.target.value);
              go({ q: e.target.value }, "replace");
            }}
            placeholder="Search recipes by name…"
            aria-label="Search recipes by name"
            className="mb-4 min-h-touch w-full rounded border border-hairline bg-raised px-4"
          />

          <label className="mb-4 flex items-center gap-2 text-sm text-ink-secondary">
            <input
              type="checkbox"
              checked={includeArchived}
              onChange={(e) => go({ archived: e.target.checked }, "push")}
              className="size-4"
            />
            Show archived recipes
          </label>

          <div className="mb-6 flex flex-wrap gap-2" role="group" aria-label="Filter by category">
            <Chip active={categoryId === null} onClick={() => go({ category: null }, "push")}>All</Chip>
            {(categories.data ?? []).map((c) => (
              <Chip key={c.id} active={categoryId === c.id} onClick={() => go({ category: c.id }, "push")}>
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
