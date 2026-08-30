"use client";

import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Badge } from "@/components/ds/Badge";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { RequireRole } from "@/components/RequireRole";
import { Loading } from "@/components/Loading";
import { ACCESS_LABELS, employmentTypeLabel } from "@/components/staff/labels";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { api, type StaffProfileView } from "@/lib/api";

/**
 * The staff register (E6-S8): who works at this temple, and who used to.
 *
 * <p>A list and nothing else since 2026-08-21. Hiring, updating and terminating each have a screen
 * of their own now — this page's job is to say who is here, and to be the place all three of them
 * come back to with their confirmation waiting.
 *
 * <p>Former staff get their own section rather than a status column in one list. An admin looking
 * for them is asking a different question — who was the cook last Janmashtami, and why did they
 * leave — and mixing the two buries the answer under the people they see every day. What the two
 * tables no longer do is look different: both carry the same columns and the same buttons, because
 * a row that reads one way in one table and another way in the next has to be learned twice.
 *
 * <p><b>A former employee this temple raised a record about is drawn differently</b> (B9, item 2):
 * their name in the danger ink with a Banned pill beside it. An ordinary leaving and a dismissal
 * with a warning attached are then one glance apart. That fact used to be reachable only through a
 * link at the top of this page, which meant it could be read in full or not at all.
 */
export default function StaffPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      {/* useSearchParams (for the confirmation a committed screen sends back) needs a boundary. */}
      <Suspense>
        <StaffView />
      </Suspense>
    </RequireRole>
  );
}

/** The three screens that come back here, and what each of them leaves on the page. */
const FLASHES = [
  { param: "hired", tone: "success", said: (who: string) => `${who} is on the staff register.` },
  { param: "updated", tone: "success", said: (who: string) => `${who}’s record was saved.` },
  { param: "terminated", tone: "info", said: (who: string) => `${who}’s employment has ended.` },
] as const;

function StaffView() {
  const register = useAuthedQuery(useCallback((t: string | undefined) => api.staffRegister(t), []));
  // Only for the count on the quiet line under Former staff. The records themselves are read on the
  // person, or on the audit list this links to.
  const bans = useAuthedQuery(useCallback((t: string | undefined) => api.templeBans(t), []));

  const router = useRouter();
  const searchParams = useSearchParams();
  const [flash, setFlash] = useState<{ tone: "success" | "info"; said: string } | null>(null);

  // Somebody was just hired, updated or terminated: capture it for the notice and strip the query
  // param so a refresh doesn't say it again. Guarded by a ref so it fires exactly once — setting an
  // object flash re-renders, and a test's useRouter can hand back a fresh object each render, which
  // would otherwise re-trigger this effect into a loop.
  const captured = useRef(false);
  useEffect(() => {
    if (captured.current) return;
    for (const { param, tone, said } of FLASHES) {
      const who = searchParams.get(param);
      if (!who) continue;
      captured.current = true;
      setFlash({ tone, said: said(who) });
      router.replace("/staff");
      return;
    }
  }, [searchParams, router]);

  const current = register.data?.current ?? [];
  const former = register.data?.former ?? [];
  const banCount = bans.data?.length ?? 0;

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/staff" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Staff</h1>
              <p className="mt-1 text-ink-secondary">
                Hiring here is the only way anyone is given access to the app.
              </p>
            </div>
            <ButtonLink href="/staff/new">Hire someone</ButtonLink>
          </header>

          {flash && (
            <div className="mb-6">
              <InlineNotice tone={flash.tone} autoDismiss title={flash.said} />
            </div>
          )}

          {register.loading ? (
            <Loading label="Loading staff…" />
          ) : register.error ? (
            <ErrorNotice error={register.error} />
          ) : (
            <>
              <StaffTable
                heading="Current staff"
                empty="Press “Hire someone” to add your first."
                rows={current.map((staff) => ({ staff, banned: false }))}
              />

              {former.length > 0 && (
                <div className="mt-10">
                  <StaffTable
                    heading="Former staff"
                    caption="Kept so you can answer who worked here, and when."
                    empty=""
                    former
                    rows={former.map((f) => ({ staff: f.profile, banned: f.banned }))}
                  />
                  {/* A quiet line and not a navigation item: what this temple has ever recorded about
                      anybody is not daily work, and it should take a moment to reach. It is read-only
                      there — a record is corrected or taken back on the person’s own record, so that
                      it can never be changed from two places (Q5). */}
                  {banCount > 0 && (
                    <Link
                      href="/staff/bans"
                      className="mt-3 inline-block text-sm text-accent-text hover:underline"
                    >
                      Records we have raised · {banCount}
                    </Link>
                  )}
                </div>
              )}
            </>
          )}
        </div>
      </main>
    </div>
  );
}

// ---------------------------------------------------------------------------

function StaffTable({
  heading,
  caption,
  empty,
  rows,
  former = false,
}: {
  heading: string;
  caption?: string;
  empty: string;
  rows: { staff: StaffProfileView; banned: boolean }[];
  /** Former staff carry the day they left, and are read rather than edited. */
  former?: boolean;
}) {
  const id = `${heading.replace(/\s+/g, "-").toLowerCase()}-heading`;

  return (
    <section aria-labelledby={id}>
      <h2 id={id} className="mb-1 text-lg">
        {heading} <span className="text-sm text-ink-muted tabular-nums">({rows.length})</span>
      </h2>
      {caption && <p className="mb-3 text-sm text-ink-secondary">{caption}</p>}

      {rows.length === 0 ? (
        <div className="rounded-lg bg-raised px-6 py-14 text-center">
          <p className="mx-auto max-w-prose text-ink-secondary">{empty}</p>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-lg bg-raised">
          <table className="w-full text-left">
            <thead className="bg-sunken text-sm text-ink-secondary">
              <tr>
                <th className="px-5 py-3 font-medium">Name</th>
                <th className="px-5 py-3 font-medium">Job</th>
                <th className="px-5 py-3 font-medium">Contact</th>
                <th className="px-5 py-3 font-medium">Access</th>
                {/* Joined and PAN left this table on 2026-08-20. The joining date is on the record
                    and rarely the thing being scanned for, and a PAN is not something to have sitting
                    in a column at all — it is now read from the person’s own record, where it is one
                    deliberate act rather than an inch from every other row. The room they freed goes
                    to the actions. */}
                {former && <th className="px-5 py-3 font-medium">Left</th>}
                <th className="px-5 py-3 font-medium text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(({ staff: s, banned }) => (
                <tr key={s.id} className="border-t border-hairline align-middle hover:bg-sunken">
                  <td className={`px-5 py-4 ${banned ? "text-danger" : ""}`}>
                    {s.fullName}
                    {banned && (
                      <span className="ml-2">
                        <Badge tone="danger">Banned</Badge>
                      </span>
                    )}
                    {s.jobTitle === "UNRECORDED" && (
                      <span className="ml-2">
                        <Badge tone="warning">Job not recorded</Badge>
                      </span>
                    )}
                  </td>
                  <td className="px-5 py-4">
                    {s.jobTitleLabel}
                    <div className="text-xs text-ink-muted">{employmentTypeLabel(s.employmentType)}</div>
                  </td>
                  <td className="px-5 py-4 text-sm text-ink-secondary">
                    <div className="tabular-nums">{s.phone ?? "—"}</div>
                    <div>{s.email ?? ""}</div>
                  </td>
                  <td className="px-5 py-4 text-sm">
                    {s.systemAccess ? (
                      ACCESS_LABELS[s.systemAccess]
                    ) : (
                      <span className="text-ink-muted">No login</span>
                    )}
                  </td>
                  {former && (
                    <td className="px-5 py-4 text-sm tabular-nums text-ink-secondary">
                      {/* The date and nothing else. How and why they left are on their record, which
                          is one press away in this same row — a second line of prose here made this
                          table read as a different table from the one above it. */}
                      {s.lastWorkingDay}
                    </td>
                  )}
                  <td className="px-5 py-4">
                    {/* One row, right-aligned, wrapping only on a narrow window. Terminate sits last
                        and apart: it is the one action here nobody takes twice. Former staff get
                        View instead, because they have no editable form and would otherwise have no
                        way into their own record. Current staff do not (Q6) — Update is that record,
                        and a fourth button would be a second door to the same room. */}
                    <div className="flex flex-wrap items-center justify-end gap-2">
                      {former && (
                        <ButtonLink href={`/staff/${s.id}`} variant="secondary" size="sm" className={ROW_ACTION}>
                          View
                        </ButtonLink>
                      )}
                      <ButtonLink
                        href={`/staff/${s.id}/pay`}
                        variant="secondary"
                        size="sm"
                        className={ROW_ACTION}
                      >
                        Pay
                      </ButtonLink>
                      {!former && (
                        <>
                          <ButtonLink
                            href={`/staff/${s.id}/edit`}
                            variant="secondary"
                            size="sm"
                            className={ROW_ACTION}
                          >
                            Update
                          </ButtonLink>
                          <ButtonLink
                            href={`/staff/${s.id}/terminate`}
                            variant="danger"
                            size="sm"
                            className={`${ROW_ACTION} ml-1`}
                          >
                            Terminate
                          </ButtonLink>
                        </>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

/**
 * The row actions, asked for on 2026-08-20: they should read as controls rather than as underlined
 * words in the middle of a table.
 *
 * <p>Everything that makes them look like buttons — the border, the hover fill, the focus ring, the
 * radius — comes from {@link ButtonLink}. All this adds is a little more room at each end, so three
 * short words in a narrow column do not read as cramped.
 *
 * <p>They were briefly pill-shaped. They are not any more: §4 of the design system gives the pill
 * radius to status chips alone, so that a shape says whether something is a state or an action, and
 * a screen that spends it on buttons takes that distinction away everywhere.
 *
 * <p>Every one of them is a link now rather than a button. Each opens a screen with its own address,
 * which is what makes the browser's back button do the obvious thing.
 */
const ROW_ACTION = "px-4";
