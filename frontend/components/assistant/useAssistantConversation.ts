"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  ApiError,
  createAssistantConversation,
  getAssistantConversation,
  streamAssistantMessage,
  toApiError,
  type AssistantEvent,
  type AssistantProposal,
} from "@/lib/api";

/**
 * One conversation with the Kitchen Assistant, held by the panel in the app's root layout.
 *
 * <p>The panel lives in the root layout rather than on a page, so this state outlives navigation:
 * open the assistant on Today, go to Inventory, open it again, and the conversation is where it was
 * (the brief for E10-T13). A reload loses React state, so the conversation's id is also kept in
 * sessionStorage and read back from the server on the next open — the server has the words; the
 * tool lines and error boxes were never saved, so a reloaded conversation shows questions and
 * answers only. "New" forgets the id; the next message starts another conversation.
 */

/** One thing on screen in the conversation. */
export type Entry =
  | { id: string; kind: "user"; text: string }
  | { id: string; kind: "assistant"; text: string }
  /** A lookup. `running` while the answer is still coming; `stopped` if it ended in an error or Stop. */
  | { id: string; kind: "tool"; tool: string; summary: string; state: "running" | "finished" | "stopped" }
  | { id: string; kind: "draft"; proposal: AssistantProposal }
  | { id: string; kind: "error"; code: string; message: string; action: string; retry: boolean };

/** The two daily limits. Either one means no more questions today, so the box is closed. */
const LIMIT_CODES = new Set(["KMS-400192", "KMS-400193"]);

/**
 * Failures worth a "Try again": the assistant was unavailable or took too long (KMS-500008,
 * KMS-500009), or the request never reached the server. Anything else — a limit, a conversation that
 * is not yours — would fail the same way a second time.
 */
const RETRY_CODES = new Set(["KMS-500008", "KMS-500009", "KMS-0000"]);

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
      setEntries((current) =>
        current.length > 0
          ? current
          : convo.messages.map((m) => ({ id: m.id, kind: m.role === "USER" ? "user" : "assistant", text: m.content }) as Entry)
      );
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

  return { entries, busy, blocked, ask, stop, clear, retry, restore };
}

export type AssistantConversationState = ReturnType<typeof useAssistantConversation>;
