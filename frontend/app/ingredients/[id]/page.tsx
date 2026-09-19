"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Loading } from "@/components/Loading";
import { Badge } from "@/components/ds/Badge";
import { PageHeader } from "@/components/ds/PageHeader";
import { IngredientFacts } from "@/components/ingredient/IngredientFacts";
import { IngredientVendors } from "@/components/ingredient/IngredientVendors";
import { MarketRate } from "@/components/ingredient/MarketRate";
import { PackSizes } from "@/components/ingredient/PackSizes";
import { can, holdersOf } from "@/components/ingredient/access";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * One ingredient's own page (Q-10, T-286): what it is, the packs it is sold in, what it costs today and
 * who sells it. Reached by the name on `/ingredients`.
 *
 * <p><strong>Who can open it follows `GET /ingredients/{id}`</strong>, which declares `MANAGE_RECIPES`.
 * Each control inside follows its own endpoint: pack sizes `MANAGE_RECIPES`, the market rate
 * `MANAGE_INVENTORY`, and the vendors section `MANAGE_VENDORS` — which is also what the three reads it
 * needs declare, so the section is not fetched, let alone drawn, for anyone without it. The permission
 * to role translation is in `components/ingredient/access.tsx`, in one place.
 *
 * <p><strong>No mock exists for this page</strong>, so it is built from the pieces the mocked detail
 * pages already use — the back link and `PageHeader` of the invoice page, its `card` sections with a
 * `dl` of facts, and the vendor page's Supplies table — and screenshots at 1280 and 390 went to Rajeev
 * with the proof (docs/work/proof/T-286.md).
 *
 * <p><strong>Price history.</strong> Rajeev wants historic prices "as a proper graph on each
 * Ingredient's detail page", after UAT (§2 of the procurement requirements puts the chart out of this
 * build, and R-VEN-3 asks for arrows only, no list). Nothing is drawn for it now. Its place is the
 * Price card, under the market rate, at the card's full width; the card was named "Price" rather than
 * "Market rate" so it does not need renaming when the chart arrives.
 */
export default function IngredientDetailPage() {
  return (
    <RequireRole roles={holdersOf("MANAGE_RECIPES")}>
      <IngredientDetailView />
    </RequireRole>
  );
}

function IngredientDetailView() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const { appUser } = useAuth();
  const role = appUser?.role;
  const canRecipes = can("MANAGE_RECIPES", role);
  const canInventory = can("MANAGE_INVENTORY", role);
  const canVendors = can("MANAGE_VENDORS", role);

  const fetchIngredient = useCallback((token: string | undefined) => api.getIngredient(id, token), [id]);
  const { data: ingredient, error, loading, reload } = useAuthedQuery(fetchIngredient);

  /*
    The three reads behind MANAGE_VENDORS. Asked only of somebody who holds it — a refusal from any
    of them would otherwise put an error on a page the person is entitled to — and each answers empty
    for anyone else, so the hooks stay unconditional.
  */
  const fetchSupplies = useCallback(
    (token: string | undefined) => (canVendors ? api.listIngredientSupplies(id, token) : Promise.resolve([])),
    [id, canVendors]
  );
  const fetchVendors = useCallback(
    (token: string | undefined) => (canVendors ? api.listVendors(true, token) : Promise.resolve([])),
    [canVendors]
  );
  const fetchPreferred = useCallback(
    (token: string | undefined) => (canVendors ? api.listPreferredVendors(token) : Promise.resolve([])),
    [canVendors]
  );
  const supplies = useAuthedQuery(fetchSupplies);
  const vendors = useAuthedQuery(fetchVendors);
  const preferred = useAuthedQuery(fetchPreferred);

  /** After a vendor link or edit: the rows, and who holds the preference now, since a tick moves it. */
  function reloadVendors() {
    supplies.reload();
    preferred.reload();
  }

  // A supply lives on /supplies, not here (T-089); the back link goes to the list it came from.
  const back = ingredient?.supply
    ? { href: "/supplies", label: "← All supplies" }
    : { href: "/ingredients", label: "← All ingredients" };

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref={ingredient?.supply ? "/supplies" : "/ingredients"} />
      <main className="min-w-0 flex-1 px-4 py-10 sm:px-8">
        <div className="mx-auto max-w-content">
          <Link href={back.href} className="text-sm text-accent-text hover:underline">{back.label}</Link>

          {/* Only the first load replaces the page; a reload after a save keeps it on screen. */}
          {loading && !ingredient ? (
            <Loading label="Loading ingredient…" />
          ) : error ? (
            <div className="mt-6"><ErrorNotice error={error} /></div>
          ) : !ingredient ? null : (
            <div className="mt-3 grid gap-6">
              <PageHeader
                title={ingredient.name}
                // The category and unit are the first facts in the card below, so the header says
                // only the import label, where there is one: the list's own words for the list's own
                // label (`ADDED_BY_IMPORT` on /ingredients, Rajeev's wording of 2026-09-10), neutral as
                // it is there.
                subtitle={ingredient.libraryDerived ? <Badge>Added by a Recipe Import</Badge> : undefined}
              />

              <IngredientFacts ingredient={ingredient} />

              <PackSizes ingredient={ingredient} canEdit={canRecipes} onChanged={reload} />

              <MarketRate ingredient={ingredient} canEdit={canInventory} onChanged={reload} />

              {canVendors &&
                (supplies.error ? (
                  <ErrorNotice error={supplies.error} />
                ) : supplies.loading && !supplies.data ? (
                  <Loading label="Loading vendors…" />
                ) : (
                  <IngredientVendors
                    ingredient={ingredient}
                    supplies={supplies.data ?? []}
                    vendors={vendors.data ?? []}
                    preferred={preferred.data ?? []}
                    onChanged={reloadVendors}
                  />
                ))}
            </div>
          )}
        </div>
      </main>
    </div>
  );
}
