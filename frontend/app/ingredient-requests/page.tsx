"use client";

import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Loading } from "@/components/Loading";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { EmptyState } from "@/components/ds/EmptyState";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { SegmentedControl } from "@/components/ds/SegmentedControl";
import { RequestStatusBadge } from "@/components/IngredientRequestStatus";
import { api, type IngredientRequestStatus } from "@/lib/api";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { shortDate } from "@/lib/format";

/**
 * Every request a kitchen has raised, newest first (E10-S8).
 *
 * <p>One list with a filter over it rather than a screen per state: draft, awaiting review and
 * approved are the same records with a different answer on them, and fetching them separately is
 * how approving something makes it vanish from one list without appearing in the other.
 *
 * <p>Everybody who can raise a request can read all of them, drafts included. That is deliberate
 * and it is in the design: the alternative is two people separately drafting a request for the same
 * feast. What a person may <em>do</em> to one they did not write is answered on the record itself.
 */

type Filter = "ALL" | IngredientRequestStatus;

const FILTERS: readonly { value: Filter; label: string }[] = [
  { value: "ALL", label: "All" },
  { value: "DRAFT", label: "Draft" },
  { value: "SUBMITTED", label: "Awaiting review" },
  { value: "APPROVED", label: "Approved" },
  { value: "DENIED", label: "Denied" },
  { value: "ISSUED", label: "Issued" },
];

/** What each filter says when it has nothing in it. A blank list should still tell you something. */
const NOTHING_HERE: Record<Filter, { title: string; body: string }> = {
  ALL: {
    title: "No requests yet",
    body: "When a kitchen needs something from the store, it asks here.",
  },
  DRAFT: {
    title: "No drafts",
    body: "A request stays a draft until somebody sends it for review.",
  },
  SUBMITTED: {
    title: "Nothing waiting for an answer",
    body: "Requests sent for review land here until they are approved or denied.",
  },
  APPROVED: {
    title: "Nothing approved and waiting",
    body: "An approved request stays here until the store records what it handed over.",
  },
  DENIED: {
    title: "Nothing has been denied",
    body: "A denied request stays on the record with the note explaining why.",
  },
  ISSUED: {
    title: "Nothing has been issued yet",
    body: "A request moves here once the store records what actually went over the counter.",
  },
};

const DEFAULT_FILTER: Filter = "ALL";

function filterFrom(value: string | null): Filter {
  return FILTERS.some((f) => f.value === value) ? (value as Filter) : DEFAULT_FILTER;
}

export default function IngredientRequestsPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      {/* useSearchParams — which filter is on lives in the address bar, so the view is linkable. */}
      <Suspense>
        <IngredientRequestsView />
      </Suspense>
    </RequireRole>
  );
}

function IngredientRequestsView() {
  const router = useRouter();
  const params = useSearchParams();
  const filter = filterFrom(params.get("status"));

  // Replaced rather than pushed: the filter narrows one screen, and back should leave the screen
  // rather than walk back through six tabs somebody flicked across.
  function choose(next: Filter) {
    router.replace(
      next === DEFAULT_FILTER ? "/ingredient-requests" : `/ingredient-requests?status=${next}`
    );
  }

  const { data, error, loading } = useAuthedQuery(
    useCallback(
      (token: string | undefined) =>
        api.listIngredientRequests(filter === "ALL" ? null : filter, token),
      [filter]
    )
  );
  const rows = data ?? [];

  // A request deleted from its own record has nowhere to go back to, so the confirmation travels
  // in the address. The ref guards the capture against a router object that is new every render.
  const deleted = params.get("deleted");
  const [flash, setFlash] = useState<string | null>(null);
  const captured = useRef(false);
  useEffect(() => {
    if (captured.current || !deleted) return;
    captured.current = true;
    setFlash(deleted);
    router.replace("/ingredient-requests");
  }, [deleted, router]);

  const empty = NOTHING_HERE[filter];

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/ingredient-requests" />

      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Ingredient requests</h1>
              <p className="mt-1 max-w-prose text-ink-secondary">
                What each kitchen has asked the store for, and where it stands.
              </p>
            </div>
            <ButtonLink href="/ingredient-requests/new">New request</ButtonLink>
          </header>

          <div className="mb-6">
            <SegmentedControl
              label="Filter requests by status"
              options={FILTERS}
              value={filter}
              onChange={choose}
            />
          </div>

          {flash && (
            <div className="mb-6">
              <InlineNotice autoDismiss title={`${flash} was deleted.`} />
            </div>
          )}

          {loading ? (
            <Loading label="Loading requests…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : rows.length === 0 ? (
            <EmptyState
              title={empty.title}
              action={
                filter === "ALL" || filter === "DRAFT" ? (
                  <ButtonLink href="/ingredient-requests/new">New request</ButtonLink>
                ) : undefined
              }
            >
              {empty.body}
            </EmptyState>
          ) : (
            <div className="overflow-hidden rounded-lg bg-raised">
              <table className="w-full text-left">
                <thead className="bg-sunken text-sm text-ink-secondary">
                  <tr>
                    <th className="px-5 py-3 font-medium">Reference</th>
                    <th className="px-5 py-3 font-medium">Kitchen</th>
                    <th className="px-5 py-3 font-medium">Needed on</th>
                    <th className="px-5 py-3 font-medium">Raised by</th>
                    <th className="px-5 py-3 font-medium">Status</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.id} className="border-t border-hairline hover:bg-sunken">
                      <td className="px-5 py-4">
                        <Link
                          href={`/ingredient-requests/${row.id}`}
                          className="font-mono font-medium hover:text-accent-text hover:underline"
                        >
                          {row.reference}
                        </Link>
                      </td>
                      <td className="px-5 py-4">{row.kitchenName}</td>
                      <td className="px-5 py-4 text-ink-secondary">{shortDate(row.neededOn)}</td>
                      <td className="px-5 py-4 text-ink-secondary">{row.requestedByName}</td>
                      <td className="px-5 py-4">
                        <RequestStatusBadge status={row.status} />
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
