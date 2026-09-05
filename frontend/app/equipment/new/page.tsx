"use client";

import { useCallback, useState } from "react";
import { useRouter } from "next/navigation";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { EquipmentForm, type NewEquipment } from "@/components/EquipmentForm";
import { api, toApiError, type ApiError, type ServiceProviderView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * Register a piece of equipment (E3-S11 D2).
 *
 * <p>Its own screen and its own address, because the register holds twelve fields and the design
 * system's rule is that four or more become a screen. A panel over the list would also sit on top
 * of the very register somebody is checking the machine is not already on.
 *
 * <p>It can make two requests, and the second one is an administrator's. Registering the thing is
 * `MANAGE_INVENTORY`; setting how often it must be serviced and naming the firm that does it is
 * `MANAGE_EQUIPMENT_SERVICING`, on its own endpoint (E3-S10 D10). Kitchen staff are never shown
 * that half, so for them there is only ever one request.
 */

/** Named so the header's primary button can submit the form in the body. */
const FORM = "register-equipment";

export default function NewEquipmentPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <NewEquipmentView />
    </RequireRole>
  );
}

function NewEquipmentView() {
  const { appUser, getToken } = useAuth();
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [nonce, setNonce] = useState(0);

  // MANAGE_EQUIPMENT_SERVICING is the temple admin's alone, reading the provider list included.
  const isAdmin = appUser?.role === "TEMPLE_ADMIN";
  const fetchProviders = useCallback(
    (token: string | undefined) => {
      void nonce;
      return isAdmin ? api.listServiceProviders(token) : Promise.resolve([]);
    },
    [isAdmin, nonce]
  );
  const { data: providers } = useAuthedQuery(fetchProviders);

  async function register(input: NewEquipment) {
    setBusy(true);
    setError(null);
    try {
      const token = await getToken();
      const created = await api.createEquipment(
        {
          name: input.name,
          category: input.category,
          storageLocation: input.storageLocation,
          condition: input.condition,
          acquisitionDate: input.acquisitionDate,
          source: input.source,
          notes: input.notes,
          serialNumber: input.serialNumber,
          purchaseCostInr: input.purchaseCostInr,
          warrantyExpiry: input.warrantyExpiry,
        },
        token
      );
      // Only when there is something to say. A machine with no interval and no company named is a
      // machine nobody has decided about, and sending an empty schedule would be this screen
      // asserting a decision on the temple's behalf.
      if (isAdmin && (input.intervalCount != null || input.serviceProviderId != null)) {
        await api.setEquipmentServiceSchedule(
          created.id,
          {
            intervalCount: input.intervalCount,
            intervalUnit: input.intervalUnit,
            serviceProviderId: input.serviceProviderId,
          },
          token
        );
      }
      // Rule 8: back to the list, with the confirmation waiting there rather than here.
      router.push(`/equipment?added=${encodeURIComponent(input.name)}`);
    } catch (e) {
      setError(toApiError(e, "We couldn’t put that on the register."));
      setBusy(false);
    }
  }

  /** Adds a company from the picker and hands its id back, so what was typed is what is selected. */
  async function addProvider(input: { name: string; phone: string | null }) {
    setError(null);
    try {
      const created = await api.createServiceProvider(input, await getToken());
      setNonce((n) => n + 1);
      return created.id;
    } catch (e) {
      setError(toApiError(e, "We couldn’t add that company."));
      return null;
    }
  }

  return (
    <FocusScreen
      task="Register equipment"
      who="Something the temple owns and keeps"
      activeHref="/equipment"
      actions={
        <>
          <ButtonLink href="/equipment" variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={FORM} disabled={busy}>
            Register it
          </Button>
        </>
      }
    >
      <EquipmentForm
        formId={FORM}
        isAdmin={!!isAdmin}
        providers={(providers ?? []) as ServiceProviderView[]}
        busy={busy}
        error={error}
        onSubmit={register}
        onAddProvider={addProvider}
      />
    </FocusScreen>
  );
}
