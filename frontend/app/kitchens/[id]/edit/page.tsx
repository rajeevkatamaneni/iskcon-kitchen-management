"use client";

import { useCallback, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { BusyPot, Loading } from "@/components/Loading";
import { ErrorNotice } from "@/components/ErrorNotice";
import { KitchenForm } from "@/components/KitchenForm";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type KitchenInput } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

const KITCHEN_FORM_ID = "edit-kitchen";

export default function EditKitchenPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <EditKitchenView />
    </RequireRole>
  );
}

function EditKitchenView() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const { getToken } = useAuth();
  const router = useRouter();
  const fetchKitchen = useCallback((token: string | undefined) => api.getKitchen(id, token), [id]);
  const { data: kitchen, error, loading } = useAuthedQuery(fetchKitchen);

  const [busy, setBusy] = useState(false);
  const [saveError, setSaveError] = useState<ApiError | null>(null);

  async function save(input: KitchenInput) {
    setBusy(true);
    setSaveError(null);
    try {
      await api.updateKitchen(id, input, await getToken());
      // Committing returns to the list with the confirmation waiting there.
      router.push(`/kitchens?updated=${encodeURIComponent(input.name)}`);
    } catch (e) {
      setSaveError(toApiError(e, "We couldn’t save that kitchen."));
      setBusy(false);
    }
  }

  return (
    <FocusScreen
      task="Edit kitchen"
      who={kitchen?.name}
      activeHref="/kitchens"
      actions={
        <>
          <ButtonLink href="/kitchens" variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={KITCHEN_FORM_ID} disabled={busy || !kitchen} busy={busy}>
            {busy ? (
              <span className="inline-flex items-center gap-2">
                <BusyPot />
                Saving…
              </span>
            ) : (
              "Save changes"
            )}
          </Button>
        </>
      }
    >
      {loading ? (
        <Loading />
      ) : error ? (
        <ErrorNotice error={error} />
      ) : kitchen ? (
        <KitchenForm initial={kitchen} formId={KITCHEN_FORM_ID} busy={busy} error={saveError} onSubmit={save} />
      ) : null}
    </FocusScreen>
  );
}
