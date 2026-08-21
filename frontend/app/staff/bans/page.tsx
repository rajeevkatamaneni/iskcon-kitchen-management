"use client";

import { useCallback } from "react";
import Link from "next/link";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { RequireRole } from "@/components/RequireRole";
import { Loading } from "@/components/Loading";
import { BanRecord } from "@/components/staff/Ban";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { api } from "@/lib/api";

/**
 * The records this temple has raised about people it dismissed (B9).
 *
 * <p>Demoted and made read-only on 2026-08-21 (Q5). It came off the top of the staff register, where
 * it was the only way to know that a record existed at all — the former-staff table says that now,
 * on the row. Correcting a record and taking one back live on the person's own record and nowhere
 * else, so that one record can never be changed from two places.
 *
 * <p>Deleting the page was proposed and argued down. A ban is looked at through the person almost
 * always. But not when somebody asks what this temple has ever said about anybody, or audits one
 * before it fades at ten years. That job needs a list, and finding them one former employee at a
 * time is bad at it.
 *
 * <p><b>Only this temple's own records appear here.</b> There is no screen anywhere in the product
 * that shows anybody else's, and no endpoint that would serve one. The only way another temple's
 * record is ever seen is as a finding on a hire that is actually being made.
 */
export default function StaffBansPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <BansView />
    </RequireRole>
  );
}

function BansView() {
  const bans = useAuthedQuery(useCallback((t: string | undefined) => api.templeBans(t), []));
  const rows = bans.data ?? [];

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/staff" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6">
            <h1>Records we have raised</h1>
            <p className="mt-1 max-w-prose text-ink-secondary">
              People your temple dismissed and chose to warn other temples about.
            </p>
          </header>

          <div className="mb-6">
            <InlineNotice tone="info">
              <p>Only your own temple’s records are here.</p>
              <p>Open the person to correct one, or to take it back.</p>
            </InlineNotice>
          </div>

          {bans.loading ? (
            <Loading label="Loading records…" />
          ) : bans.error ? (
            <ErrorNotice error={bans.error} />
          ) : rows.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="mx-auto max-w-prose text-ink-secondary">
                Your temple has raised none.
              </p>
            </div>
          ) : (
            <ul className="grid gap-4">
              {rows.map((ban) => (
                <li key={ban.id} className="rounded-lg bg-raised px-6 py-5">
                  <h2 className="mb-1 text-lg">
                    {/* The row’s way in. A record is read whole on the person, beside the employment
                        it came out of. */}
                    <Link
                      href={`/staff/${ban.staffProfileId}`}
                      className="font-medium hover:text-accent-text hover:underline"
                    >
                      {ban.personName}
                    </Link>
                  </h2>
                  <BanRecord ban={ban} />
                </li>
              ))}
            </ul>
          )}
        </div>
      </main>
    </div>
  );
}
