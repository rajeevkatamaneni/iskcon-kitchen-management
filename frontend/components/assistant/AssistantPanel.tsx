"use client";

import { useEffect, useRef, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { Button } from "@/components/ds/Button";
import { canUseAssistant } from "./access";
import { Composer, Messages } from "./AssistantParts";
import { useAssistantConversation } from "./useAssistantConversation";

/**
 * The Kitchen Assistant: an "Ask the assistant" button at the foot of every page, opening a panel.
 *
 * <p>Option B of the mockup (docs/work/mocks/dev-assistant.page.tsx), chosen by Rajeev on 2026-10-06
 * over a page of its own. From 1024px up the panel sits on the right edge, 448px wide, over the page
 * and without a scrim, so the page beside it can still be read and used — the whole point of B is
 * asking about the screen you are on. Below 1024 it takes the whole screen: a 448px panel on a 375
 * phone would leave nothing beside it worth keeping.
 *
 * <p><strong>Mounted once, in the root layout.</strong> Every screen renders its own Sidebar, so a
 * panel mounted by a screen would be thrown away on every navigation and the conversation with it.
 * In the layout it survives moving between pages, which the brief asked for.
 *
 * <p>Shown only to someone who holds `USE_KITCHEN_ASSISTANT` (see `access.ts`). The api refuses
 * everyone else regardless.
 */
export function AssistantLauncher() {
  const { status, appUser, getToken } = useAuth();
  if (status !== "signed-in" || !appUser || !canUseAssistant(appUser.role)) return null;
  // Keyed by person and temple: switching temple, or someone else signing in on this browser, starts
  // a clean panel instead of showing the last conversation to a temple it does not belong to.
  const key = `kms.assistant.${appUser.userId}.${appUser.tenantId ?? "none"}`;
  return <AssistantDock key={key} storageKey={key} getToken={getToken} />;
}

function AssistantDock({ storageKey, getToken }: { storageKey: string; getToken: () => Promise<string | undefined> }) {
  const convo = useAssistantConversation({ storageKey, getToken });
  const [open, setOpen] = useState(false);
  // On the wrapper: Button is a plain function component and takes no ref.
  const launcher = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLTextAreaElement>(null);
  const scroller = useRef<HTMLDivElement>(null);
  const wasOpen = useRef(false);
  const { restore } = convo;

  // On open: bring back the conversation from before a reload (once), and put the cursor in the box.
  // On close: give focus back to the button that opened it, so a keyboard user is where they were.
  useEffect(() => {
    if (open) {
      void restore();
      input.current?.focus();
    } else if (wasOpen.current) {
      launcher.current?.querySelector("button")?.focus();
    }
    wasOpen.current = open;
  }, [open, restore]);

  // Keeps the newest entry in view as the reply streams in.
  useEffect(() => {
    const el = scroller.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [convo.entries, open]);

  return (
    <>
      {!open && (
        <div ref={launcher} className="fixed bottom-4 right-4 z-30 sm:bottom-8 sm:right-8">
          <Button
            variant="ghost"
            icon="message-chatbot"
            onClick={() => setOpen(true)}
            className="shadow-overlay"
          >
            Ask the assistant
          </Button>
        </div>
      )}

      {open && (
        <aside
          aria-label="Assistant"
          onKeyDown={(e) => {
            if (e.key === "Escape") {
              e.stopPropagation();
              setOpen(false);
            }
          }}
          className="card fixed inset-0 z-40 flex flex-col !rounded-none lg:inset-y-0 lg:left-auto lg:right-0 lg:w-full lg:max-w-md lg:shadow-overlay"
        >
          <header className="flex min-h-14 items-center justify-between gap-3 border-b border-hairline px-4 py-2">
            <h2 className="text-lg font-medium text-ink">Assistant</h2>
            <div className="flex gap-2">
              <Button
                variant="ghost"
                onClick={convo.clear}
                disabled={convo.entries.length === 0}
                aria-label="Start a new conversation"
              >
                New
              </Button>
              <Button variant="ghost" onClick={() => setOpen(false)}>
                Close
              </Button>
            </div>
          </header>
          <div
            ref={scroller}
            data-testid="assistant-messages"
            role="log"
            aria-live="polite"
            // Busy while an answer streams, so a screen reader reads the finished answer once
            // instead of every few words as they arrive.
            aria-busy={convo.busy}
            className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 py-4"
          >
            {/* Anchored to the foot, next to the box, the way every chat reads. */}
            <div className="flex min-h-full flex-col justify-end">
              <Messages convo={convo} />
            </div>
          </div>
          <div className="border-t border-hairline p-4">
            <Composer convo={convo} inputRef={input} />
          </div>
        </aside>
      )}
    </>
  );
}
