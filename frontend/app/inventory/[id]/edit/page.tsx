"use client";

import { useCallback, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Loading } from "@/components/Loading";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { Form } from "@/components/ds/Form";
import { countedBox } from "@/components/ds/formMessages";
import { HintedField } from "@/components/ds/InfoHint";
import { PLAIN_NUMBER } from "@/components/InventoryItemForm";
import { api, toApiError, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { stepForUnit, unitLabel } from "@/lib/format";

/** The same box as every other form on this screen family — 44px tall, hairline, rounded. */
const FIELD = "min-h-touch rounded-control border border-hairline px-3";

/** Named so the header's primary button can submit the form in the body. */
const FORM = "edit-inventory-item";

/**
 * Changing what the temple decided about one consumable (T-440).
 *
 * <h3>Why this screen exists at all</h3>
 *
 * <p>These three fields used to be an inline form that replaced the item's row on the Inventory
 * list. Rajeev, 2026-09-20: *"Inventory, remove the edit button and move the functionality the
 * current edit button provides into the edit screen. When the user clicks on the Ingredient Name, it
 * opens in the view mode, then they see the edit button, click on that and it goes to the edit
 * screen which shows save and cancel."* So the list's name is the way in, `/inventory/[id]` is the
 * record, and this is the form — the shape a staff record has had since T-428, built the same way so
 * the two cannot drift apart: a `FocusScreen`, `[Cancel] [Save changes]` top right, and nothing
 * repeated at the foot.
 *
 * <h3>What it can change, and what it deliberately cannot</h3>
 *
 * <p>Exactly what the inline row could: where it is stored, the level below which the temple asks to
 * be warned, and the note. Everything else on the record — on hand, committed, available, on order,
 * how long it lasts, when it was last counted — is counted from the ledger or remembered, and none
 * of it is a thing to type into. Stock is changed by recording an adjustment on the record itself,
 * which leaves a movement behind; a form that quietly rewrote a count would not.
 *
 * <p><strong>The level carries no unit picker</strong>, and that is the inline row's rule kept
 * rather than an omission. Settled on 2026-09-08 (build-list item I1): changing an ingredient's unit
 * once stock exists is a conversion problem, so the box says which unit the number is in and the Add
 * form is where a unit is chosen. Nothing is sent for `reorderThresholdUnit`, which tells the server
 * the figure is already in the ingredient's own unit (see `CreateInventoryItemInput`).
 *
 * <p><strong>A level is whole whenever the stock figure is</strong> (T-424): "tell me when aprons
 * drop below 3.6" is a rule nobody can read off a shelf. The box carries `step="1"` for a counted
 * ingredient and `Form` refuses it in the server's own words — "Agarbatti is counted in whole
 * pieces" (T-431) — with the server saying the same thing and naming the two whole numbers either
 * side of it (KMS-400191) if a request gets past the browser. A fractional level already on file
 * still shows, and is refused on Save: the figure is wrong and saying so is the point.
 */
export default function EditInventoryItemPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <EditInventoryItemView />
    </RequireRole>
  );
}

function EditInventoryItemView() {
  const id = useParams<{ id: string }>().id;
  const router = useRouter();
  const { getToken } = useAuth();

  const fetchItem = useCallback((token: string | undefined) => api.getInventoryItem(id, token), [id]);
  const { data, error, loading } = useAuthedQuery(fetchItem);
  const item = data?.item;

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!item) return;
    const f = new FormData(event.currentTarget);
    const level = String(f.get("reorderThreshold") ?? "").trim();
    setBusy(true);
    setActionError(null);
    try {
      await api.updateInventoryItem(
        item.itemId,
        {
          storageLocation: emptyToNull(String(f.get("storageLocation") ?? "")),
          // Blank is a real answer: it means "stop warning me", and the server stores null for it.
          reorderThreshold: level === "" ? null : Number(level),
          notes: emptyToNull(String(f.get("notes") ?? "")),
        },
        await getToken()
      );
      // Back to the record, not to the list: the record is where this was opened from and where the
      // change is now readable, so the confirmation waits there (FocusScreen's eighth rule, with the
      // record standing in for the list).
      router.push(`/inventory/${id}?saved=${encodeURIComponent(item.ingredientName)}`);
    } catch (e) {
      setActionError(toApiError(e, "We couldn’t save that change."));
      setBusy(false);
    }
  }

  return (
    <FocusScreen
      task="Update inventory item"
      who={item ? `${item.ingredientName} · ${item.category}` : undefined}
      activeHref="/inventory"
      actions={
        <>
          {/* Straight back to the record, saving nothing. The list is two clicks away from there and
              this screen was opened from the record, so returning to the list would land somebody
              somewhere they did not come from. */}
          <ButtonLink href={`/inventory/${id}`} variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={FORM} disabled={busy || !item}>
            Save changes
          </Button>
        </>
      }
    >
      {actionError && (
        <div className="grid gap-3">
          <ErrorNotice error={actionError} />
          {/* The lines a refusal names, under the box that says what happened — the same treatment
              the record gives them, so a unit or a fraction refused by the server says which item it
              is about rather than leaving that sentence off the screen. */}
          {actionError.fieldErrors.length > 0 && (
            <ul className="grid gap-1 rounded border border-hairline bg-raised px-5 py-4 text-sm">
              {actionError.fieldErrors.map((f, i) => (
                <li key={i}>{f.message}</li>
              ))}
            </ul>
          )}
        </div>
      )}

      {loading ? (
        <Loading label="Loading the item…" />
      ) : error ? (
        <ErrorNotice error={error} />
      ) : !item ? null : (
        /* Two fields on a row from `md` up and the note across the bottom, so no row of this form is
           half empty at either width; one column on a phone, which is the same arrangement the Add
           screen uses. */
        <Form
          id={FORM}
          aria-label="Update inventory item"
          className="grid grid-cols-1 gap-4 md:grid-cols-2"
          onSubmit={submit}
        >
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Where is it stored</span>
            <input
              name="storageLocation"
              defaultValue={item.storageLocation ?? ""}
              placeholder="Main store, cold room…"
              className={FIELD}
            />
          </label>

          {/* Still one field though there are two things on the line: the number is what the label
              names, and the unit beside it is fixed text saying what the number is in. */}
          <HintedField
            label={`Tell me when ${item.ingredientName} drops below`}
            hint="Leave it blank if you’d rather not be warned. You can change it later."
          >
            {(fieldId) => (
              <div className="flex items-center gap-2">
                <input
                  id={fieldId}
                  name="reorderThreshold"
                  type="number"
                  inputMode={stepForUnit(item.unit) === "1" ? "numeric" : "decimal"}
                  min="0"
                  step={stepForUnit(item.unit)}
                  defaultValue={item.reorderThreshold == null ? "" : String(item.reorderThreshold)}
                  // The item's own name and unit, so a refused fraction says why rather than reading
                  // the label back: "Agarbatti is counted in whole pieces" (T-431).
                  {...countedBox(item.ingredientName, item.unit)}
                  className={`${FIELD} ${PLAIN_NUMBER} min-w-0 flex-1`}
                />
                <span className="text-ink">{unitLabel(item.unit)}</span>
              </div>
            )}
          </HintedField>

          <label className="flex flex-col gap-1 text-sm text-ink-secondary md:col-span-2">
            <span className="pl-field-inset font-medium text-ink">Notes</span>
            <input name="notes" defaultValue={item.notes ?? ""} className={FIELD} />
          </label>
        </Form>
      )}
    </FocusScreen>
  );
}

function emptyToNull(value: string): string | null {
  const trimmed = value.trim();
  return trimmed === "" ? null : trimmed;
}
