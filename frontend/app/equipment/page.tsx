"use client";

import Link from "next/link";
import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { TEMPLE_NAV } from "@/lib/nav";
import { api, toApiError, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

const CATEGORIES = ["MACHINE", "TOOL", "FURNITURE"];
const CATEGORY_LABEL: Record<string, string> = { MACHINE: "Machine", TOOL: "Tool", FURNITURE: "Furniture" };
const CONDITIONS = ["GOOD", "NEEDS_REPAIR", "IN_REPAIR", "SCRAPPED"];
const CONDITION_LABEL: Record<string, string> = {
  GOOD: "Good",
  NEEDS_REPAIR: "Needs repair",
  IN_REPAIR: "In repair",
  SCRAPPED: "Scrapped",
};
const CONDITION_CLASS: Record<string, string> = {
  GOOD: "bg-success-bg text-success",
  NEEDS_REPAIR: "bg-warning-bg text-warning",
  IN_REPAIR: "bg-warning-bg text-warning",
  SCRAPPED: "bg-sunken text-ink-muted",
};

export default function EquipmentPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_STAFF"]}>
      <EquipmentListView />
    </RequireRole>
  );
}

function EquipmentListView() {
  const { getToken } = useAuth();
  const [includeScrapped, setIncludeScrapped] = useState(false);

  const fetcher = useCallback(
    (token: string | undefined) => api.listEquipment({ includeScrapped }, token),
    [includeScrapped]
  );
  const { data, error, loading, reload } = useAuthedQuery(fetcher);
  const equipment = data ?? [];

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [showAdd, setShowAdd] = useState(false);

  async function add(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const f = new FormData(form);
    setBusy(true);
    setActionError(null);
    try {
      await api.createEquipment(
        {
          name: String(f.get("name") ?? ""),
          category: String(f.get("category") ?? "TOOL"),
          storageLocation: emptyToNull(String(f.get("storageLocation") ?? "")),
          condition: String(f.get("condition") ?? "GOOD"),
          acquisitionDate: emptyToNull(String(f.get("acquisitionDate") ?? "")),
          source: emptyToNull(String(f.get("source") ?? "")),
          notes: emptyToNull(String(f.get("notes") ?? "")),
        },
        await getToken()
      );
      form.reset();
      setShowAdd(false);
      reload();
    } catch (e) {
      setActionError(toApiError(e, "We couldn't register that equipment."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar templeName="Your temple" items={TEMPLE_NAV} activeHref="/equipment" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Equipment</h1>
              <p className="mt-1 text-ink-secondary">
                The temple&rsquo;s durable assets — grinders, cauldrons, tables — and their condition.
              </p>
            </div>
            <button
              type="button"
              onClick={() => setShowAdd((s) => !s)}
              className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover"
            >
              Register equipment
            </button>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

          {showAdd && (
            <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="add-heading">
              <h2 id="add-heading" className="text-lg">Register a piece of equipment</h2>
              <form className="mt-4 grid grid-cols-2 gap-4" aria-label="Register equipment" onSubmit={add}>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Name
                  <input name="name" required className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Category
                  <select name="category" className="min-h-touch rounded border border-hairline bg-canvas px-3">
                    {CATEGORIES.map((c) => <option key={c} value={c}>{CATEGORY_LABEL[c]}</option>)}
                  </select>
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Location
                  <input name="storageLocation" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Condition
                  <select name="condition" defaultValue="GOOD" className="min-h-touch rounded border border-hairline bg-canvas px-3">
                    {CONDITIONS.filter((c) => c !== "SCRAPPED").map((c) => <option key={c} value={c}>{CONDITION_LABEL[c]}</option>)}
                  </select>
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Acquired on
                  <input name="acquisitionDate" type="date" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Source
                  <select name="source" defaultValue="" className="min-h-touch rounded border border-hairline bg-canvas px-3">
                    <option value="">Unknown</option>
                    <option value="PURCHASED">Purchased</option>
                    <option value="DONATED">Donated</option>
                  </select>
                </label>
                <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
                  Notes
                  <input name="notes" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                </label>
                <div className="col-span-2">
                  <button type="submit" disabled={busy} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">
                    Register
                  </button>
                </div>
              </form>
            </section>
          )}

          <label className="mb-4 flex items-center gap-2 text-sm text-ink-secondary">
            <input type="checkbox" checked={includeScrapped} onChange={(e) => setIncludeScrapped(e.target.checked)} className="h-5 w-5 rounded-sm border-hairline-strong" />
            Show scrapped items
          </label>

          {loading ? (
            <p className="text-ink-secondary">Loading equipment…</p>
          ) : error ? (
            <ErrorNotice error={error} />
          ) : equipment.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">No equipment yet</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">Register the temple&rsquo;s assets above.</p>
            </div>
          ) : (
            <div className="overflow-hidden rounded-lg bg-raised">
              <table className="w-full text-left">
                <thead className="bg-sunken text-sm text-ink-secondary">
                  <tr>
                    <th className="px-5 py-3 font-medium">Name</th>
                    <th className="px-5 py-3 font-medium">Category</th>
                    <th className="px-5 py-3 font-medium">Location</th>
                    <th className="px-5 py-3 font-medium">Condition</th>
                  </tr>
                </thead>
                <tbody>
                  {equipment.map((e) => (
                    <tr key={e.id} className="border-t border-hairline align-middle">
                      <td className="px-5 py-3">
                        <Link href={`/equipment/${e.id}`} className="font-medium text-accent-text hover:underline">{e.name}</Link>
                      </td>
                      <td className="px-5 py-3 text-ink-secondary">{CATEGORY_LABEL[e.category] ?? e.category}</td>
                      <td className="px-5 py-3 text-ink-secondary">{e.storageLocation ?? "—"}</td>
                      <td className="px-5 py-3">
                        <span className={`rounded-sm px-2 py-1 text-xs ${CONDITION_CLASS[e.condition] ?? ""}`}>
                          {CONDITION_LABEL[e.condition] ?? e.condition}
                        </span>
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
