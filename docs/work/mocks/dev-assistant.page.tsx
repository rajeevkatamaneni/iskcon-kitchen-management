// LOCAL PLAYGROUND — delete before any commit or deploy
"use client";

/*
 * THROWAWAY MOCK for E10-S3-mock — the Kitchen Assistant chat, so Rajeev can judge where it lives
 * and how it behaves by using it rather than by reading about it.
 *
 * Two places for the chat, switchable from the mock bar at the top:
 *
 *   A — its own page, "Assistant" in the menu (/assistant). The whole working area is the chat.
 *   B — a panel that opens from any page. Shown over a sample Today page; a button at the foot of
 *       the screen opens it, the page stays where it was, and on a phone the panel takes the screen.
 *
 * Both draw exactly the same conversation, from the same components, so the only thing that differs
 * between A and B is where the chat sits. Every state Rajeev asked to see is a scene in the mock bar:
 * an empty conversation, a streamed answer with a "Checking stock…" line, the assistant asking for
 * missing details, a meal-plan draft, a purchase-order draft, and errors. The draft cards' Confirm and
 * Reject work, and the mock bar can also force a card into confirmed, rejected or expired. Typing in
 * the box and pressing Send plays a canned reply, so the streaming can be watched at any time.
 *
 * Built from the app's own parts: Button, Badge, Card, PageHeader, StatTile, InlineNotice, InfoHint,
 * SegmentedControl, CookingLoader, the ruled-table constants, the field label class and the unit and
 * money formatters. So the table fitter and the phone card layout in globals.css apply to the draft
 * cards exactly as they would on a real screen.
 *
 * The menu is a copy of the real Sidebar's markup (as dev-deliveries did), not the component: the
 * real one reads nav.ts, which has no Assistant entry yet, and adding one is a change to a shared
 * file this mock may not make. The copy takes the Kitchen Manager's real menu from `navForRole` and,
 * for option A only, puts "Assistant" first in the top group, under Today.
 *
 * Sample data only, held in component state: no API call, no session, nothing saved. "Streaming" is
 * timers. The KMS code on the errors is a placeholder (KMS-000000): the real codes are reserved by
 * task T11 and are not allocated yet.
 *
 * URL parameters, so a screenshot can be taken without the mock bar: ?opt=A|B &scene=… &card=…
 * &bar=0 &open=1 (B: start with the panel open).
 */

import Link from "next/link";
import { useCallback, useEffect, useId, useMemo, useRef, useState, type ReactNode } from "react";
import { navForRole, type NavGroup } from "@/lib/nav";
import { money, unitLabel } from "@/lib/format";
import { Button } from "@/components/ds/Button";
import { Badge } from "@/components/ds/Badge";
import { Card } from "@/components/ds/Card";
import { PageHeader } from "@/components/ds/PageHeader";
import { StatTile } from "@/components/ds/StatTile";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { InfoHint } from "@/components/ds/InfoHint";
import { SegmentedControl } from "@/components/ds/SegmentedControl";
import { CookingLoader } from "@/components/CookingLoader";
import { FIELD_LABEL } from "@/components/Field";
import { RULED_TABLE, THEAD, TR, TH_PRIMARY, TD_PRIMARY, TH_FIXED, TD_FIXED_NUM } from "@/components/ds/table";

// ---------------------------------------------------------------------------------------------
// Sample data. ISKCON South Bengaluru, Monday 5 October 2026. Prices are plausible wholesale
// prices for the city, not real quotes. Kannada names are the glossary's stored names.
// ---------------------------------------------------------------------------------------------

const TEMPLE = "ISKCON South Bengaluru";
const ME = "Govinda Das";
/** Placeholder until T11 reserves the real codes in ErrorCode.java. */
const CODE = "KMS-000000";

interface DishLine {
  name: string;
  kannada: string;
  amount: number;
  unit: string;
}

const MEAL_DISHES: DishLine[] = [
  { name: "Bisi Bele Bath", kannada: "ಬಿಸಿ ಬೇಳೆ ಬಾತ್", amount: 57, unit: "L" },
  { name: "Hesaru Bele Kosambari (Moong Dal and Cucumber Salad)", kannada: "ಹೆಸರು ಬೇಳೆ ಕೋಸಂಬರಿ", amount: 14, unit: "KG" },
  { name: "Majjige Huli with Ash Gourd", kannada: "ಬೂದುಗುಂಬಳ ಮಜ್ಜಿಗೆ ಹುಳಿ", amount: 35, unit: "L" },
  { name: "Kesari Bath", kannada: "ಕೇಸರಿ ಬಾತ್", amount: 19, unit: "KG" },
];

interface PoLine {
  name: string;
  kannada: string;
  qty: number;
  unit: string;
  rate: number;
}

const PO_LINES: PoLine[] = [
  { name: "Toor dal", kannada: "ತೊಗರಿ ಬೇಳೆ", qty: 50, unit: "KG", rate: 132 },
  { name: "Rice, Sona Masoori (aged, 25 Kg bag)", kannada: "ಸೋನಾ ಮಸೂರಿ ಅಕ್ಕಿ", qty: 100, unit: "KG", rate: 58 },
  { name: "Jaggery, Mandya (block)", kannada: "ಬೆಲ್ಲ", qty: 15, unit: "KG", rate: 62 },
  { name: "Groundnut oil, double-filtered", kannada: "ಕಡಲೆಕಾಯಿ ಎಣ್ಣೆ", qty: 30, unit: "L", rate: 189 },
];
const PO_TOTAL = PO_LINES.reduce((sum, l) => sum + l.qty * l.rate, 0);

// ---------------------------------------------------------------------------------------------
// The conversation model. One list of entries; each entry is one thing on screen.
// ---------------------------------------------------------------------------------------------

type CardState = "pending" | "confirmed" | "rejected" | "expired";

type Entry =
  | { id: string; kind: "user"; text: string }
  | { id: string; kind: "assistant"; text: string }
  /** A tool call: "Checking stock…" while it runs, then a quiet record of what was checked. */
  | { id: string; kind: "tool"; running: string; done: string; finished: boolean }
  | { id: string; kind: "draft"; draft: "meal" | "po"; state: CardState }
  | { id: string; kind: "error"; title: string; body: string; next: string; retry: boolean };

/**
 * One step of a scripted reply. The player walks a list of these with timers: a tool line appears
 * "running", then finishes; text streams in a few characters at a time; a draft card arrives whole.
 */
type Step =
  | { kind: "tool"; running: string; done: string; ms: number }
  | { kind: "text"; text: string }
  | { kind: "draft"; draft: "meal" | "po" }
  | { kind: "error"; title: string; body: string; next: string; retry: boolean };

const STOCK_Q = "How much ತೊಗರಿ ಬೇಳೆ do we have?";
const STOCK_REPLY: Step[] = [
  { kind: "tool", running: "Checking stock…", done: "Checked stock: Toor dal", ms: 1400 },
  {
    kind: "text",
    text:
      "There are 42 Kg of Toor dal (ತೊಗರಿ ಬೇಳೆ) in the Main Store. That is above its reorder level of 25 Kg, " +
      "and covers about 6 days of the meals planned this week.\n\n" +
      "Another 50 Kg is on order from Sri Balaji Traders on PO-0041, due Thursday 8 October.",
  },
];

const MEAL_Q1 = "Plan lunch for Sunday";
const MEAL_REPLY1: Step[] = [
  { kind: "tool", running: "Checking the calendar…", done: "Checked the calendar: Sunday 11 October", ms: 1100 },
  { kind: "tool", running: "Checking meals already planned…", done: "Checked meals planned on 11 October", ms: 900 },
  {
    kind: "text",
    text:
      "Sunday 11 October is not an Ekadashi, and no lunch is planned yet. To draft it I need two things:\n\n" +
      "1. Which dishes?\n2. How many adults, children and seniors?",
  },
];
const MEAL_Q2 = "Bisi bele bath, kosambari, majjige huli and kesari bath. 180 adults, 40 children, 30 seniors.";
const MEAL_REPLY2: Step[] = [
  { kind: "tool", running: "Finding recipes…", done: "Found 4 recipes", ms: 1300 },
  {
    kind: "text",
    text:
      "I found all four. One question first: there are two Majjige Huli recipes, one with ash gourd and one " +
      "with mixed vegetables. Which one?",
  },
];
const MEAL_Q3 = "Ash gourd";
const MEAL_REPLY3: Step[] = [
  { kind: "tool", running: "Working out amounts…", done: "Worked out amounts for 228 servings", ms: 1200 },
  { kind: "text", text: "Here is the draft. Nothing is saved until you press Confirm." },
  { kind: "draft", draft: "meal" },
];

const PO_Q = "Make a purchase order for Sri Balaji Traders from the shopping list, needed by Thursday";
const PO_REPLY: Step[] = [
  { kind: "tool", running: "Finding the vendor…", done: "Found Sri Balaji Traders", ms: 900 },
  { kind: "tool", running: "Reading the shopping list…", done: "Read 4 lines for Sri Balaji Traders", ms: 1100 },
  {
    kind: "text",
    text:
      "Here is the draft, at Sri Balaji Traders' current prices. Confirming saves it as a draft order. " +
      "It is not sent to them until you send it.",
  },
  { kind: "draft", draft: "po" },
];

const LOW_Q = "What is running low?";
const LOW_REPLY: Step[] = [
  { kind: "tool", running: "Checking stock…", done: "Checked 5 items below reorder level", ms: 1300 },
  {
    kind: "text",
    text:
      "Five items are below their reorder level:\n\n" +
      "• Ghee (ತುಪ್ಪ): 6 Kg, reorder at 15 Kg\n" +
      "• Cardamom, green (ಏಲಕ್ಕಿ): 200 g, reorder at 500 g\n" +
      "• Urad dal, split and husked (ಉದ್ದಿನ ಬೇಳೆ): 9 Kg, reorder at 20 Kg\n" +
      "• Curry leaves (ಕರಿಬೇವು): 0.5 Kg, reorder at 2 Kg\n" +
      "• Banana leaf (ಬಾಳೆ ಎಲೆ): 120 pieces, reorder at 300 pieces\n\n" +
      "Ghee and cardamom are on Sunday's lunch. Shall I add them to the shopping list?",
  },
];

const ANY_REPLY: Step[] = [
  { kind: "tool", running: "Checking stock…", done: "Checked stock", ms: 1200 },
  {
    kind: "text",
    text:
      "This is a mock, so every question gets this answer. In the real assistant this is where the reply " +
      "streams in, a few words at a time, as it is written.",
  },
];

const SUGGESTIONS: { text: string; reply: Step[] }[] = [
  { text: STOCK_Q, reply: STOCK_REPLY },
  { text: MEAL_Q1, reply: MEAL_REPLY1 },
  { text: "Make a purchase order for Sri Balaji Traders", reply: PO_REPLY },
  { text: LOW_Q, reply: LOW_REPLY },
];

type Scene = "empty" | "answer" | "details" | "meal" | "po" | "error";

const SCENES: { value: Scene; label: string }[] = [
  { value: "empty", label: "Empty" },
  { value: "answer", label: "Streaming answer" },
  { value: "details", label: "Asks for details" },
  { value: "meal", label: "Meal draft" },
  { value: "po", label: "Order draft" },
  { value: "error", label: "Errors" },
];

let nextId = 0;
const id = () => `e${++nextId}`;

/** A finished conversation, built from the same scripts the player would have played. */
function settled(turns: [string, Step[]][], card: CardState): Entry[] {
  const out: Entry[] = [];
  for (const [q, steps] of turns) {
    out.push({ id: id(), kind: "user", text: q });
    for (const s of steps) {
      if (s.kind === "tool") out.push({ id: id(), kind: "tool", running: s.running, done: s.done, finished: true });
      if (s.kind === "text") out.push({ id: id(), kind: "assistant", text: s.text });
      if (s.kind === "draft") out.push({ id: id(), kind: "draft", draft: s.draft, state: card });
      if (s.kind === "error") out.push({ ...s, id: id(), kind: "error" });
    }
  }
  return out;
}

const TIMEOUT: Extract<Step, { kind: "error" }> = {
  kind: "error",
  title: "The assistant took too long to answer.",
  body: "Nothing was changed.",
  next: "Try again. If it keeps happening, ask a shorter question.",
  retry: true,
};
const BUDGET: Extract<Step, { kind: "error" }> = {
  kind: "error",
  title: "The assistant has done enough for today.",
  body: "Your temple has used today's 500 questions. It starts again at midnight.",
  next: "Until then, use the screens in the menu.",
  retry: false,
};

/** What each scene starts with. "answer" starts empty and plays itself, so the stream is seen live. */
function sceneEntries(scene: Scene, card: CardState): Entry[] {
  switch (scene) {
    case "empty":
    case "answer":
      return [];
    case "details":
      return settled([[MEAL_Q1, MEAL_REPLY1], [MEAL_Q2, MEAL_REPLY2]], card);
    case "meal":
      return settled([[MEAL_Q1, MEAL_REPLY1], [MEAL_Q2, MEAL_REPLY2], [MEAL_Q3, MEAL_REPLY3]], card);
    case "po":
      return settled([[PO_Q, PO_REPLY]], card);
    case "error":
      return settled(
        [
          [LOW_Q, [{ kind: "tool", running: "Checking stock…", done: "Checking stock stopped", ms: 0 }, TIMEOUT]],
          [LOW_Q, [BUDGET]],
        ],
        card,
      );
  }
}

// ---------------------------------------------------------------------------------------------
// The player: plays a scripted reply into the conversation with timers.
// ---------------------------------------------------------------------------------------------

function useConversation(scene: Scene, card: CardState) {
  const [entries, setEntries] = useState<Entry[]>(() => sceneEntries(scene, card));
  const [busy, setBusy] = useState(false);
  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);

  const stop = useCallback(() => {
    timers.current.forEach(clearTimeout);
    timers.current = [];
    setBusy(false);
  }, []);

  const play = useCallback(
    (question: string, steps: Step[]) => {
      stop();
      setBusy(true);
      setEntries((e) => [...e, { id: id(), kind: "user", text: question }]);
      let at = 500; // the pause before anything comes back
      const later = (ms: number, fn: () => void) => timers.current.push(setTimeout(fn, ms));
      for (const s of steps) {
        if (s.kind === "tool") {
          const tid = id();
          later(at, () => setEntries((e) => [...e, { id: tid, kind: "tool", running: s.running, done: s.done, finished: false }]));
          at += s.ms;
          later(at, () =>
            setEntries((e) => e.map((x) => (x.id === tid && x.kind === "tool" ? { ...x, finished: true } : x))),
          );
          at += 250;
        } else if (s.kind === "text") {
          const tid = id();
          // Words arrive in small bursts, the way a model streams them: 2–4 words every 60ms.
          const words = s.text.split(/(?<=\s)/);
          later(at, () => setEntries((e) => [...e, { id: tid, kind: "assistant", text: "" }]));
          let shown = 0;
          while (shown < words.length) {
            shown = Math.min(words.length, shown + 2 + (shown % 3));
            const upto = shown;
            at += 60;
            later(at, () =>
              setEntries((e) =>
                e.map((x) => (x.id === tid && x.kind === "assistant" ? { ...x, text: words.slice(0, upto).join("") } : x)),
              ),
            );
          }
          at += 200;
        } else if (s.kind === "draft") {
          later(at, () => setEntries((e) => [...e, { id: id(), kind: "draft", draft: s.draft, state: "pending" }]));
          at += 100;
        } else {
          later(at, () => setEntries((e) => [...e, { ...s, id: id(), kind: "error" }]));
        }
      }
      later(at, () => setBusy(false));
    },
    [stop],
  );

  // A new scene (or card state from the mock bar) resets the conversation to that scene.
  useEffect(() => {
    stop();
    setEntries(sceneEntries(scene, card));
    if (scene === "answer") {
      const t = setTimeout(() => play(STOCK_Q, STOCK_REPLY), 400);
      return () => clearTimeout(t);
    }
  }, [scene, card, play, stop]);

  useEffect(() => stop, [stop]);

  const setCard = (entryId: string, state: CardState) =>
    setEntries((e) => e.map((x) => (x.id === entryId && x.kind === "draft" ? { ...x, state } : x)));

  /** What the composer sends: the next turn of a scripted flow when there is one, else a canned reply. */
  function send(text: string) {
    const lastUser = [...entries].reverse().find((x) => x.kind === "user")?.text;
    if (lastUser === MEAL_Q1) return play(text, MEAL_REPLY2);
    if (lastUser === MEAL_Q2) return play(text, MEAL_REPLY3);
    play(text, ANY_REPLY);
  }

  const budgetUsed = entries.some((x) => x.kind === "error" && !x.retry);
  const clear = () => {
    stop();
    setEntries([]);
  };
  return { entries, busy, play, send, setCard, budgetUsed, stop, clear };
}

// ---------------------------------------------------------------------------------------------
// The conversation's parts. Shared, unchanged, by option A and option B.
// ---------------------------------------------------------------------------------------------

/**
 * The person's own words: a sunken block on the trailing side. Not the accent, which means "the
 * main thing to press" and nothing else, and not a bubble with a tail — a block in the card corner.
 */
function UserTurn({ text }: { text: string }) {
  return (
    <div className="flex justify-end">
      <p className="ml-12 whitespace-pre-wrap rounded-card bg-sunken px-4 py-2 text-ink">{text}</p>
    </div>
  );
}

/** The assistant's words: plain text on the page, full width of the column, no bubble, so a long
 *  answer reads as a paragraph rather than as a speech balloon. */
function AssistantTurn({ text, streaming }: { text: string; streaming: boolean }) {
  return (
    <div className="whitespace-pre-wrap text-ink" aria-live={streaming ? "polite" : undefined}>
      {text}
      {streaming && <span className="ml-0.5 inline-block h-4 w-2 animate-pulse bg-ink-muted align-middle" aria-hidden="true" />}
    </div>
  );
}

/**
 * A tool call. While it runs: the app's own cooking pot and "Checking stock…", in muted text. When it
 * is done it stays as a quiet one-line record ("Checked stock: Toor dal"), so the person can see what
 * the answer was based on without it competing with the answer.
 */
function ToolLine({ running, done, finished }: { running: string; done: string; finished: boolean }) {
  return (
    <p className="flex items-center gap-2 text-sm text-ink-muted">
      {finished ? (
        <span className="inline-block h-5 w-5 flex-none" aria-hidden="true" />
      ) : (
        <CookingLoader className="h-5 w-5 flex-none" decorative />
      )}
      <span role={finished ? undefined : "status"}>{finished ? done : running}</span>
    </p>
  );
}

function Facts({ items }: { items: { label: string; value: ReactNode; hint?: string }[] }) {
  // A label over each value, the way a readout sits in a form. They sit side by side at their own
  // widths and wrap only when the next one does not fit, so a short value never forces a long one
  // onto two lines (a four-column grid did exactly that at 640px: "Lunch, ready by / 12:00").
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

const STATE_BADGE: Record<CardState, ReactNode> = {
  // Waiting for the reader to act: that is what amber means here.
  pending: <Badge tone="warning">Not saved yet</Badge>,
  // The reader's own action did what they expected: the one place green belongs.
  confirmed: <Badge tone="success">Saved</Badge>,
  rejected: <Badge>Rejected</Badge>,
  expired: <Badge>Expired</Badge>,
};

function ItemName({ name, kannada }: { name: string; kannada: string }) {
  return (
    <>
      <span className="block text-ink">{name}</span>
      <span lang="kn" className="block text-sm text-ink-secondary">
        {kannada}
      </span>
    </>
  );
}

function MealDraftBody() {
  return (
    <div className="grid gap-4">
      <Facts
        items={[
          { label: "Date", value: "Sun 11 Oct" },
          { label: "Meal", value: "Lunch, ready by 12:00" },
          { label: "Kitchen", value: "Main Kitchen" },
          {
            label: "People",
            value: "180 adults, 40 children, 30 seniors",
            hint: "Cooked for 228 servings. A child counts as 0.6 of a serving and a senior as 0.8.",
          },
        ]}
      />
      <table className={RULED_TABLE}>
        {/* Not sticky. Every app table's header sticks to the top of its scroller from 1024 up; in a
            chat that scroller is the conversation, so a draft's header floated over the messages above
            it as they scrolled. `lg:!static` on this card's own head only, from 1024 up where the sticking
            happens: below that the phone card layout hides the head by positioning it, and a plain
            `!static` undid that and showed "DISH AMOUNT" squashed above the cards. */}
        <thead className={`${THEAD} lg:!static`}>
          <tr>
            <th className={TH_PRIMARY}>Dish</th>
            <th className={TH_FIXED}>Amount</th>
          </tr>
        </thead>
        <tbody>
          {MEAL_DISHES.map((d) => (
            <tr key={d.name} className={TR}>
              <td className={TD_PRIMARY}>
                <ItemName name={d.name} kannada={d.kannada} />
              </td>
              <td className={TD_FIXED_NUM}>
                {d.amount} {unitLabel(d.unit)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function PoDraftBody() {
  return (
    <div className="grid gap-4">
      <Facts
        items={[
          { label: "Vendor", value: "Sri Balaji Traders" },
          { label: "Needed by", value: "Thu 8 Oct" },
          { label: "Lines", value: PO_LINES.length },
          { label: "Total", value: money(PO_TOTAL, "INR") },
        ]}
      />
      <table className={RULED_TABLE}>
        {/* Not sticky. Every app table's header sticks to the top of its scroller from 1024 up; in a
            chat that scroller is the conversation, so a draft's header floated over the messages above
            it as they scrolled. `lg:!static` on this card's own head only, from 1024 up where the sticking
            happens: below that the phone card layout hides the head by positioning it, and a plain
            `!static` undid that and showed "DISH AMOUNT" squashed above the cards. */}
        <thead className={`${THEAD} lg:!static`}>
          <tr>
            <th className={TH_PRIMARY}>Item</th>
            <th className={TH_FIXED}>Quantity</th>
            <th className={TH_FIXED}>Amount</th>
          </tr>
        </thead>
        <tbody>
          {PO_LINES.map((l) => (
            <tr key={l.name} className={TR}>
              <td className={TD_PRIMARY}>
                <ItemName name={l.name} kannada={l.kannada} />
              </td>
              {/* The price sits under the quantity rather than in a column of its own: four columns
                  needed 402px and the panel (option B) has 367 inside its card. */}
              <td className={TD_FIXED_NUM}>
                <span className="block">
                  {l.qty} {unitLabel(l.unit)}
                </span>
                <span className="block text-sm text-ink-secondary">
                  at {money(l.rate, "INR")}/{unitLabel(l.unit)}
                </span>
              </td>
              <td className={TD_FIXED_NUM}>{money(l.qty * l.rate, "INR")}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * A draft the assistant made. Every write is one of these (decision D-a, 2026-10-05): the server
 * runs it only when the person presses Confirm, with their own login. The buttons say Confirm and
 * Reject because those are the words of the decision; the line above them says what Confirm does.
 */
function DraftCard({
  draft,
  state,
  compact,
  onConfirm,
  onReject,
}: {
  draft: "meal" | "po";
  state: CardState;
  /** In the panel (option B) the card keeps its phone padding at every width. */
  compact: boolean;
  onConfirm: () => void;
  onReject: () => void;
}) {
  const [saving, setSaving] = useState(false);
  const meal = draft === "meal";
  const title = meal ? "Lunch on Sunday 11 October" : "Purchase order for Sri Balaji Traders";

  function confirm() {
    setSaving(true);
    setTimeout(() => {
      setSaving(false);
      onConfirm();
    }, 900);
  }

  return (
    <Card title={title} meta={meal ? "Meal plan draft" : "Purchase order draft"} action={STATE_BADGE[state]} padding={compact ? "p-4" : "p-4 sm:p-6"}>
      <div className={state === "rejected" || state === "expired" ? "opacity-60" : ""}>
        {meal ? <MealDraftBody /> : <PoDraftBody />}
      </div>

      <div className="mt-4 border-t border-hairline pt-4">
        {state === "pending" && (
          <div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3">
            <p className="min-w-0 grow basis-60 text-sm text-ink-secondary">
              {meal ? "Confirm adds this meal to the planner." : "Confirm saves this as a draft order. It is not sent."}
            </p>
            <div className="flex gap-2">
              <Button variant="ghost" onClick={onReject} disabled={saving}>
                Reject
              </Button>
              <Button onClick={confirm} busy={saving}>
                Confirm
              </Button>
            </div>
          </div>
        )}
        {state === "confirmed" && (
          <p className="text-sm text-ink">
            {meal ? (
              <>
                Added to the planner.{" "}
                <Link href="/planner" className="link">
                  Open lunch on 11 October
                </Link>
              </>
            ) : (
              <>
                Saved as draft PO-0047. Not sent to Sri Balaji Traders yet.{" "}
                <Link href="/orders" className="link">
                  Open PO-0047
                </Link>
              </>
            )}
          </p>
        )}
        {state === "rejected" && <p className="text-sm text-ink-secondary">You rejected this draft. Nothing was saved.</p>}
        {state === "expired" && (
          <p className="text-sm text-ink-secondary">
            This draft expired after 24 hours. Nothing was saved. Ask again for a fresh one.
          </p>
        )}
      </div>
    </Card>
  );
}

function ErrorTurn({ title, body, next, retry, onRetry }: { title: string; body: string; next: string; retry: boolean; onRetry: () => void }) {
  return (
    <InlineNotice
      tone="danger"
      title={title}
      action={
        retry ? (
          <Button variant="ghost" onClick={onRetry}>
            Try again
          </Button>
        ) : undefined
      }
    >
      <p>
        {body} {next}
      </p>
      <p className="mt-1 text-xs tabular-nums">{CODE}</p>
    </InlineNotice>
  );
}

/** The first thing on an empty conversation: one sentence and four questions to start from. */
function EmptyConversation({ onPick }: { onPick: (text: string, reply: Step[]) => void }) {
  return (
    <div className="grid gap-4">
      <div className="grid gap-1">
        <p className="text-lg font-medium text-ink">Ask about stock, meals or orders</p>
        <p className="text-ink-secondary">
          The assistant can check stock, plan a meal and draft a purchase order. It asks before it saves anything.
        </p>
      </div>
      <div className="flex flex-wrap gap-2">
        {SUGGESTIONS.map((s) => (
          <Button key={s.text} variant="ghost" onClick={() => onPick(s.text, s.reply)} className="text-left">
            {s.text}
          </Button>
        ))}
      </div>
    </div>
  );
}

function Messages({ convo, compact = false }: { convo: ReturnType<typeof useConversation>; compact?: boolean }) {
  const { entries, busy } = convo;
  const lastAssistant = [...entries].reverse().find((x) => x.kind === "assistant")?.id;
  if (entries.length === 0) return <EmptyConversation onPick={convo.play} />;
  return (
    <div className="grid gap-4">
      {entries.map((e) => {
        switch (e.kind) {
          case "user":
            return <UserTurn key={e.id} text={e.text} />;
          case "assistant":
            return <AssistantTurn key={e.id} text={e.text} streaming={busy && e.id === lastAssistant} />;
          case "tool":
            return <ToolLine key={e.id} {...e} />;
          case "draft":
            return (
              <DraftCard
                key={e.id}
                draft={e.draft}
                state={e.state}
                compact={compact}
                onConfirm={() => convo.setCard(e.id, "confirmed")}
                onReject={() => convo.setCard(e.id, "rejected")}
              />
            );
          case "error":
            return <ErrorTurn key={e.id} {...e} onRetry={() => convo.play(LOW_Q, LOW_REPLY)} />;
        }
      })}
    </div>
  );
}

/**
 * Where the person types. One box that grows to five lines, the microphone's place, and Send.
 *
 * The microphone is drawn, disabled, so the row is judged at the width it will have when dictation
 * arrives and nothing moves then. In the real build it is left out until dictation works: a button
 * that never does anything is a button somebody presses and wonders about.
 */
function Composer({ convo }: { convo: ReturnType<typeof useConversation> }) {
  const [text, setText] = useState("");
  const boxId = useId();
  const box = useRef<HTMLTextAreaElement>(null);
  const blocked = convo.budgetUsed;

  // Grows with what is typed, up to five lines, then scrolls inside itself.
  useEffect(() => {
    const el = box.current;
    if (!el) return;
    // Empty, it is one line at the touch height like every other box; only typed text grows it.
    // (Measured: at the body's 26px line the box came out 45px beside the 44px buttons, so the box
    // sets a 24px line, which makes it 42 and lets min-h-touch hold it at exactly 44.)
    if (!text) {
      el.style.height = "";
      return;
    }
    el.style.height = "auto";
    // scrollHeight leaves out the 1px border top and bottom, and the box is border-box, so
    // without the 2px the empty box came out 42px for 44px of content and showed a scrollbar.
    el.style.height = `${Math.max(44, Math.min(el.scrollHeight + 2, 5 * 24 + 18))}px`;
  }, [text]);

  function submit() {
    const t = text.trim();
    if (!t || convo.busy || blocked) return;
    convo.send(t);
    setText("");
  }

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
      // Not a grid. A grid track sizes to its child's min-content, and a textarea's min-content is
      // its default 20 columns: the row came out 353px in a 343px column at 375 and pushed Send
      // 10px past the gutter (measured).
      className="min-w-0"
    >
      <label htmlFor={boxId} className="sr-only">
        Message the assistant
      </label>
      <div className="flex items-end gap-2">
        <textarea
          ref={box}
          id={boxId}
          rows={1}
          value={text}
          disabled={blocked}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              submit();
            }
          }}
          placeholder={blocked ? "Back at midnight" : "Type a question"}
          className="min-h-touch min-w-0 flex-1 resize-none rounded-control border border-hairline-strong px-3 py-2 text-base leading-6 disabled:bg-sunken"
        />
        <Button
          variant="ghost"
          size="icon"
          icon="microphone"
          disabled
          aria-label="Dictate (coming later)"
          title="Dictation is coming later"
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
    </form>
  );
}

/** Keeps the newest entry in view as the reply streams in. */
function useStickToBottom(dep: unknown) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [dep]);
  return ref;
}

// ---------------------------------------------------------------------------------------------
// The menu: the real Sidebar's markup, fed the Kitchen Manager's real menu (plus Assistant in A).
// ---------------------------------------------------------------------------------------------

const ASSISTANT_HREF = "/dev-assistant";

function menu(withAssistant: boolean): NavGroup[] {
  const groups = navForRole("KITCHEN_MANAGER");
  if (!withAssistant) return groups;
  return groups.map((group, i) => {
    if (i !== 0 || group.items.length === 0) return group;
    const items = [...group.items];
    items.splice(1, 0, { ...group.items[0], id: "assistant", href: ASSISTANT_HREF, label: "Assistant", icon: "message-chatbot" });
    return { ...group, items };
  });
}

function MockSidebar({ withAssistant, activeHref }: { withAssistant: boolean; activeHref: string }) {
  const [open, setOpen] = useState(false);
  const groups = useMemo(() => menu(withAssistant), [withAssistant]);
  return (
    <>
      <div
        className={
          open
            ? "fixed inset-y-0 left-0 z-50 flex shadow-overlay motion-safe:animate-drawer-in lg:contents"
            : "hidden lg:contents"
        }
      >
        <nav
          aria-label="Main"
          className="sidebar-surface sticky top-0 flex h-screen w-sidebar shrink-0 flex-col gap-4 overflow-hidden px-4 py-6 max-lg:h-full"
        >
          {open && (
            <button
              type="button"
              onClick={() => setOpen(false)}
              className="absolute right-3 top-3 z-10 flex min-h-touch items-center gap-2 rounded-control px-3 text-sm text-ink-secondary hover:bg-sunken hover:text-ink lg:hidden"
            >
              <i className="ti ti-x text-lg" aria-hidden="true" />
              Close
            </button>
          )}
          <div className="grid justify-items-center gap-1 px-2">
            <img src="/brand/iskcon-icon.svg" alt="" aria-hidden="true" className="h-16 w-16 flex-none object-contain" />
            <span className="block text-center text-lg font-medium leading-tight text-ink">{TEMPLE}</span>
          </div>
          <div className="-mx-3 grid min-h-0 flex-1 content-start gap-6 overflow-y-auto px-3">
            {groups.map((group) => (
              <div key={group.id} className="grid gap-1">
                {group.title && (
                  <span className="mb-1 px-3 text-xs uppercase tracking-eyebrow text-ink-muted">{group.title}</span>
                )}
                {group.items.map((item) => {
                  const active = item.href === activeHref;
                  return (
                    <Link
                      key={item.id}
                      href={item.href === ASSISTANT_HREF ? "#" : item.href}
                      aria-current={active ? "page" : undefined}
                      className={[
                        "flex min-h-touch items-center gap-3 rounded px-3 text-nav",
                        "transition-[transform,box-shadow,background-color,color] duration-state ease-out",
                        active
                          ? "bg-accent-bg font-semibold text-accent-text"
                          : "text-ink-secondary hover:-translate-y-0.5 hover:bg-sunken hover:text-ink hover:shadow-lift",
                      ].join(" ")}
                    >
                      <i className={`ti ti-${item.icon} text-lg`} aria-hidden="true" />
                      {item.label}
                    </Link>
                  );
                })}
              </div>
            ))}
          </div>
          <div className="border-t border-hairline px-3 pt-3 text-sm">
            <span className="block font-medium text-ink">{ME}</span>
            <span className="block text-ink-secondary">Kitchen Manager</span>
          </div>
        </nav>
      </div>
      {open && <div aria-hidden="true" onClick={() => setOpen(false)} className="fixed inset-0 z-40 bg-ink/40 lg:hidden" />}
      <header className="app-topbar topbar-surface sticky top-0 z-30 order-first flex min-h-14 items-center gap-3 px-4 py-1 lg:hidden">
        <button
          type="button"
          onClick={() => setOpen(true)}
          aria-expanded={open}
          className="-ms-3 flex min-h-touch flex-none items-center gap-2 rounded-control px-3 text-base font-medium text-ink hover:bg-sunken"
        >
          <i className="ti ti-menu-2 text-lg" aria-hidden="true" />
          Menu
        </button>
        <span className="min-w-0 flex-1 font-medium leading-tight text-ink">{TEMPLE}</span>
      </header>
    </>
  );
}

// ---------------------------------------------------------------------------------------------
// Option A — its own page.
// ---------------------------------------------------------------------------------------------

/**
 * The whole working area is the conversation. The header and the box stay put and the messages
 * scroll between them, so Send is always on screen however long the conversation gets. The column
 * is the reading width (`max-w-prose`, 640px), centred, header included, so the title, the
 * messages and the box share one left edge.
 */
function OptionA({ convo, bar }: { convo: ReturnType<typeof useConversation>; bar: ReactNode }) {
  // The window scrolls, not a box inside the page. An inner scroller put its scrollbar inside the
  // centred column, which moved the messages 7px left of the title and the box above and below
  // them (measured at 1280: card at x=453, title and box at x=460).
  useEffect(() => {
    window.scrollTo({ top: document.documentElement.scrollHeight });
  }, [convo.entries]);
  return (
    <div className="flex min-h-screen">
      <MockSidebar withAssistant activeHref={ASSISTANT_HREF} />
      <main className="flex min-w-0 flex-1 flex-col">
        {bar}
        <div className="px-4 pt-6 sm:px-8 sm:pt-8">
          <div className="mx-auto max-w-prose">
            <PageHeader
              title="Assistant"
              actions={
                // "New", not "New conversation": PageHeader gives the title a 240px basis, and with the
                // longer label the button fell onto a row of its own at 375. The panel says the same.
                <Button
                  variant="ghost"
                  onClick={convo.clear}
                  disabled={convo.entries.length === 0}
                  aria-label="Start a new conversation"
                >
                  New
                </Button>
              }
            />
          </div>
        </div>
        {/* Anchored to the foot, next to the box, the way every chat reads: a short conversation sits
            just above where the person types rather than under the title with a gap below it. */}
        <div data-testid="messages" className="flex flex-1 flex-col justify-end px-4 py-6 sm:px-8">
          <div className="mx-auto w-full max-w-prose">
            <Messages convo={convo} />
          </div>
        </div>
        {/* The box rides along at the foot of the window, so Send is always on screen. Opaque and
            sunken, the band FocusScreen uses for its sticky header, because what scrolls under a
            sticky band must not show through it. */}
        <div className="sticky bottom-0 z-10 border-t border-hairline bg-sunken px-4 py-4 sm:px-8">
          <div className="mx-auto max-w-prose">
            <Composer convo={convo} />
          </div>
        </div>
      </main>
    </div>
  );
}

// ---------------------------------------------------------------------------------------------
// Option B — a panel that opens from any page.
// ---------------------------------------------------------------------------------------------

/** Enough of Today to show the panel over a real page, not a blank one. */
function SampleToday() {
  return (
    <div className="mx-auto grid max-w-content gap-6 p-4 sm:p-8">
      <PageHeader title="Today" subtitle="Monday, 5 October · Dasami · Padmanabha masa" />
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatTile label="Servings today" value="214" icon="bowl" note="Breakfast 48 · Lunch 118 · Dinner 48" />
        <StatTile label="Items below reorder level" value={5} tone="warning" icon="package" note="Order these before they run out" />
        <StatTile label="Working today" value="5 staff · 3 volunteers" icon="users" note="Lunch 5 of 6" />
        <StatTile label="Cost of materials" value="₹2,140" icon="receipt" note="3 meals from the plan" />
      </div>
      <Card title="Meals planned for today" meta="In the order they are due">
        <p className="text-ink-secondary">Breakfast at 07:30, Lunch at 12:00, Dinner at 19:30.</p>
      </Card>
    </div>
  );
}

/**
 * The panel. From 1024 up it sits on the right edge, 448px wide (`max-w-md`), over the page and
 * without a scrim, so the page beside it can still be read and used. Below 1024 it takes the
 * whole screen: a 448px panel on a 375 phone would leave nothing beside it worth keeping.
 */
function OptionB({
  convo,
  bar,
  startOpen,
}: {
  convo: ReturnType<typeof useConversation>;
  bar: ReactNode;
  startOpen: boolean;
}) {
  const [open, setOpen] = useState(startOpen);
  const scroller = useStickToBottom([convo.entries, open]);
  return (
    <div className="flex min-h-screen">
      <MockSidebar withAssistant={false} activeHref="/today" />
      <main className="min-w-0 flex-1">
        {bar}
        <SampleToday />
      </main>

      {!open && (
        <div className="fixed bottom-4 right-4 z-30 sm:bottom-8 sm:right-8">
          <Button variant="ghost" icon="message-chatbot" onClick={() => setOpen(true)} className="shadow-overlay">
            Ask the assistant
          </Button>
        </div>
      )}

      {open && (
        <aside
          aria-label="Assistant"
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
          <div ref={scroller} data-testid="messages" className="min-h-0 flex-1 overflow-y-auto px-4 py-4">
            <div className="flex min-h-full flex-col justify-end">
              <Messages convo={convo} compact />
            </div>
          </div>
          <div className="border-t border-hairline p-4">
            <Composer convo={convo} />
          </div>
        </aside>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------------------------
// The mock bar (not part of either option) and the page.
// ---------------------------------------------------------------------------------------------

type Opt = "A" | "B";

function readParam<T extends string>(name: string, allowed: readonly T[], fallback: T): T {
  if (typeof window === "undefined") return fallback;
  const v = new URLSearchParams(window.location.search).get(name);
  return allowed.includes(v as T) ? (v as T) : fallback;
}

export default function DevAssistant() {
  const [ready, setReady] = useState(false);
  const [opt, setOpt] = useState<Opt>("A");
  const [scene, setScene] = useState<Scene>("empty");
  const [card, setCardAll] = useState<CardState>("pending");
  const [showBar, setShowBar] = useState(true);
  const [startOpen, setStartOpen] = useState(false);

  // Read once, in the browser, so a screenshot can be taken of any state without the mock bar.
  useEffect(() => {
    setOpt(readParam("opt", ["A", "B"] as const, "A"));
    setScene(readParam("scene", SCENES.map((s) => s.value), "empty"));
    setCardAll(readParam("card", ["pending", "confirmed", "rejected", "expired"] as const, "pending"));
    setShowBar(readParam("bar", ["0", "1"] as const, "1") === "1");
    setStartOpen(readParam("open", ["0", "1"] as const, "0") === "1");
    setReady(true);
  }, []);

  const convo = useConversation(scene, card);

  const bar = showBar ? (
    <div className="border-b border-dashed border-hairline-strong bg-sunken px-4 py-3 text-sm sm:px-8">
      <div className="flex flex-wrap items-center gap-x-6 gap-y-2">
        <span className="font-semibold text-ink">Mock only</span>
        <div className="flex items-center gap-2">
          <span className={FIELD_LABEL.replace("pl-field-inset ", "")}>Where</span>
          <SegmentedControl<Opt>
            label="Where the chat lives"
            value={opt}
            onChange={setOpt}
            options={[
              { value: "A", label: "A · Own page" },
              { value: "B", label: "B · Panel" },
            ]}
          />
        </div>
        <label className="flex items-center gap-2">
          <span className={FIELD_LABEL.replace("pl-field-inset ", "")}>Scene</span>
          <select value={scene} onChange={(e) => setScene(e.target.value as Scene)} className="min-h-9 rounded-control px-2">
            {SCENES.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </label>
        <label className="flex items-center gap-2">
          <span className={FIELD_LABEL.replace("pl-field-inset ", "")}>Draft card</span>
          <select value={card} onChange={(e) => setCardAll(e.target.value as CardState)} className="min-h-9 rounded-control px-2">
            <option value="pending">Waiting</option>
            <option value="confirmed">Confirmed</option>
            <option value="rejected">Rejected</option>
            <option value="expired">Expired</option>
          </select>
        </label>
      </div>
    </div>
  ) : null;

  if (!ready) return null;
  return opt === "A" ? (
    <OptionA convo={convo} bar={bar} />
  ) : (
    <OptionB convo={convo} bar={bar} startOpen={startOpen} />
  );
}
