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

const CATEGORY_LABEL: Record<string, string> = { MACHINE: "Machine", TOOL: "Tool", FURNITURE: "Furniture" };
const SOURCE_LABEL: Record<string, string> = { PURCHASED: "Purchased", DONATED: "Donated" };
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

export default function EquipmentDetailPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_STAFF"]}>
      <EquipmentDetailView />
    </RequireRole>
  );
}

function EquipmentDetailView() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const { getToken } = useAuth();

  const fetcher = useCallback((token: string | undefined) => api.getEquipment(id, token), [id]);
  const { data, error, loading, reload } = useAuthedQuery(fetcher);

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);

  const equipment = data?.equipment;
  const history = data?.history ?? [];
  const scrapped = equipment?.condition === "SCRAPPED";

  async function changeCondition(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const f = new FormData(form);
    setBusy(true);
    setActionError(null);
    try {
      await api.changeEquipmentCondition(
        id,
        String(f.get("condition") ?? ""),
        String(f.get("reason") ?? ""),
        await getToken()
      );
      form.reset();
      reload();
    } catch (e) {
      setActionError(toApiError(e, "We couldn't change the condition."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar templeName="Your temple" activeHref="/equipment" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <Link href="/equipment" className="text-sm text-accent-text hover:underline">← Equipment</Link>

          {loading ? (
            <p className="mt-6 text-ink-secondary">Loading…</p>
          ) : error ? (
            <div className="mt-6"><ErrorNotice error={error} /></div>
          ) : equipment ? (
            <>
              <header className="mb-6 mt-3 flex flex-wrap items-start justify-between gap-4">
                <div>
                  <h1>{equipment.name}</h1>
                  <p className="mt-1 text-ink-secondary">
                    {CATEGORY_LABEL[equipment.category] ?? equipment.category}
                    {equipment.storageLocation ? ` · ${equipment.storageLocation}` : ""}
                    {equipment.source ? ` · ${SOURCE_LABEL[equipment.source] ?? equipment.source}` : ""}
                    {equipment.acquisitionDate ? ` · acquired ${equipment.acquisitionDate}` : ""}
                  </p>
                  {equipment.notes && <p className="mt-2 max-w-prose text-sm text-ink-secondary">{equipment.notes}</p>}
                </div>
                <span className={`rounded-sm px-3 py-1.5 text-sm ${CONDITION_CLASS[equipment.condition] ?? ""}`}>
                  {CONDITION_LABEL[equipment.condition] ?? equipment.condition}
                </span>
              </header>

              {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

              <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="change-heading">
                <h2 id="change-heading" className="text-lg">Change condition</h2>
                {scrapped ? (
                  <p className="mt-2 text-sm text-ink-secondary">
                    This item has been scrapped, so its condition can&rsquo;t change. Register a replacement if you&rsquo;ve acquired one.
                  </p>
                ) : (
                  <form className="mt-4 grid grid-cols-2 gap-4" aria-label="Change condition" onSubmit={changeCondition}>
                    <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                      New condition
                      <select name="condition" required className="min-h-touch rounded border border-hairline bg-canvas px-3">
                        {CONDITIONS.filter((c) => c !== equipment.condition).map((c) => (
                          <option key={c} value={c}>{CONDITION_LABEL[c]}</option>
                        ))}
                      </select>
                    </label>
                    <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                      Reason
                      <input name="reason" required placeholder="Pressure valve leaking…" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
                    </label>
                    <div className="col-span-2">
                      <button type="submit" disabled={busy} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">
                        Record change
                      </button>
                    </div>
                  </form>
                )}
              </section>

              <section>
                <h2 className="mb-3 text-lg">History</h2>
                <div className="overflow-hidden rounded-lg bg-raised">
                  <table className="w-full text-left text-sm">
                    <thead className="bg-sunken text-ink-secondary">
                      <tr>
                        <th className="px-5 py-3 font-medium">When</th>
                        <th className="px-5 py-3 font-medium">Change</th>
                        <th className="px-5 py-3 font-medium">Reason</th>
                        <th className="px-5 py-3 font-medium">By</th>
                      </tr>
                    </thead>
                    <tbody>
                      {history.map((h) => (
                        <tr key={h.id} className="border-t border-hairline align-top">
                          <td className="px-5 py-3 text-ink-secondary">{new Date(h.createdAt).toLocaleString()}</td>
                          <td className="px-5 py-3">
                            {h.fromCondition ? `${CONDITION_LABEL[h.fromCondition] ?? h.fromCondition} → ` : ""}
                            {CONDITION_LABEL[h.toCondition] ?? h.toCondition}
                          </td>
                          <td className="px-5 py-3 text-ink-secondary">{h.reason}</td>
                          <td className="px-5 py-3 text-ink-secondary">{h.actorName ?? "—"}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </section>
            </>
          ) : null}
        </div>
      </main>
    </div>
  );
}
