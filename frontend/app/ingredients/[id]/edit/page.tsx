"use client";

import { useCallback, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Loading } from "@/components/Loading";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { IngredientForm } from "@/components/IngredientForm";
import { holdersOf } from "@/components/ingredient/access";
import {
  DuplicateIngredientPrompt,
  lookalikeFrom,
  type Lookalike,
} from "@/components/DuplicateIngredientPrompt";
import {
  api,
  toApiError,
  type ApiError,
  type CreateIngredientInput,
  type UpdateIngredientInput,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * Named so the header's Save button can submit a form it is not inside.
 *
 * <p>A module constant and not exported: a `page.tsx` in the App Router may export nothing but its
 * default, and an extra export type-checks and passes vitest before failing `next build`.
 */
const FORM = "edit-ingredient";

/**
 * Changing one ingredient — or one supply (T-441).
 *
 * <h3>Why this screen exists</h3>
 *
 * <p>Rajeev, 2026-09-20: "remove the edit button and move the functionality the current edit button
 * provides into the edit screen. When the user clicks on the Ingrident Name, it open in the view mode,
 * then they see the edit button, Click on that and it goes to the edit screen wchi shows save and
 * cancel." Before this there were two places to change an ingredient and neither was whole: an editing
 * row on the list held the four fields nothing else could reach — aliases, unit, the Ekadashi flag and
 * the category — while the ingredient's own page held its packs, its price and its vendors. So a person
 * who opened the thing by name could not rename it, and a person who could rename it was looking at a
 * table rather than at the ingredient.
 *
 * <p>The shape is Staff's, which he approved two days ago and asked the rest of the app to follow:
 * `/staff` opens a record by name, `/staff/[id]` reads it with Edit top right, `/staff/[id]/edit` is
 * the form with Cancel and Save. This is the third of those three for the catalogue.
 *
 * <p><strong>Cancel and Save both return to the ingredient's own page, not to the list</strong>, which
 * is the one place this differs from `/staff/[id]/edit` (it goes back to the register). Edit is pressed
 * on the detail page now, so the detail page is where the person was; returning them to a list they may
 * not have come from would lose their place and hide the change they just made. The confirmation rule
 * (Rule 8 — commit returns to the list with the confirmation waiting there) was written when a form's
 * only caller was a list; here the page that returns shows the saved values themselves, which says the
 * same thing more plainly than a banner would.
 *
 * <h3>Both halves of the catalogue</h3>
 *
 * <p>One screen for food and for supplies, as `/ingredients/[id]` beside it is one page for both. D-1
 * settled that a supply is an `ingredients` row carrying `is_supply`, so there is one endpoint, one set
 * of fields and one form. What differs is the wording, the sidebar and the Ekadashi box, and each of
 * those is decided from `ingredient.supply` rather than from the URL somebody arrived by.
 *
 * <p><strong>There is no "Move to Supplies" here, and its absence is deliberate.</strong> Rajeev, asked
 * about the checkbox the list's editing row carried: "Not needed. they can delete and recreate as a
 * supply." Its mirror on the supplies list ("Move to Ingredients") goes with it, for his reason rather
 * than by omission — the same argument reads the same way in both directions. `UpdateIngredientInput`
 * still requires `supply` because the Java field is a primitive and an absent key means food, so this
 * screen states the value the row already has, every time, through the form's `kind`.
 *
 * <p><strong>What is not on this form, and where it is instead.</strong> The buying flag
 * (`notBought`) is not in `UpdateIngredientInput` at all — it has a route of its own that is audited on
 * every move — so it stays a checkbox on the ingredient's own page, beside the pack sizes, the market
 * rate and the vendors. Those four are alike in the way Staff's documents and conduct notes are alike:
 * each saves the moment it is pressed and none of them is undone by the Cancel on this screen.
 */
export default function EditIngredientPage() {
  return (
    <RequireRole roles={holdersOf("MANAGE_RECIPES")}>
      <EditIngredientScreen />
    </RequireRole>
  );
}

function EditIngredientScreen() {
  const id = useParams<{ id: string }>().id;
  const router = useRouter();
  const { appUser, getToken } = useAuth();
  // `MANAGE_DIETARY_POLICY` in the server's words. Read off the same map every other control on the
  // ingredient's page reads, so a grant that moves moves in one file.
  const canSetEkadashi = appUser?.role === "TEMPLE_ADMIN";

  const fetchIngredient = useCallback((token: string | undefined) => api.getIngredient(id, token), [id]);
  const { data: ingredient, error, loading } = useAuthedQuery(fetchIngredient);

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  // The rename the server said looks like another ingredient (R-DUP-2), held with what was typed so
  // the confirmed save sends exactly the same edit again. It moved here with the form it belongs to.
  const [lookalike, setLookalike] = useState<{ input: UpdateIngredientInput; existing: Lookalike } | null>(
    null
  );

  const detail = `/ingredients/${id}`;
  const supply = ingredient?.supply === true;

  async function save(input: UpdateIngredientInput) {
    setBusy(true);
    setActionError(null);
    try {
      await api.updateIngredient(id, input, await getToken());
      setLookalike(null);
      router.push(detail);
    } catch (e) {
      const existing = lookalikeFrom(e);
      if (existing) {
        // Not an error to read but a question to answer, so it is asked in the prompt rather than
        // printed above the form.
        setLookalike({ input, existing });
      } else {
        setLookalike(null);
        setActionError(toApiError(e, "We couldn’t save that change."));
      }
      setBusy(false);
    }
  }

  /**
   * The form collects a create payload — the same six fields — and this turns it into an update.
   *
   * <p>Two of them are not a copy. `supply` is the row's own stored value, never the form's guess,
   * because there is no control for it any more. And `ekadashiProhibited` is sent only by somebody who
   * was offered the box: `UpdateIngredientRequest` holds a boxed `Boolean` where null means "leave it
   * alone", so omitting the key is a statement rather than a silence, and it is the only true statement
   * a Kitchen Manager can make — they may rename a prohibited ingredient, they may not decide what is
   * prohibited. The key is absent or it is a value; spreading it in is how you get "absent", because
   * `{ k: undefined }` still creates the key.
   */
  function submit(input: CreateIngredientInput) {
    if (!ingredient) return;
    void save({
      name: input.name,
      category: input.category,
      unit: input.unit,
      supply: ingredient.supply,
      ...(canSetEkadashi ? { ekadashiProhibited: input.ekadashiProhibited } : {}),
      aliases: input.aliases,
    });
  }

  return (
    <FocusScreen
      task={supply ? "Update supply" : "Update ingredient"}
      who={ingredient?.name}
      activeHref={supply ? "/supplies" : "/ingredients"}
      actions={
        <>
          <ButtonLink href={detail} variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={FORM} disabled={busy || !ingredient}>
            Save changes
          </Button>
        </>
      }
    >
      {actionError && <ErrorNotice error={actionError} />}

      {loading && !ingredient ? (
        <Loading label="Loading ingredient…" />
      ) : error ? (
        <ErrorNotice error={error} />
      ) : !ingredient ? null : (
        <IngredientForm
          formId={FORM}
          kind={ingredient.supply ? "SUPPLY" : "FOOD"}
          ingredient={ingredient}
          isAdmin={canSetEkadashi}
          /*
            Never on this form. The flag is not part of the update payload — see the note on the
            component above — and offering a box that the Save button cannot send would be a
            control that silently does nothing.
          */
          canMarkNotBought={false}
          busy={busy}
          error={null}
          onSubmit={submit}
        />
      )}

      {lookalike && (
        <DuplicateIngredientPrompt
          candidate={lookalike.input.name}
          existing={lookalike.existing}
          busy={busy}
          /*
            "Use Curd" on a rename: this ingredient keeps its name — the edit is dropped, nothing is
            saved — and Curd's own edit screen opens instead, which is where "Use Curd" lands from the
            add screens too. Merging the two rows is the merge tool's job (R-DUP-3), not a rename's.
          */
          onUse={() => {
            const to = lookalike.existing.id;
            setLookalike(null);
            router.push(`/ingredients/${to}/edit`);
          }}
          onDifferent={() => void save({ ...lookalike.input, confirmDifferent: true })}
          onDismiss={() => setLookalike(null)}
        />
      )}
    </FocusScreen>
  );
}

