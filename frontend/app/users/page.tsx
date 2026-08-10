"use client";

import { useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { TEMPLE_NAV } from "@/lib/nav";
import { api, toApiError, type ApiError, type UserRole, type UserStatus } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * Temple user management (E1-S12): the screen a Temple Admin uses to add people, change what they
 * can do, and disable those who leave — so a temple with one admin can actually staff itself.
 *
 * <p>Roles are the fixed set; a platform operator can't be minted here. Adding someone creates their
 * account ahead of their first sign-in (they claim it then, E1-S6, and give their own consent,
 * E1-S8). Disabling blocks access but never deletes — history stays intact. A Temple Admin can't
 * change their own role or disable themselves; the backend refuses it and this screen doesn't offer
 * it.
 */

const ROLES: { value: UserRole; label: string }[] = [
  { value: "TEMPLE_ADMIN", label: "Temple admin" },
  { value: "KITCHEN_STAFF", label: "Kitchen staff" },
  { value: "VOLUNTEER", label: "Volunteer" },
];

function roleLabel(role: string): string {
  return ROLES.find((r) => r.value === role)?.label ?? role;
}

export default function UsersPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <UsersView />
    </RequireRole>
  );
}

function UsersView() {
  const { appUser, getToken } = useAuth();
  const { data, error, loading, reload } = useAuthedQuery(api.listUsers);
  const users = data ?? [];

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);

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

  async function addPerson(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    await run(
      (token) =>
        api.addUser(
          {
            fullName: String(data.get("fullName") ?? ""),
            email: String(data.get("email") ?? ""),
            phone: String(data.get("phone") ?? ""),
            role: String(data.get("role") ?? "VOLUNTEER") as UserRole,
          },
          token
        ),
      "We couldn't add that person."
    );
    form.reset();
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar templeName="Your temple" items={TEMPLE_NAV} activeHref="/users" />

      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-8">
            <h1>People</h1>
            <p className="mt-1 text-ink-secondary">
              Everyone at your temple, and what they can do.
            </p>
          </header>

          {actionError && (
            <div className="mb-6">
              <ErrorNotice error={actionError} />
            </div>
          )}

          <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="add-heading">
            <h2 id="add-heading" className="text-lg">
              Add someone
            </h2>
            <p className="mt-1 text-sm text-ink-secondary">
              They&apos;ll be able to sign in with the email or phone you enter here.
            </p>
            <form className="mt-4 grid grid-cols-2 gap-4" aria-label="Add a person" onSubmit={addPerson}>
              <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                Full name
                <input name="fullName" required className="min-h-touch rounded border border-hairline bg-raised px-3" />
              </label>
              <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                Role
                <select name="role" className="min-h-touch rounded border border-hairline bg-raised px-3">
                  {ROLES.map((r) => (
                    <option key={r.value} value={r.value}>
                      {r.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                Email
                <input name="email" type="email" required className="min-h-touch rounded border border-hairline bg-raised px-3" />
              </label>
              <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                Phone
                <input name="phone" required placeholder="+919876543210" className="min-h-touch rounded border border-hairline bg-raised px-3" />
              </label>
              <div className="col-span-2">
                <button
                  type="submit"
                  disabled={busy}
                  className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60"
                >
                  Add person
                </button>
              </div>
            </form>
          </section>

          {loading ? (
            <p className="text-ink-secondary">Loading people…</p>
          ) : error ? (
            <ErrorNotice error={error} />
          ) : users.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">Just you so far</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                Add a cook, a volunteer coordinator, or a fellow administrator, and they&apos;ll
                appear here.
              </p>
            </div>
          ) : (
            <div className="overflow-hidden rounded-lg bg-raised">
              <table className="w-full text-left">
                <thead className="bg-sunken text-sm text-ink-secondary">
                  <tr>
                    <th className="px-5 py-3 font-medium">Name</th>
                    <th className="px-5 py-3 font-medium">Email</th>
                    <th className="px-5 py-3 font-medium">Role</th>
                    <th className="px-5 py-3 font-medium">Status</th>
                    <th className="px-5 py-3 font-medium">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {users.map((user) => {
                    const isSelf = user.id === appUser?.userId;
                    const nextStatus: UserStatus = user.status === "ACTIVE" ? "DISABLED" : "ACTIVE";
                    return (
                      <tr key={user.id} className="border-t border-hairline align-middle">
                        <td className="px-5 py-4">{user.fullName}</td>
                        <td className="px-5 py-4 text-ink-secondary">{user.email}</td>
                        <td className="px-5 py-4">
                          {isSelf ? (
                            roleLabel(user.role)
                          ) : (
                            <label className="sr-only" htmlFor={`role-${user.id}`}>
                              Role for {user.fullName}
                            </label>
                          )}
                          {!isSelf && (
                            <select
                              id={`role-${user.id}`}
                              value={user.role}
                              disabled={busy}
                              onChange={(e) => {
                                // Capture the chosen role now: the select is controlled by
                                // user.role, so React reverts its value across the await before
                                // the list reloads.
                                const role = e.target.value as UserRole;
                                run(
                                  (token) => api.changeUserRole(user.id, role, token),
                                  "We couldn't change that role."
                                );
                              }}
                              className="min-h-touch rounded border border-hairline bg-raised px-2"
                            >
                              {ROLES.map((r) => (
                                <option key={r.value} value={r.value}>
                                  {r.label}
                                </option>
                              ))}
                            </select>
                          )}
                        </td>
                        <td className="px-5 py-4">
                          {user.status === "ACTIVE" ? (
                            <span className="text-success">Active</span>
                          ) : (
                            <span className="text-ink-muted">Disabled</span>
                          )}
                        </td>
                        <td className="px-5 py-4 text-sm">
                          {isSelf ? (
                            <span className="text-ink-muted">You</span>
                          ) : (
                            <button
                              type="button"
                              disabled={busy}
                              onClick={() =>
                                run(
                                  (token) => api.setUserStatus(user.id, nextStatus, token),
                                  "We couldn't update that person."
                                )
                              }
                              className="rounded border border-hairline-strong px-3 py-1 transition-colors duration-state hover:bg-sunken disabled:opacity-60"
                            >
                              {user.status === "ACTIVE" ? "Disable" : "Enable"}
                            </button>
                          )}
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
