"use client";

import { useCallback, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { RequireRole } from "@/components/RequireRole";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Loading } from "@/components/Loading";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { HintedField } from "@/components/ds/InfoHint";
import { SOURCE_LABEL } from "@/components/EquipmentWords";
import {
  api,
  toApiError,
  type ApiError,
  type EquipmentSource,
  type EquipmentView,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * Correcting what the register says about a machine (docket M7).
 *
 * <p>The `PUT` behind this has existed since the register did; nothing had ever called it, so a
 * serial number typed with a transposed digit or a warranty date a year out was permanent. It is a
 * correction screen, not a state-change screen, and the difference is the whole shape of it.
 *
 * <p><strong>What it deliberately cannot change.</strong> Two fields sit on the record and are
 * absent here, each for its own reason, and both reasons are the server's rather than this
 * screen's — `UpdateEquipmentRequest` refuses them, so offering them would be offering a request
 * that gets thrown away:
 *
 * <ul>
 *   <li><em>Condition</em> moves only through a recorded change with a reason beside it, on the
 *       item page. A machine that quietly became "needs repair" with nobody's name against it is
 *       exactly the history the audit trail exists to prevent.
 *   <li><em>The service interval, the company and its phone number</em> are one decision — money
 *       and somebody's diary — and belong to the administrator alone (E3-S10 D10), on their own
 *       endpoint. *Change the schedule* on the item page is where that lives.
 * </ul>
 *
 * <p>Everything else on the record is derived (last serviced, next service) and has no field
 * anywhere: the newest service row is what those mean.
 */

/** Named so the sticky header's commit button can submit the form in the body (§4, rule 6). */
const FORM = "edit-equipment";

const FIELD = "min-h-touch rounded-control border border-hairline px-3";

const SOURCES: EquipmentSource[] = ["PURCHASED", "DONATED"];

export default function EditEquipmentPage() {
  // The same three roles the register itself admits: writing to the register is MANAGE_INVENTORY,
  // and correcting a row is the same permission as creating it. Matching /equipment/new rather than
  // narrowing it — a cook who registered the machine is the person holding the invoice with the
  // serial number on it.
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <EditEquipmentView />
    </RequireRole>
  );
}

function EditEquipmentView() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const router = useRouter();
  const { getToken } = useAuth();

  const fetchItem = useCallback((token: string | undefined) => api.getEquipment(id, token), [id]);
  const { data, error, loading } = useAuthedQuery(fetchItem);
  const item = data?.equipment ?? null;

  const [busy, setBusy] = useState(false);
  const [saveError, setSaveError] = useState<ApiError | null>(null);

  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    const cost = String(f.get("purchaseCostInr") ?? "").trim();
    const name = String(f.get("name") ?? "").trim();

    setBusy(true);
    setSaveError(null);
    try {
      await api.updateEquipment(
        id,
        {
          name,
          storageLocation: emptyToNull(String(f.get("storageLocation") ?? "")),
          acquisitionDate: emptyToNull(String(f.get("acquisitionDate") ?? "")),
          source: (emptyToNull(String(f.get("source") ?? "")) as EquipmentSource | null) ?? null,
          notes: emptyToNull(String(f.get("notes") ?? "")),
          serialNumber: emptyToNull(String(f.get("serialNumber") ?? "")),
          purchaseCostInr: cost === "" ? null : Number(cost),
          warrantyExpiry: emptyToNull(String(f.get("warrantyExpiry") ?? "")),
        },
        await getToken()
      );
      // Rule 8: back to the record this came from — which is the list for this screen, because it
      // is the one place the correction can be seen to have landed — with the confirmation waiting
      // there rather than flashing here on a screen that is about to be replaced.
      router.push(`/equipment/${id}?saved=${encodeURIComponent(name)}`);
    } catch (e) {
      setSaveError(toApiError(e, "We couldn’t save those changes."));
      setBusy(false);
    }
  }

  return (
    <FocusScreen
      task="Edit equipment"
      who={item ? item.name : undefined}
      activeHref="/equipment"
      actions={
        <>
          <ButtonLink href={`/equipment/${id}`} variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={FORM} busy={busy} disabled={busy || !item}>
            Save changes
          </Button>
        </>
      }
    >
      {loading ? (
        <Loading />
      ) : error ? (
        <ErrorNotice error={error} />
      ) : item ? (
        <EditForm item={item} busy={busy} error={saveError} onSubmit={save} />
      ) : null}
    </FocusScreen>
  );
}

/**
 * The eight fields the server will accept, in the order the item page reads them back, so that
 * somebody correcting one thing finds it in the place they just looked at it.
 */
function EditForm({
  item,
  busy,
  error,
  onSubmit,
}: {
  item: EquipmentView;
  busy: boolean;
  error: ApiError | null;
  onSubmit: (event: React.FormEvent<HTMLFormElement>) => void;
}) {
  return (
    <>
      {error && <ErrorNotice error={error} />}

      <p className="max-w-[60ch] text-sm text-ink-secondary">
        Condition and servicing are not here. Condition moves through a recorded change, with the
        reason kept beside it; the interval and the company that comes are set together on{" "}
        <em>Change the schedule</em>. Both are on the item’s own page.
      </p>

      <form
        id={FORM}
        className="grid grid-cols-2 gap-4"
        aria-label="Edit equipment"
        aria-busy={busy}
        onSubmit={onSubmit}
      >
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Name</span>
          <input
            name="name"
            required
            maxLength={200}
            defaultValue={item.name}
            className={FIELD}
          />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Where it lives</span>
          <input
            name="storageLocation"
            maxLength={120}
            defaultValue={item.storageLocation ?? ""}
            className={FIELD}
          />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">How it came to the temple</span>
          <select name="source" defaultValue={item.source ?? ""} className={FIELD}>
            <option value="">Not recorded</option>
            {SOURCES.map((s) => (
              <option key={s} value={s}>
                {SOURCE_LABEL[s]}
              </option>
            ))}
          </select>
        </label>

        <HintedField
          label="Acquired on"
          hint="Where nothing has ever been serviced, this is what the next service is counted from."
        >
          {(fieldId) => (
            <input
              id={fieldId}
              name="acquisitionDate"
              type="date"
              defaultValue={item.acquisitionDate ?? ""}
              className={FIELD}
            />
          )}
        </HintedField>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">What it cost (₹)</span>
          <input
            name="purchaseCostInr"
            type="number"
            min="0"
            step="0.01"
            defaultValue={item.purchaseCostInr == null ? "" : String(item.purchaseCostInr)}
            className={FIELD}
          />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Warranty runs to</span>
          <input
            name="warrantyExpiry"
            type="date"
            defaultValue={item.warrantyExpiry ?? ""}
            className={FIELD}
          />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Serial number</span>
          <input
            name="serialNumber"
            maxLength={120}
            defaultValue={item.serialNumber ?? ""}
            className={FIELD}
          />
        </label>

        {/* Last and across both columns, as on the register form: the one field somebody writes a
            sentence in, and a single line the width of a date picker invites three words and no
            more. */}
        <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Notes</span>
          <textarea
            name="notes"
            rows={4}
            maxLength={1000}
            defaultValue={item.notes ?? ""}
            className="min-h-touch rounded-control border border-hairline px-3 py-2"
          />
        </label>
      </form>
    </>
  );
}

function emptyToNull(value: string): string | null {
  const trimmed = value.trim();
  return trimmed === "" ? null : trimmed;
}
