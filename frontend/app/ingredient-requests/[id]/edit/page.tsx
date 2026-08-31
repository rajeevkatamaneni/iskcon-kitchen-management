"use client";

import { useCallback, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Loading } from "@/components/Loading";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { IngredientRequestForm, type CommitIntent } from "@/components/IngredientRequestForm";
import { api, toApiError, type ApiError, type IngredientRequestInput } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

const FORM_ID = "edit-ingredient-request";

/**
 * Rewriting a request nobody has answered yet (E10-S9).
 *
 * <p>A draft is its author's alone and a submitted request is also the approver's to correct. Both
 * of those are decided by the API on every request; this screen only avoids offering the form to
 * somebody it will refuse, and says plainly when a request has moved past editing.
 */
export default function EditIngredientRequestPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <EditIngredientRequestView />
    </RequireRole>
  );
}

function EditIngredientRequestView() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const { appUser, getToken } = useAuth();
  const router = useRouter();

  const { data, error, loading } = useAuthedQuery(
    useCallback((token: string | undefined) => api.getIngredientRequest(id, token), [id])
  );

  const [busy, setBusy] = useState(false);
  const [saveError, setSaveError] = useState<ApiError | null>(null);

  async function commit(input: IngredientRequestInput, intent: CommitIntent) {
    setBusy(true);
    setSaveError(null);
    try {
      const token = await getToken();
      await api.updateIngredientRequest(id, input, token);
      if (intent === "SUBMIT") {
        await api.submitIngredientRequest(id, token);
      }
      router.push(`/ingredient-requests/${id}`);
    } catch (e) {
      setSaveError(toApiError(e, "We couldn’t save that request."));
      setBusy(false);
    }
  }

  if (loading || error || !data) {
    return (
      <Frame>
        {loading ? <Loading /> : error ? <ErrorNotice error={error} /> : null}
      </Frame>
    );
  }

  const status = data.request.status;
  if (status !== "DRAFT" && status !== "SUBMITTED") {
    return (
      <Frame>
        <InlineNotice
          tone="warning"
          title={`${data.request.reference} has been answered, so it can no longer be changed.`}
        >
          Raise a fresh request if the kitchen needs something different.
        </InlineNotice>
      </Frame>
    );
  }

  // The record itself offers no way in here for these people, but a typed address is a way in, and
  // an empty form that saves into a refusal is a worse answer than the refusal itself.
  const isAuthor = appUser?.userId === data.request.requestedBy;
  const mayApprove = appUser?.role === "TEMPLE_ADMIN" || appUser?.role === "KITCHEN_MANAGER";
  const mayEdit = isAuthor || (status === "SUBMITTED" && mayApprove);
  if (!mayEdit) {
    return (
      <Frame>
        <InlineNotice title={`${data.request.reference} is not yours to change.`}>
          {status === "DRAFT"
            ? "A draft belongs to the person who raised it until they send it for review."
            : "Only the person who raised it, or somebody who can answer it, may correct it now."}
        </InlineNotice>
      </Frame>
    );
  }

  return (
    <IngredientRequestForm
      formId={FORM_ID}
      task="Edit request"
      cancelHref={`/ingredient-requests/${id}`}
      status={status}
      initial={data}
      busy={busy}
      error={saveError}
      onCommit={commit}
    />
  );
}

/** The page around a state that has no form in it — loading, unreachable, or already answered. */
function Frame({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/ingredient-requests" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">{children}</div>
      </main>
    </div>
  );
}
