"use client";

import Link from "next/link";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { PLATFORM_NAV } from "@/lib/nav";
import { api } from "@/lib/api";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * Platform administration — the temples on this installation.
 *
 * <p>Read-only for release 1. Shows enough to confirm a temple exists and is being used, and
 * nothing about what happens inside it: running the platform is not the same as running a
 * temple, and the permission model enforces that boundary rather than trusting this screen to
 * respect it. Only a platform operator ({@code SUPER_ADMIN}) may see it.
 */
export default function TenantsPage() {
  return (
    <RequireRole roles={["SUPER_ADMIN"]}>
      <TenantsView />
    </RequireRole>
  );
}

function TenantsView() {
  const { data, error, loading } = useAuthedQuery(api.listTenants);
  const tenants = data ?? [];

  return (
    <div className="flex min-h-screen">
      <Sidebar templeName="Platform" items={PLATFORM_NAV} activeHref="/tenants" />

      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-8 flex items-center justify-between gap-4">
            <div>
              <h1>Temples</h1>
              <p className="mt-1 text-ink-secondary">
                Every temple on this installation.
              </p>
            </div>

            <Link
              href="/tenants/new"
              className="min-h-touch rounded bg-accent px-5 py-2.5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover"
            >
              Add a temple
            </Link>
          </header>

          {loading ? (
            <p className="text-ink-secondary">Loading temples…</p>
          ) : error ? (
            <ErrorNotice error={error} />
          ) : tenants.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">No temples yet</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                Adding a temple creates its workspace and its first administrator, who
                can sign in straight away.
              </p>
              <Link
                href="/tenants/new"
                className="mt-6 inline-block min-h-touch rounded bg-accent px-5 py-2.5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover"
              >
                Add the first temple
              </Link>
            </div>
          ) : (
            <div className="overflow-hidden rounded-lg bg-raised">
              <table className="w-full text-left">
                <thead className="bg-sunken text-sm text-ink-secondary">
                  <tr>
                    <th className="px-5 py-3 font-medium">Temple</th>
                    <th className="px-5 py-3 font-medium">Web address</th>
                    <th className="px-5 py-3 font-medium">Timezone</th>
                    <th className="px-5 py-3 font-medium">People</th>
                    <th className="px-5 py-3 font-medium">80G</th>
                  </tr>
                </thead>
                <tbody>
                  {tenants.map((tenant) => (
                    <tr key={tenant.id} className="border-t border-hairline">
                      <td className="px-5 py-4">{tenant.name}</td>
                      <td className="px-5 py-4 font-mono text-sm text-ink-secondary">
                        {tenant.slug}
                      </td>
                      <td className="px-5 py-4 text-ink-secondary">{tenant.timezone}</td>
                      <td className="px-5 py-4 text-ink-secondary">{tenant.user_count}</td>
                      <td className="px-5 py-4">
                        {tenant.is_80g_approved ? (
                          <span className="rounded-sm bg-success-bg px-2.5 py-1 text-sm text-success">
                            Approved
                          </span>
                        ) : (
                          <span className="text-sm text-ink-muted">Not approved</span>
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
