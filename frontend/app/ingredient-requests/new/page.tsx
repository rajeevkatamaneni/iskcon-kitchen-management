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
      router.push(`/ingredient-requests/${id}`);
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
