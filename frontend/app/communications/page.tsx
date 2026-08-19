"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { RequireRole } from "@/components/RequireRole";
import { Loading } from "@/components/Loading";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import {
  api,
  toApiError,
  type ApiError,
  type CommunicationCategory,
  type CommunicationCategoryOption,
  type CommunicationChannel,
  type CommunicationDelivery,
  type CommunicationPreview,
  type CommunicationView,
} from "@/lib/api";

/**
 * Writing to the temple's community (E8-S2, E8-S3).
 *
 * <p>The screen is the order of the work: write it, look at it, send it to yourself, and only then
 * send it to four hundred people. Each step is a separate button because each is a separate decision,
 * and the last one is not undoable.
 *
 * <p>Two things this screen has to be honest about, because both are limits nobody would guess:
 * WhatsApp cannot carry a letter — Meta delivers only templates it has already approved — so choosing
 * that channel visibly changes what is being written, before six hundred words have been typed into a
 * box that will not send them. And the letter is sanitised on save, so a paste from Google Docs
 * arrives as its words and structure rather than its styling; the composer says so rather than
 * letting somebody discover it in the preview.
 */

export default function CommunicationsPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <CommunicationsView />
    </RequireRole>
  );
}

type Panel =
  | { mode: "list" }
  | { mode: "compose"; existing: CommunicationView | null }
  | { mode: "sent"; communication: CommunicationView };

function CommunicationsView() {
  const { getToken } = useAuth();
  const list = useAuthedQuery(useCallback((t: string | undefined) => api.listCommunications(t), []));
  const categories = useAuthedQuery(
    useCallback((t: string | undefined) => api.communicationCategories(t), [])
  );

  const [panel, setPanel] = useState<Panel>({ mode: "list" });
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const all = list.data ?? [];
  const drafts = all.filter((c) => c.status === "DRAFT");
  const sent = all.filter((c) => c.status === "SENT");

  async function run<T>(work: (t: string | undefined) => Promise<T>, failure: string): Promise<T | null> {
    setBusy(true);
    setActionError(null);
    try {
      const result = await work(await getToken());
      list.reload();
      return result;
    } catch (e) {
      setActionError(toApiError(e, failure));
      return null;
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/communications" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Communications</h1>
              <p className="mt-1 text-ink-secondary">
                Write to the devotees of your temple. Everyone can choose which kinds they receive —
                except reminders and receipts, which always reach them.
              </p>
            </div>
            {panel.mode === "list" && (
              <button
                type="button"
                onClick={() => setPanel({ mode: "compose", existing: null })}
                className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover"
              >
                Write a message
              </button>
            )}
          </header>

          {actionError && (
            <div className="mb-6">
              <ErrorNotice error={actionError} />
            </div>
          )}
          {notice && (
            <div className="mb-6">
              <InlineNotice tone="success">{notice}</InlineNotice>
            </div>
          )}

          {panel.mode === "compose" && (
            <Composer
              key={panel.existing?.id ?? "new"}
              existing={panel.existing}
              categories={categories.data ?? []}
              busy={busy}
              onClose={() => {
                setPanel({ mode: "list" });
                setNotice(null);
              }}
              onNotice={setNotice}
              run={run}
            />
          )}

          {panel.mode === "sent" && (
            <SentDetail
              communication={panel.communication}
              onClose={() => setPanel({ mode: "list" })}
            />
          )}

          {panel.mode === "list" &&
            (list.loading ? (
              <Loading label="Loading messages…" />
            ) : list.error ? (
              <ErrorNotice error={list.error} />
            ) : all.length === 0 ? (
              <div className="rounded-lg bg-raised px-6 py-14 text-center">
                <p className="text-lg">Nothing written yet</p>
                <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                  Write a newsletter, announce a festival, or tell everyone the kitchen is closed on
                  Tuesday. You can see exactly how it will look, and send yourself a copy, before
                  anybody else gets it.
                </p>
              </div>
            ) : (
              <div className="grid gap-8">
                <CommunicationTable
                  heading="Drafts"
                  rows={drafts}
                  empty="Nothing in progress."
                  onOpen={(c) => setPanel({ mode: "compose", existing: c })}
                />
                <CommunicationTable
                  heading="Sent"
                  rows={sent}
                  empty="Nothing has gone out yet."
                  onOpen={(c) => setPanel({ mode: "sent", communication: c })}
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
  onOpen,
}: {
  heading: string;
  rows: CommunicationView[];
  empty: string;
  onOpen: (c: CommunicationView) => void;
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
                <tr key={c.id} className="border-t border-hairline align-middle">
                  <td className="px-5 py-3">
                    <button
                      type="button"
                      onClick={() => onOpen(c)}
                      className="text-left font-medium text-accent-text hover:underline"
                    >
                      {c.subject}
                    </button>
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

function Composer({
  existing,
  categories,
  busy,
  onClose,
  onNotice,
  run,
}: {
  existing: CommunicationView | null;
  categories: CommunicationCategoryOption[];
  busy: boolean;
  onClose: () => void;
  onNotice: (message: string | null) => void;
  run: <T>(work: (t: string | undefined) => Promise<T>, failure: string) => Promise<T | null>;
}) {
  const [id, setId] = useState<string | null>(existing?.id ?? null);
  const [category, setCategory] = useState<CommunicationCategory>(existing?.category ?? "NEWSLETTER");
  const [channel, setChannel] = useState<CommunicationChannel>(existing?.channel ?? "EMAIL");
  const [subject, setSubject] = useState(existing?.subject ?? "");
  const [summary, setSummary] = useState(existing?.whatsappSummary ?? "");
  const [preview, setPreview] = useState<CommunicationPreview | null>(null);
  const [narrow, setNarrow] = useState(false);
  const [confirming, setConfirming] = useState<number | null>(null);

  // Uncontrolled on purpose: a contenteditable driven by React state fights the browser's own
  // caret, and every keystroke would jump the cursor to the end of the letter.
  const body = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (body.current && existing?.bodyHtml) {
      body.current.innerHTML = existing.bodyHtml;
    }
  }, [existing]);

  function input() {
    return {
      category,
      channel,
      subject: subject.trim(),
      bodyHtml: body.current?.innerHTML ?? "",
      whatsappSummary: summary.trim() || null,
    };
  }

  async function save(): Promise<string | null> {
    const current = input();
    if (id) {
      const ok = await run((t) => api.updateCommunication(id, current, t), "We couldn't save that.");
      return ok === null ? null : id;
    }
    const created = await run((t) => api.createCommunication(current, t), "We couldn't save that.");
    if (created) setId(created.id);
    return created?.id ?? null;
  }

  async function saveAndPreview() {
    const savedId = await save();
    if (!savedId) return;
    const result = await run((t) => api.previewCommunication(savedId, t), "We couldn't build a preview.");
    if (result) setPreview(result);
  }

  async function sendTest() {
    const savedId = await save();
    if (!savedId) return;
    const ok = await run((t) => api.testCommunication(savedId, t), "We couldn't send you a copy.");
    if (ok !== null) onNotice("A copy is on its way to your own address.");
  }

  async function askToSend() {
    const savedId = await save();
    if (!savedId) return;
    const result = await run((t) => api.communicationAudience(savedId, t), "We couldn't count the audience.");
    if (result) setConfirming(result.count);
  }

  async function reallySend() {
    if (!id) return;
    const result = await run((t) => api.sendCommunication(id, t), "We couldn't send that.");
    setConfirming(null);
    if (result) {
      onNotice(`Sent to ${result.audience} devotee${result.audience === 1 ? "" : "s"}.`);
      onClose();
    }
  }

  return (
    <section className="mb-8 grid gap-6" aria-labelledby="composer-heading">
      <div className="rounded-lg bg-raised px-6 py-5">
        <h2 id="composer-heading" className="text-lg">
          {existing ? "Edit message" : "Write a message"}
        </h2>

        <form className="mt-4 grid gap-4" aria-label="Write a communication" onSubmit={(e) => e.preventDefault()}>
          <div className="grid gap-4 sm:grid-cols-2">
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              What kind of message is this?
              <select
                value={category}
                onChange={(e) => setCategory(e.target.value as CommunicationCategory)}
                className={FIELD}
              >
                {categories.map((c) => (
                  <option key={c.value} value={c.value}>
                    {c.label}
                  </option>
                ))}
              </select>
              <span className="text-xs text-ink-muted">
                {categories.find((c) => c.value === category)?.description ??
                  "This is what devotees choose to receive or decline."}
              </span>
            </label>

            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              How should it go out?
              <select
                value={channel}
                onChange={(e) => setChannel(e.target.value as CommunicationChannel)}
                className={FIELD}
              >
                <option value="EMAIL">Email</option>
                <option value="WHATSAPP">WhatsApp</option>
              </select>
            </label>
          </div>

          {channel === "WHATSAPP" && (
            <InlineNotice tone="warning" title="WhatsApp can't carry a letter">
              WhatsApp only delivers messages that match a template Meta has already approved, so a
              newsletter can&rsquo;t go out on it. What devotees receive is your temple&rsquo;s name,
              the subject, the one line below, and a link to the full message on the web. Write the
              letter anyway — the link opens it.
            </InlineNotice>
          )}

          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            Subject
            <input
              value={subject}
              onChange={(e) => setSubject(e.target.value)}
              required
              className={FIELD}
            />
          </label>

          {channel === "WHATSAPP" && (
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              The one line WhatsApp carries
              <input
                value={summary}
                onChange={(e) => setSummary(e.target.value)}
                maxLength={300}
                placeholder="Kitchen seva starts at 4am and everyone is welcome."
                className={FIELD}
              />
            </label>
          )}

          <div className="grid gap-1 text-sm text-ink-secondary">
            <span>The message</span>
            <Toolbar />
            <div
              ref={body}
              contentEditable
              suppressContentEditableWarning
              role="textbox"
              aria-multiline="true"
              aria-label="The message"
              className="min-h-[16rem] rounded border border-hairline bg-canvas px-4 py-3 text-base leading-relaxed text-ink outline-none focus:border-accent-border"
            />
            <span className="text-xs text-ink-muted">
              Type here, or paste a newsletter from Word or Google Docs. Headings, bold, lists and
              links come across; the other program&rsquo;s fonts and colours are dropped, because they
              describe a page that doesn&rsquo;t exist inside an email.
            </span>
          </div>

          <div className="flex flex-wrap items-center gap-3">
            <button type="button" onClick={saveAndPreview} disabled={busy} className={PRIMARY}>
              Save and preview
            </button>
            <button type="button" onClick={sendTest} disabled={busy || !subject.trim()} className={SECONDARY}>
              Send myself a copy
            </button>
            <button
              type="button"
              onClick={askToSend}
              disabled={busy || !subject.trim()}
              className="min-h-touch rounded border border-accent-border bg-accent-bg px-4 text-accent-text hover:brightness-95 disabled:opacity-60"
            >
              Send to everyone…
            </button>
            <button type="button" onClick={onClose} className={SECONDARY}>
              Close
            </button>
          </div>
        </form>
      </div>

      {confirming !== null && (
        <div className="rounded-lg bg-raised px-6 py-5">
          <InlineNotice tone="warning" title={`This will reach ${confirming} devotee${confirming === 1 ? "" : "s"}`}>
            Everyone who has agreed to be contacted and has not turned off{" "}
            <strong>{categories.find((c) => c.value === category)?.label ?? "this kind"}</strong>. It
            cannot be unsent.
          </InlineNotice>
          <div className="mt-4 flex gap-3">
            <button type="button" onClick={reallySend} disabled={busy} className={PRIMARY}>
              Send it
            </button>
            <button type="button" onClick={() => setConfirming(null)} className={SECONDARY}>
              Not yet
            </button>
          </div>
        </div>
      )}

      {preview && (
        <div className="rounded-lg bg-raised px-6 py-5">
          <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
            <h3 className="text-lg">How it will arrive</h3>
            <button type="button" onClick={() => setNarrow((n) => !n)} className={SECONDARY}>
              {narrow ? "Show at full width" : "Show at phone width"}
            </button>
          </div>

          <p className="mb-2 text-sm text-ink-secondary">
            Subject: <span className="text-ink">{preview.subject}</span>
          </p>

          <iframe
            title="Email preview"
            // Sandboxed with nothing granted: the letter is already sanitised, and a preview that
            // could run anything would be a hole opened by the screen meant to inspect for holes.
            sandbox=""
            srcDoc={preview.emailHtml}
            className={`h-[32rem] w-full rounded border border-hairline bg-white transition-all ${narrow ? "max-w-[420px]" : ""}`}
          />

          <h4 className="mt-6 text-sm font-medium text-ink">And on WhatsApp</h4>
          <p className="mt-1 text-xs text-ink-muted">
            All WhatsApp can carry — the rest lives behind the link.
          </p>
          <p className="mt-2 max-w-md whitespace-pre-wrap rounded-lg rounded-bl-none bg-success-bg px-4 py-3 text-sm text-ink">
            {preview.whatsappText}
          </p>
        </div>
      )}
    </section>
  );
}

/**
 * The formatting a temple letter actually needs, and nothing else.
 *
 * <p>{@code execCommand} is formally deprecated and still the only thing every browser implements
 * for this. The alternative is a rich-text engine, which is a large dependency for bold, a list and
 * a link — and whatever it produced would be sanitised down to the same small vocabulary anyway.
 */
function Toolbar() {
  const apply = (command: string, value?: string) => (event: React.MouseEvent) => {
    event.preventDefault();
    document.execCommand(command, false, value);
  };

  return (
    <div className="flex flex-wrap gap-1 rounded border border-hairline bg-sunken px-2 py-1">
      <ToolButton label="Bold" onMouseDown={apply("bold")}>
        <strong>B</strong>
      </ToolButton>
      <ToolButton label="Italic" onMouseDown={apply("italic")}>
        <em>I</em>
      </ToolButton>
      <ToolButton label="Heading" onMouseDown={apply("formatBlock", "<h2>")}>
        H
      </ToolButton>
      <ToolButton label="Bulleted list" onMouseDown={apply("insertUnorderedList")}>
        •
      </ToolButton>
      <ToolButton label="Numbered list" onMouseDown={apply("insertOrderedList")}>
        1.
      </ToolButton>
      <ToolButton
        label="Link"
        onMouseDown={(event) => {
          event.preventDefault();
          const url = window.prompt("Link to where?");
          if (url) document.execCommand("createLink", false, url);
        }}
      >
        Link
      </ToolButton>
      <ToolButton label="Plain text" onMouseDown={apply("removeFormat")}>
        Clear
      </ToolButton>
    </div>
  );
}

function ToolButton({
  label,
  onMouseDown,
  children,
}: {
  label: string;
  onMouseDown: (event: React.MouseEvent) => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      // Mouse-down, not click: a click fires after the caret has already left the letter, and the
      // formatting would land on nothing.
      onMouseDown={onMouseDown}
      className="min-w-9 rounded px-2 py-1 text-sm text-ink-secondary hover:bg-raised hover:text-ink"
    >
      {children}
    </button>
  );
}

// ---------------------------------------------------------------------------

function SentDetail({
  communication,
  onClose,
}: {
  communication: CommunicationView;
  onClose: () => void;
}) {
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
          <button type="button" onClick={onClose} className={SECONDARY}>
            Back
          </button>
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
                  <tr key={`${d.recipientName}-${i}`} className="border-t border-hairline">
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

const FIELD = "min-h-touch rounded border border-hairline bg-canvas px-3";
const PRIMARY =
  "min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60";
const SECONDARY = "min-h-touch rounded border border-hairline px-4 hover:bg-sunken disabled:opacity-60";
