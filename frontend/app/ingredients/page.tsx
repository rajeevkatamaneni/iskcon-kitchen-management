"use client";

import Link from "next/link";
import { Suspense, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { EmptyState } from "@/components/ds/EmptyState";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { SegmentedControl } from "@/components/ds/SegmentedControl";
import { Badge } from "@/components/ds/Badge";
import { NOT_BOUGHT } from "@/components/ingredient/IngredientFacts";
import { api, toApiError, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";
import { unitLabel } from "@/lib/format";
import { RULED_TABLE, THEAD, TR, ACTIONS_ROW, TH_PRIMARY, TD_PRIMARY, TH_FIXED, TD_FIXED, TH_ACTIONS_FIXED, TD_ACTIONS_FIXED, TH_SECOND, TD_SECOND } from "@/components/ds/table";
import { Button } from "@/components/ds/Button";

/**
 * The label a recipe import leaves on the rows it created (T-119). Rajeev's exact words, chosen
 * over a shorter one on 2026-09-10 — do not reword them.
 *
 * <p>It is a label rather than a coloured row, and that was decided by the design system rather
 * than by taste. `DESIGN_SYSTEM.md:115` says of the semantic colours: "Never decorative. If one of
 * these appears, something is genuinely low, wrong, overdue, or complete." `warning` belongs to low
 * stock, expiring soon and an under-filled shift — things that are *deficient*. An ingredient an
 * import created is not deficient: the import picks a category from the name and takes the unit
 * from the book's own quantity, and both are often right. Forty imported recipes could put sixty
 * amber rows on this screen, which is wallpaper rather than a signal, and colour cannot be read
 * aloud, sorted or searched.
 *
 * <p>For the same reason it does not say "unchecked" or "needs details". All anybody knows about
 * one of these rows is how it got here, so that is all it says.
 */
const ADDED_BY_IMPORT = "Added by a Recipe Import";

/**
 * Show everything, or only what an import created.
 *
 * <p>A `SegmentedControl` over the whole list, which is the pattern `/ingredient-requests`,
 * `/donations` and `/leave` already use — one list with a filter over it rather than a second
 * screen — and it lives in the address bar for the same reason theirs do: the view is then
 * linkable, which is what lets the message on `/recipes` point straight at it.
 */
type Filter = "ALL" | "ADDED_BY_IMPORT";

const FILTERS: readonly { value: Filter; label: string }[] = [
  { value: "ALL", label: "All ingredients" },
  { value: "ADDED_BY_IMPORT", label: "Added by an import" },
];

/**
 * The filtered view's address. Deliberately *not* exported, and repeated as a literal in
 * `app/recipes/page.tsx` where the message links to it: a `page.tsx` in the App Router may only
 * export its default and Next's own reserved names, and an extra export here type-checks and passes
 * vitest before failing `next build` — which is a defect this repo has shipped before.
 */
const ADDED_BY_IMPORT_HREF = "/ingredients?show=added-by-import";

function filterFrom(value: string | null): Filter {
  return value === "added-by-import" ? "ADDED_BY_IMPORT" : "ALL";
}

function hrefFor(filter: Filter): string {
  return filter === "ADDED_BY_IMPORT" ? ADDED_BY_IMPORT_HREF : "/ingredients";
}

export default function IngredientsPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      {/* useSearchParams — for the confirmation a new ingredient comes back with — needs a boundary. */}
      <Suspense>
        <IngredientsView />
      </Suspense>
    </RequireRole>
  );
}

function IngredientsView() {
  const { appUser, getToken } = useAuth();
  const isAdmin = appUser?.role === "TEMPLE_ADMIN";
  const { data, error, loading, reload } = useAuthedQuery(api.listIngredients);
  const ingredients = data ?? [];

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);

  // Adding an ingredient happens on /ingredients/new and ends back here, so the confirmation has to
  // travel in the URL. Captured behind a ref because setting it re-renders, and a router object
  // that is new on each render would otherwise turn this effect into a loop.
  const router = useRouter();
  const params = useSearchParams();
  const added = params.get("added");
  const filter = filterFrom(params.get("show"));
  const [flash, setFlash] = useState<string | null>(null);
  const captured = useRef(false);
  useEffect(() => {
    if (captured.current || !added) return;
    captured.current = true;
    setFlash(added);
    // Strips `added` and keeps `show`. Replacing with a bare "/ingredients" here would have thrown
    // anybody who added an ingredient while filtered back to the whole catalogue, which reads as
    // the filter having failed rather than as the parameter having been tidied away.
    router.replace(hrefFor(filter));
  }, [added, filter, router]);

  // Replaced rather than pushed, as on `/ingredient-requests`: the filter narrows one screen, and
  // Back should leave the screen rather than walk back through every tab somebody flicked across.
  function choose(next: Filter) {
    router.replace(hrefFor(next));
  }

  /*
    Counted from the list this screen is already holding rather than from the count endpoint, which
    exists for `/recipes` — a second request here would ask the server a question it just answered.

    It falls on its own, because `reload()` after a save refetches the list and the row that was
    saved comes back with the mark cleared. That is the whole point of part 4: without it the filter
    is a list that only grows, and a list that only grows is one nobody opens twice.
  */
  /*
    T-089 — this screen is now the FOOD half of the catalogue and nothing else.

    Rajeev ruled on 2026-09-08 that Supplies gets a menu item of its own, and the line he drew is
    consumption: anything used up by use that is not food is a supply (LPG, kerosene, cleaning
    liquid, bulbs, brooms), and anything not consumed is equipment (ladders, extension boxes,
    plastic stools) whatever it costs. D-1's boolean already says exactly that, so nothing about
    the row moved — the split is here, on the client, where the two screens each take their half.

    Filtered here rather than asked of the server, for the reason `/equipment` gives about its own
    three filters: every screen in the app loads the whole catalogue through the one
    `api.listIngredients`, and a supply is still offered by the inventory, ingredient-request,
    purchase-order, in-kind-donation and vendor-supply pickers on purpose. A server-side split
    would have had to be undone in five places to get back to where D-1 already is.
  */
  /*
    T-402. A marked ingredient is NOT filtered out of this screen, and the difference from the
    supply filter above is worth stating because the two flags look alike from here. `/supplies`
    exists, so a supply shown on this screen would be on two lists at once; there is no "things the
    temple never buys" screen and there should not be one — water is an ingredient, it goes in
    recipes, it draws stock and it is costed. Only the shopping list treats it differently, so only
    the shopping list leaves it out. Here it is a badge on the row.
  */
  const food = ingredients.filter((i) => !i.supply);
  const addedByImport = food.filter((i) => i.libraryDerived);
  const shown = filter === "ADDED_BY_IMPORT" ? addedByImport : food;

  // Let the banner stand, then clear itself. Keyed on `flash` so stripping the param above does not
  // cut the timer short.
  useEffect(() => {
    if (!flash) return;
    const timer = setTimeout(() => setFlash(null), 6000);
    return () => clearTimeout(timer);
  }, [flash]);

  /*
    "Use Curd" from an add screen used to land here as `?edit=<id>`, which opened the existing
    ingredient's editing row on this list (T-251). There is no editing row any more (T-441), so both
    add screens now send it straight to `/ingredients/<id>/edit` — one hop instead of two, and it
    works for a supply without this screen having to forward it, because the edit screen is the same
    screen for both halves of the catalogue.
  */

  async function run(mutation: (token: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    setActionError(null);
    try {
      await mutation(await getToken());
      reload();
      return true;
    } catch (e) {
      setActionError(toApiError(e, failure));
      return false;
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/ingredients" />
      <main className="min-w-0 flex-1 px-4 py-10 sm:px-8">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div className="min-w-0 grow basis-60">
              <h1>Ingredients</h1>
              <p className="mt-1 text-ink-secondary">
                The shared vocabulary for recipes, inventory and orders. Everything the temple
                cooks with lives here, and everything else it uses up lives under Supplies.
              </p>
            </div>
            {/*
              The one way to the merge tool (R-DUP-3, T-276), which has no menu item. Shown only to
              those who hold MERGE_INGREDIENTS — the Temple Admin, the same `isAdmin` test this
              screen already uses — because anyone else following it would only be told "Not your
              page". Style E beside the primary: two actions, and adding is the everyday one.
            */}
            <div className="flex flex-wrap gap-3">
              {isAdmin && (
                <ButtonLink href="/ingredients/merge" variant="secondary">Merge duplicates</ButtonLink>
              )}
              <ButtonLink href="/ingredients/new">Add an ingredient</ButtonLink>
            </div>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

          {flash && (
            <div className="mb-6">
              {/*
                It said "It can be ordered and stocked now, and — if it is food — put into a
                recipe" while D-1's supplies shared this screen and the banner could not tell which
                had just been added. Since T-089 it can: /ingredients/new adds food and nothing
                else, so the hedge goes and the sentence says the thing it was hedging about.
              */}
              <InlineNotice tone="success" autoDismiss title={`${flash} was added.`}>
                It can go into a recipe now, and be ordered and stocked.
              </InlineNotice>
            </div>
          )}

          {/*
            Standing context with a number in it, and it appears only while the number is above
            zero — so a temple that has never imported a recipe never sees it, and one that has
            reviewed everything an import left stops seeing it. That is what makes the filter
            underneath a queue rather than a permanent tab.

            `info` rather than `warning`, for the reason set out on ADDED_BY_IMPORT above: an
            ingredient an import created is not deficient, and `warning` in this system means
            something is genuinely low, wrong or overdue.

            It is not dismissible either. A confirmation fades because what it confirms has already
            happened; this is a list of rows still to be looked at.
          */}
          {!loading && !error && addedByImport.length > 0 && (
            <div className="mb-6">
              <InlineNotice
                tone="info"
                title={
                  addedByImport.length === 1
                    ? "1 ingredient was added by a recipe import"
                    : `${addedByImport.length} ingredients were added by a recipe import`
                }
              >
                An import creates any ingredient a recipe needs that this temple doesn’t already
                have, and picks its category and unit itself. Filter to them below and check each
                one — saving an ingredient clears its label.
              </InlineNotice>
            </div>
          )}

          {/*
            Shown only once there is something to filter to. A two-option control that is
            permanently half-empty is a control that teaches people it does nothing.
          */}
          {!loading && !error && (addedByImport.length > 0 || filter === "ADDED_BY_IMPORT") && (
            <div className="mb-6">
              <SegmentedControl
                label="Filter ingredients by how they were added"
                options={FILTERS}
                value={filter}
                onChange={choose}
              />
            </div>
          )}

          {loading ? (
            <Loading label="Loading ingredients…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : food.length === 0 ? (
            /*
              Counted over the food half rather than the whole catalogue (T-089). A temple that has
              catalogued nothing but LPG and soap has an empty Ingredients screen and a full
              Supplies one, and telling it "no ingredients yet" is the true and useful thing to say
              — the alternative was a table header over no rows.
            */
            <EmptyState
              title="No ingredients yet"
              action={<ButtonLink href="/ingredients/new">Add an ingredient</ButtonLink>}
            >
              Every recipe, stock item and order is written in these words, so this is the list they
              all start from.
            </EmptyState>
          ) : shown.length === 0 ? (
            /*
              The filter emptied rather than the catalogue. Its own state, saying so: the panel
              above says "No ingredients yet" and offers Add, which would be a lie here and would
              send somebody off to type a row they do not need.

              This is the state the whole task is aiming at — every ingredient an import created has
              been looked at — so it says that rather than apologising for a blank list.
            */
            <EmptyState
              title="Nothing left from an import"
              action={<ButtonLink href="/ingredients">Show all ingredients</ButtonLink>}
            >
              Every ingredient a recipe import added has been edited and saved since, so none of
              them is still labelled.
            </EmptyState>
          ) : (
            <div className="table-wrap overflow-x-auto">
              <table className={RULED_TABLE}>
                <thead className={THEAD}>
                  <tr>
                    <th className={TH_PRIMARY}>Name</th>
                    <th className={TH_SECOND}>Category</th>
                    <th className={TH_FIXED}>Unit</th>
                    {/*
                      There was a Type column here, reading "Supply" or "Food" on every row. T-089
                      took it out: with supplies on a screen of their own, every row on this one is
                      food, and a column that prints the same word all the way down is the
                      wallpaper the badge was deliberately avoiding when only the exception was
                      marked. Moving a mis-catalogued row across was an act on the editing row until
                      T-441, and is now no act at all: Rajeev removed "Move to Supplies" on
                      2026-09-20 — "Not needed. they can delete and recreate as a supply."

                      Rajeev asked for exactly this again on 2026-09-10 (T-121), having seen the
                      column on the deployed build, which is a version behind: "Keep type for
                      clasifying things in the backend and driving logic. There is no reason for it
                      be PROUDLY displayed on UI." Nothing was left to remove and nothing was
                      removed — but the ruling is worth recording HERE, because the reason the
                      column is gone is not the reason it must stay out of the API. The flag is
                      load-bearing in three places: this screen exists as the `!supply` half of the
                      catalogue and `/supplies` as the other, `RecipeService` refuses a supply on a
                      recipe line with KMS-400127 for the raw POST that never met a picker, and five
                      other pickers deliberately DO offer supplies. Take the field out of the
                      payload to match the screen and all three break. Display only.
                    */}
                    <th className={TH_FIXED}>Ekadashi</th>
                    <th className={TH_ACTIONS_FIXED}><span className="sr-only">Actions</span></th>
                  </tr>
                </thead>
                <tbody>
                  {shown.map((ing) => (
                    <tr key={ing.id} className={TR}>
                      {/*
                        The label sits under the name rather than in a column of its own, and that
                        is not only about width: a column would print something on every row —
                        a badge, or the blank where one isn't — and would say of a hand-typed
                        ingredient that it is *not* import-created, which is a fact nobody needs.
                        Under the name it is the exception speaking, which is the same call the
                        Type cell already makes by badging only supplies.

                        It also keeps the header at five columns — Name, Category, Unit, Ekadashi,
                        Actions — and since T-441 there is no editing row that has to match it.
                      */}
                      <td className={TD_PRIMARY}>
                      {/* The way to the ingredient's own page (Q-10, T-286): its pack sizes, market
                          rate, vendors and — since T-441 — its Edit button all live there. The name
                          and not a button, so the link reads as the thing it opens; and since T-441
                          it is the only way in, which is what Rajeev asked for on 2026-09-20. */}
                        <Link href={`/ingredients/${ing.id}`} className="text-accent-text hover:underline">
                          {ing.name}
                        </Link>
                        {/*
                          Two labels that can both apply, in one wrapping row under the name
                          (T-402). Under the name for exactly the reason the import label is —
                          the exception speaks, and nothing is printed on the rows that are
                          ordinary — and in a `flex-wrap` so a row carrying both does not push the
                          cell wider than the column.

                          Neutral, like the import badge and unlike the Ekadashi cell. Amber is
                          for something low, wrong or overdue; a temple that has decided it does
                          not buy water has nothing wrong with it.
                        */}
                        {(ing.libraryDerived || ing.notBought) && (
                          <span className="mt-1 flex flex-wrap gap-1">
                            {ing.libraryDerived && <Badge>{ADDED_BY_IMPORT}</Badge>}
                            {ing.notBought && <Badge>{NOT_BOUGHT}</Badge>}
                          </span>
                        )}
                      </td>
                      <td className={`${TD_SECOND} text-ink-secondary`}>{ing.category}</td>
                      <td className={`${TD_FIXED} text-ink-secondary`}>{unitLabel(ing.unit)}</td>
                      {/*
                        The only dietary flag a row carries, since D-18 deleted the other one that
                        used to sit beside it.

                        **A LABEL, NOT A CONTROL, AND THAT IS THE WHOLE SHAPE OF IT.** It was a
                        button here from T-045 until 2026-09-10 — one click on the row flipped the
                        flag — and Rajeev took it out looking at the deployed screen:

                          "Ingredients don't go in and out of Ekadashi restriction EVER. They are
                           either IN or OUT. Once set CORRECTLY, there is no reason to change it."

                        Which settles two things at once. A permanent fact about an ingredient
                        should not sit behind a control that one stray click reverses, in a table
                        somebody is scanning rather than operating; and a thing that reads as a
                        label should not turn out to be a button, because the only way to discover
                        that it was is to have already changed something. Setting it is done where
                        every other fact about the ingredient is set — on `/ingredients/[id]/edit`
                        since T-441, deliberately, with a Save and a Cancel at the top of it.

                        So this cell renders identically for everybody, admin or not. Who is
                        *offered* the checkbox is decided on the edit screen; it does not decide
                        what this row looks like, because the state is the same fact whoever is
                        reading it.
                      */}
                      <td className={TD_FIXED}>
                        {ing.ekadashiProhibited ? (
                          // Blue, Ekadashi's colour everywhere else: a classification, not a
                          // warning (Rajeev, 2026-09-18, T-227). The grain confirm is the warning.
                          <span className="rounded-control bg-info-bg px-2 py-1 text-xs text-info font-semibold">Prohibited</span>
                        ) : (
                          <span className="text-xs text-ink-muted">Allowed</span>
                        )}
                      </td>
                      {/*
                        Delete alone since T-441. Rajeev, 2026-09-20: "remove the edit button and
                        move the functionality the current edit button provides into the edit
                        screen" — so changing an ingredient starts at its name, which opens it in
                        view mode, and Edit is on that page. Delete stays on the row because it is
                        not a change to the thing but a removal of it, and it is the one action a
                        person does while scanning the list.
                      */}
                      <td className={TD_ACTIONS_FIXED}>
                        <div className={ACTIONS_ROW}>
                          <Button variant="danger" size="sm" disabled={busy} onClick={() => run((t) => api.deleteIngredient(ing.id, t), "That ingredient is in use, or couldn’t be removed.")}>Delete</Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </main>
    </div>
  );
}
