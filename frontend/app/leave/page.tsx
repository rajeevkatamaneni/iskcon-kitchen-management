"use client";

import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Loading } from "@/components/Loading";
import { api, toApiError, type ApiError, type LeaveView, type MealCrewView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { shortDate } from "@/lib/format";
import { Badge } from "@/components/ds/Badge";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { EmptyState } from "@/components/ds/EmptyState";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { SegmentedControl } from "@/components/ds/SegmentedControl";

/**
 * The leave queue (B7): what is waiting, and what has been granted.
 *
 * <p>One list, filtered, rather than two screens. "Pending" and "approved" are the same records with
 * a different answer on them, and fetching them separately is how approving something makes it
 * vanish from one list without appearing in the other.
 *
 * <p>Behind TEMPLE_ADMIN and KITCHEN_MANAGER, which are the two roles holding APPROVE_LEAVE. A
 * manager runs the roster and leave is what the roster bends around; they are still deliberately not
 * shown the staff register, which is the only screen salary and PAN appear on.
 */

type Filter = "PENDING" | "APPROVED" | "ALL";

const FILTERS: readonly { value: Filter; label: string }[] = [
  { value: "PENDING", label: "Waiting" },
  { value: "APPROVED", label: "Approved" },
  { value: "ALL", label: "Everything" },
];

/** The tab a URL with no `tab` on it means. What is waiting is the reason to open this screen. */
const DEFAULT_FILTER: Filter = "PENDING";

function filterFrom(value: string | null): Filter {
  return FILTERS.some((f) => f.value === value) ? (value as Filter) : DEFAULT_FILTER;
}

export default function LeavePage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER"]}>
      {/* useSearchParams — the tab, and the confirmation recorded leave comes back with. */}
      <Suspense>
        <LeaveQueueView />
      </Suspense>
    </RequireRole>
  );
}

function LeaveQueueView() {
  const { getToken } = useAuth();
  const router = useRouter();
  const params = useSearchParams();

  // Item 22: which tab you are on is what you are looking at, so it lives in the address bar. It is
  // pushed rather than replaced, because moving from Waiting to Approved changes what the screen
  // shows — and back should return to the tab before it rather than throw you off the page.
  const filter = filterFrom(params.get("tab"));
  function choose(next: Filter) {
    router.push(next === DEFAULT_FILTER ? "/leave" : `/leave?tab=${next}`);
  }

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const queue = useAuthedQuery(useCallback((t: string | undefined) => api.leaveQueue(t), []));

  // Recording happens on /leave/record and ends here, so the confirmation travels in the URL. The
  // ref guards the capture against a router object that is new on every render.
  const recorded = params.get("recorded");
  const captured = useRef(false);
  useEffect(() => {
    if (captured.current || !recorded) return;
    captured.current = true;
    setNotice(`${recorded}\u2019s leave was recorded and approved.`);
    router.replace(`/leave?tab=${filterFrom(params.get("tab"))}`);
  }, [recorded, params, router]);

  const all = queue.data ?? [];
  const shown = all.filter((l) => (filter === "ALL" ? true : l.status === filter));

  async function run(mutation: (t: string | undefined) => Promise<unknown>, ok: string, failure: string) {
    setBusy(true);
    setActionError(null);
    setNotice(null);
    try {
      await mutation(await getToken());
      queue.reload();
      setNotice(ok);
      return true;
    } catch (e) {
      setActionError(toApiError(e, failure));
      return false;
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/leave" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Leave</h1>
              <p className="mt-1 max-w-prose text-ink-secondary">
                Time off, sick and unpaid leave. Approved leave takes the person off the schedule and
                out of the day&rsquo;s head count.
              </p>
            </div>
            <ButtonLink href="/leave/record">Record leave for someone</ButtonLink>
          </header>

          {actionError && <div className="mb-4"><ErrorNotice error={actionError} /></div>}
          {notice && <div className="mb-4"><InlineNotice tone="success" autoDismiss>{notice}</InlineNotice></div>}

          <div className="mb-4">
            <SegmentedControl options={FILTERS} value={filter} onChange={choose} label="Which leave to show" />
          </div>

          {queue.loading ? (
            <Loading label="Loading leave…" />
          ) : queue.error ? (
            <ErrorNotice error={queue.error} />
          ) : shown.length === 0 ? (
            <EmptyState title={filter === "PENDING" ? "Nothing waiting" : "Nothing to show"}>
              {filter === "PENDING"
                ? "Every request has been answered. Record leave yourself for staff with no login."
                : "Leave will appear here once somebody asks for it or you record it."}
            </EmptyState>
          ) : (
            <ul className="grid gap-3">
              {shown.map((leave) => (
                <li key={leave.id} className="rounded-lg bg-raised px-6 py-4">
                  <div className="flex flex-wrap items-start justify-between gap-4">
                    <div>
                      <p className="font-medium text-ink">
                        {leave.staffName} <span className="text-ink-muted">· {leave.jobTitleLabel}</span>
                      </p>
                      <p className="mt-1 text-sm text-ink-secondary tabular-nums">
                        {leave.leaveTypeLabel} · {dateRange(leave)}
                      </p>
                      {leave.reason && <p className="mt-1 max-w-prose text-sm text-ink-secondary">{leave.reason}</p>}
                      <p className="mt-1 text-xs text-ink-muted">
                        {leave.requestedByName ? `Asked for by ${leave.requestedByName}` : "Recorded by the temple"}
                        {leave.decidedByName ? ` · answered by ${leave.decidedByName}` : ""}
                      </p>
                      {leave.decisionNote && <p className="mt-1 text-xs text-ink-muted">{leave.decisionNote}</p>}
                      {leave.status === "PENDING" && <WhatItCosts id={leave.id} />}
                    </div>
                    <div className="flex flex-none flex-col items-end gap-2">
                      <StatusBadge status={leave.status} />
                      {leave.status === "PENDING" && (
                        <div className="flex gap-2">
                          <Button size="sm" disabled={busy} onClick={() => run((t) => api.decideLeave(leave.id, "approve", null, t), "Approved. They were told.", "We couldn't approve that.")}>
                            Approve
                          </Button>
                          <Button size="sm" variant="secondary" disabled={busy} onClick={() => run((t) => api.decideLeave(leave.id, "decline", null, t), "Declined. They were told.", "We couldn't decline that.")}>
                            Decline
                          </Button>
                        </div>
                      )}
                      {leave.status === "APPROVED" && (
                        <Button size="sm" variant="ghost" disabled={busy} onClick={() => run((t) => api.decideLeave(leave.id, "revoke", null, t), "Revoked. They were told.", "We couldn't revoke that.")}>
                          Revoke
                        </Button>
                      )}
                    </div>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>
      </main>
    </div>
  );
}

/**
 * What approving this request would cost the kitchen (item 24).
 *
 * <p>An approver answering a day off is the person best placed to know it leaves lunch short, and
 * the worst placed to work it out: it needs the roster, the week's meals and the crew each of them
 * was planned for. So the screen says it. <b>Told, and never stopped</b> — nothing here disables
 * Approve or asks for a second confirmation. A temple that needs somebody to have the day off needs
 * it whatever the count says, and a rule that argued back would only teach people to ignore it.
 *
 * <p>Fetched per row rather than carried on the queue, because it is a roster-and-planner query for
 * each request and a list of forty would pay for all of them to answer one. Only pending rows ask,
 * and a request the person was not standing in for any meal on answers with nothing and shows
 * nothing.
 */
function WhatItCosts({ id }: { id: string }) {
  const impact = useAuthedQuery(useCallback((t: string | undefined) => api.leaveImpact(id, t), [id]));
  const meals = impact.data ?? [];
  if (meals.length === 0) return null;

  return (
    <p className={`mt-2 text-sm ${meals.some((m) => m.shortOfCrew) ? "text-warning" : "text-ink-secondary"}`}>
      Approving this leaves {meals.map(describe).join(", ")}.
    </p>
  );
}

/** "Lunch on 24 Aug at 4 of 8" — the meal, the day, and the two numbers that matter. */
function describe(meal: MealCrewView): string {
  const at = meal.crewRequired === null ? `at ${meal.rostered}` : `at ${meal.rostered} of ${meal.crewRequired}`;
  return `${meal.mealKind} on ${shortDate(meal.planDate)} ${at}`;
}

function StatusBadge({ status }: { status: LeaveView["status"] }) {
  if (status === "APPROVED") return <Badge tone="success">Approved</Badge>;
  if (status === "DECLINED") return <Badge tone="danger">Declined</Badge>;
  if (status === "REVOKED") return <Badge tone="neutral">Revoked</Badge>;
  return <Badge tone="warning">Waiting</Badge>;
}

function dateRange(leave: LeaveView): string {
  if (leave.halfDay) return `${leave.fromDate} (half day)`;
  return leave.fromDate === leave.toDate ? leave.fromDate : `${leave.fromDate} to ${leave.toDate}`;
}
