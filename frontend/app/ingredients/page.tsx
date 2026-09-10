"use client";

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
import { splitAliases } from "@/components/IngredientForm";
import { api, toApiError, type ApiError, type IngredientView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";
import { FOOD_UNITS, unitLabel } from "@/lib/format";
import { TABLE, TD_ACTIONS, TD_TEXT, THEAD, TH_ACTIONS, TH_TEXT, TR, ACTIONS_ROW, WRAP } from "@/components/ds/table";
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

/*
  The editing row's field styling, named once rather than repeated on five controls.

  `FIELD` wraps a control in its `<label>`, which is what puts the word on the screen AND gives the
  control its accessible name in one element — the reason Rajeev's "a placeholder is not a label"
  is fixed here rather than by adding a second `aria-label` nobody can see. It matches the pattern
  `components/IngredientForm.tsx` already uses on the add screen, a size down, because these sit
  inside a table row rather than on a page.
*/
const FIELD = "flex flex-col gap-1 text-xs text-ink-secondary";
const FIELD_LABEL = "font-medium text-ink";
const FIELD_INPUT = "min-h-touch w-full rounded-control border border-hairline px-2";

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
  const [editing, setEditing] = useState<string | null>(null);

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
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-8 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Ingredients</h1>
              <p className="mt-1 text-ink-secondary">
                The shared vocabulary for recipes, inventory and orders. Everything the temple
                cooks with lives here, and everything else it uses up lives under Supplies.
              </p>
            </div>
            <ButtonLink href="/ingredients/new">Add an ingredient</ButtonLink>
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
              <table className={TABLE}>
                <thead className={THEAD}>
                  <tr>
                    <th className={`${TH_TEXT} ${WRAP}`}>Name</th>
                    <th className={TH_TEXT}>Category</th>
                    <th className={TH_TEXT}>Unit</th>
                    {/*
                      There was a Type column here, reading "Supply" or "Food" on every row. T-089
                      took it out: with supplies on a screen of their own, every row on this one is
                      food, and a column that prints the same word all the way down is the
                      wallpaper the badge was deliberately avoiding when only the exception was
                      marked. Moving a mis-catalogued row across is now an act on the editing row
                      rather than a state to read on every row that is fine where it is.

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
                    <th className={TH_TEXT}>Ekadashi</th>
                    <th className={TH_ACTIONS}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {shown.map((ing) =>
                    editing === ing.id ? (
                      <EditRow
                        key={ing.id}
                        ingredient={ing}
                        canSetEkadashi={isAdmin}
                        busy={busy}
                        onCancel={() => setEditing(null)}
                        onSave={async (input) => {
                          const ok = await run((t) => api.updateIngredient(ing.id, input, t), "We couldn’t save that.");
                          if (ok) setEditing(null);
                        }}
                      />
                    ) : (
                      <tr key={ing.id} className={TR}>
                        {/*
                          The label sits under the name rather than in a column of its own, and that
                          is not only about width: a column would print something on every row —
                          a badge, or the blank where one isn't — and would say of a hand-typed
                          ingredient that it is *not* import-created, which is a fact nobody needs.
                          Under the name it is the exception speaking, which is the same call the
                          Type cell already makes by badging only supplies.

                          It also keeps the header at five columns, which is what the editing row's
                          cell count is measured against — Name, Category, Unit, Ekadashi, Actions.
                          (It said six until T-121: the Type column had gone in T-089 and this
                          sentence had not noticed. The test that actually holds the two in step is
                          "keeps the editing row the same width as the header", which counts both
                          rather than trusting a number written down here.)
                        */}
                        <td className={`${TD_TEXT} ${WRAP}`}>
                          <span>{ing.name}</span>
                          {ing.libraryDerived && (
                            <span className="mt-1 flex">
                              <Badge>{ADDED_BY_IMPORT}</Badge>
                            </span>
                          )}
                        </td>
                        <td className={`${TD_TEXT} text-ink-secondary`}>{ing.category}</td>
                        <td className={`${TD_TEXT} text-ink-secondary`}>{unitLabel(ing.unit)}</td>
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
                          that it was is to have already changed something. Setting it is now done
                          where every other fact about the ingredient is set — inside the editing
                          row, deliberately, with a Save at the end of it.

                          So this cell renders identically for everybody, admin or not. `isAdmin`
                          still decides who is *offered* the checkbox in `EditRow`; it no longer
                          decides what this row looks like, because the state is the same fact
                          whoever is reading it.
                        */}
                        <td className={TD_TEXT}>
                          {ing.ekadashiProhibited ? (
                            <span className="rounded-sm bg-warning-bg px-2 py-1 text-xs text-warning font-semibold">Prohibited</span>
                          ) : (
                            <span className="text-xs text-ink-muted">Allowed</span>
                          )}
                        </td>
                        <td className={TD_ACTIONS}>
                          <div className={ACTIONS_ROW}>
                            <Button variant="ghost" size="sm" onClick={() => setEditing(ing.id)}>Edit</Button>
                            <Button variant="danger" size="sm" disabled={busy} onClick={() => run((t) => api.deleteIngredient(ing.id, t), "That ingredient is in use, or couldn’t be removed.")}>Delete</Button>
                          </div>
                        </td>
                      </tr>
                    )
                  )}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </main>
    </div>
  );
}

/**
 * The editing row, laid out in the order Rajeev gave on 2026-09-10: **Name · Aliases · Units ·
 * Ekadashi**, then Actions.
 *
 * <p><strong>Where Category went, since his list did not mention it.</strong> Under Name, in the
 * same cell. Deleting it was never on the table — it is the one column on this table that says
 * something different on every row, and `/ingredients/new` requires it — so the question was only
 * where it goes in an order that names four fields for the four cells before Actions. It shares
 * Name's cell because they are the pair that answer "what is this thing", because that cell is the
 * wide `WRAP` one and has the room, and because Category is the field a recipe import *guesses*
 * (from the name, which is now directly above it) — so on the rows this screen most wants reviewed,
 * the guess and the thing it was guessed from are read together. Rajeev's four then fall across the
 * remaining cells in exactly his order.
 *
 * <p><strong>Every field is labelled, and the labels are visible.</strong> Aliases in particular
 * used to be a bare input with `placeholder="Aliases"`, which Rajeev called out: a placeholder is
 * not a label. It vanishes the moment somebody types — so the one person who cannot see what the
 * box is for is the one who has already put something in it — and it is not announced as the
 * field's name. The `<label>` wrapper here gives each control the same accessible name it had from
 * `aria-label` while also putting the word on the screen, so nothing that queried these by label
 * has to change.
 *
 * <p><strong>The Ekadashi checkbox is offered only to a Temple Admin, and the row says so.</strong>
 * The permission has not moved: `MANAGE_DIETARY_POLICY` still decides who may set the flag, and
 * `IngredientService.update` still refuses anyone else — the checkbox is not the guard. Everyone
 * else sees the state, unchangeable, exactly as the view row shows it. What that person's save
 * sends is `ekadashiProhibited: undefined`, which `JSON.stringify` omits and the server reads as
 * "leave it alone"; sending `false` on their behalf would ask to un-prohibit a row they were never
 * shown a control for.
 */
function EditRow({
  ingredient,
  canSetEkadashi,
  busy,
  onSave,
  onCancel,
}: {
  ingredient: IngredientView;
  canSetEkadashi: boolean;
  busy: boolean;
  onSave: (input: {
    name: string;
    category: string;
    unit: string;
    supply: boolean;
    ekadashiProhibited?: boolean;
    aliases: string[];
  }) => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState(ingredient.name);
  const [category, setCategory] = useState(ingredient.category);
  const [unit, setUnit] = useState(ingredient.unit);
  /*
    Not "is this a supply" any more, but "move it" (T-089).

    The box used to be seeded from the row, because the row could be either thing and
    `UpdateIngredientInput.supply` is required — an unseeded box turned every edited supply back
    into food on Save. With the catalogue split across two screens that whole hazard is gone: every
    row reachable from here is food, so the box starts empty and asks the only question left, which
    is whether this one belongs on the other screen. Ticking it and saving sends `supply: true` and
    the row leaves this list on the reload.

    Still sent explicitly on every save, ticked or not. The server field is a primitive `boolean`
    and an absent key deserialises to `false`, so a payload that leaves it off would be *right* here
    by luck and wrong the moment this component is copied.
  */
  const [move, setMove] = useState(false);
  const [aliases, setAliases] = useState(ingredient.aliases.join(", "));
  /*
    Seeded from the row, unlike the move box above, and the difference is what each one asks. "Move
    to Supplies" asks a question that is fresh every time the row is opened; this one shows a
    standing fact, so it has to open holding the value the row already has or Save would silently
    un-prohibit every prohibited ingredient anybody edited.

    Which is also why nothing here compares it against `ingredient.ekadashiProhibited` to decide
    whether the save "counted" for the import label. That comparison is the server's — see
    `IngredientService.update`. A client that scored its own save would be handing the server a
    verdict to trust, and a raw POST could then clear the label off the whole catalogue without
    changing a value.
  */
  const [ekadashiProhibited, setEkadashiProhibited] = useState(ingredient.ekadashiProhibited);

  return (
    <tr className="border-t border-hairline bg-sunken align-top">
      {/* Name, with Category beneath it — see the note above this component. */}
      <td className={`${TD_TEXT} ${WRAP}`}>
        <label className={FIELD}>
          <span className={FIELD_LABEL}>Name</span>
          <input value={name} onChange={(e) => setName(e.target.value)} className={FIELD_INPUT} />
        </label>
        <label className={`${FIELD} mt-2`}>
          <span className={FIELD_LABEL}>Category</span>
          <input value={category} onChange={(e) => setCategory(e.target.value)} className={FIELD_INPUT} />
        </label>
      </td>
      {/*
        Aliases sits second now, next to the two name fields, because an alias IS a name — it is
        what the shopping list and the vendor call the same thing. It used to sit in the last cell,
        borrowed from the Ekadashi column while that column held nothing editable; now that Ekadashi
        has a control of its own, it takes a cell of its own.

        The hint is in the label rather than in a placeholder, so it survives somebody typing.
      */}
      <td className={TD_TEXT}>
        <label className={FIELD}>
          <span className={FIELD_LABEL}>Aliases (comma-separated)</span>
          <input value={aliases} onChange={(e) => setAliases(e.target.value)} className={FIELD_INPUT} />
        </label>
      </td>
      <td className={TD_TEXT}>
        <label className={FIELD}>
          <span className={FIELD_LABEL}>Unit</span>
          <select value={unit} onChange={(e) => setUnit(e.target.value)} className={FIELD_INPUT}>
            {FOOD_UNITS.map((u) => <option key={u} value={u}>{unitLabel(u)}</option>)}
          </select>
        </label>
      </td>
      {/*
        The two checkboxes, together in the cell under the Ekadashi header. They read as a pair
        because they are one: both are standing facts about the ingredient rather than edits to its
        text, and neither prints anything on a row that does not need it.
      */}
      <td className={TD_TEXT}>
        {canSetEkadashi ? (
          <label className="flex items-center gap-2 text-xs text-ink-secondary">
            <input
              type="checkbox"
              aria-label="Ekadashi-prohibited"
              checked={ekadashiProhibited}
              onChange={(e) => setEkadashiProhibited(e.target.checked)}
              className="h-5 w-5 rounded-sm border-hairline-strong accent-accent"
            />
            Ekadashi-prohibited
          </label>
        ) : ingredient.ekadashiProhibited ? (
          <span className="rounded-sm bg-warning-bg px-2 py-1 text-xs text-warning font-semibold">Prohibited</span>
        ) : (
          <span className="text-xs text-ink-muted">Allowed</span>
        )}
        <label className="mt-2 flex items-center gap-2 text-xs text-ink-secondary">
          <input
            type="checkbox"
            aria-label="Move to Supplies"
            checked={move}
            onChange={(e) => setMove(e.target.checked)}
            className="h-5 w-5 rounded-sm border-hairline-strong accent-accent"
          />
          Move to Supplies
        </label>
      </td>
      <td className={TD_ACTIONS}>
        <div className={ACTIONS_ROW}>
          <Button
            size="sm"
            disabled={busy}
            onClick={() =>
              onSave({
                name,
                category,
                unit,
                supply: move,
                /*
                  Spread rather than `ekadashiProhibited: canSetEkadashi ? x : undefined`, which
                  reads the same and is not: that form still CREATES the key, holding `undefined`.
                  `JSON.stringify` happens to drop such a key, so the wire would look right — and
                  every test that inspected the payload object would see a field this person was
                  never offered. The key is absent or it is a value; there is no third state.
                */
                ...(canSetEkadashi ? { ekadashiProhibited } : {}),
                aliases: splitAliases(aliases),
              })
            }
          >
            Save
          </Button>
          <Button variant="ghost" size="sm" onClick={onCancel}>Cancel</Button>
        </div>
      </td>
    </tr>
  );
}
