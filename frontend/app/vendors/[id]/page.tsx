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
import { VendorStatusDialog } from "@/components/VendorStatusDialog";
import { api, toApiError, type ApiError, type VendorStatusChange } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { contractWarning, money, moment } from "@/lib/format";
import { ALL_LANGUAGES, languageLabel } from "@/lib/languages";
import { Loading } from "@/components/Loading";
import { TABLE, THEAD, TR, TH_TEXT, TH_NUM, TH_ACTIONS, TD_TEXT, TD_NUM, TD_ACTIONS, WRAP } from "@/components/ds/table";

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
            phone: String(f.get("phone") ?? "").trim(),
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
    const price = String(f.get("lastPrice") ?? "").trim();
    const ok = await run(
      (token) =>
        api.setVendorSupply(
          id,
          {
            ingredientId: String(f.get("ingredientId") ?? ""),
            lastPrice: price === "" ? null : Number(price),
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

              <section className="mb-8 rounded-lg bg-raised px-6 py-5">
                <h2 className="text-lg">Details</h2>
                <form className="mt-4 grid grid-cols-2 gap-4" aria-label="Edit vendor" onSubmit={save}>
                  <Field name="name" label="Name" defaultValue={vendor.name} required />
                  <Field name="phone" label="Phone (with country code)" defaultValue={vendor.phone} required />
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
                    <select name="preferredLanguage" defaultValue={vendor.preferredLanguage} className="min-h-touch rounded border border-hairline bg-canvas px-3">
                      {ALL_LANGUAGES.map((l) => <option key={l.code} value={l.code}>{l.label}</option>)}
                    </select>
                  </label>
                  <div className="col-span-2"><Field name="address" label="Address" defaultValue={vendor.address ?? ""} /></div>
                  <div className="col-span-2"><Field name="notes" label="Notes" defaultValue={vendor.notes ?? ""} /></div>
                  <div className="col-span-2 flex items-center gap-3">
                    <button type="submit" disabled={busy} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">
                      Save changes
                    </button>
                    {saved && <span className="text-sm text-success">Saved.</span>}
                  </div>
                </form>
              </section>

              <section className="rounded-lg bg-raised px-6 py-5">
                <h2 className="text-lg">Supplies</h2>
                <p className="mt-1 text-sm text-ink-secondary">
                  A preferred supply is what the shopping list suggests.
                </p>

                {supplies.length > 0 && (
                  <table className={`${TABLE} mt-4`}>
                    <thead className={THEAD}>
                      <tr>
                        <th className={`${TH_TEXT} ${WRAP}`}>Ingredient</th>
                        <th className={TH_NUM}>Last price</th>
                        <th className={TH_TEXT}>Preferred</th>
                        <th className={TH_ACTIONS}>Remove</th>
                      </tr>
                    </thead>
                    <tbody>
                      {supplies.map((s) => (
                        <tr key={s.ingredientId} className={TR}>
                          <td className={`${TD_TEXT} ${WRAP}`}>{s.ingredientName}</td>
                          <td className={TD_NUM}>{money(s.lastPrice, "INR")}</td>
                          <td className={TD_TEXT}>{s.preferred ? <span className="rounded-sm bg-accent-bg px-2 py-1 text-xs text-accent-text font-semibold">Preferred</span> : "—"}</td>
                          <td className={TD_ACTIONS}>
                            <Button variant="danger" size="sm" disabled={busy} onClick={() => run((t) => api.removeVendorSupply(id, s.ingredientId, t), "We couldn’t remove that supply.")}>
                              Remove
                            </Button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}

                <form className="mt-4 flex flex-wrap items-end gap-4" aria-label="Add a supply" onSubmit={addSupply}>
                  <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                    <span className="pl-field-inset font-medium text-ink">Ingredient</span>
                    <select name="ingredientId" required className="min-h-touch rounded border border-hairline bg-canvas px-3">
                      <option value="">Choose…</option>
                      {available.map((i) => <option key={i.id} value={i.id}>{i.name}</option>)}
                    </select>
                  </label>
                  <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                    <span className="pl-field-inset font-medium text-ink">Last price (₹)</span>
                    <input name="lastPrice" type="number" min="0" step="any" className="min-h-touch w-32 rounded border border-hairline bg-canvas px-3" />
                  </label>
                  <label className="flex items-center gap-2 text-sm text-ink-secondary">
                    <input name="preferred" type="checkbox" /> Preferred
                  </label>
                  <button type="submit" disabled={busy || available.length === 0} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">
                    Add supply
                  </button>
                </form>
              </section>

              <section className="mt-8 rounded-lg bg-raised px-6 py-5">
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

function Field({
  name, label, defaultValue, type = "text", required = false, hint,
}: { name: string; label: string; defaultValue?: string; type?: string; required?: boolean; hint?: string }) {
  return (
    <label className="flex flex-col gap-1 text-sm text-ink-secondary">
      <span className="pl-field-inset font-medium text-ink">{label}</span>
      <input name={name} type={type} defaultValue={defaultValue} required={required} className="min-h-touch rounded border border-hairline bg-canvas px-3" />
      {hint && <span className="pl-field-inset text-sm text-ink-secondary">{hint}</span>}
    </label>
  );
}

function emptyToNull(s: string): string | null {
  const t = s.trim();
  return t === "" ? null : t;
}
