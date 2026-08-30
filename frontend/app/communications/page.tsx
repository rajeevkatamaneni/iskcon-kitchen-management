"use client";

import Link from "next/link";
import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { RequireRole } from "@/components/RequireRole";
import { Loading } from "@/components/Loading";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { api, type CommunicationDelivery, type CommunicationView } from "@/lib/api";

/**
 * What this temple has written to its devotees — the drafts and what has gone out.
 *
 * <p>Writing one is a screen of its own, because it is five fields and an act that cannot be
 * unsent. What comes back here is the confirmation and, for anything already sent, the record of
 * who it reached.
 */

export default function CommunicationsPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      {/* useSearchParams — the message being looked at, and what a sent one comes back with. */}
      <Suspense>
        <CommunicationsView />
      </Suspense>
    </RequireRole>
  );
}

function CommunicationsView() {
  const list = useAuthedQuery(useCallback((t: string | undefined) => api.listCommunications(t), []));
  const router = useRouter();
  const params = useSearchParams();

  const all = list.data ?? [];
  const drafts = all.filter((c) => c.status === "DRAFT");
  const sent = all.filter((c) => c.status === "SENT");

  // Item 22: which sent message is open is what somebody is looking at, so it is in the URL and
  // back closes it rather than leaving the page. Pushed, because opening one changes what is shown.
  const openId = params.get("message");
  const open = openId ? all.find((c) => c.id === openId) ?? null : null;

  // Sending happens on the composer's own screen and ends here, so the confirmation travels in the
  // URL. The ref guards the capture against a router object that is new on every render.
  const sentSubject = params.get("sent");
  const audience = params.get("audience");
  const [flash, setFlash] = useState<string | null>(null);
  const captured = useRef(false);
  useEffect(() => {
    if (captured.current || !sentSubject) return;
    captured.current = true;
    const count = Number(audience ?? 0);
    setFlash(`${sentSubject} went to ${count} devotee${count === 1 ? "" : "s"}.`);
    router.replace("/communications");
  }, [sentSubject, audience, router]);

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/communications" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Communications</h1>
              <p className="mt-1 text-ink-secondary">
                Devotees choose which kinds they receive. Reminders and receipts always reach them.
              </p>
            </div>
            <ButtonLink href="/communications/new">Write a message</ButtonLink>
          </header>

          {flash && (
            <div className="mb-6">
              <InlineNotice tone="success" autoDismiss title={flash} />
            </div>
          )}

          {open && <SentDetail communication={open} />}

          {!open &&
            (list.loading ? (
              <Loading label="Loading messages…" />
            ) : list.error ? (
              <ErrorNotice error={list.error} />
            ) : all.length === 0 ? (
              <div className="rounded-lg bg-raised px-6 py-14 text-center">
                <p className="text-lg">Nothing written yet</p>
                <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                  Write one, and send yourself a copy before anybody else gets it.
                </p>
              </div>
            ) : (
              <div className="grid gap-8">
                <CommunicationTable
                  heading="Drafts"
                  rows={drafts}
                  empty="Nothing in progress."
                  hrefFor={(c) => `/communications/${c.id}/edit`}
                />
                <CommunicationTable
                  heading="Sent"
                  rows={sent}
                  empty="Nothing has gone out yet."
                  hrefFor={(c) => `/communications?message=${c.id}`}
                />
              </div>
            ))}
        </div>
      </main>
    </div>
  );
}

// ---------------------------------------------------------------------------

function CommunicationTable({
  heading,
  rows,
  empty,
  hrefFor,
}: {
  heading: string;
  rows: CommunicationView[];
  empty: string;
  /** Where a row goes: a draft to its composer, a sent message to its record. */
  hrefFor: (c: CommunicationView) => string;
}) {
  const id = `${heading.toLowerCase()}-heading`;
  return (
    <section aria-labelledby={id}>
      <h2 id={id} className="mb-3 text-lg">
        {heading} <span className="text-sm text-ink-muted tabular-nums">({rows.length})</span>
      </h2>
      {rows.length === 0 ? (
        <p className="rounded-lg bg-raised px-6 py-8 text-center text-ink-secondary">{empty}</p>
      ) : (
        <div className="overflow-x-auto rounded-lg bg-raised">
          <table className="w-full text-left">
            <thead className="bg-sunken text-sm text-ink-secondary">
              <tr>
                <th className="px-5 py-3 font-medium">Subject</th>
                <th className="px-5 py-3 font-medium">Kind</th>
                <th className="px-5 py-3 font-medium">Sent as</th>
                <th className="px-5 py-3 font-medium text-right">Reached</th>
                <th className="px-5 py-3 font-medium">When</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.id} className="border-t border-hairline align-middle hover:bg-sunken">
                  <td className="px-5 py-3">
                    <Link href={hrefFor(c)} className="font-medium text-accent-text hover:underline">
                      {c.subject}
                    </Link>
                    {c.author && <div className="text-xs text-ink-muted">by {c.author}</div>}
                  </td>
                  <td className="px-5 py-3 text-ink-secondary">{CATEGORY_LABELS[c.category] ?? c.category}</td>
                  <td className="px-5 py-3 text-ink-secondary">{c.channel === "EMAIL" ? "Email" : "WhatsApp"}</td>
                  <td className="px-5 py-3 text-right tabular-nums">{c.audienceCount ?? "—"}</td>
                  <td className="px-5 py-3 text-ink-secondary tabular-nums">
                    {new Date(c.sentAt ?? c.createdAt).toLocaleDateString()}
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

const CATEGORY_LABELS: Record<string, string> = {
  NEWSLETTER: "Newsletter",
  FESTIVAL_ANNOUNCEMENT: "Festivals and events",
  SEVA_OPPORTUNITY: "Seva opportunities",
  APPEAL: "Appeals for support",
  TEMPLE_NOTICE: "Temple notices",
  OPERATIONAL: "Reminders and receipts",
};

// ---------------------------------------------------------------------------

function SentDetail({ communication }: { communication: CommunicationView }) {
  const deliveries = useAuthedQuery(
    useCallback((t: string | undefined) => api.communicationDeliveries(communication.id, t), [
      communication.id,
    ])
  );
  const rows: CommunicationDelivery[] = deliveries.data ?? [];

  return (
    <section className="mb-8 grid gap-6" aria-labelledby="sent-heading">
      <div className="rounded-lg bg-raised px-6 py-5">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <h2 id="sent-heading" className="text-lg">
              {communication.subject}
            </h2>
            <p className="mt-1 text-sm text-ink-secondary">
              {CATEGORY_LABELS[communication.category] ?? communication.category} ·{" "}
              {communication.channel === "EMAIL" ? "Email" : "WhatsApp"} · sent{" "}
              {communication.sentAt ? new Date(communication.sentAt).toLocaleString() : ""} to{" "}
              {communication.audienceCount} devotee{communication.audienceCount === 1 ? "" : "s"}
            </p>
          </div>
          <ButtonLink href="/communications" variant="secondary">
            Close
          </ButtonLink>
        </div>

        <p className="mt-4 text-sm text-ink-secondary">
          The web copy:{" "}
          <a
            href={`/c/${communication.publicToken}`}
            target="_blank"
            rel="noreferrer"
            className="text-accent-text hover:underline"
          >
            /c/{communication.publicToken.slice(0, 8)}…
          </a>{" "}
          — what the &ldquo;read in your browser&rdquo; link opens, and what WhatsApp points at.
        </p>
      </div>

      <div className="rounded-lg bg-raised px-6 py-5">
        <h3 className="text-lg">Who it went to</h3>
        {deliveries.loading ? (
          <Loading label="Loading recipients…" />
        ) : rows.length === 0 ? (
          <p className="mt-2 text-ink-secondary">No recipients recorded.</p>
        ) : (
          <div className="mt-4 overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-ink-secondary">
                <tr>
                  <th className="py-2 font-medium">Devotee</th>
                  <th className="py-2 font-medium">Channel</th>
                  <th className="py-2 font-medium">Outcome</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((d, i) => (
                  <tr key={`${d.recipientName}-${i}`} className="border-t border-hairline hover:bg-sunken">
                    <td className="py-2">{d.recipientName}</td>
                    <td className="py-2 text-ink-secondary">{d.channel ?? "—"}</td>
                    <td className="py-2">
                      <DeliveryOutcome status={d.status} reason={d.suppressedReason} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  );
}

function DeliveryOutcome({ status, reason }: { status: string; reason: string | null }) {
  if (status === "SENT" || status === "DELIVERED") {
    return <span className="text-success">{status === "DELIVERED" ? "Delivered" : "Sent"}</span>;
  }
  if (status === "FAILED") return <span className="text-danger">Failed</span>;
  if (status === "SUPPRESSED") {
    return (
      <span className="text-ink-muted">
        Not sent — {reason === "OPTED_OUT" ? "they turned this kind off" : "they haven’t agreed to be contacted"}
      </span>
    );
  }
  return <span className="text-ink-muted">Queued</span>;
}
