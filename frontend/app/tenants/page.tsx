"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import { InlineNotice } from "@/components/ds/InlineNotice";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api } from "@/lib/api";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";

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
      {/* useSearchParams (for the post-create flash) must sit under a Suspense boundary. */}
      <Suspense>
        <TenantsView />
      </Suspense>
    </RequireRole>
  );
}

function TenantsView() {
  const { data, error, loading } = useAuthedQuery(api.listTenants);
  const tenants = data ?? [];

  const router = useRouter();
  const searchParams = useSearchParams();
  const createdSlug = searchParams.get("created");
  const deletedName = searchParams.get("deleted");
  const [flash, setFlash] = useState<{ kind: "created" | "deleted"; label: string } | null>(null);

  // A temple was just created or deleted: capture it for the banner and strip the query param so a
  // refresh doesn't flash it again. Guarded by a ref so it fires exactly once — setting an object
  // flash re-renders, and a test's useRouter can hand back a fresh object each render, which would
  // otherwise re-trigger this effect into a loop.
  const captured = useRef(false);
  useEffect(() => {
    if (captured.current) return;
    if (createdSlug) {
      captured.current = true;
      setFlash({ kind: "created", label: createdSlug });
      router.replace("/tenants");
    } else if (deletedName) {
      captured.current = true;
      setFlash({ kind: "deleted", label: deletedName });
      router.replace("/tenants");
    }
  }, [createdSlug, deletedName, router]);

  // Let the banner flash, then clear itself. Keyed on `flash` so stripping the param above doesn't
  // cut the timer short.
  useEffect(() => {
    if (!flash) return;
    const timer = setTimeout(() => setFlash(null), 6000);
    return () => clearTimeout(timer);
  }, [flash]);

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/tenants" />

      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-8 flex items-center justify-between gap-4">
            <div>
              <h1>Temples</h1>
            </div>

            <Link
              href="/tenants/new"
              className="min-h-touch rounded bg-accent px-5 py-2.5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover"
            >
              Add a temple
            </Link>
          </header>

          {flash?.kind === "created" && (
            <div className="mb-6">
              <InlineNotice
                tone="success"
                autoDismiss
                title={
                  <>
                    <span className="font-mono">{flash.label}</span> is ready.
                  </>
                }
              >
                Its administrator can sign in with the email address you entered.
              </InlineNotice>
            </div>
          )}

          {flash?.kind === "deleted" && (
            <div className="mb-6">
              <InlineNotice autoDismiss title={`${flash.label} was deleted, along with all of its data.`} />
            </div>
          )}

          {loading ? (
            <Loading label="Loading temples…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : tenants.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">No temples yet</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                Adding a temple creates its first administrator too.
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
                    <tr key={tenant.id} className="border-t border-hairline hover:bg-raised/60">
                      <td className="px-5 py-4">
                        <Link
                          href={`/tenants/${tenant.id}`}
                          className="font-medium hover:text-accent-text hover:underline"
                        >
                          {tenant.name}
                        </Link>
                      </td>
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
