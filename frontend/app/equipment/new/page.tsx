"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { EquipmentForm, type NewEquipment } from "@/components/EquipmentForm";
import { api, toApiError, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";

/**
 * Register a piece of equipment (E3-S11 D2).
 *
 * <p>Its own screen and its own address, because the register holds twelve fields and the design
 * system's rule is that four or more become a screen. A panel over the list would also sit on top
 * of the very register somebody is checking the machine is not already on.
 *
 * <p>It can make two requests, and the second one is an administrator's. Registering the thing is
 * `MANAGE_INVENTORY`; setting how often it must be serviced and naming the company that does it is
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

  // MANAGE_EQUIPMENT_SERVICING is the temple admin's alone.
  const isAdmin = appUser?.role === "TEMPLE_ADMIN";

  async function register(input: NewEquipment) {
    setBusy(true);
    setError(null);
    try {
      const token = await getToken();
      const created = await api.createEquipment(
        {
          name: input.name,
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
      // Only when there is something to say. A machine with no interval, no company named and an
      // un-ticked box is a machine nobody has decided about, and sending an empty schedule would be
      // this screen asserting a decision on the temple's behalf.
      //
      // The ticked box is a decision and so it counts as something to say (T-143) — and it is the
      // only one of the four that can be the *whole* of what somebody said. A ladder has no
      // interval and no service company by definition, so without it in this condition the tick
      // would be collected by the form and then silently dropped on the way out, which is the shape
      // of defect T-090 shipped green.
      if (
        isAdmin &&
        (input.intervalCount != null ||
          input.neverNeedsServicing ||
          input.serviceCompany != null ||
          input.serviceCompanyPhone != null)
      ) {
        await api.setEquipmentServiceSchedule(
          created.id,
          {
            intervalCount: input.intervalCount,
            intervalUnit: input.intervalUnit,
            // Whatever the person ticked, and false when they did not — which is still nobody
            // asserting anything, because this endpoint is reached at all only when somebody has
            // said something about servicing.
            //
            // It was hard-coded false until T-143. T-120 put the tick box only on the item's own
            // page, so a temple registering sixty stools had to register them and then open sixty
            // pages to say the one thing that was true of all of them. Rajeev: "So declaring sixty
            // stools un-serviced means sixty visits to sixty pages."
            neverNeedsServicing: input.neverNeedsServicing,
            serviceCompany: input.serviceCompany,
            serviceCompanyPhone: input.serviceCompanyPhone,
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
        busy={busy}
        error={error}
        onSubmit={register}
      />
    </FocusScreen>
  );
}
