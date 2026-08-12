"use client";

import { useState } from "react";
import { ErrorNotice } from "@/components/ErrorNotice";
import { api, toApiError, type ApiError, type CalendarDayView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { fullTithiName, masaName } from "@/lib/calendar-names";

/**
 * One day of the Vaishnava calendar, and — for a Temple Admin — the correction to it (E4-S3).
 *
 * <p>Two audiences in one panel. Anyone planning meals can see *why* a day is marked as it is: the
 * tithi, the month, whether it is a fasting day and which one, the festivals, sunrise. That was
 * previously invisible, so an Ekadashi warning arrived with no explanation behind it.
 *
 * <p>Underneath, only a Temple Admin sees the correction. The calendar is computed astronomically
 * and is right almost always — but "almost" is why the locked requirement asks for this: an adhika
 * masa, a Maha-Dvadashi edge case, or a local GBC ruling can put the temple a day out, and a temple
 * must never be forced to work around its own system on a fasting day. A correction always carries a
 * reason, is visible to everyone as hand-made, survives the nightly recompute, and can be reverted.
 */

/** Tithi 0..14 are Krsna paksa, 15..29 Gaura — the index implies the paksa, so the label can say it. */
const TITHI_OPTIONS = Array.from({ length: 30 }, (_, i) => ({
  value: i,
  label: fullTithiName(i, i < 15 ? 0 : 1),
}));

export function CalendarDayPanel({
  date,
  day,
  canCorrect,
  onClose,
  onChanged,
}: {
  date: string;
  day: CalendarDayView | undefined;
  canCorrect: boolean;
  onClose: () => void;
  onChanged: () => void;
}) {
  const { getToken } = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [correcting, setCorrecting] = useState(false);

  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    await run(async (token) => {
      await api.setCalendarOverride(
        date,
        {
          isEkadashi: f.get("isEkadashi") === "on",
          ekadashiName: String(f.get("ekadashiName") ?? "").trim() || null,
          tithi: Number(f.get("tithi")),
          festivalNote: String(f.get("festivalNote") ?? "").trim() || null,
          reason: String(f.get("reason") ?? "").trim(),
        },
        token
      );
    }, "We couldn't correct that date.");
  }

  async function revert() {
    await run(
      (token) => api.revertCalendarOverride(date, token),
      "We couldn't undo that correction."
    );
  }

  async function run(fn: (token: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    setError(null);
    try {
      await fn(await getToken());
      setCorrecting(false);
      onChanged();
    } catch (e) {
      setError(toApiError(e, failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="mt-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="day-heading">
      <div className="flex items-center justify-between gap-4">
        <h2 id="day-heading" className="text-lg">
          {new Date(`${date}T00:00:00`).toLocaleDateString(undefined, {
            weekday: "long",
            day: "numeric",
            month: "long",
            year: "numeric",
          })}
        </h2>
        <button type="button" onClick={onClose} className="text-sm text-ink-secondary hover:underline">
          Close
        </button>
      </div>

      {error && (
        <div className="mt-4">
          <ErrorNotice error={error} />
        </div>
      )}

      {!day ? (
        <p className="mt-4 text-sm text-ink-secondary">
          This date hasn&rsquo;t been calculated yet, so there is nothing to show or correct. The
          calendar is built for about eighteen months ahead.
        </p>
      ) : (
        <>
          <dl className="mt-4 grid grid-cols-2 gap-x-8 gap-y-3 text-sm sm:grid-cols-3">
            <Fact label="Tithi" value={fullTithiName(day.tithi, day.paksa)} />
            <Fact label="Month" value={masaName(day.masa)} />
            <Fact label="Gaurabda year" value={day.gaurabdaYear ? String(day.gaurabdaYear) : "—"} />
            <Fact
              label="Ekadashi"
              value={day.isEkadashi ? day.ekadashiName || "Yes — fasting day" : "No"}
            />
            <Fact label="Fast" value={day.fastType || "—"} />
            <Fact label="Maha-Dvadashi" value={day.mahadvadashi || "—"} />
            <Fact label="Sunrise" value={day.sunrise ?? "—"} />
            <Fact label="Sunset" value={day.sunset ?? "—"} />
            <Fact
              label="Festivals"
              value={day.festivals.length ? day.festivals.map((f) => f.text).join(", ") : "—"}
            />
          </dl>

          {day.overridden && (
            <div className="mt-4 rounded-sm bg-warning-bg px-4 py-3 text-sm text-warning">
              <p className="font-medium">This date was corrected by hand.</p>
              {day.overrideReason && <p className="mt-1">Reason: {day.overrideReason}</p>}
              {canCorrect && (
                <button
                  type="button"
                  onClick={revert}
                  disabled={busy}
                  className="mt-3 min-h-touch rounded-sm border border-warning/40 px-4 text-sm transition-colors duration-state hover:bg-warning/10 disabled:opacity-60"
                >
                  {busy ? "Undoing…" : "Undo the correction"}
                </button>
              )}
            </div>
          )}

          {canCorrect && !correcting && (
            <button
              type="button"
              onClick={() => setCorrecting(true)}
              className="mt-5 min-h-touch rounded-sm border border-hairline-strong px-5 text-sm transition-colors duration-state hover:bg-canvas"
            >
              Correct this date
            </button>
          )}

          {canCorrect && correcting && (
            <form onSubmit={save} aria-label="Correct this date" className="mt-5 space-y-4 border-t border-hairline pt-5">
              <p className="text-sm text-ink-secondary">
                The calendar is calculated from this temple&rsquo;s own location. Correct it only when
                you know it to be wrong here — a local ruling, or an edge case the calculation got
                wrong. Everyone will see that this date was corrected, and why.
              </p>

              <label className="flex items-start gap-3 text-sm">
                <input
                  type="checkbox"
                  name="isEkadashi"
                  defaultChecked={day.isEkadashi}
                  className="mt-1 h-5 w-5 rounded-sm border-hairline-strong"
                />
                <span>
                  <span className="font-medium text-ink">This is an Ekadashi fasting day</span>
                  <span className="mt-0.5 block text-ink-secondary">
                    Drives the grain-and-bean warning when meals are planned on this date.
                  </span>
                </span>
              </label>

              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Ekadashi name
                  <input
                    name="ekadashiName"
                    defaultValue={day.ekadashiName ?? ""}
                    placeholder="Pandava Nirjala"
                    className="min-h-touch rounded border border-hairline bg-canvas px-3"
                  />
                </label>

                <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                  Tithi
                  <select
                    name="tithi"
                    defaultValue={day.tithi}
                    className="min-h-touch rounded border border-hairline bg-canvas px-3"
                  >
                    {TITHI_OPTIONS.map((t) => (
                      <option key={t.value} value={t.value}>
                        {t.label}
                      </option>
                    ))}
                  </select>
                </label>
              </div>

              <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                Festival note
                <input
                  name="festivalNote"
                  placeholder="Temple observes Ratha Yatra today"
                  className="min-h-touch rounded border border-hairline bg-canvas px-3"
                />
              </label>

              <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                Why are you correcting this? (required)
                <textarea
                  name="reason"
                  required
                  rows={2}
                  placeholder="Local GBC ruling — the temple observes the fast on this date."
                  className="rounded border border-hairline bg-canvas px-3 py-2"
                />
              </label>

              <div className="flex items-center gap-3">
                <button
                  type="submit"
                  disabled={busy}
                  className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60"
                >
                  {busy ? "Saving…" : "Save correction"}
                </button>
                <button
                  type="button"
                  onClick={() => setCorrecting(false)}
                  className="min-h-touch rounded px-3 text-sm text-ink-secondary hover:underline"
                >
                  Cancel
                </button>
              </div>
            </form>
          )}
        </>
      )}
    </section>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-ink-secondary">{label}</dt>
      <dd className="mt-0.5">{value}</dd>
    </div>
  );
}
