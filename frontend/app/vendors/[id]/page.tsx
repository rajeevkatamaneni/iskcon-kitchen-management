"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { ALL_LANGUAGES, languageLabel } from "@/lib/languages";
import { Loading } from "@/components/Loading";

export default function VendorDetailPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_STAFF"]}>
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
          },
          token
        ),
      "We couldn't save those changes."
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
      "We couldn't set that supply."
    );
    if (ok) form.reset();
  }

  const vendor = data?.vendor;
  const supplies = data?.supplies ?? [];
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
              <header className="mb-6 mt-3">
                <h1>{vendor.name}</h1>
                <p className="mt-1 text-ink-secondary">
                  {vendor.active ? "Active vendor" : "Inactive vendor"} · {languageLabel(vendor.preferredLanguage)}
                </p>
              </header>

              {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

              <section className="mb-8 rounded-lg bg-raised px-6 py-5">
                <h2 className="text-lg">Details</h2>
                <form className="mt-4 grid grid-cols-2 gap-4" aria-label="Edit vendor" onSubmit={save}>
                  <Field name="name" label="Name" defaultValue={vendor.name} required />
                  <Field name="phone" label="Phone (with country code)" defaultValue={vendor.phone} required />
                  <Field name="contactPerson" label="Contact person" defaultValue={vendor.contactPerson ?? ""} />
                  <Field name="email" label="Email" type="email" defaultValue={vendor.email ?? ""} />
                  <Field name="gstin" label="GSTIN" defaultValue={vendor.gstin ?? ""} />
                  <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                    Preferred language
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
                  The ingredients this vendor supplies, with the last price paid. A preferred supply is what the order list suggests.
                </p>

                {supplies.length > 0 && (
                  <table className="mt-4 w-full text-left">
                    <thead className="text-sm text-ink-secondary">
                      <tr>
                        <th className="py-2 font-medium">Ingredient</th>
                        <th className="py-2 font-medium text-right">Last price</th>
                        <th className="py-2 font-medium">Preferred</th>
                        <th className="py-2 font-medium text-right">Remove</th>
                      </tr>
                    </thead>
                    <tbody>
                      {supplies.map((s) => (
                        <tr key={s.ingredientId} className="border-t border-hairline">
                          <td className="py-2">{s.ingredientName}</td>
                          <td className="py-2 text-right tabular-nums">{s.lastPrice == null ? "—" : `₹${s.lastPrice}`}</td>
                          <td className="py-2">{s.preferred ? <span className="rounded-sm bg-accent-bg px-2 py-1 text-xs text-accent-text">Preferred</span> : "—"}</td>
                          <td className="py-2 text-right">
                            <button type="button" disabled={busy} onClick={() => run((t) => api.removeVendorSupply(id, s.ingredientId, t), "We couldn't remove that supply.")} className="text-sm text-ink-secondary hover:underline disabled:opacity-60">
                              Remove
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}

                <form className="mt-4 flex flex-wrap items-end gap-4" aria-label="Add a supply" onSubmit={addSupply}>
                  <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                    Ingredient
                    <select name="ingredientId" required className="min-h-touch rounded border border-hairline bg-canvas px-3">
                      <option value="">Choose…</option>
                      {available.map((i) => <option key={i.id} value={i.id}>{i.name}</option>)}
                    </select>
                  </label>
                  <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                    Last price (₹)
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
            </>
          )}
        </div>
      </main>
    </div>
  );
}

function Field({
  name, label, defaultValue, type = "text", required = false,
}: { name: string; label: string; defaultValue?: string; type?: string; required?: boolean }) {
  return (
    <label className="flex flex-col gap-1 text-sm text-ink-secondary">
      {label}
      <input name={name} type={type} defaultValue={defaultValue} required={required} className="min-h-touch rounded border border-hairline bg-canvas px-3" />
    </label>
  );
}

function emptyToNull(s: string): string | null {
  const t = s.trim();
  return t === "" ? null : t;
}
