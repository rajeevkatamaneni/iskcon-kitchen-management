"use client";

import Link from "next/link";
import { useCallback, useMemo, useState } from "react";
import { Badge } from "@/components/ds/Badge";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { Card } from "@/components/ds/Card";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { PageHeader } from "@/components/ds/PageHeader";
import { Screen } from "@/components/ds/Screen";
import { SegmentedControl } from "@/components/ds/SegmentedControl";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Sidebar } from "@/components/Sidebar";
import { api, type CalendarDayView } from "@/lib/api";
import { dayLabel, masaName } from "@/lib/calendar-names";
import { hhmm, longDate, todayIso } from "@/lib/format";
import { dayEvents, dayKind, kitchenNote, type DayEvent, type DayKind } from "@/lib/vaishnava-day";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * The Vaishnava calendar (E4-S9).
 *
 * <p>The temple's own calendar, read on its own terms: tithi, naksatra, masa, sunrise and sunset,
 * the fasts and the feasts. The planner answers "what are we cooking"; this answers "what day is
 * it", which in a temple kitchen is the question that comes first.
 *
 * <p>Ported from the ISKCON Kitchen Design System's `CalendarScreen`: month, week and year, a legend,
 * and a panel for the selected day that ends in the one thing to do about it — plan its menu.
 */
export default function CalendarPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_STAFF"]}>
      <CalendarScreen />
    </RequireRole>
  );
}

type View = "month" | "week" | "year";

const VIEWS = [
  { value: "month" as const, label: "Month" },
  { value: "week" as const, label: "Week" },
  { value: "year" as const, label: "Year" },
];

const DOW = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const MONTHS = [
  "January", "February", "March", "April", "May", "June",
  "July", "August", "September", "October", "November", "December",
];

/** Day tone by kind — the same three colours the legend names, and nothing else. */
const CELL_TONES: Record<Exclude<DayKind, null>, string> = {
  ekadasi: "bg-accent-bg",
  fast: "bg-warning-bg",
  festival: "bg-success-bg",
};

const DOT_TONES: Record<DayEvent["kind"], string> = {
  ekadasi: "bg-accent",
  fast: "bg-warning",
  festival: "bg-success",
  observance: "bg-ink-muted",
};

function CalendarScreen() {
  const today = todayIso();
  const [view, setView] = useState<View>("month");
  const [anchor, setAnchor] = useState(today);
  const [selected, setSelected] = useState(today);

  const { from, to } = useMemo(() => rangeFor(view, anchor), [view, anchor]);
  const load = useCallback((token?: string) => api.calendarRange(from, to, token), [from, to]);
  const { data, error, loading } = useAuthedQuery<CalendarDayView[]>(load);

  const byDate = useMemo(() => {
    const map = new Map<string, CalendarDayView>();
    for (const day of data ?? []) map.set(day.date, day);
    return map;
  }, [data]);

  const selectedDay = byDate.get(selected);

  return (
    <div className="flex min-h-screen bg-canvas">
      <Sidebar activeHref="/calendar" />
      <main className="flex-1">
        <Screen>
          <PageHeader
            title="Vaishnava calendar"
            subtitle={subtitle(selectedDay)}
            actions={
              <>
                <Button
                  variant="secondary"
                  onClick={() => {
                    setAnchor(today);
                    setSelected(today);
                  }}
                >
                  Today
                </Button>
                <ButtonLink href={`/planner?date=${selected}`}>Open the meal planner</ButtonLink>
              </>
            }
            tabs={
              <div className="flex flex-wrap items-center gap-4">
                <SegmentedControl
                  label="Calendar view"
                  options={VIEWS}
                  value={view}
                  onChange={setView}
                />
                <div className="flex items-center gap-2">
                  <IconButton label="Previous" icon="chevron-left" onClick={() => setAnchor(shift(view, anchor, -1))} />
                  <span className="min-w-[170px] text-center text-sm font-medium text-ink">
                    {heading(view, anchor)}
                  </span>
                  <IconButton label="Next" icon="chevron-right" onClick={() => setAnchor(shift(view, anchor, 1))} />
                </div>
                <Legend />
              </div>
            }
          />

          {error && <ErrorNotice error={error} />}
          {loading && !data && <p className="text-ink-secondary">Loading the calendar…</p>}

          {data && view === "month" && (
            <div className="grid items-start gap-4 xl:grid-cols-[1fr_340px]">
              <MonthGrid
                anchor={anchor}
                today={today}
                selected={selected}
                byDate={byDate}
                onSelect={setSelected}
              />
              <DayPanel date={selected} day={selectedDay} />
            </div>
          )}

          {data && view === "week" && <WeekStrip anchor={anchor} today={today} byDate={byDate} />}

          {data && view === "year" && (
            <YearView
              anchor={anchor}
              byDate={byDate}
              onPickMonth={(iso) => {
                setAnchor(iso);
                setView("month");
              }}
            />
          )}
        </Screen>
      </main>
    </div>
  );
}

function subtitle(day: CalendarDayView | undefined): string {
  if (!day) return "The temple's own calendar — tithi, fasts and festivals";
  const parts: string[] = [];
  if (day.gaurabdaYear != null) parts.push(`Gaurabda ${day.gaurabdaYear}`);
  parts.push(`${masaName(day.masa)} masa`);
  return parts.join(" · ");
}

function Legend() {
  const items = [
    ["bg-accent", "Ekadasi"],
    ["bg-warning", "Fasting day"],
    ["bg-success", "Festival or feast"],
    ["bg-ink-muted", "Observance"],
  ] as const;
  return (
    <div className="flex flex-wrap gap-4 text-xs text-ink-secondary">
      {items.map(([dot, label]) => (
        <span key={label} className="inline-flex items-center gap-2">
          <span className={`h-2 w-2 rounded-full ${dot}`} aria-hidden="true" />
          {label}
        </span>
      ))}
    </div>
  );
}

function IconButton({ label, icon, onClick }: { label: string; icon: string; onClick: () => void }) {
  return (
    <button
      type="button"
      aria-label={label}
      onClick={onClick}
      className="flex min-h-touch min-w-touch items-center justify-center rounded text-ink-secondary transition-colors duration-state hover:bg-sunken hover:text-ink"
    >
      <i className={`ti ti-${icon} text-lg`} aria-hidden="true" />
    </button>
  );
}

// ---- Month -----------------------------------------------------------------

function MonthGrid({
  anchor,
  today,
  selected,
  byDate,
  onSelect,
}: {
  anchor: string;
  today: string;
  selected: string;
  byDate: Map<string, CalendarDayView>;
  onSelect: (iso: string) => void;
}) {
  const cells = monthCells(anchor);
  const month = Number(anchor.slice(5, 7));

  return (
    <Card tone="canvas" padding="p-0" className="overflow-hidden">
      <div className="grid grid-cols-7 border-b border-hairline">
        {DOW.map((d) => (
          <div key={d} className="p-3 text-xs uppercase tracking-[0.06em] text-ink-muted">
            {d}
          </div>
        ))}
      </div>
      <div className="grid grid-cols-7">
        {cells.map((iso) => {
          const day = byDate.get(iso);
          const kind = dayKind(day);
          const events = dayEvents(day);
          const inMonth = Number(iso.slice(5, 7)) === month;
          const isSelected = iso === selected;

          return (
            <button
              key={iso}
              type="button"
              onClick={() => onSelect(iso)}
              aria-current={isSelected ? "date" : undefined}
              className={[
                "grid min-h-[104px] content-start gap-1 border-b border-r border-hairline p-3 text-left",
                kind ? CELL_TONES[kind] : "bg-transparent",
                isSelected ? "outline outline-2 -outline-offset-2 outline-accent" : "",
                inMonth ? "" : "opacity-40",
              ].join(" ")}
            >
              <span className="flex items-center justify-between gap-1">
                <span
                  className={[
                    "inline-flex h-6 w-6 items-center justify-center rounded-full text-sm font-medium",
                    iso === today ? "bg-ink text-ink-inverse" : "text-ink",
                  ].join(" ")}
                >
                  {Number(iso.slice(8, 10))}
                </span>
                {day && (
                  <span className="text-xs text-ink-muted">
                    {day.paksa === 1 ? "◑" : "◐"} {tithiShort(day)}
                  </span>
                )}
              </span>
              {events.slice(0, 2).map((e) => (
                <span key={e.label} className="flex items-start gap-1.5 text-xs leading-tight text-ink-secondary">
                  <span className={`mt-1 h-1.5 w-1.5 flex-none rounded-full ${DOT_TONES[e.kind]}`} aria-hidden="true" />
                  <span className="line-clamp-2">{e.label}</span>
                </span>
              ))}
              {events.length > 2 && (
                <span className="text-xs text-ink-muted">+{events.length - 2} more</span>
              )}
            </button>
          );
        })}
      </div>
    </Card>
  );
}

/** The number within the fortnight, or the name when the day is the full or new moon. */
function tithiShort(day: CalendarDayView): string {
  const within = day.tithi % 15;
  if (within === 14) return day.tithi < 15 ? "Amavasya" : "Purnima";
  return String(within + 1);
}

// ---- The selected day ------------------------------------------------------

function DayPanel({ date, day }: { date: string; day: CalendarDayView | undefined }) {
  const events = dayEvents(day);
  const note = kitchenNote(day);

  return (
    <Card
      title={longDate(date)}
      meta={day ? dayLabel(day) : "Not computed for this temple yet"}
      className="xl:sticky xl:top-6"
    >
      <div className="grid gap-4">
        {day && (
          <div className="grid grid-cols-2 gap-3">
            <Fact label="Sunrise" value={hhmm(day.sunrise)} />
            <Fact label="Sunset" value={hhmm(day.sunset)} />
          </div>
        )}

        {events.length > 0 ? (
          <div className="grid gap-3">
            {events.map((e) => (
              <div key={e.label} className={`grid gap-0.5 border-l-2 pl-3 ${BORDER_TONES[e.kind]}`}>
                <span className="text-sm font-medium text-ink">{e.label}</span>
                {e.note && <span className="text-xs text-ink-secondary">{e.note}</span>}
              </div>
            ))}
          </div>
        ) : (
          <p className="text-ink-secondary">
            {day
              ? "An ordinary day. Regular offerings and the standard menu."
              : "This temple's calendar has not been computed this far ahead."}
          </p>
        )}

        {day?.overridden && (
          <Badge tone="warning" shape="pill">
            Corrected by hand{day.overrideReason ? ` — ${day.overrideReason}` : ""}
          </Badge>
        )}

        {note && <InlineNotice tone={note.tone}>{note.text}</InlineNotice>}

        <ButtonLink href={`/planner?date=${date}`} variant="secondary" fullWidth>
          Plan this day&rsquo;s menu
        </ButtonLink>
      </div>
    </Card>
  );
}

const BORDER_TONES: Record<DayEvent["kind"], string> = {
  ekadasi: "border-accent",
  fast: "border-warning",
  festival: "border-success",
  observance: "border-hairline-strong",
};

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <span className="grid gap-0.5">
      <span className="text-xs text-ink-muted">{label}</span>
      <span className="text-sm font-medium tabular-nums text-ink">{value}</span>
    </span>
  );
}

// ---- Week ------------------------------------------------------------------

function WeekStrip({
  anchor,
  today,
  byDate,
}: {
  anchor: string;
  today: string;
  byDate: Map<string, CalendarDayView>;
}) {
  const days = weekDays(anchor);
  return (
    <div className="grid gap-3 md:grid-cols-4 xl:grid-cols-7">
      {days.map((iso) => {
        const day = byDate.get(iso);
        const kind = dayKind(day);
        const note = kitchenNote(day);
        return (
          <Card
            key={iso}
            tone="canvas"
            padding="p-4"
            className={[
              "grid content-start gap-3",
              kind ? CELL_TONES[kind] : "",
              iso === today ? "outline outline-2 -outline-offset-2 outline-ink" : "",
            ].join(" ")}
          >
            <span className="grid">
              <span className="text-xs uppercase tracking-[0.06em] text-ink-muted">
                {DOW[new Date(`${iso}T00:00:00`).getDay()]}
              </span>
              <span className="text-lg font-medium text-ink">{Number(iso.slice(8, 10))}</span>
            </span>
            {day && <span className="text-xs text-ink-secondary">{dayLabel(day)}</span>}
            {day && (
              <span className="text-xs tabular-nums text-ink-muted">
                ↑ {hhmm(day.sunrise)} · ↓ {hhmm(day.sunset)}
              </span>
            )}
            {dayEvents(day).map((e) => (
              <span key={e.label} className={`grid gap-0.5 border-l-2 pl-2 ${BORDER_TONES[e.kind]}`}>
                <span className="text-xs text-ink">{e.label}</span>
              </span>
            ))}
            {note && (
              <span className="border-t border-hairline pt-2 text-xs text-ink-secondary">
                {note.text}
              </span>
            )}
          </Card>
        );
      })}
    </div>
  );
}

// ---- Year ------------------------------------------------------------------

function YearView({
  anchor,
  byDate,
  onPickMonth,
}: {
  anchor: string;
  byDate: Map<string, CalendarDayView>;
  onPickMonth: (iso: string) => void;
}) {
  const year = Number(anchor.slice(0, 4));
  const marked = [...byDate.values()]
    .filter((d) => dayKind(d) !== null)
    .sort((a, b) => a.date.localeCompare(b.date));

  return (
    <div className="grid items-start gap-4 xl:grid-cols-[1fr_320px]">
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {MONTHS.map((name, index) => {
          const first = `${year}-${String(index + 1).padStart(2, "0")}-01`;
          return (
            <Card key={name} tone="canvas" padding="p-4">
              <button
                type="button"
                onClick={() => onPickMonth(first)}
                className="grid w-full gap-3 text-left"
              >
                <span className="text-sm font-medium text-ink">{name}</span>
                <span className="grid grid-cols-7 gap-0.5">
                  {DOW.map((d) => (
                    <span key={d} className="text-center text-[10px] text-ink-muted">
                      {d[0]}
                    </span>
                  ))}
                  {monthCells(first).map((iso) => {
                    const inMonth = Number(iso.slice(5, 7)) === index + 1;
                    const kind = dayKind(byDate.get(iso));
                    return (
                      <span
                        key={iso}
                        className={[
                          "h-[18px] rounded-sm text-center text-[11px] leading-[18px]",
                          !inMonth ? "text-transparent" : kind ? "font-semibold text-ink" : "text-ink-secondary",
                          inMonth && kind ? CELL_TONES[kind] : "",
                        ].join(" ")}
                      >
                        {Number(iso.slice(8, 10))}
                      </span>
                    );
                  })}
                </span>
              </button>
            </Card>
          );
        })}
      </div>

      <Card
        title="Festivals and fasts"
        meta={`${marked.length} marked days in ${year}`}
        className="xl:sticky xl:top-6"
      >
        <div className="grid max-h-[560px] gap-3 overflow-auto">
          {marked.map((day) => {
            const first = dayEvents(day)[0];
            return (
              <span
                key={day.date}
                className={`grid gap-0.5 border-l-2 pl-3 ${BORDER_TONES[first?.kind ?? "observance"]}`}
              >
                <span className="text-xs text-ink-muted">
                  {Number(day.date.slice(8, 10))} {MONTHS[Number(day.date.slice(5, 7)) - 1].slice(0, 3)}
                </span>
                <span className="text-sm font-medium text-ink">{first?.label ?? "Marked day"}</span>
              </span>
            );
          })}
          {marked.length === 0 && (
            <p className="text-sm text-ink-secondary">
              Nothing computed for this year yet. The calendar is built a year ahead in the
              background.
            </p>
          )}
        </div>
      </Card>
    </div>
  );
}

// ---- Dates -----------------------------------------------------------------
//
// Plain "YYYY-MM-DD" strings throughout, parsed at local midnight. Dates here are calendar days,
// not instants: a Date built from "2026-08-15" alone is UTC midnight, which is the previous day in
// the Americas — the exact mistake that had Today and the planner disagreeing (INT-12).

function parse(iso: string): Date {
  return new Date(`${iso}T00:00:00`);
}

function iso(d: Date): string {
  return [d.getFullYear(), String(d.getMonth() + 1).padStart(2, "0"), String(d.getDate()).padStart(2, "0")].join("-");
}

function addDays(isoDate: string, days: number): string {
  const d = parse(isoDate);
  d.setDate(d.getDate() + days);
  return iso(d);
}

function startOfWeek(isoDate: string): string {
  const d = parse(isoDate);
  return addDays(isoDate, -d.getDay());
}

/** Six weeks from the Sunday on or before the first — the grid every month calendar draws. */
function monthCells(anchor: string): string[] {
  const first = `${anchor.slice(0, 7)}-01`;
  const start = startOfWeek(first);
  return Array.from({ length: 42 }, (_, i) => addDays(start, i));
}

function weekDays(anchor: string): string[] {
  const start = startOfWeek(anchor);
  return Array.from({ length: 7 }, (_, i) => addDays(start, i));
}

function rangeFor(view: View, anchor: string): { from: string; to: string } {
  if (view === "week") {
    const start = startOfWeek(anchor);
    return { from: start, to: addDays(start, 6) };
  }
  if (view === "year") {
    const year = anchor.slice(0, 4);
    return { from: `${year}-01-01`, to: `${year}-12-31` };
  }
  const cells = monthCells(anchor);
  return { from: cells[0], to: cells[cells.length - 1] };
}

function shift(view: View, anchor: string, by: number): string {
  if (view === "week") return addDays(anchor, by * 7);
  const d = parse(anchor);
  if (view === "year") {
    d.setFullYear(d.getFullYear() + by);
  } else {
    d.setDate(1);
    d.setMonth(d.getMonth() + by);
  }
  return iso(d);
}

function heading(view: View, anchor: string): string {
  if (view === "year") return anchor.slice(0, 4);
  if (view === "week") {
    const start = startOfWeek(anchor);
    const end = addDays(start, 6);
    const m = (s: string) => MONTHS[Number(s.slice(5, 7)) - 1].slice(0, 3);
    return `${Number(start.slice(8, 10))} ${m(start)} – ${Number(end.slice(8, 10))} ${m(end)} ${end.slice(0, 4)}`;
  }
  return `${MONTHS[Number(anchor.slice(5, 7)) - 1]} ${anchor.slice(0, 4)}`;
}
