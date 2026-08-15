"use client";

import Link from "next/link";
import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { ALL_LANGUAGES, languageLabel } from "@/lib/languages";
import { Loading } from "@/components/Loading";

export default function VendorsPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_STAFF"]}>
      <VendorsView />
    </RequireRole>
  );
}

function VendorsView() {
  const { getToken } = useAuth();
  const [activeOnly, setActiveOnly] = useState(false);
  const fetchVendors = useCallback(
    (token: string | undefined) => api.listVendors(activeOnly, token),
    [activeOnly]
  );
  const { data, error, loading, reload } = useAuthedQuery(fetchVendors);
  const vendors = data ?? [];

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [showAdd, setShowAdd] = useState(false);

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

  async function add(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const f = new FormData(form);
    const ok = await run(
      (token) =>
        api.createVendor(
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
      "We couldn't add that vendor."
    );
    if (ok) {
      form.reset();
      setShowAdd(false);
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/vendors" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Vendors</h1>
              <p className="mt-1 text-ink-secondary">
                Who the temple buys from — the WhatsApp number a purchase order goes to.
              </p>
            </div>
            <button
              type="button"
              onClick={() => setShowAdd((s) => !s)}
              className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover"
            >
              Add a vendor
            </button>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

          {showAdd && (
            <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="add-heading">
              <h2 id="add-heading" className="text-lg">New vendor</h2>
              <form className="mt-4 grid grid-cols-2 gap-4" aria-label="Add a vendor" onSubmit={add}>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Name
                  <input name="name" required className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Phone (with country code)
                  <input name="phone" required placeholder="+919876543210" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Contact person
                  <input name="contactPerson" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Email
                  <input name="email" type="email" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  GSTIN
                  <input name="gstin" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Preferred language
                  <select name="preferredLanguage" defaultValue="en" className="min-h-touch rounded border border-hairline bg-canvas px-3">
                    {ALL_LANGUAGES.map((l) => <option key={l.code} value={l.code}>{l.label}</option>)}
                  </select>
                </label>
                <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
                  Address
                  <input name="address" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
                  Notes
                  <input name="notes" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <div className="col-span-2">
                  <button type="submit" disabled={busy} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">
                    Add vendor
                  </button>
                </div>
              </form>
            </section>
          )}

          <div className="mb-4">
            <label className="text-sm text-ink-secondary">
              <input type="checkbox" checked={activeOnly} onChange={(e) => setActiveOnly(e.target.checked)} className="mr-2 align-middle" />
              Active vendors only
            </label>
          </div>

          {loading ? (
            <Loading label="Loading vendors…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : vendors.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">No vendors yet</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                Add a vendor above, then set which ingredients they supply so the order list can suggest them.
              </p>
            </div>
          ) : (
            <div className="overflow-hidden rounded-lg bg-raised">
              <table className="w-full text-left">
                <thead className="bg-sunken text-sm text-ink-secondary">
                  <tr>
                    <th className="px-5 py-3 font-medium">Vendor</th>
                    <th className="px-5 py-3 font-medium">Phone</th>
                    <th className="px-5 py-3 font-medium">Language</th>
                    <th className="px-5 py-3 font-medium">Status</th>
                    <th className="px-5 py-3 font-medium text-right">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {vendors.map((v) => (
                    <tr key={v.id} className="border-t border-hairline align-middle">
                      <td className="px-5 py-3">
                        <Link href={`/vendors/${v.id}`} className="font-medium text-accent-text hover:underline">
                          {v.name}
                        </Link>
                        {v.contactPerson && <span className="ml-2 text-xs text-ink-muted">{v.contactPerson}</span>}
                      </td>
                      <td className="px-5 py-3 text-ink-secondary tabular-nums">{v.phone}</td>
                      <td className="px-5 py-3 text-ink-secondary">{languageLabel(v.preferredLanguage)}</td>
                      <td className="px-5 py-3">
                        <div className="flex flex-wrap gap-1.5">
                          {v.active ? (
                            <span className="rounded-sm bg-success-bg px-2 py-1 text-xs text-success">Active</span>
                          ) : (
                            <span className="rounded-sm bg-sunken px-2 py-1 text-xs text-ink-muted">Inactive</span>
                          )}
                          {!v.whatsappReachable && (
                            <span className="rounded-sm bg-warning-bg px-2 py-1 text-xs text-warning">Recheck WhatsApp</span>
                          )}
                        </div>
                      </td>
                      <td className="px-5 py-3 text-right">
                        {v.active ? (
                          <button type="button" disabled={busy} onClick={() => run((t) => api.deactivateVendor(v.id, t), "We couldn't deactivate that vendor.")} className="text-sm text-ink-secondary hover:underline disabled:opacity-60">
                            Deactivate
                          </button>
                        ) : (
                          <button type="button" disabled={busy} onClick={() => run((t) => api.reactivateVendor(v.id, t), "We couldn't reactivate that vendor.")} className="text-sm text-accent-text hover:underline disabled:opacity-60">
                            Reactivate
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </main>
    </div>
  );
}

function emptyToNull(s: string): string | null {
  const t = s.trim();
  return t === "" ? null : t;
}
