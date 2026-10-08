"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  ApiError,
  confirmAssistantAction,
  createAssistantConversation,
  getAssistantConversation,
  rejectAssistantAction,
  streamAssistantMessage,
  toApiError,
  type AssistantConversation,
  type AssistantEvent,
  type AssistantProposal,
} from "@/lib/api";

/**
 * One conversation with the Kitchen Assistant, held by the panel in the app's root layout.
 *
 * <p>The panel lives in the root layout rather than on a page, so this state outlives navigation:
 * open the assistant on Today, go to Inventory, open it again, and the conversation is where it was
 * (the brief for E10-T13). A reload loses React state, so the conversation's id is also kept in
 * sessionStorage and read back from the server on the next open — the server has the words and
 * the drafts; the tool lines and error boxes were never saved, so a reloaded conversation shows
 * questions, answers and draft cards (each with its status as it stands now). "New" forgets the id;
 * the next message starts another conversation.
 */

/** A refusal shown inside a draft card: the server's words, its next step and its permanent code. */
export type DraftRefusal = { code: string; message: string; action: string };

/** One thing on screen in the conversation. */
export type Entry =
  | { id: string; kind: "user"; text: string }
  | { id: string; kind: "assistant"; text: string }
  /** A lookup. `running` while the answer is still coming; `stopped` if it ended in an error or Stop. */
  | { id: string; kind: "tool"; tool: string; summary: string; state: "running" | "finished" | "stopped" }
  /** A draft card. `refusal` is the last Confirm or Reject the server turned down, until the next press. */
  | { id: string; kind: "draft"; proposal: AssistantProposal; refusal?: DraftRefusal | null }
  | { id: string; kind: "error"; code: string; message: string; action: string; retry: boolean };

/** The two daily limits. Either one means no more questions today, so the box is closed. */
const LIMIT_CODES = new Set(["KMS-400192", "KMS-400193"]);

/**
 * Failures worth a "Try again": the assistant was unavailable or took too long (KMS-500008,
 * KMS-500009), or the request never reached the server. Anything else — a limit, a conversation that
 * is not yours — would fail the same way a second time.
 */
const RETRY_CODES = new Set(["KMS-500008", "KMS-500009", "KMS-0000"]);

/** The three refusals of Confirm and Reject (E10-S4 contract). */
export const DRAFT_EXPIRED = "KMS-400194";
export const DRAFT_CHANGED = "KMS-400195";
export const DRAFT_ALREADY_HANDLED = "KMS-400196";

let nextId = 0;
const newId = () => `a${++nextId}`;

function storageGet(key: string): string | null {
  try {
    return window.sessionStorage.getItem(key);
  } catch {
    return null;
  }
}

function storageSet(key: string, value: string | null) {
  try {
    if (value) window.sessionStorage.setItem(key, value);
    else window.sessionStorage.removeItem(key);
  } catch {
    // Private windows can refuse storage. The conversation still works; it just won't survive a reload.
  }
}

/** A saved conversation as entries: each question, each reply, and the reply's draft cards after its words. */
function fromSaved(convo: AssistantConversation): Entry[] {
  const out: Entry[] = [];
  for (const m of convo.messages) {
    if (m.role === "USER") {
      out.push({ id: m.id, kind: "user", text: m.content });
      continue;
    }
    // A reply that only drafted something may have no words of its own; no empty block for it.
    if (m.content) out.push({ id: m.id, kind: "assistant", text: m.content });
    for (const p of m.proposals ?? []) out.push({ id: `${m.id}:${p.actionId}`, kind: "draft", proposal: p });
  }
  return out;
}

/** The draft as the server now has it, from a saved conversation, or null if it is not there. */
function findProposal(convo: AssistantConversation, actionId: string): AssistantProposal | null {
  for (const m of convo.messages) {
    const found = m.proposals?.find((p) => p.actionId === actionId);
    if (found) return found;
  }
  return null;
}

/** One draft card changed, the rest left as they are. */
function patchDraft(
  entries: Entry[],
  entryId: string,
  change: (e: Extract<Entry, { kind: "draft" }>) => Extract<Entry, { kind: "draft" }>
): Entry[] {
  return entries.map((e) => (e.id === entryId && e.kind === "draft" ? change(e) : e));
}

/** Every lookup still running is over: finished when the answer moved on, stopped when it failed. */
function settleTools(entries: Entry[], to: "finished" | "stopped"): Entry[] {
  return entries.some((e) => e.kind === "tool" && e.state === "running")
    ? entries.map((e) => (e.kind === "tool" && e.state === "running" ? { ...e, state: to } : e))
    : entries;
}

export function useAssistantConversation({
  storageKey,
  getToken,
}: {
  /** Per person and temple, so a switch of temple or a different person on this browser starts clean. */
  storageKey: string;
  getToken: () => Promise<string | undefined>;
}) {
  const [entries, setEntries] = useState<Entry[]>([]);
  const [busy, setBusy] = useState(false);
  /** A daily limit was reached: nothing more can be asked today, so the box is closed. */
  const [blocked, setBlocked] = useState(false);
  const conversationId = useRef<string | null>(null);
  const abort = useRef<AbortController | null>(null);
  const restored = useRef(false);

  /** Reads the last conversation back after a reload. Once per mount, and only into an empty panel. */
  const restore = useCallback(async () => {
    if (restored.current) return;
    restored.current = true;
    const id = storageGet(storageKey);
    if (!id) return;
    try {
      const convo = await getAssistantConversation(id, await getToken());
      conversationId.current = convo.id;
      setEntries((current) => (current.length > 0 ? current : fromSaved(convo)));
    } catch {
      // Gone, or not this person's: start fresh rather than show an error for something they didn't ask.
      storageSet(storageKey, null);
    }
  }, [storageKey, getToken]);

  useEffect(() => () => abort.current?.abort(), []);

  const ask = useCallback(
    async (text: string) => {
      if (busy || blocked) return;
      setBusy(true);
      const questionId = newId();
      setEntries((e) => [...e, { id: questionId, kind: "user", text }]);
      const controller = new AbortController();
      abort.current = controller;

      // The reply to this question is one assistant entry, grown as its pieces arrive. A lookup in
      // the middle ("Let me check." → Checking stock… → the answer) starts a second one below it, so
      // the text reads in the order it was written.
      let replyId: string | null = null;
      const onEvent = (event: AssistantEvent) => {
        switch (event.type) {
          case "text": {
            // The id is chosen out here, not inside the updater, so the updater stays pure: React may
            // run it twice, and a second run must append to the same entry, not start another.
            const target = replyId ?? (replyId = newId());
            setEntries((e) => {
              const settled = settleTools(e, "finished");
              const at = settled.findIndex((x) => x.id === target);
              if (at === -1) return [...settled, { id: target, kind: "assistant", text: event.text }];
              const current = settled[at];
              if (current.kind !== "assistant") return settled;
              const next = settled.slice();
              next[at] = { ...current, text: current.text + event.text };
              return next;
            });
            break;
          }
          case "tool_call": {
            replyId = null;
            const toolId = newId();
            setEntries((e) => [
              ...settleTools(e, "finished"),
              { id: toolId, kind: "tool", tool: event.tool, summary: event.summary, state: "running" },
            ]);
            break;
          }
          case "proposal": {
            replyId = null;
            const draftId = newId();
            setEntries((e) => [...settleTools(e, "finished"), { id: draftId, kind: "draft", proposal: event.proposal }]);
            break;
          }
          case "language":
            // Reserved for the reply-language work (another builder's); nothing to show for it here.
            break;
          case "done":
            setEntries((e) => settleTools(e, "finished"));
            break;
          case "error": {
            const errorId = newId();
            setEntries((e) => [
              ...settleTools(e, "stopped"),
              {
                id: errorId,
                kind: "error",
                code: event.code,
                message: event.message,
                action: event.action,
                retry: RETRY_CODES.has(event.code),
              },
            ]);
            break;
          }
        }
      };

      try {
        const token = await getToken();
        if (!conversationId.current) {
          const created = await createAssistantConversation(token);
          conversationId.current = created.id;
          storageSet(storageKey, created.id);
        }
        await streamAssistantMessage(conversationId.current, text, onEvent, { token, signal: controller.signal });
      } catch (caught) {
        if (controller.signal.aborted) {
          // Stop. What arrived stays on screen; the server saves no reply for a run the browser left.
          setEntries((e) => settleTools(e, "stopped"));
        } else {
          const error: ApiError = toApiError(caught, "We couldn't reach the assistant.");
          if (LIMIT_CODES.has(error.code)) setBlocked(true);
          const errorId = newId();
          setEntries((e) => [
            ...settleTools(e, "stopped"),
            {
              id: errorId,
              kind: "error",
              code: error.code,
              message: error.message,
              action: error.action,
              retry: RETRY_CODES.has(error.code),
            },
          ]);
        }
      } finally {
        if (abort.current === controller) abort.current = null;
        setBusy(false);
      }
    },
    [busy, blocked, getToken, storageKey]
  );

  /**
   * Confirm or Reject on a draft card. Resolves once the card shows the outcome, which is what the
   * card waits on to clear its busy state.
   *
   * <p>On success the card re-renders from the draft the server sends back, never from what the press
   * was meant to do. On a refusal the card keeps the server's words, next step and code. Two refusals
   * also change the card: KMS-400194 means the server has stopped accepting this draft, so it shows
   * Expired at once rather than waiting for its clock; KMS-400196 means it was already confirmed or
   * rejected (another tab, or a press whose answer was lost), so the card is read back from the
   * conversation to show which, with the link if it was confirmed.
   */
  const decide = useCallback(
    async (entryId: string, actionId: string, choice: "confirm" | "reject") => {
      setEntries((e) => patchDraft(e, entryId, (d) => ({ ...d, refusal: null })));
      let token: string | undefined;
      try {
        token = await getToken();
        const send = choice === "confirm" ? confirmAssistantAction : rejectAssistantAction;
        const proposal = await send(actionId, token);
        setEntries((e) => patchDraft(e, entryId, (d) => ({ ...d, proposal, refusal: null })));
      } catch (caught) {
        const error = toApiError(caught, "We couldn't reach the server.");
        const refusal: DraftRefusal = { code: error.code, message: error.message, action: error.action };
        let fresh: AssistantProposal | null = null;
        if (error.code === DRAFT_ALREADY_HANDLED && conversationId.current) {
          try {
            fresh = findProposal(await getAssistantConversation(conversationId.current, token), actionId);
          } catch {
            // The refusal is still shown; the card just keeps its last known status.
          }
        }
        setEntries((e) =>
          patchDraft(e, entryId, (d) => ({
            ...d,
            refusal,
            proposal: fresh ?? (error.code === DRAFT_EXPIRED ? { ...d.proposal, status: "EXPIRED" } : d.proposal),
          }))
        );
      }
    },
    [getToken]
  );

  const stop = useCallback(() => abort.current?.abort(), []);

  /** "New": forget this conversation. The next message starts another one. */
  const clear = useCallback(() => {
    abort.current?.abort();
    conversationId.current = null;
    storageSet(storageKey, null);
    setEntries([]);
  }, [storageKey]);

  /** "Try again" on an error: ask the last question again. */
  const retry = useCallback(() => {
    const last = [...entries].reverse().find((e) => e.kind === "user");
    if (last && last.kind === "user") void ask(last.text);
  }, [entries, ask]);

  return { entries, busy, blocked, ask, stop, clear, retry, restore, decide };
}

export type AssistantConversationState = ReturnType<typeof useAssistantConversation>;
