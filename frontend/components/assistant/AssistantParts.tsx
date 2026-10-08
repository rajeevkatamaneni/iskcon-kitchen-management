"use client";

import { useEffect, useId, useRef, useState } from "react";
import Link from "next/link";
import type { AssistantProposal } from "@/lib/api";
import { money, quantity, unitLabel } from "@/lib/format";
import { Button } from "@/components/ds/Button";
import { Form } from "@/components/ds/Form";
import { Badge } from "@/components/ds/Badge";
import { Card } from "@/components/ds/Card";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { InfoHint } from "@/components/ds/InfoHint";
import { CookingLoader } from "@/components/CookingLoader";
import { AssistantMarkdown } from "./AssistantMarkdown";
import { RULED_TABLE, THEAD, TR, TH_PRIMARY, TD_PRIMARY, TH_FIXED, TD_FIXED_NUM } from "@/components/ds/table";
import {
  DRAFT_ALREADY_HANDLED,
  DRAFT_CHANGED,
  DRAFT_EXPIRED,
  type AssistantConversationState,
  type DraftRefusal,
  type Entry,
} from "./useAssistantConversation";

/*
 * The conversation's parts, built to the approved mockup (docs/work/mocks/dev-assistant.page.tsx,
 * option B, chosen by Rajeev 2026-10-06). Each function here is the mock's function of the same name
 * with its sample data replaced by what the api sends; where the real build differs from the mock,
 * the comment at that spot says why.
 */

/**
 * The person's own words: a sunken block on the trailing side. Not the accent, which means "the
 * main thing to press" and nothing else, and not a bubble with a tail — a block in the card corner.
 */
function UserTurn({ text }: { text: string }) {
  return (
    <div className="flex justify-end">
      <p className="ml-12 whitespace-pre-wrap break-words rounded-card bg-sunken px-4 py-2 text-ink">{text}</p>
    </div>
  );
}

/** The assistant's words: full width of the column, no bubble, so a long answer reads as a paragraph
 *  rather than as a speech balloon. Read as Markdown (E10-MD), so a table is a table and bold is bold;
 *  `AssistantMarkdown` says how, and what it refuses to draw. While the answer streams, the cursor
 *  sits on the line under it: the answer is blocks now (paragraphs, a list, a table), and there is no
 *  "end of the text" inside a table to put it at. */
function AssistantTurn({ text, streaming }: { text: string; streaming: boolean }) {
  return (
    <div className="min-w-0 text-ink">
      <AssistantMarkdown text={text} streaming={streaming} />
      {streaming && <span className="mt-1 block h-4 w-2 animate-pulse bg-ink-muted" aria-hidden="true" />}
    </div>
  );
}

/**
 * What each lookup is called on screen. The api sends the tool's own name (`get_inventory`), which
 * is a word for a programmer; the person sees what it is doing, in the mock's words. A tool this
 * build does not know yet still gets a sentence rather than its name.
 */
const TOOL_WORDS: Record<string, { running: string; done: string; stopped: string }> = {
  get_inventory: { running: "Checking stock…", done: "Checked stock", stopped: "Checking stock stopped" },
  list_low_stock: {
    running: "Checking what is running low…",
    done: "Checked what is running low",
    stopped: "Checking what is running low stopped",
  },
};
const ANY_TOOL = { running: "Looking it up…", done: "Looked it up", stopped: "Looking it up stopped" };

/**
 * The values of the lookup's arguments, without their names: `item_name=toor dal` becomes "toor dal",
 * so the finished line reads "Checked stock: toor dal" as in the mock. Exported for its test.
 */
export function toolDetail(summary: string): string {
  if (!summary) return "";
  return summary
    .split(", ")
    .map((part) => {
      const eq = part.indexOf("=");
      return eq === -1 ? part : part.slice(eq + 1);
    })
    .filter(Boolean)
    .join(", ");
}

/**
 * A lookup. While it runs: the app's own cooking pot and "Checking stock…", in muted text. When it
 * is done it stays as a quiet one-line record ("Checked stock: toor dal"), so the person can see what
 * the answer was based on without it competing with the answer.
 */
function ToolLine({ tool, summary, state }: { tool: string; summary: string; state: "running" | "finished" | "stopped" }) {
  const words = TOOL_WORDS[tool] ?? ANY_TOOL;
  const detail = toolDetail(summary);
  const label = state === "running" ? words.running : state === "stopped" ? words.stopped : words.done;
  return (
    <p className="flex items-center gap-2 text-sm text-ink-muted">
      {state === "running" ? (
        <CookingLoader className="h-5 w-5 flex-none" decorative />
      ) : (
        <span className="inline-block h-5 w-5 flex-none" aria-hidden="true" />
      )}
      <span className="min-w-0 break-words">
        {label}
        {state === "finished" && detail ? `: ${detail}` : ""}
      </span>
    </p>
  );
}

function Facts({ items }: { items: { label: string; value: string; hint?: string | null }[] }) {
  // A label over each value, the way a readout sits in a form. They sit side by side at their own
  // widths and wrap only when the next one does not fit, so a short value never forces a long one
  // onto two lines (a four-column grid did exactly that in the mock at 640px).
  return (
    <dl className="flex flex-wrap gap-x-8 gap-y-3">
      {items.map((f) => (
        <div key={f.label} className="grid content-start gap-1">
          <dt className="flex items-center gap-1.5 text-sm text-ink-secondary">
            {f.label}
            {f.hint && <InfoHint text={f.hint} label={f.label} />}
          </dt>
          <dd className="font-medium text-ink">{f.value}</dd>
        </div>
      ))}
    </dl>
  );
}

const STATE_BADGE: Record<AssistantProposal["status"], { tone?: "warning" | "success"; label: string }> = {
  // Waiting for the reader to act: that is what amber means here.
  PROPOSED: { tone: "warning", label: "Not saved yet" },
  // The reader's own action did what they expected: the one place green belongs.
  CONFIRMED: { tone: "success", label: "Saved" },
  REJECTED: { label: "Rejected" },
  EXPIRED: { label: "Expired" },
};

function ItemName({ name, localName }: { name: string; localName: string | null }) {
  return (
    <>
      <span className="block text-ink">{name}</span>
      {localName && (
        // The temple's language. Kannada today; the attribute is left off rather than guessed for
        // another temple's, since a wrong `lang` makes a screen reader mispronounce it.
        <span className="block text-sm text-ink-secondary">{localName}</span>
      )}
    </>
  );
}

/**
 * The draft's table, sized for the 448px panel.
 *
 * <p>The one change from the mock that Rajeev sanctioned. In the mock the purchase-order table had
 * three columns (Item; Quantity with the price under it; Amount). Measured in the real panel at 1280,
 * the card's table is 380px wide and the two number columns took 102 and 98 of it (their headings
 * and the row's 20px end inset set that, not the figures), which left the item 148px of text: three
 * of the four names broke over two lines and every row stood 127px tall.
 *
 * <p>So an order line reads the way the meal draft already does, in two columns: the item, with its
 * Kannada name and then "100 Kg at ₹58/Kg" under it, and the Amount. Quantity and price are read
 * together anyway — the amount is one times the other — and the item gets the width back. The proof
 * has the widths before and after.
 */
function DraftTable({ proposal }: { proposal: AssistantProposal }) {
  const order = proposal.kind === "PURCHASE_ORDER";
  return (
    <table className={RULED_TABLE}>
      {/* Not sticky. Every app table's header sticks to the top of its scroller from 1024 up; in a
          chat that scroller is the conversation, so a draft's header floated over the messages above
          it as they scrolled (found in the mock). `lg:!static` from 1024 up only: below that the
          phone card layout hides the head by positioning it, and a plain `!static` undid that. */}
      <thead className={`${THEAD} lg:!static`}>
        <tr>
          <th className={TH_PRIMARY}>{order ? "Item" : "Dish"}</th>
          <th className={TH_FIXED}>Amount</th>
        </tr>
      </thead>
      <tbody>
        {proposal.lines.map((l, i) => (
          <tr key={`${l.name}-${i}`} className={TR}>
            <td className={TD_PRIMARY}>
              <ItemName name={l.name} localName={l.localName} />
              {order && (
                <span className="block text-sm text-ink-secondary">
                  {quantity(l.quantity, l.unit)}
                  {l.rate != null && ` at ${money(l.rate, "INR")}/${unitLabel(l.unit)}`}
                </span>
              )}
            </td>
            <td className={TD_FIXED_NUM}>
              {order ? money(l.amount, "INR") : quantity(l.quantity, l.unit)}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * The longest a browser timer can wait (about 24.8 days). A draft lasts 24 hours, so this only matters
 * for a clock that is badly wrong; past it the timer fires early, finds the draft still live, and
 * sets itself again.
 */
const LONGEST_TIMER_MS = 2 ** 31 - 1;

/**
 * The status the card shows: the server's, except that a draft still marked PROPOSED shows Expired
 * from the moment its `expiresAt` passes. The server would refuse it then anyway (KMS-400194), and a
 * Confirm button that can only fail is a button somebody presses. Ticks over by itself while the card
 * is on screen.
 */
function useShownStatus(proposal: AssistantProposal): AssistantProposal["status"] {
  const ends = proposal.status === "PROPOSED" ? Date.parse(proposal.expiresAt) : NaN;
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (Number.isNaN(ends) || ends <= now) return;
    const t = setTimeout(() => setNow(Date.now()), Math.min(ends - now + 50, LONGEST_TIMER_MS));
    return () => clearTimeout(t);
  }, [ends, now]);
  return !Number.isNaN(ends) && ends <= now ? "EXPIRED" : proposal.status;
}

/**
 * A refusal of Confirm or Reject, inside the card it belongs to: the server's words, its next step and
 * its permanent code, laid out like the conversation's own error box.
 *
 * <p>The tone follows what the reader has to do (the colour rule of 2026-09-18). A draft that expired
 * or changed is amber: nothing broke, but they must ask again for a fresh one. A draft already handled
 * is information, since the card below it now shows what happened to it. Anything else — the server
 * unreachable, a permission refused — is red, as it is in the conversation.
 */
function RefusalNotice({ refusal }: { refusal: DraftRefusal }) {
  const tone =
    refusal.code === DRAFT_EXPIRED || refusal.code === DRAFT_CHANGED
      ? "warning"
      : refusal.code === DRAFT_ALREADY_HANDLED
        ? "info"
        : "danger";
  return (
    <InlineNotice tone={tone} title={refusal.message}>
      <p>{refusal.action}</p>
      <p className="mt-1 text-xs tabular-nums">{refusal.code}</p>
    </InlineNotice>
  );
}

/** The fact the server labelled "Vendor", for the confirmed line's "Not sent to … yet". */
function vendorOf(proposal: AssistantProposal): string | null {
  return proposal.facts.find((f) => f.label === "Vendor")?.value ?? null;
}

/**
 * What a confirmed draft became, in the mock's words, with a link to it. The link is an in-app
 * navigation: the panel is mounted in the root layout, so it stays open with the conversation in it
 * while the page behind changes to the order.
 */
function ConfirmedLine({ proposal }: { proposal: AssistantProposal }) {
  const result = proposal.result;
  if (!result) return <p className="text-sm text-ink">Saved.</p>;
  const link = (
    <Link href={result.href} className="link">
      Open {result.label}
    </Link>
  );
  if (proposal.kind === "MEAL_PLAN") {
    return <p className="text-sm text-ink">Added to the planner. {link}</p>;
  }
  const vendor = vendorOf(proposal);
  return (
    <p className="text-sm text-ink">
      Saved as draft {result.label}. {vendor ? `Not sent to ${vendor} yet.` : "Not sent yet."} {link}
    </p>
  );
}

/**
 * A draft the assistant made. Every write is one of these (decision D-a, 2026-10-05): the server
 * runs it only when the person presses Confirm, with their own login.
 *
 * <p>Pressing Confirm or Reject shows that button busy and disables both until the server answers,
 * and a second press in the meantime is dropped even before React has re-rendered the disabled
 * buttons (a double-click lands both clicks in the same frame), so one press is one request. The card
 * then shows whatever the server sent back. Without `onDecide` the buttons are disabled rather than
 * doing nothing when pressed.
 */
export function DraftCard({
  proposal,
  refusal,
  onDecide,
}: {
  proposal: AssistantProposal;
  refusal?: DraftRefusal | null;
  onDecide?: (choice: "confirm" | "reject") => Promise<void>;
}) {
  const [pressed, setPressed] = useState<"confirm" | "reject" | null>(null);
  const inFlight = useRef(false);
  const meal = proposal.kind === "MEAL_PLAN";
  const status = useShownStatus(proposal);
  const badge = STATE_BADGE[status];

  async function decide(choice: "confirm" | "reject") {
    if (!onDecide || inFlight.current) return;
    inFlight.current = true;
    setPressed(choice);
    try {
      await onDecide(choice);
    } finally {
      inFlight.current = false;
      setPressed(null);
    }
  }

  // A draft whose source changed since it was drafted would be refused again, so its buttons go and
  // the notice above says to ask for a fresh one. An expired one says so in its notice, so the plain
  // "expired" sentence is not said twice.
  const changed = refusal?.code === DRAFT_CHANGED;
  const expiredNotice = refusal?.code === DRAFT_EXPIRED;

  return (
    <Card
      title={proposal.title}
      meta={meal ? "Meal plan draft" : "Purchase order draft"}
      action={
        // On one line. Found measuring S4: beside a two-line title the Card's action slot squeezed
        // "Not saved yet" into two lines (97×43 at 1280, 80×43 at 375), a cramped pill. The title
        // wraps instead; it has the room.
        <span className="whitespace-nowrap">
          <Badge tone={badge.tone}>{badge.label}</Badge>
        </span>
      }
      // The panel keeps the card's phone padding at every width (the mock's `compact`).
      padding="p-4"
    >
      <div className={status === "REJECTED" || status === "EXPIRED" ? "grid gap-4 opacity-60" : "grid gap-4"}>
        <Facts items={proposal.facts} />
        <DraftTable proposal={proposal} />
      </div>

      <div className="mt-4 grid gap-3 border-t border-hairline pt-4">
        {refusal && <RefusalNotice refusal={refusal} />}
        {status === "PROPOSED" && !changed && (
          // The second sanctioned change. In the mock the sentence had a 240px basis, which with the
          // two buttons beside it needed 449px in a card 382px wide inside the panel, so Reject and
          // Confirm dropped to a row of their own under a one-line sentence. Here the basis is 160px:
          // at 1280 the sentence gets 165px beside the buttons and reads in two lines (measured), so
          // they stay on one row. On a phone it would get 77px and six lines (measured), so there it
          // takes the row above and the buttons, still side by side, sit at the trailing edge below.
          // It wraps `pretty`, or the mock's sentence leaves one word alone on its last line.
          <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-3">
            <p className="min-w-0 flex-1 basis-40 text-sm text-ink-secondary [text-wrap:pretty]">{proposal.confirmSays}</p>
            <div className="ml-auto flex flex-none gap-2">
              <Button
                variant="ghost"
                onClick={() => void decide("reject")}
                busy={pressed === "reject"}
                disabled={!onDecide || pressed !== null}
              >
                Reject
              </Button>
              <Button onClick={() => void decide("confirm")} busy={pressed === "confirm"} disabled={!onDecide || pressed !== null}>
                Confirm
              </Button>
            </div>
          </div>
        )}
        {status === "REJECTED" && <p className="text-sm text-ink-secondary">You rejected this draft. Nothing was saved.</p>}
        {status === "EXPIRED" && !expiredNotice && (
          <p className="text-sm text-ink-secondary">This draft expired after 24 hours. Nothing was saved. Ask again for a fresh one.</p>
        )}
        {status === "CONFIRMED" && <ConfirmedLine proposal={proposal} />}
      </div>
    </Card>
  );
}

/**
 * A failure, with the server's own words, its next step and its permanent code. The mock's layout
 * (title, then what to do, then the code, quiet) with the real catalogue text in place of the
 * mock's placeholder.
 */
function ErrorTurn({
  code,
  message,
  action,
  retry,
  onRetry,
  disabled,
}: {
  code: string;
  message: string;
  action: string;
  retry: boolean;
  onRetry: () => void;
  disabled: boolean;
}) {
  return (
    <InlineNotice
      tone="danger"
      title={message}
      action={
        retry ? (
          <Button variant="ghost" onClick={onRetry} disabled={disabled}>
            Try again
          </Button>
        ) : undefined
      }
    >
      <p>{action}</p>
      <p className="mt-1 text-xs tabular-nums">{code}</p>
    </InlineNotice>
  );
}

/**
 * The questions offered on an empty conversation: one about stock and one that drafts an order.
 *
 * <p>Two of the mock's four, on purpose. The mock also offered "Plan lunch for Sunday", but the
 * assistant cannot draft a meal yet (S4 drafts purchase orders only), and a suggestion it cannot do
 * is the first thing a person would press. The order is "for toor dal", not the mock's "for Sri Balaji
 * Traders": a sample vendor's name is not a vendor in any real temple, while every temple stocks toor
 * dal, and it follows on from the stock question beside it. Add the meal back when meal drafts land.
 */
const SUGGESTIONS = ["How much ತೊಗರಿ ಬೇಳೆ do we have?", "Make a purchase order for toor dal"];

/** The first thing on an empty conversation: one sentence and the questions to start from. */
function EmptyConversation({ onPick, disabled }: { onPick: (text: string) => void; disabled: boolean }) {
  return (
    <div className="grid gap-4">
      <div className="grid gap-1">
        {/* The mock's heading and sentence, less the meal planning it cannot do yet. */}
        <p className="text-lg font-medium text-ink">Ask about stock or orders</p>
        <p className="text-ink-secondary">
          The assistant can check stock and draft a purchase order. It asks before it saves anything.
        </p>
      </div>
      <div className="flex flex-wrap gap-2">
        {SUGGESTIONS.map((s) => (
          <Button key={s} variant="ghost" onClick={() => onPick(s)} disabled={disabled} className="text-left">
            {s}
          </Button>
        ))}
      </div>
    </div>
  );
}

export function Messages({ convo }: { convo: AssistantConversationState }) {
  const { entries, busy } = convo;
  if (entries.length === 0) return <EmptyConversation onPick={(t) => void convo.ask(t)} disabled={busy || convo.blocked} />;
  const last = entries[entries.length - 1];
  return (
    <div className="grid gap-4">
      {entries.map((e: Entry) => {
        switch (e.kind) {
          case "user":
            return <UserTurn key={e.id} text={e.text} />;
          case "assistant":
            return <AssistantTurn key={e.id} text={e.text} streaming={busy && e.id === last.id} />;
          case "tool":
            return <ToolLine key={e.id} tool={e.tool} summary={e.summary} state={e.state} />;
          case "draft":
            return (
              <DraftCard
                key={e.id}
                proposal={e.proposal}
                refusal={e.refusal}
                onDecide={(choice) => convo.decide(e.id, e.proposal.actionId, choice)}
              />
            );
          case "error":
            return (
              <ErrorTurn
                key={e.id}
                code={e.code}
                message={e.message}
                action={e.action}
                retry={e.retry && !convo.blocked}
                onRetry={convo.retry}
                disabled={busy}
              />
            );
        }
      })}
    </div>
  );
}

/**
 * Where the person types: one box that grows to five lines, and Send (Stop while an answer comes).
 *
 * <p>No microphone. The mock drew one, disabled, to judge the row at its final width, and said itself
 * that the real build leaves it out until dictation works: a button that never does anything is a
 * button somebody presses and wonders about.
 */
export function Composer({ convo, inputRef }: { convo: AssistantConversationState; inputRef?: React.RefObject<HTMLTextAreaElement> }) {
  const [text, setText] = useState("");
  const boxId = useId();
  const ownRef = useRef<HTMLTextAreaElement>(null);
  const box = inputRef ?? ownRef;
  const blocked = convo.blocked;

  // Grows with what is typed, up to five lines, then scrolls inside itself.
  useEffect(() => {
    const el = box.current;
    if (!el) return;
    // Empty, it is one line at the touch height like every other box; only typed text grows it.
    // (Measured in the mock: at the body's 26px line the box came out 45px beside the 44px buttons,
    // so the box sets a 24px line, which makes it 42 and lets min-h-touch hold it at exactly 44.)
    if (!text) {
      el.style.height = "";
      return;
    }
    el.style.height = "auto";
    // scrollHeight leaves out the 1px border top and bottom, and the box is border-box.
    el.style.height = `${Math.max(44, Math.min(el.scrollHeight + 2, 5 * 24 + 18))}px`;
  }, [text, box]);

  function submit() {
    const t = text.trim();
    if (!t || convo.busy || blocked) return;
    void convo.ask(t);
    setText("");
  }

  return (
    <Form
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
      // Not a grid: a grid track sizes to the textarea's min-content (its default 20 columns), which
      // in the mock pushed Send 10px past the gutter at 375.
      className="min-w-0"
    >
      <div className="flex items-end gap-2">
        <textarea
          ref={box}
          id={boxId}
          // Named, not labelled: the box has no visible label in the mock, and its placeholder is
          // not one, since it disappears as soon as somebody types.
          aria-label="Message the assistant"
          rows={1}
          value={text}
          disabled={blocked}
          maxLength={4000}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
              e.preventDefault();
              submit();
            }
          }}
          // The limit's own words say "tomorrow", so the box does too (the mock said "Back at
          // midnight", written before the real codes existed).
          placeholder={blocked ? "Available again tomorrow" : "Type a question"}
          className="min-h-touch min-w-0 flex-1 resize-none rounded-control border border-hairline-strong px-3 py-2 text-base leading-6 disabled:bg-sunken"
        />
        {convo.busy ? (
          <Button variant="ghost" onClick={convo.stop}>
            Stop
          </Button>
        ) : (
          <Button type="submit" disabled={blocked || !text.trim()}>
            Send
          </Button>
        )}
      </div>
    </Form>
  );
}
