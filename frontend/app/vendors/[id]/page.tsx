"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Badge } from "@/components/ds/Badge";
import { Button } from "@/components/ds/Button";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { HintedField } from "@/components/ds/InfoHint";
import { VendorStatusDialog } from "@/components/VendorStatusDialog";
import { api, toApiError, type ApiError, type VendorStatusChange } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { contractWarning, money, moment } from "@/lib/format";
import { ALL_LANGUAGES, languageLabel } from "@/lib/languages";
import { Loading } from "@/components/Loading";
import { TABLE, THEAD, TR, TH_TEXT, TH_NUM, TH_ACTIONS, TD_TEXT, TD_NUM, TD_ACTIONS, ACTIONS_ROW, WRAP } from "@/components/ds/table";
import type { VendorSupplyView } from "@/lib/api";

export default function VendorDetailPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <VendorDetailView />
    </RequireRole>
  );
}

function VendorDetailView() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const { getToken } = useAuth();

  const fetchVendor = useCallback((token: string | undefined) => api.getVendor(id, token), [id]);
  const { data, error, loading, reload } = useAuthedQuery(fetchVendor);
  const { data: ingredientsData } = useAuthedQuery(api.listIngredients);
  const ingredients = ingredientsData ?? [];

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [saved, setSaved] = useState(false);
  const [changingStatus, setChangingStatus] = useState(false);
  /*
    Which supply row is open for editing, by its ingredient id — the same shape `/ingredients` uses
    for the same interaction, and keyed the same way its rows are.

    Before T-131 a supply row had one control, Remove, and the only way to correct anything about it
    was to remove it and add it again. That is not a longer route to the same place: adding it again
    starts from an empty form, so the price the temple last paid and the preference the shopping list
    reads both go in the bin to change a lead time. Worse, the Add form's picker deliberately offers
    only ingredients this vendor does NOT already supply, so for an existing supply the lead-time box
    could not be reached at all — the field T-090 shipped was unfillable on every row that already
    existed, which is every row a real temple has.
  */
  const [editing, setEditing] = useState<string | null>(null);

  async function run(mutation: (token: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    setActionError(null);
    try {
      await mutation(await getToken());
      reload();
      return true;
    } catch (e) {
      setActionError(toApiError(e, failure));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    const ok = await run(
      (token) =>
        api.updateVendor(
          id,
          {
            name: String(f.get("name") ?? "").trim(),
            // emptyToNull, like every other optional field: emptying this box is how a vendor the
            // temple now only ever walks into loses a number it should no longer carry, and "" is
            // not a number — it would be refused by the E.164 pattern (T-025).
            phone: emptyToNull(String(f.get("phone") ?? "")),
            contactPerson: emptyToNull(String(f.get("contactPerson") ?? "")),
            email: emptyToNull(String(f.get("email") ?? "")),
            address: emptyToNull(String(f.get("address") ?? "")),
            gstin: emptyToNull(String(f.get("gstin") ?? "")),
            preferredLanguage: String(f.get("preferredLanguage") ?? "en"),
            notes: emptyToNull(String(f.get("notes") ?? "")),
            contractEndDate: emptyToNull(String(f.get("contractEndDate") ?? "")),
          },
          token
        ),
      "We couldn’t save those changes."
    );
    if (ok) {
      setSaved(true);
      setTimeout(() => setSaved(false), 2000);
    }
  }

  async function addSupply(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const f = new FormData(form);
    const ok = await run(
      (token) =>
        api.setVendorSupply(
          id,
          {
            ingredientId: String(f.get("ingredientId") ?? ""),
            lastPrice: numberOrNull(String(f.get("lastPrice") ?? "")),
            leadTimeDays: numberOrNull(String(f.get("leadTimeDays") ?? "")),
            preferred: f.get("preferred") === "on",
          },
          token
        ),
      "We couldn’t set that supply."
    );
    if (ok) form.reset();
  }

  const vendor = data?.vendor;
  const supplies = data?.supplies ?? [];
  const statusHistory = data?.statusHistory ?? [];
  const suppliedIds = new Set(supplies.map((s) => s.ingredientId));
  const available = ingredients.filter((i) => !suppliedIds.has(i.id));

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/vendors" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <Link href="/vendors" className="text-sm text-accent-text hover:underline">← All vendors</Link>

          {loading ? (
            <Loading label="Loading vendor…" />
          ) : error ? (
            <div className="mt-6"><ErrorNotice error={error} /></div>
          ) : !vendor ? null : (
            <>
              <header className="mb-6 mt-3 flex flex-wrap items-start justify-between gap-4">
                <div>
                  <h1>{vendor.name}</h1>
                  <p className="mt-1 text-ink-secondary">
                    {vendor.active ? "Active vendor" : "Inactive vendor"} · {languageLabel(vendor.preferredLanguage)}
                  </p>
                </div>
                <Button
                  type="button"
                  variant={vendor.active ? "ghost" : "secondary"}
                  onClick={() => setChangingStatus(true)}
                >
                  {vendor.active ? "Make inactive" : "Bring back"}
                </Button>
              </header>

              {/* A warning and nothing else. The vendor below is still active, still selectable, and
                  still whatever the shopping list decided they were — the date changes none of it. */}
              {vendor.contractEndingSoon && vendor.contractEndDate && (
                <div className="mb-6">
                  <InlineNotice tone="warning" title={contractWarning(vendor.contractEndDate)}>
                    They are still active and can still be ordered from. Renew the agreement, or make
                    them inactive and say why.
                  </InlineNotice>
                </div>
              )}

              {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

              <section className="card mb-8 px-6 py-5">
                <h2 className="text-lg">Details</h2>
                <form className="mt-4 grid grid-cols-2 gap-4" aria-label="Edit vendor" onSubmit={save}>
                  <Field name="name" label="Name" defaultValue={vendor.name} required />
                  {/* Not required, and `?? ""` rather than the bare value: a vendor may have no
                      number at all now (T-025), and an uncontrolled input cannot be handed null. */}
                  <Field
                    name="phone"
                    label="Phone (with country code)"
                    defaultValue={vendor.phone ?? ""}
                    hint="Only needed to send orders on WhatsApp. Leave it blank for a shop you walk into."
                  />
                  <Field name="contactPerson" label="Contact person" defaultValue={vendor.contactPerson ?? ""} />
                  <Field name="email" label="Email" type="email" defaultValue={vendor.email ?? ""} />
                  <Field name="gstin" label="GSTIN" defaultValue={vendor.gstin ?? ""} />
                  <Field
                    name="contractEndDate"
                    label="Contract ends"
                    type="date"
                    defaultValue={vendor.contractEndDate ?? ""}
                    hint="Only a reminder. Nothing switches off on this date."
                  />
                  <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                    <span className="pl-field-inset font-medium text-ink">Preferred language</span>
                    <select name="preferredLanguage" defaultValue={vendor.preferredLanguage} className="min-h-touch rounded-control border border-hairline px-3">
                      {ALL_LANGUAGES.map((l) => <option key={l.code} value={l.code}>{l.label}</option>)}
                    </select>
                  </label>
                  <div className="col-span-2"><Field name="address" label="Address" defaultValue={vendor.address ?? ""} /></div>
                  <div className="col-span-2"><Field name="notes" label="Notes" defaultValue={vendor.notes ?? ""} /></div>
                  <div className="col-span-2 flex items-center gap-3">
                    <button type="submit" disabled={busy} className="btn btn-primary min-h-touch px-5 transition-colors duration-state disabled:opacity-60">
                      Save changes
                    </button>
                    {saved && <span className="text-sm text-success">Saved.</span>}
                  </div>
                </form>
              </section>

              <section className="card px-6 py-5">
                <h2 className="text-lg">Supplies</h2>
                <p className="mt-1 text-sm text-ink-secondary">
                  A preferred supply is what the shopping list suggests, and its lead time is what
                  the planner counts back from to work out the last day something can be ordered.
                  Edit a row to change any of the three.
                </p>
                {/* Said once, over the table and the form both, because both offer the tick and
                    neither can show what it will affect: the other vendor is not on this screen.
                    It is a plain statement of what happens rather than a warning, because moving a
                    preference is a normal thing to do and the app does it without complaint. */}
                <p className="mt-1 text-sm text-ink-secondary">
                  Only one vendor can be preferred for an ingredient, so ticking Preferred here
                  takes it from whichever vendor holds it now.
                </p>

                {supplies.length > 0 && (
                  <table className={`${TABLE} mt-4`}>
                    <thead className={THEAD}>
                      <tr>
                        <th className={`${TH_TEXT} ${WRAP}`}>Ingredient</th>
                        <th className={TH_NUM}>Last price</th>
                        <th className={TH_NUM}>Lead time</th>
                        <th className={TH_TEXT}>Preferred</th>
                        {/* "Remove" until T-131, when the column stopped holding only the one
                            control. Named for what the column is rather than for what happens to
                            be in it, as every other table on the site names it. */}
                        <th className={TH_ACTIONS}>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {supplies.map((s) =>
                        editing === s.ingredientId ? (
                          <SupplyEditRow
                            key={s.ingredientId}
                            supply={s}
                            busy={busy}
                            onCancel={() => setEditing(null)}
                            onSave={async (input) => {
                              const ok = await run(
                                (t) => api.setVendorSupply(id, { ingredientId: s.ingredientId, ...input }, t),
                                "We couldn’t save that supply."
                              );
                              if (ok) setEditing(null);
                            }}
                          />
                        ) : (
                        <tr key={s.ingredientId} className={TR}>
                          <td className={`${TD_TEXT} ${WRAP}`}>{s.ingredientName}</td>
                          <td className={TD_NUM}>{money(s.lastPrice, "INR")}</td>
                          {/* An em dash, never a nought. Nobody having said how long this vendor
                              takes and this vendor delivering the same day are different facts, and
                              a "0 days" here would read as the second. */}
                          <td className={TD_NUM}>
                            {s.leadTimeDays === null
                              ? <span className="text-ink-muted">—</span>
                              : `${s.leadTimeDays} ${s.leadTimeDays === 1 ? "day" : "days"}`}
                          </td>
                          <td className={TD_TEXT}>{s.preferred ? <span className="rounded-sm bg-accent-bg px-2 py-1 text-xs text-accent-text font-semibold">Preferred</span> : "—"}</td>
                          <td className={TD_ACTIONS}>
                            <div className={ACTIONS_ROW}>
                              <Button variant="ghost" size="sm" onClick={() => setEditing(s.ingredientId)}>Edit</Button>
                              <Button variant="danger" size="sm" disabled={busy} onClick={() => run((t) => api.removeVendorSupply(id, s.ingredientId, t), "We couldn’t remove that supply.")}>
                                Remove
                              </Button>
                            </div>
                          </td>
                        </tr>
                        )
                      )}
                    </tbody>
                  </table>
                )}

                <form className="mt-4 flex flex-wrap items-end gap-4" aria-label="Add a supply" onSubmit={addSupply}>
                  <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                    <span className="pl-field-inset font-medium text-ink">Ingredient</span>
                    <select name="ingredientId" required className="min-h-touch rounded-control border border-hairline px-3">
                      <option value="">Choose…</option>
                      {available.map((i) => <option key={i.id} value={i.id}>{i.name}</option>)}
                    </select>
                  </label>
                  <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                    <span className="pl-field-inset font-medium text-ink">Last price (₹)</span>
                    <input name="lastPrice" type="number" min="0" step="any" className="min-h-touch w-32 rounded-control border border-hairline px-3" />
                  </label>
                  {/* Left blank until somebody knows the answer. The hint says so rather than the
                      field guessing on their behalf — an invented lead time is worse than none,
                      because the planner would count back from it and say there was time. */}
                  <HintedField
                    label="Lead time (days)"
                    hint="How long this vendor takes to deliver this item once you ask. Leave it blank if you don’t know — we’ll assume two days until somebody records it. Put 0 for a shop you walk into and carry it back from."
                  >
                    {(fieldId) => (
                      <input
                        id={fieldId}
                        name="leadTimeDays"
                        type="number"
                        min="0"
                        max="365"
                        step="1"
                        className="min-h-touch w-32 rounded-control border border-hairline px-3"
                      />
                    )}
                  </HintedField>
                  <label className="flex items-center gap-2 text-sm text-ink-secondary">
                    <input name="preferred" type="checkbox" 
                className="accent-accent"
              /> Preferred
                  </label>
                  <button type="submit" disabled={busy || available.length === 0} className="btn btn-primary min-h-touch px-5 transition-colors duration-state disabled:opacity-60">
                    Add supply
                  </button>
                </form>
              </section>

              <section className="card mt-8 px-6 py-5">
                <h2 className="text-lg">Active and inactive</h2>
                <p className="mt-1 text-sm text-ink-secondary">
                  Why this vendor has been dropped, and brought back. Kept as it was written — nothing
                  here is ever edited or removed.
                </p>

                {statusHistory.length === 0 ? (
                  <p className="mt-4 text-sm text-ink-secondary">
                    This vendor has never been made inactive.
                  </p>
                ) : (
                  <ol className="mt-4 space-y-3">
                    {statusHistory.map((c: VendorStatusChange) => (
                      <li key={c.id} className="border-t border-hairline pt-3 first:border-t-0 first:pt-0">
                        <div className="flex flex-wrap items-center gap-2">
                          <Badge tone={c.toActive ? "success" : "neutral"}>
                            {c.toActive ? "Brought back" : "Made inactive"}
                          </Badge>
                          <span className="text-sm text-ink-secondary">
                            {c.actorName ?? "Someone since removed"} · {moment(c.createdAt)}
                          </span>
                        </div>
                        <p className="mt-1 text-sm">
                          {c.reason ?? <span className="text-ink-muted">No reason given</span>}
                        </p>
                      </li>
                    ))}
                  </ol>
                )}
              </section>
            </>
          )}
        </div>
      </main>

      {changingStatus && vendor && (
        <VendorStatusDialog
          vendor={vendor}
          onCancel={() => setChangingStatus(false)}
          onDone={() => {
            setChangingStatus(false);
            reload();
          }}
        />
      )}
    </div>
  );
}

/**
 * One text field of the vendor's details.
 *
 * <p>Built on {@link HintedField} rather than keeping its own wrapping `<label>`: the hint is now
 * the "i" beside the label, and an "i" is a button, which a `<label>` would claim as its own
 * control the moment it was put inside one. The render-function child is what forces the id onto
 * the input instead.
 */
function Field({
  name, label, defaultValue, type = "text", required = false, hint,
}: { name: string; label: string; defaultValue?: string; type?: string; required?: boolean; hint?: string }) {
  return (
    <HintedField label={label} hint={hint}>
      {(id) => (
        <input id={id} name={name} type={type} defaultValue={defaultValue} required={required} className="min-h-touch rounded-control border border-hairline px-3" />
      )}
    </HintedField>
  );
}

function emptyToNull(s: string): string | null {
  const t = s.trim();
  return t === "" ? null : t;
}

/**
 * What a number box on this screen means, in one place: **blank is null and zero is zero.**
 *
 * <p>Named rather than written inline at each of the four call sites, because the two ways of
 * getting it wrong are opposite and both are one character from correct. `Number("")` is `0`, so
 * the obvious coercion turns "nobody has recorded how long this vendor takes" into "this vendor
 * delivers the same day" — and every ordering screen then counts back nought days and tells a cook
 * there is still time to order rice that can no longer be got. Guarding with `Number(t) || null`
 * fixes that and breaks the other direction, quietly discarding the real `0` a cash-and-carry shop
 * has. The blank is tested as a string, before anything is coerced at all.
 */
function numberOrNull(raw: string): number | null {
  const t = raw.trim();
  return t === "" ? null : Number(t);
}

/** A supply's own value as a number box holds it — and `0` is "0", never "". */
function boxValue(n: number | null): string {
  return n === null ? "" : String(n);
}

/**
 * One supply row, opened for editing.
 *
 * <p><strong>The pattern is `/ingredients`', deliberately and not by coincidence.</strong> That
 * screen gives each row an Edit and a Delete, and Edit swaps the row for the same row as inputs
 * with a Save and a Cancel at the end of it. This is the same interaction on the same kind of thing,
 * so it is the same shape: a `<tr>` of exactly the header's five cells, the row's own values seeded
 * into the controls, and nothing committed until Save.
 *
 * <p><strong>The ingredient is not editable, and that is the one departure.</strong> On
 * `/ingredients` the name is a field, because the name is a property of the thing. Here the
 * ingredient is what the row *is* — the server addresses a supply by `(vendor, ingredient)` — so
 * changing it in this box would not correct this supply, it would create a different one and leave
 * this one behind. Somebody who wants that removes the row and adds the other.
 *
 * <p><strong>All three values are sent on every Save, including the ones nobody touched.</strong>
 * Not defensiveness: the server writes the whole row (see `setVendorSupply` in `lib/api.ts`), so a
 * save that left one out would erase it. Seeding each control from the row is what makes that safe,
 * and it is why the price box opens holding the price rather than empty.
 *
 * <p><strong>Preferred is offered here</strong> rather than left out, because it is one of the three
 * facts that Remove-and-add was throwing away and the point of the row is that none of them should
 * need that. The unique index allows one preferred vendor per ingredient across the temple, and it
 * is not this screen that keeps that true: `VendorService.setSupply` clears any other vendor's
 * preference for the ingredient inside the same transaction before writing this one, so the tick
 * *moves* the preference and cannot collide with it. What the screen owes the person is therefore
 * not a refusal but a sentence saying so, and that sits above the table where it covers this row and
 * the Add form both — the other vendor is not on this screen and cannot be named here.
 */
function SupplyEditRow({
  supply,
  busy,
  onSave,
  onCancel,
}: {
  supply: VendorSupplyView;
  busy: boolean;
  onSave: (input: { lastPrice: number | null; leadTimeDays: number | null; preferred: boolean }) => void;
  onCancel: () => void;
}) {
  const [lastPrice, setLastPrice] = useState(boxValue(supply.lastPrice));
  const [leadTimeDays, setLeadTimeDays] = useState(boxValue(supply.leadTimeDays));
  const [preferred, setPreferred] = useState(supply.preferred);

  return (
    <tr className="border-t border-hairline bg-sunken align-top">
      <td className={`${TD_TEXT} ${WRAP}`}>{supply.ingredientName}</td>
      <td className={TD_NUM}>
        <HintedField label="Last price (₹)">
          {(fieldId) => (
            <input
              id={fieldId}
              type="number"
              min="0"
              step="any"
              value={lastPrice}
              onChange={(e) => setLastPrice(e.target.value)}
              className="min-h-touch w-32 rounded-control border border-hairline px-3"
            />
          )}
        </HintedField>
      </td>
      {/* The field this task exists for, and the hint is the same words the Add form uses, because
          it is answering the same question. Emptying the box is a real and useful edit — a lead time
          recorded from a guess should be removable back to "nobody has said" — so the hint has to be
          here too, where the clearing actually happens. */}
      <td className={TD_NUM}>
        <HintedField
          label="Lead time (days)"
          hint="How long this vendor takes to deliver this item once you ask. Leave it blank if you don’t know — we’ll assume two days until somebody records it. Put 0 for a shop you walk into and carry it back from."
        >
          {(fieldId) => (
            <input
              id={fieldId}
              type="number"
              min="0"
              max="365"
              step="1"
              value={leadTimeDays}
              onChange={(e) => setLeadTimeDays(e.target.value)}
              className="min-h-touch w-32 rounded-control border border-hairline px-3"
            />
          )}
        </HintedField>
      </td>
      <td className={TD_TEXT}>
        <label className="flex items-center gap-2 text-sm text-ink-secondary">
          {/* No `aria-label`: the wrapping `<label>` already names it, exactly as the Add form's
              own Preferred box does, and a redundant one only invites the two to drift apart. */}
          <input
            type="checkbox"
            checked={preferred}
            onChange={(e) => setPreferred(e.target.checked)}
            className="h-5 w-5 rounded-sm border-hairline-strong accent-accent"
          />
          Preferred
        </label>
      </td>
      <td className={TD_ACTIONS}>
        <div className={ACTIONS_ROW}>
          <Button
            size="sm"
            disabled={busy}
            onClick={() =>
              onSave({
                lastPrice: numberOrNull(lastPrice),
                leadTimeDays: numberOrNull(leadTimeDays),
                preferred,
              })
            }
          >
            Save
          </Button>
          <Button variant="ghost" size="sm" onClick={onCancel}>Cancel</Button>
        </div>
      </td>
    </tr>
  );
}
