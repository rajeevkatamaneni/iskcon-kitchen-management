"use client";

import { useCallback, useState, type ReactNode } from "react";
import { api, toApiError, type NoticeSeverity, type PlatformNotice } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Badge } from "@/components/ds/Badge";
import { TEMPLE_TIME_ZONE } from "@/lib/format";

/**
 * The platform notice board, as it appears to somebody receiving one (E9-S1).
 *
 * <p>Two exports, one look. {@link PlatformNotices} is the band at the top of Today — it fetches its
 * own feed, renders nothing at all when there is nothing to say, and is meant to cost the Today
 * screen one import and one line. {@link NoticeCard} is the same card the permanent board at
 * /notices draws, so a notice cannot look like one thing on Today and another an hour later.
 *
 * <p><strong>Only urgent is drawn loud.</strong> Urgent gets the danger wash and the danger rule.
 * Important gets a rule and nothing else. Information gets a hairline. A board where every entry
 * shouts is a board people learn to scroll past, and the one time it matters they will scroll past
 * that too (build brief 2026-08-20, §11).
 *
 * <p>A withdrawn notice is drawn quiet whatever it was raised as. A retracted recall that kept
 * shouting would be worse than the recall.
 *
 * <p>The body is plain text and is rendered as text. There is deliberately no HTML anywhere in this
 * file: this is the one payload in the product that one temple writes and another temple's browser
 * renders, so React's escaping is doing real security work here, not just tidiness.
 */

const SEVERITY: Record<
  NoticeSeverity,
  { card: string; heading: string; badge: "danger" | "warning" | "neutral"; label: string }
> = {
  URGENT: {
    card: "border-l-4 border-danger bg-danger-bg",
    heading: "text-danger",
    badge: "danger",
    label: "Urgent",
  },
  IMPORTANT: {
    // A rule down the edge and nothing more. Enough to find in a stack, not enough to compete with
    // an urgent one sitting above it.
    card: "border-l-4 border-warning bg-raised",
    heading: "text-ink",
    badge: "warning",
    label: "Important",
  },
  INFORMATION: {
    card: "border-l-4 border-hairline bg-raised",
    heading: "text-ink",
    badge: "neutral",
    label: "Information",
  },
};

const WITHDRAWN = {
  // Raised, not sunken. The severity chip's neutral tone *is* bg-sunken, so on a sunken card the
  // chip vanished into its own background and its label was left floating with nothing round it —
  // which is most of what made a withdrawn notice look unfinished. The card stays quiet by the
  // hairline rule and the muted heading, which is where its quietness was meant to come from.
  card: "border-l-4 border-hairline bg-raised",
  heading: "text-ink-secondary",
  badge: "neutral" as const,
  label: "Withdrawn",
};

/**
 * "14 August 2026, 21:40" in the temple's own clock.
 *
 * <p>Not the shared date formatters: those take a temple day, and a notice carries the instant it
 * was posted — which for a recall raised at nine on a Sunday evening is the part that matters.
 */
export function noticeMoment(iso: string): string {
  return new Date(iso).toLocaleString("en-GB", {
    timeZone: TEMPLE_TIME_ZONE,
    day: "numeric",
    month: "long",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/**
 * One notice, drawn the same way wherever it appears.
 *
 * @param onDismiss  offered only where dismissing means something — the Today band. The permanent
 *                   board has nothing to clear, since nothing ever leaves it.
 * @param action     the withdraw control, on the board. Passed in rather than built here so this
 *                   file does not need to know what a withdrawal form looks like.
 */
export function NoticeCard({
  notice,
  onDismiss,
  action,
}: {
  notice: PlatformNotice;
  onDismiss?: (id: string) => void;
  action?: ReactNode;
}) {
  const style = notice.withdrawn ? WITHDRAWN : SEVERITY[notice.severity];

  return (
    <article
      data-severity={notice.severity}
      data-withdrawn={notice.withdrawn ? "true" : "false"}
      className={["rounded-lg px-5 py-4", style.card].join(" ")}
    >
      {/*
        Everything in this card starts on one left edge — the severity chip, the subject, the body,
        the actions. The subject used to sit beside the chip, which pushed it a chip’s width in
        while every line beneath it began at the card’s padding: three different left edges in one
        card, and it read as carelessly as that sounds. The chip now has the first line to itself
        with the provenance opposite it, and the subject drops to its own.
      */}
      <header className="flex flex-wrap items-baseline justify-between gap-3">
        <Badge tone={style.badge}>{style.label}</Badge>
        {/* The raising temple, always named, even when it is the reader’s own — "in the open" is
            one of the three things standing in for the review this board deliberately does
            without, and a name that disappears for some readers is not in the open. */}
        <p className="text-xs text-ink-muted">
          {notice.raisedBy}
          {notice.mine ? " (your temple)" : ""} · {noticeMoment(notice.raisedAt)}
        </p>
      </header>

      <h3 className={["mt-2 text-base font-medium", style.heading].join(" ")}>{notice.subject}</h3>

      {notice.withdrawn ? (
        <div className="mt-2 text-sm text-ink-secondary">
          <p>
            <span className="font-medium">Withdrawn</span> by {notice.withdrawnBy}
            {notice.withdrawnAt ? ` on ${noticeMoment(notice.withdrawnAt)}` : ""} —{" "}
            {notice.withdrawnReason}
          </p>
          {/* The original stays legible underneath. Somebody who acted on it needs to see what is
              being retracted, not just that something was. */}
          <p className="mt-2 whitespace-pre-wrap text-ink-muted">{notice.body}</p>
        </div>
      ) : (
        <p className="mt-2 whitespace-pre-wrap text-sm text-ink">{notice.body}</p>
      )}

      {(onDismiss || action) && (
        // Pulled left by the controls' own padding, so their words sit on the same edge as the
        // body above rather than a few pixels inside it.
        <div className="mt-3 -ml-3 flex flex-wrap items-center gap-1">
          {action}
          {onDismiss && (
            <button
              type="button"
              onClick={() => onDismiss(notice.id)}
              className="min-h-9 rounded px-3 text-sm text-ink-secondary transition-colors duration-state hover:bg-sunken hover:text-ink"
            >
              Dismiss
            </button>
          )}
        </div>
      )}
    </article>
  );
}

/**
 * The band at the top of Today. Renders nothing when there is nothing outstanding, which is most
 * days — a heading over an empty list is how a screen teaches people to ignore that part of it.
 *
 * <p>A failed fetch also renders nothing. Today is a morning screen assembled from a dozen sources,
 * and a red box about the notice service is not what a cook needs at six; the permanent board is
 * where a failure is worth reporting properly.
 */
export function PlatformNotices() {
  const { getToken } = useAuth();
  const { data } = useAuthedQuery(useCallback((t: string | undefined) => api.noticeFeed(t), []));

  // Cleared here as well as on the server, so the card goes the moment it is clicked rather than a
  // round trip later. The server is still the record; this is only what the person sees meanwhile.
  const [cleared, setCleared] = useState<string[]>([]);

  const notices = (data ?? []).filter((n) => !cleared.includes(n.id));
  if (notices.length === 0) return null;

  async function dismiss(id: string) {
    setCleared((c) => [...c, id]);
    try {
      await api.dismissNotice(id, await getToken());
    } catch (e) {
      // Nothing to say to the person: they asked for it gone and it is gone from their screen. It
      // will simply be back tomorrow. Recorded so a failing dismiss endpoint is not invisible.
      console.warn("Couldn’t record that dismissal", toApiError(e).code);
    }
  }

  return (
    <section aria-label="Platform notices" className="grid gap-3">
      {notices.map((n) => (
        <NoticeCard key={n.id} notice={n} onDismiss={dismiss} />
      ))}
    </section>
  );
}
