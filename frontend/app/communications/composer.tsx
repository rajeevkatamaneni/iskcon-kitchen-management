"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import {
  api,
  toApiError,
  type ApiError,
  type CommunicationCategory,
  type CommunicationChannel,
  type CommunicationPreview,
  type CommunicationView,
} from "@/lib/api";

/**
 * Writing to the temple's community (E8-S2, E8-S3), on a screen of its own.
 *
 * <p>The screen is the order of the work: write it, look at it, send it to yourself, and only then
 * send it to four hundred people. Each step is a separate button because each is a separate
 * decision, and the last one is not undoable. All four sit together in the header, which is the one
 * place this screen commits from.
 *
 * <p>Two things this screen has to be honest about, because both are limits nobody would guess:
 * WhatsApp cannot carry a letter — Meta delivers only templates it has already approved — so choosing
 * that channel visibly changes what is being written, before six hundred words have been typed into a
 * box that will not send them. And the letter is sanitised on save, so a paste from Google Docs
 * arrives as its words and structure rather than its styling; the composer says so rather than
 * letting somebody discover it in the preview.
 */

const FIELD = "min-h-touch rounded border border-hairline bg-canvas px-3";

export function Composer({ existing }: { existing: CommunicationView | null }) {
  const { getToken } = useAuth();
  const router = useRouter();
  const categories = useAuthedQuery(
    useCallback((t: string | undefined) => api.communicationCategories(t), [])
  );

  const [id, setId] = useState<string | null>(existing?.id ?? null);
  const [category, setCategory] = useState<CommunicationCategory>(existing?.category ?? "NEWSLETTER");
  const [channel, setChannel] = useState<CommunicationChannel>(existing?.channel ?? "EMAIL");
  const [subject, setSubject] = useState(existing?.subject ?? "");
  const [summary, setSummary] = useState(existing?.whatsappSummary ?? "");
  const [preview, setPreview] = useState<CommunicationPreview | null>(null);
  const [narrow, setNarrow] = useState(false);
  const [confirming, setConfirming] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  // Uncontrolled on purpose: a contenteditable driven by React state fights the browser's own
  // caret, and every keystroke would jump the cursor to the end of the letter.
  const body = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (body.current && existing?.bodyHtml) {
      body.current.innerHTML = existing.bodyHtml;
    }
  }, [existing]);

  const options = categories.data ?? [];

  async function run<T>(work: (t: string | undefined) => Promise<T>, failure: string): Promise<T | null> {
    setBusy(true);
    setError(null);
    try {
      return await work(await getToken());
    } catch (e) {
      setError(toApiError(e, failure));
      return null;
    } finally {
      setBusy(false);
    }
  }

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
      const ok = await run((t) => api.updateCommunication(id, current, t), "We couldn’t save that.");
      return ok === null ? null : id;
    }
    const created = await run((t) => api.createCommunication(current, t), "We couldn’t save that.");
    if (created) setId(created.id);
    return created?.id ?? null;
  }

  async function saveAndPreview() {
    const savedId = await save();
    if (!savedId) return;
    const result = await run((t) => api.previewCommunication(savedId, t), "We couldn’t build a preview.");
    if (result) setPreview(result);
  }

  async function sendTest() {
    const savedId = await save();
    if (!savedId) return;
    const ok = await run((t) => api.testCommunication(savedId, t), "We couldn’t send you a copy.");
    if (ok !== null) setNotice("A copy is on its way to your own address.");
  }

  async function askToSend() {
    const savedId = await save();
    if (!savedId) return;
    const result = await run((t) => api.communicationAudience(savedId, t), "We couldn’t count the audience.");
    if (result) setConfirming(result.count);
  }

  async function reallySend() {
    if (!id) return;
    const result = await run((t) => api.sendCommunication(id, t), "We couldn’t send that.");
    setConfirming(null);
    if (result) {
      router.push(
        `/communications?sent=${encodeURIComponent(subject.trim())}&audience=${result.audience}`
      );
    }
  }

  return (
    <FocusScreen
      task={existing ? "Edit a message" : "Write a message"}
      who="To the devotees of this temple"
      activeHref="/communications"
      actions={
        <>
          <ButtonLink href="/communications" variant="secondary">
            Cancel
          </ButtonLink>
          <Button variant="secondary" onClick={sendTest} disabled={busy || !subject.trim()}>
            Send myself a copy
          </Button>
          <Button variant="secondary" onClick={saveAndPreview} disabled={busy}>
            Save and preview
          </Button>
          <Button onClick={askToSend} disabled={busy || !subject.trim()}>
            Send to everyone…
          </Button>
        </>
      }
    >
      {error && <ErrorNotice error={error} />}
      {notice && <InlineNotice tone="success" autoDismiss>{notice}</InlineNotice>}

      <form className="grid gap-4" aria-label="Write a communication" onSubmit={(e) => e.preventDefault()}>
        <div className="grid gap-4 sm:grid-cols-2">
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">What kind of message is this?</span>
            <select
              value={category}
              onChange={(e) => setCategory(e.target.value as CommunicationCategory)}
              className={FIELD}
            >
              {options.map((c) => (
                <option key={c.value} value={c.value}>
                  {c.label}
                </option>
              ))}
            </select>
            <span className="pl-field-inset text-sm text-ink-secondary">
              {options.find((c) => c.value === category)?.description ??
                "This is what devotees choose to receive or decline."}
            </span>
          </label>

          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">How should it go out?</span>
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
          <InlineNotice tone="warning" title="WhatsApp can’t carry a letter">
            Meta only delivers messages matching a template it has already approved. Devotees get
            your temple’s name, the subject, the line below, and a link. Write the letter anyway.
          </InlineNotice>
        )}

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Subject</span>
          <input value={subject} onChange={(e) => setSubject(e.target.value)} required className={FIELD} />
        </label>

        {channel === "WHATSAPP" && (
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">The one line WhatsApp carries</span>
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
          <span className="pl-field-inset font-medium text-ink">The message</span>
          <Toolbar />
          <div
            ref={body}
            contentEditable
            suppressContentEditableWarning
            role="textbox"
            aria-multiline="true"
            aria-label="The message"
            className="min-h-64 rounded border border-hairline bg-canvas px-4 py-3 text-base leading-relaxed text-ink outline-none focus:border-accent-border"
          />
          <span className="pl-field-inset text-sm text-ink-secondary">
            Pasted fonts and colours are dropped.
          </span>
        </div>
      </form>

      {confirming !== null && (
        <div className="rounded-lg bg-raised px-6 py-5">
          <InlineNotice
            tone="warning"
            title={`This will reach ${confirming} devotee${confirming === 1 ? "" : "s"}`}
          >
            Everyone who has agreed to be contacted and has not turned off{" "}
            <strong>{options.find((c) => c.value === category)?.label ?? "this kind"}</strong>. It
            cannot be unsent.
          </InlineNotice>
          <div className="mt-4 flex gap-3">
            <Button variant="secondary" onClick={() => setConfirming(null)}>
              Not yet
            </Button>
            <Button onClick={reallySend} disabled={busy}>
              Send it
            </Button>
          </div>
        </div>
      )}

      {preview && (
        <div className="rounded-lg bg-raised px-6 py-5">
          <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
            <h2 className="text-lg">How it will arrive</h2>
            <Button variant="secondary" onClick={() => setNarrow((n) => !n)}>
              {narrow ? "Show at full width" : "Show at phone width"}
            </Button>
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
            className={`h-96 w-full rounded border border-hairline bg-white transition-all ${narrow ? "max-w-md" : ""}`}
          />

          <h3 className="mt-6 text-sm font-medium text-ink">And on WhatsApp</h3>
          <p className="mt-1 text-sm text-ink-secondary">All WhatsApp can carry.</p>
          <p className="mt-2 max-w-md whitespace-pre-wrap rounded-lg rounded-bl-none bg-success-bg px-4 py-3 text-sm text-ink">
            {preview.whatsappText}
          </p>
        </div>
      )}
    </FocusScreen>
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
