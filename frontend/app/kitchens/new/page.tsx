"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { BusyPot } from "@/components/Loading";
import { KitchenForm } from "@/components/KitchenForm";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type KitchenInput } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";

const KITCHEN_FORM_ID = "new-kitchen";

export default function NewKitchenPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <NewKitchenView />
    </RequireRole>
  );
}

function NewKitchenView() {
  const { getToken } = useAuth();
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function create(input: KitchenInput) {
    setBusy(true);
    setError(null);
    try {
      await api.createKitchen(input, await getToken());
      // Committing returns to the list with the confirmation waiting there.
      router.push(`/kitchens?added=${encodeURIComponent(input.name)}`);
    } catch (e) {
      setError(toApiError(e, "We couldn’t add that kitchen."));
      setBusy(false);
    }
  }

  return (
    <FocusScreen
      task="New kitchen"
      who="For this temple"
      activeHref="/kitchens"
      actions={
        <>
          <ButtonLink href="/kitchens" variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={KITCHEN_FORM_ID} disabled={busy} busy={busy}>
            {busy ? (
              <span className="inline-flex items-center gap-2">
                <BusyPot />
                Saving…
              </span>
            ) : (
              "Add kitchen"
            )}
          </Button>
        </>
      }
    >
      <KitchenForm formId={KITCHEN_FORM_ID} busy={busy} error={error} onSubmit={create} />
    </FocusScreen>
  );
}
