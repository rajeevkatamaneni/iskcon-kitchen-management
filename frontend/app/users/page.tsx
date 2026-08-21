"use client";

import { useCallback, useMemo, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type UserStatus, type UserSummary } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";

/**
 * The devotee register (E1-S12): everyone who has registered themselves at this temple.
 *
 * <p>There is no form here. Devotees join by registering — they choose the temple, give their own
 * name and contact details, and consent for themselves. An admin creating an account on someone's
 * behalf produced a person who had agreed to nothing and a set of contact details nobody had
 * confirmed, so that road is closed (2026-08-18). The temple's own employees are hired on
 * <b>/staff</b>, which is the only way anyone gets more than a devotee's access.
 *
 * <p>Nor is there a role control: a devotee holds one role, by definition. What remains is the one
 * decision an admin genuinely makes about a devotee — whether they may still sign in. Disabling
 * never deletes; shift history, signups and donations must survive the person leaving.
 */

export default function DevoteesPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <DevoteesView />
    </RequireRole>
  );
}

function DevoteesView() {
  const { getToken } = useAuth();
  const { data, error, loading, reload } = useAuthedQuery(
    useCallback((t: string | undefined) => api.listUsers(t, "VOLUNTEER"), [])
  );
  const devotees = useMemo(() => data ?? [], [data]);

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [search, setSearch] = useState("");

  const shown = useMemo(() => matching(devotees, search), [devotees, search]);
  const activeCount = devotees.filter((d) => d.status === "ACTIVE").length;

  async function run(mutation: (token: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    setActionError(null);
    try {
      await mutation(await getToken());
      reload();
    } catch (e) {
      setActionError(toApiError(e, failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/users" />

      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6">
            <h1>Devotees</h1>
            <p className="mt-1 text-ink-secondary">
              They sign themselves up. You decide who may still sign in.
            </p>
          </header>

          {actionError && (
            <div className="mb-6">
              <ErrorNotice error={actionError} />
            </div>
          )}

          {devotees.length > 0 && (
            <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
              <label className="flex-1 text-sm text-ink-secondary">
                <span className="sr-only">Search devotees</span>
                <input
                  type="search"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder="Search by name, email or phone"
                  className="min-h-touch w-full max-w-sm rounded border border-hairline bg-canvas px-3"
                />
              </label>
              <p className="text-sm text-ink-muted tabular-nums">
                {activeCount} active
                {devotees.length !== activeCount && ` · ${devotees.length - activeCount} disabled`}
              </p>
            </div>
          )}

          {loading ? (
            <Loading label="Loading devotees…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : devotees.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">No devotees yet</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                Share your temple’s registration link and they will appear here.
              </p>
            </div>
          ) : shown.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">Nobody matches &ldquo;{search}&rdquo;</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                Try part of a name, an email address, or a phone number.
              </p>
            </div>
          ) : (
            <div className="overflow-hidden rounded-lg bg-raised">
              <table className="w-full text-left">
                <thead className="bg-sunken text-sm text-ink-secondary">
                  <tr>
                    <th className="px-5 py-3 font-medium">Name</th>
                    <th className="px-5 py-3 font-medium">Email</th>
                    <th className="px-5 py-3 font-medium">Phone</th>
                    <th className="px-5 py-3 font-medium">Registered</th>
                    <th className="px-5 py-3 font-medium">Status</th>
                    <th className="px-5 py-3 font-medium">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {shown.map((devotee) => {
                    const nextStatus: UserStatus = devotee.status === "ACTIVE" ? "DISABLED" : "ACTIVE";
                    return (
                      <tr key={devotee.id} className="border-t border-hairline align-middle hover:bg-raised/60">
                        <td className="px-5 py-4">{devotee.fullName}</td>
                        <td className="px-5 py-4 text-ink-secondary">{devotee.email || "—"}</td>
                        <td className="px-5 py-4 text-ink-secondary tabular-nums">{devotee.phone || "—"}</td>
                        <td className="px-5 py-4 text-ink-secondary tabular-nums">
                          {new Date(devotee.createdAt).toLocaleDateString()}
                        </td>
                        <td className="px-5 py-4">
                          {devotee.status === "ACTIVE" ? (
                            <span className="text-success">Active</span>
                          ) : (
                            <span className="text-ink-muted">Disabled</span>
                          )}
                        </td>
                        <td className="px-5 py-4 text-sm">
                          <button
                            type="button"
                            disabled={busy}
                            onClick={() =>
                              run(
                                (token) => api.setUserStatus(devotee.id, nextStatus, token),
                                "We couldn’t update that devotee."
                              )
                            }
                            className="rounded border border-hairline-strong px-3 py-1 transition-colors duration-state hover:bg-sunken disabled:opacity-60"
                          >
                            {devotee.status === "ACTIVE" ? "Disable" : "Enable"}
                          </button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </main>
    </div>
  );
}

/** Name, email or phone contains what was typed. A temple's register grows past one screen. */
function matching(devotees: UserSummary[], search: string): UserSummary[] {
  const needle = search.trim().toLowerCase();
  if (!needle) return devotees;
  return devotees.filter((d) =>
    [d.fullName, d.email, d.phone].some((field) => (field ?? "").toLowerCase().includes(needle))
  );
}
