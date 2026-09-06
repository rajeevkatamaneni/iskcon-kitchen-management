"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Badge } from "@/components/ds/Badge";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FieldRow } from "@/components/ds/FieldRow";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { InfoHint } from "@/components/ds/InfoHint";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { SegmentedControl } from "@/components/ds/SegmentedControl";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Loading } from "@/components/Loading";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type ReusePlanPreview } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { longDate, todayIso } from "@/lib/format";

/**
 * Reusing a plan somebody already made (2026-09-05).
 *
 * <p><strong>Why this exists.</strong> "Duplicate last week" could do one thing — last week onto
 * this week. Rajeev refused to build a fifteen-day planner for the temple that asked for one, on the
 * grounds that this is an application for a thousand temples buying on every cycle there is; the
 * copy tool was week-shaped in exactly the way he refused to let the planner be. And copying is not
 * a corner of the product: <em>"copying a previous weeks meal and making adjustments seems to be
 * popular across the board... more than often thigs stay steady state with slight modifications."</em>
 * If that is what everybody does, the copier is the product.
 *
 * <p><strong>Why it is a screen and not a button.</strong> This writes across a fortnight of plan in
 * one press. The old button did it and then reported what it had done, so a planner learned that
 * three meals fell foul of a fast, or that two days were left alone, afterwards. Everything here is
 * shown before anything is written — which is also where the fasting care Rajeev asked for lives.
 *
 * <p><strong>Surgical, not brute force.</strong> Main meals are offered; a festival feast never is,
 * because its occasion comes from the calendar on the day it is cooked and the same dishes on an
 * ordinary Wednesday are a large lunch wearing the wrong name. Events are offered by name with the
 * count of how often they occurred, because nothing in the schema records whether an event repeats —
 * so the screen shows the evidence and a person decides.
 */
export default function ReusePlanPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      {/* The window is read from the query string, and that needs a boundary. */}
      <Suspense>
        <ReusePlanScreen />
      </Suspense>
    </RequireRole>
  );
}

type Mode = "range" | "single";

/** A page module may export only `default` and Next's own config names, so this stays private. */
function ReusePlanScreen() {
  const { getToken } = useAuth();
  const router = useRouter();
  const params = useSearchParams();
  const tokenRef = useRef(getToken);
  tokenRef.current = getToken;

  const [mode, setMode] = useState<Mode>(params.get("days") === "1" ? "single" : "range");

  const [sourceStart, setSourceStart] = useState(
    asDate(params.get("from")) ?? addDays(todayIso(), -7)
  );
  const [days, setDays] = useState(Number(params.get("days")) || 7);

  /**
   * Where the copy lands, never earlier than the day after the source window ends.
   *
   * <p>Anything earlier overlaps the window being read, which is a copy of a stretch onto itself —
   * the overlapping days would be found already planned and left alone, so it would quietly do
   * nothing for part of the range. Moving the landing day whenever the window moves keeps the common
   * case — "the cycle that just ended, onto the next one" — a single number and a button.
   */
  const [targetStart, setTargetStart] = useState(
    asDate(params.get("to")) ?? addDays(asDate(params.get("from")) ?? addDays(todayIso(), -7),
      Number(params.get("days")) || 7)
  );

  /**
   * What has been ticked. Null means "nothing has been chosen yet", which is not the same as
   * "nothing is chosen" — until the first preview lands there is no list to choose from, and
   * sending an empty one would ask the server for a copy of nothing.
   */
  const [kinds, setKinds] = useState<string[] | null>(null);
  const [events, setEvents] = useState<string[] | null>(null);

  const [preview, setPreview] = useState<ReusePlanPreview | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ApiError | null>(null);
  const [saving, setSaving] = useState(false);

  const oneDay = mode === "single";
  const window = oneDay ? 1 : days;
  /**
   * The first day a copy may land on, and it is a floor rather than a suggestion.
   *
   * <p>Two things are impossible, and both are refused rather than explained afterwards. Landing
   * inside the window being read copies a stretch onto itself: the overlap is found already planned
   * and left alone, so part of the range silently does nothing. And landing on a day that has gone
   * plans meals nobody can cook — the planner already refuses to add to a past day, and this is the
   * same rule reaching the copier.
   */
  const earliestLanding = maxDate(addDays(sourceStart, window), todayIso());

  /**
   * Moves the window, and the landing day with it.
   *
   * <p>Rajeev, 2026-09-05: the landing day "must be automatically moved to the earliest possible date
   * to land on". That is the day after the source window ends — anything earlier overlaps the days
   * being read, and the overlap would be found already planned and silently left alone, so part of
   * the range would quietly do nothing.
   */
  function moveWindow(next: { from?: string; days?: number; single?: boolean }) {
    const from = next.from ?? sourceStart;
    const span = next.single !== undefined
      ? (next.single ? 1 : days)
      : (next.days ?? window);
    setSourceStart(from);
    if (next.days !== undefined) setDays(next.days);
    setTargetStart(maxDate(addDays(from, span), todayIso()));
    // The list of things to tick belongs to a window, so changing the window forgets it.
    setKinds(null);
    setEvents(null);
  }

  // Asked again on every change, because the preview and the commit run the same walk and a preview
  // computed a second way is a preview that can disagree with the thing it previews.
  useEffect(() => {
    let live = true;
    setLoading(true);
    (async () => {
      try {
        const got = await api.previewReuse(
          { sourceStart, days: window, targetStart, mealKinds: kinds, eventNames: events },
          await tokenRef.current()
        );
        if (!live) return;
        setPreview(got);
        setError(null);
        // The first answer settles what there is to tick: every main meal found, and no event.
        if (kinds === null) setKinds(got.kinds.map((k) => k.mealKind));
        if (events === null) setEvents([]);
      } catch (e) {
        if (live) setError(toApiError(e, "We couldn’t work out what that would copy."));
      } finally {
        if (live) setLoading(false);
      }
    })();
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sourceStart, window, targetStart, kinds, events]);

  const sourceEnd = addDays(sourceStart, window - 1);
  const targetEnd = addDays(targetStart, window - 1);
  const totals = preview?.totals;

  async function commit() {
    setSaving(true);
    try {
      const result = await api.reusePlan(
        { sourceStart, days: window, targetStart, mealKinds: kinds, eventNames: events },
        await tokenRef.current()
      );
      router.push(`/planner?view=day&date=${targetStart}&reused=${result.copied}`);
    } catch (e) {
      setError(toApiError(e, "We couldn’t copy those meals."));
      setSaving(false);
    }
  }

  return (
    <FocusScreen
      task="Reuse a plan"
      who={
        oneDay
          ? `Copying ${longDate(sourceStart)} onto ${longDate(targetStart)}`
          : `Copying ${span(sourceStart, sourceEnd)} onto ${span(targetStart, targetEnd)}`
      }
      activeHref="/planner/reuse"
      actions={
        <>
          <ButtonLink href={`/planner?view=day&date=${targetStart}`} variant="secondary">
            Cancel
          </ButtonLink>
          <Button
            type="button"
            disabled={saving || !totals || totals.meals === 0}
            busy={saving}
            onClick={commit}
          >
            {saving
              ? "Copying…"
              : !totals || totals.meals === 0
                ? "Nothing to copy"
                : `Copy ${totals.meals} ${totals.meals === 1 ? "meal" : "meals"}`}
          </Button>
        </>
      }
    >
      {error && <ErrorNotice error={error} />}

      {/* 1 — the window */}
      <Step n={1} title="What to reuse">
        <p className={WHY}>
          A stretch of days for the routine — whatever your buying cycle is. One day for a festival
          you want to run again, where the date moves with the Vaishnava calendar and no arithmetic
          can find it for you.
        </p>

        <SegmentedControl
          label="What to reuse"
          options={[
            { value: "range" as const, label: "A stretch of days" },
            { value: "single" as const, label: "One day" },
          ]}
          value={mode}
          onChange={(next) => {
            setMode(next);
            moveWindow({ single: next === "single" });
          }}
        />

        {/* Starting from, then how many, then where it lands: the order somebody says it aloud, and
            the order the three answers depend on each other in (Rajeev, 2026-09-05).

            FieldRow rather than a flex row with `items-end`, which is the mistake item 23 of the
            build brief is about and which the design-system test catches: align-items lines up the
            outer edges of each field, and the outer edges are not what anybody is looking at. The
            boxes are, and only shared row tracks line those up. */}
        <FieldRow className="mt-5">
          <Field label={oneDay ? "Copy the plan for" : "Starting from"}>
            {(id) => (
              <input
                id={id}
                type="date"
                value={sourceStart}
                onChange={(e) => moveWindow({ from: e.target.value })}
                className={BOX}
              />
            )}
          </Field>

          {!oneDay && (
            <Field label="How many days" hint="Up to 62 — two months covers every buying cycle anybody runs">
              {(id) => (
                <input
                  id={id}
                  type="number"
                  min={1}
                  max={62}
                  value={days}
                  onChange={(e) =>
                    moveWindow({ days: Math.min(62, Math.max(1, Number(e.target.value) || 1)) })
                  }
                  className={`${BOX} w-24 tabular-nums`}
                />
              )}
            </Field>
          )}

          <Field
            label={oneDay ? "Onto" : "Landing on"}
            hint="Moves with the window, to the first day a copy can land on — after the days it is reading, and not in the past"
          >
            {(id) => (
              <input
                id={id}
                type="date"
                value={targetStart}
                // Not merely a default: landing inside the source window would copy a stretch onto
                // itself, and those days would be found already planned and quietly skipped.
                min={earliestLanding}
                // `min` stops the picker offering an earlier day; the clamp catches a date typed
                // straight into the field, which `min` alone does not.
                onChange={(e) => setTargetStart(maxDate(e.target.value, earliestLanding))}
                className={BOX}
              />
            )}
          </Field>
        </FieldRow>

        <p className="mt-4 rounded-lg bg-accent-bg px-4 py-3 text-sm font-semibold tabular-nums text-accent-text">
          {oneDay
            ? `${longDate(sourceStart)} → ${longDate(targetStart)}`
            : `${span(sourceStart, sourceEnd)} → ${span(targetStart, targetEnd)}`}
          <span className="font-normal text-ink-secondary">
            {" · "}
            {window} {window === 1 ? "day" : "days"} · nothing outside this window is touched
          </span>
        </p>
      </Step>

      {loading && !preview && <Loading label="Reading those days…" />}

      {preview?.sourceWasEmpty && (
        <InlineNotice tone="info">
          Nothing is planned in those days, so there is nothing to copy. Pick another stretch.
        </InlineNotice>
      )}

      {preview && !preview.sourceWasEmpty && (
        <>
          {/* 2 — what to bring */}
          <Step n={2} title="What to bring">
            <p className={WHY}>
              Everything below was found in {oneDay ? "that day" : "those days"}. Tick what repeats;
              leave what happened once. Nothing is guessed — the counts are how many times each one
              actually occurred.
            </p>

            {preview.kinds.length > 0 && (
              <>
                <h3 className={PICK_HEAD}>Main meals</h3>
                <ul className={PICK}>
                  {preview.kinds.map((k) => (
                    <Tick
                      key={k.mealKind}
                      on={(kinds ?? []).includes(k.mealKind)}
                      onToggle={() => setKinds(toggle(kinds ?? [], k.mealKind))}
                      label={k.mealKind}
                      name={<b className="font-semibold">{k.mealKind}</b>}
                      count={`${k.dayCount} ${k.dayCount === 1 ? "day" : "days"}`}
                    />
                  ))}
                </ul>
              </>
            )}

            {preview.events.length > 0 && (
              <>
                <h3 className={PICK_HEAD}>Events found in these days</h3>
                <ul className={PICK}>
                  {preview.events.map((e) => (
                    <Tick
                      key={e.eventName}
                      on={(events ?? []).includes(e.eventName)}
                      onToggle={() => setEvents(toggle(events ?? [], e.eventName))}
                      label={e.eventName}
                      name={
                        <>
                          <b className="font-semibold">{e.eventName}</b>
                          {e.outside && (
                            <span className="text-ink-secondary"> — leaves the temple</span>
                          )}
                        </>
                      }
                      badge={
                        <Badge tone={e.occurrences === 1 ? "warning" : "neutral"}>
                          {e.occurrences === 1 ? "happened once" : `happened ${e.occurrences}×`}
                        </Badge>
                      }
                      count={e.lastSeen ? shortDate(e.lastSeen) : ""}
                    />
                  ))}
                </ul>
              </>
            )}

            {preview.excluded.length > 0 && (
              <>
                <h3 className={PICK_HEAD}>Not offered</h3>
                <ul className={PICK}>
                  {preview.excluded.map((x, i) => (
                    <li key={`${x.label}-${i}`} className={`${PICK_ROW} text-ink-muted`}>
                      <span className="h-[19px] w-[19px] flex-none rounded-sm border-[1.5px] border-hairline-strong opacity-40" />
                      <span className="flex-1 text-sm">
                        <b className="font-semibold">{x.label}</b>
                      </span>
                      <Badge tone="neutral">belongs to its date</Badge>
                      <span className="whitespace-nowrap text-sm text-ink-muted">
                        {shortDate(x.on)}
                      </span>
                    </li>
                  ))}
                </ul>
                <p className={`${WHY} mt-3`}>{preview.excluded[0].reason}</p>
              </>
            )}
          </Step>

          {/* 3 — head counts */}
          {preview.headCounts.length > 0 && (
            <Step n={3} title="How many people">
              <p className={WHY}>
                Carried from the source, because these are numbers somebody chose rather than numbers
                the application invented. They are shown here so a stale one is seen before it is
                copied onto a fortnight of meals.
              </p>
              <div className="flex flex-wrap gap-4">
                {preview.headCounts.map((h) => (
                  <div
                    key={h.mealKind}
                    className="grid gap-1 rounded-card border border-hairline px-4 py-3"
                  >
                    <span className="text-xs font-semibold uppercase tracking-eyebrow text-ink-secondary">
                      {h.mealKind}
                    </span>
                    <span className="text-xl font-bold tabular-nums text-ink">
                      {(h.adults ?? 0) + (h.children ?? 0) + (h.seniors ?? 0)}
                    </span>
                    <span className="text-xs text-ink-muted">
                      {[
                        h.adults ? `${h.adults} adults` : null,
                        h.children ? `${h.children} children` : null,
                        h.seniors ? `${h.seniors} seniors` : null,
                      ]
                        .filter(Boolean)
                        .join(" · ")}
                    </span>
                  </div>
                ))}
              </div>
              <div className="mt-4 max-w-[78ch] rounded-card border border-hairline-strong border-l-4 border-l-ink bg-sunken px-4 py-3 text-sm text-ink-secondary">
                <b className="text-ink">
                  Every preparation scales from these, and so does the shopping list.
                </b>{" "}
                Last cycle’s figures are usually right — but if the hall is busier this time, correct
                the copied meals on the day rather than discovering it in a purchase order.
              </div>
            </Step>
          )}

          {/* 4 — the preview */}
          <Step n={4} title="What will happen">
            <p className={WHY}>
              Read this before you press the button. Nothing here is written until you do, and
              nothing that already exists is ever replaced.
            </p>

            <div className="mb-5 flex flex-wrap gap-3">
              <Tally value={totals?.meals ?? 0} label="meals created" />
              <Tally value={totals?.daysWritten ?? 0} label="days written to" />
              <Tally value={totals?.daysLeftAlone ?? 0} label="days left alone" tone="warning" />
              <Tally value={totals?.notCopied ?? 0} label="not copied" tone="danger" />
            </div>

            <div className={WRAP}>
              <table className={TABLE}>
                <thead className={THEAD}>
                  <tr>
                    <th className={TH_DATE}>Target day</th>
                    <th className={TH_TEXT}>What lands on it</th>
                    <th className={TH_TEXT}>Note</th>
                  </tr>
                </thead>
                <tbody>
                  {preview.days.map((d) => (
                    <tr key={d.targetDate} className={d.alreadyPlanned ? "bg-sunken/50" : TR}>
                      <td className={TD_DATE}>
                        <span className="font-semibold">{shortDate(d.targetDate)}</span>
                        <span className="block text-xs text-ink-muted">
                          from {shortDate(d.sourceDate)}
                        </span>
                      </td>
                      <td className={TD_TEXT}>
                        {d.alreadyPlanned ? (
                          <Badge tone="warning">Left alone</Badge>
                        ) : (
                          <span className="flex flex-wrap gap-1.5">
                            {d.meals.map((m, i) => (
                              <span
                                key={`${m.mealKind}-${m.recipeName}-${i}`}
                                className={
                                  m.copied
                                    ? "rounded bg-sunken px-2 py-0.5 text-xs text-ink"
                                    : "rounded border border-dashed border-hairline-strong px-2 py-0.5 text-xs text-ink-muted line-through"
                                }
                              >
                                {m.eventName ?? m.mealKind}
                              </span>
                            ))}
                          </span>
                        )}
                      </td>
                      <td className={`${TD_TEXT} text-xs text-ink-secondary`}>
                        {d.alreadyPlanned ? (
                          "This day already has meals planned. Nothing is overwritten."
                        ) : (
                          <>
                            {d.fastName && (
                              <Badge tone="danger">{d.fastName}</Badge>
                            )}{" "}
                            {d.meals
                              .filter((m) => !m.copied)
                              .map((m) => m.skippedReason)
                              .join(" ")}
                          </>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="mt-5 max-w-[78ch] rounded-card border border-hairline-strong border-l-4 border-l-ink bg-sunken px-4 py-3 text-sm text-ink-secondary">
              <b className="text-ink">Copies, not a series.</b> Each meal that lands is a plan in its
              own right — edit one and the others are untouched, cancel one and nothing asks about
              the rest.
            </div>
          </Step>
        </>
      )}
    </FocusScreen>
  );
}

// ---- the small pieces --------------------------------------------------

const WHY = "mb-5 max-w-[68ch] text-sm text-ink-secondary";
const BOX = "min-h-touch rounded-control border border-hairline px-3 text-ink";
const PICK_HEAD = "mb-2 mt-5 text-xs font-semibold uppercase tracking-eyebrow text-ink-muted";
const PICK = "grid border-t border-hairline";
const PICK_ROW = "flex items-center gap-3 border-b border-hairline py-3";

/** A numbered section, the shape the meal composer already uses for its four. */
function Step({ n, title, children }: { n: number; title: string; children: React.ReactNode }) {
  return (
    <section className="card border border-hairline p-6">
      <header className="mb-5 flex items-center gap-3">
        <span className="grid h-7 w-7 flex-none place-items-center rounded-full bg-ink text-xs font-bold text-ink-inverse">
          {n}
        </span>
        <h2 className="text-lg font-semibold text-ink">{title}</h2>
      </header>
      {children}
    </section>
  );
}

function Field({
  label, hint, children,
}: {
  label: string;
  hint?: string;
  children: (id: string) => React.ReactNode;
}) {
  const id = `f-${label.replace(/\W+/g, "-").toLowerCase()}`;
  return (
    <span className="grid gap-1.5">
      <span className="flex items-center gap-2 pl-0.5 text-sm text-ink-secondary">
        <label htmlFor={id}>{label}</label>
        {hint && <InfoHint text={hint} label={label} />}
      </span>
      {children(id)}
    </span>
  );
}

function Tick({
  on, onToggle, label, name, badge, count,
}: {
  on: boolean;
  onToggle: () => void;
  /**
   * What the control is called, in plain text.
   *
   * <p>Separate from {@code name} because the rendered name carries markup — a bold kind, a grey
   * aside — and a checkbox whose label is a node has no accessible name at all. It was unlabelled
   * until its own test could not find it, which is the useful half of writing the test.
   */
  label: string;
  name: React.ReactNode;
  badge?: React.ReactNode;
  count?: string;
}) {
  return (
    <li className={PICK_ROW}>
      <input
        type="checkbox"
        checked={on}
        onChange={onToggle}
        // The composer's, exactly: `accent-accent` sets CSS accent-color, so a ticked box takes the
        // temple's palette. Without it the native control falls back to the browser's blue, which on
        // a monochrome theme is the one thing on the screen that is not the temple's colour.
        className="h-4 w-4 flex-none accent-accent"
        aria-label={label}
      />
      <span className={`flex-1 text-sm ${on ? "text-ink" : "text-ink-muted"}`}>{name}</span>
      {badge}
      {count && <span className="whitespace-nowrap text-sm text-ink-muted">{count}</span>}
    </li>
  );
}

function Tally({
  value, label, tone,
}: {
  value: number;
  label: string;
  tone?: "warning" | "danger";
}) {
  const colour =
    tone === "warning" ? "text-warning" : tone === "danger" ? "text-danger" : "text-ink";
  return (
    <div className="grid min-w-[8.25rem] gap-px rounded-card border border-hairline px-4 py-3">
      <b className={`text-xl font-bold tabular-nums ${colour}`}>{value}</b>
      <span className="text-xs text-ink-secondary">{label}</span>
    </div>
  );
}

// ---- table classes, the app's own -------------------------------------

const WRAP = "overflow-x-auto rounded-lg border border-hairline";
const TABLE = "w-full border-collapse text-sm";
const THEAD = "bg-sunken";
const TR = "border-b border-hairline";
const TH_DATE = "px-3 py-2 text-left text-xs font-bold uppercase tracking-eyebrow text-ink-secondary";
const TH_TEXT = TH_DATE;
const TD_DATE = "whitespace-nowrap border-b border-hairline px-3 py-2.5 align-top tabular-nums";
const TD_TEXT = "border-b border-hairline px-3 py-2.5 align-top";

// ---- dates -------------------------------------------------------------

/** The later of two ISO dates. They sort as text, which is the one good thing about the format. */
function maxDate(a: string, b: string): string {
  return a > b ? a : b;
}

function addDays(iso: string, days: number): string {
  const d = new Date(`${iso}T00:00:00`);
  d.setDate(d.getDate() + days);
  return [
    d.getFullYear(),
    String(d.getMonth() + 1).padStart(2, "0"),
    String(d.getDate()).padStart(2, "0"),
  ].join("-");
}

/** "1 – 15 Sept 2026", written the Indian way like every other date in the application. */
function span(from: string, to: string): string {
  return `${shortDate(from)} – ${shortDate(to)}`;
}

function shortDate(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString("en-GB", {
    weekday: "short",
    day: "numeric",
    month: "short",
  });
}

function asDate(raw: string | null): string | null {
  if (!raw || !/^\d{4}-\d{2}-\d{2}$/.test(raw)) return null;
  return Number.isNaN(new Date(`${raw}T00:00:00`).getTime()) ? null : raw;
}

/** Ticking one thing on or off, without letting the same name in twice. */
function toggle(list: string[], value: string): string[] {
  return list.includes(value) ? list.filter((v) => v !== value) : [...list, value];
}
