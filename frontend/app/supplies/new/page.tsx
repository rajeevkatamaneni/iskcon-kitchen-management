"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { IngredientForm } from "@/components/IngredientForm";
import {
  DuplicateIngredientPrompt,
  lookalikeFrom,
  type Lookalike,
} from "@/components/DuplicateIngredientPrompt";
import { api, toApiError, type ApiError, type CreateIngredientInput } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";

/**
 * Add a supply (T-089).
 *
 * <p>The twin of `/ingredients/new`, down to the endpoint it posts to. That is not laziness: D-1
 * ruled that a supply is a row in the ingredient catalogue and not a second catalogue, because LPG
 * and leaf plates are bought from a vendor, received, stored, used up and wanted back when they run
 * low — the ingredient lifecycle, item for item. What Rajeev added on 2026-09-08 is a way in of
 * their own, so a person cataloguing the cleaning cupboard is not working through a screen that
 * calls a mop an ingredient.
 *
 * <p>So there is no `createSupply`, no supply table and no migration behind this screen. The one
 * thing it does that its twin does not is state {@code kind="SUPPLY"}, which puts `supply: true` on
 * the payload where the removed checkbox used to.
 */

/** Named so the header's primary button can submit the form in the body. */
const FORM = "add-supply";

export default function NewSupplyPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <NewSupplyView />
    </RequireRole>
  );
}

function NewSupplyView() {
  const { getToken } = useAuth();
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  // The name the server said looks like one the temple already has (R-DUP-2), with what was typed,
  // so "It's a different ingredient" can send the same thing again with the confirmation on it.
  const [lookalike, setLookalike] = useState<{ input: CreateIngredientInput; existing: Lookalike } | null>(null);

  async function add(input: CreateIngredientInput) {
    setBusy(true);
    setError(null);
    try {
      await api.createIngredient(input, await getToken());
      // Rule 8: back to the list, with the confirmation waiting there rather than here.
      router.push(`/supplies?added=${encodeURIComponent(input.name)}`);
    } catch (e) {
      const existing = lookalikeFrom(e);
      if (existing) {
        // Not an error to read but a question to answer, so it is asked in the prompt rather than
        // printed in the error notice above the form.
        setLookalike({ input, existing });
      } else {
        setLookalike(null);
        setError(toApiError(e, "We couldn’t add that supply."));
      }
      setBusy(false);
    }
  }

  return (
    <FocusScreen
      task="Add a supply"
      who="Something the temple uses up that is not food"
      activeHref="/supplies"
      actions={
        <>
          <ButtonLink href="/supplies" variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={FORM} disabled={busy}>
            Add supply
          </Button>
        </>
      }
    >
      {/*
        `isAdmin` is deliberately not passed. It gates the Ekadashi box, which a supply does not
        get at all — a fasting rule has nothing to say about dishwashing liquid — so passing it
        would be a prop that reads as a permission decision while changing nothing.
      */}
      <IngredientForm kind="SUPPLY" formId={FORM} busy={busy} error={error} onSubmit={add} />
      {lookalike && (
        <DuplicateIngredientPrompt
          candidate={lookalike.input.name}
          existing={lookalike.existing}
          busy={busy}
          /*
            "Use Curd" opens Curd's own editing row on the Ingredients list (T-251's choice). Nothing
            is added, and the row that IS the thing is in front of them — where the spelling they
            typed can go in as an alias, so the next person who types it finds Curd by it. The list
            sends a supply on to Supplies, since that half of the catalogue lives there.
          */
          onUse={() => router.push(`/ingredients?edit=${encodeURIComponent(lookalike.existing.id)}`)}
          onDifferent={() => add({ ...lookalike.input, confirmDifferent: true })}
          onDismiss={() => setLookalike(null)}
        />
      )}
    </FocusScreen>
  );
}
