"use client";

import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { RequireRole } from "@/components/RequireRole";
import { IngredientRequestForm, type CommitIntent } from "@/components/IngredientRequestForm";
import { api, toApiError, type ApiError, type IngredientRequestInput } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";

const FORM_ID = "new-ingredient-request";

/**
 * Raising a request (E10-S9). Anybody who works in a kitchen may — a shelf people help themselves
 * from is the thing this replaces.
 */
export default function NewIngredientRequestPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <NewIngredientRequestView />
    </RequireRole>
  );
}

function NewIngredientRequestView() {
  const { getToken } = useAuth();
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  /**
   * The request, once it exists.
   *
   * <p>Saving a draft and then sending it for review are two calls, and the second can be refused
   * on its own — a kitchen that joined the meal planner while the form was open, say. Without this
   * the person would press the button again and raise a second request for the same feast, so once
   * the row exists every further commit rewrites it.
   */
  const created = useRef<string | null>(null);

  async function commit(input: IngredientRequestInput, intent: CommitIntent) {
    setBusy(true);
    setError(null);
    try {
      const token = await getToken();
      let id = created.current;
      if (id) {
        await api.updateIngredientRequest(id, input, token);
      } else {
        id = (await api.createIngredientRequest(input, token)).id;
        created.current = id;
      }
      if (intent === "SUBMIT") {
        await api.submitIngredientRequest(id, token);
      }
      // The request's own page, carrying what just happened, so it lands with a confirmation
      // instead of in silence (T-370, staging defect 3: a submit created IR-2026-0003 and left the
      // person looking at an empty form, with nothing on the screen saying the request existed).
      // The same shape "Create an invoice" uses — the destination reads the parameter once, shows
      // the sentence and clears the address. Only what happened travels; the reference does not,
      // because the page it lands on has already loaded the request and knows it.
      router.push(`/ingredient-requests/${id}?created=${intent === "SUBMIT" ? "submitted" : "draft"}`);
    } catch (e) {
      setError(toApiError(e, "We couldn’t save that request."));
      setBusy(false);
    }
  }

  return (
    <IngredientRequestForm
      formId={FORM_ID}
      task="New request"
      cancelHref="/ingredient-requests"
      status="DRAFT"
      busy={busy}
      error={error}
      onCommit={commit}
    />
  );
}
