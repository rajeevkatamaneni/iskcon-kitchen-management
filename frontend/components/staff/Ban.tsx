"use client";

import { useState } from "react";
import { InlineNotice } from "@/components/ds/InlineNotice";
import type { BanCategoryOption, BanFinding } from "@/lib/api";

/**
 * The two ban surfaces (B9), kept out of the staff page so that the gravest thing this product lets
 * an administrator do is somewhere a person can find and read whole.
 *
 * <p>Neither of these is ever shown to the person a record is about. They are not told the reason in
 * the app at all: they lose access at the dismissal anyway, and telling somebody at the moment they
 * are fired invites retaliation, which is a real risk borne by the people this is built for. The
 * consequence shapes both components below — because the subject is not there to correct a wrong
 * entry, the raising temple's name, the ten-year life and the ability to take it back have to do the
 * whole job between them, so all three are on the screen and none of them is in small print.
 */

const FIELD = "min-h-touch rounded border border-hairline bg-canvas px-3";

/**
 * The option on the termination panel.
 *
 * <p>Unticked, always, and it has to be chosen deliberately. Most dismissals raise no record at all,
 * and a screen that made this the easy path — a default, a pre-selection, a nudge — would produce a
 * list of hundreds of people whose worst day at work follows them round the country. The wording is
 * written to slow the reader down for the same reason.
 */
export function BanOnTermination({ categories }: { categories: BanCategoryOption[] }) {
  const [recording, setRecording] = useState(false);

  return (
    <fieldset className="col-span-2 rounded border border-danger-bg px-4 py-3">
      <legend className="px-1 text-sm text-danger">Warn other temples about this person</legend>

      <label className="flex items-start gap-3 text-sm">
        <input
          type="checkbox"
          name="raiseBan"
          checked={recording}
          onChange={(e) => setRecording(e.target.checked)}
          className="mt-1"
        />
        <span>
          <span className="text-ink">Record this against them for every temple</span>
          <span className="block text-ink-secondary">
            Leave this unticked unless you would actually warn another temple about this person. Most
            dismissals do not need it.
          </span>
        </span>
      </label>

      {recording && (
        <div className="mt-4 grid gap-4">
          <InlineNotice tone="warning">
            Your temple&rsquo;s name and what you write here are shown to any temple that tries to
            hire this person, for the next ten years. They are not told about it and cannot answer
            it, so write only what you would be willing to say to them — and to the other temple —
            out loud. You can take it back at any time, and you should if you turn out to be wrong.
          </InlineNotice>

          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            What kind of thing was it?
            <select name="banCategory" required defaultValue="" className={FIELD}>
              <option value="" disabled>
                Choose one
              </option>
              {categories.map((c) => (
                <option key={c.value} value={c.value}>
                  {c.label}
                </option>
              ))}
            </select>
            <span className="text-xs text-ink-muted">
              The category is what another temple can compare. Both this and your own account of it
              are required.
            </span>
          </label>

          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            What happened, in your own words?
            <textarea name="banAccount" required rows={4} className={`${FIELD} py-2`} />
            <span className="text-xs text-ink-muted">
              This is what the other temple reads, and what they will ring you about. Facts and dates
              are worth more than adjectives.
            </span>
          </label>
        </div>
      )}
    </fieldset>
  );
}

/**
 * What the check found, shown before the hire is committed.
 *
 * <p>Two honest choices and no third one. Nothing here blocks anything — the hire is one press away
 * and taking the person on is a legitimate answer, often the right one. A screen that made
 * proceeding feel like overruling a machine would move the judgement from the administrator, who can
 * pick up the telephone, to a similarity threshold in the backend.
 */
export function BanFindings({
  findings,
  busy,
  onProceed,
  onStop,
}: {
  findings: BanFinding[];
  busy: boolean;
  onProceed: () => void;
  onStop: () => void;
}) {
  return (
    <section
      className="mb-8 rounded-lg border border-warning-bg bg-raised px-6 py-5"
      aria-labelledby="ban-findings-heading"
    >
      <h2 id="ban-findings-heading" className="text-lg">
        Another temple has recorded something about this person
      </h2>
      <p className="mt-1 max-w-prose text-sm text-ink-secondary">
        This is not a decision and it does not stop you. It is one temple&rsquo;s account, and the
        best thing to do with it is telephone them.
      </p>

      <ul className="mt-4 grid gap-4">
        {findings.map((f) => (
          <li key={f.banId} className="rounded border border-hairline px-4 py-3">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <span className="text-ink">{f.raisingTempleName}</span>
              <span className="text-sm text-ink-muted tabular-nums">Recorded {f.raisedOn}</span>
            </div>
            <p className="mt-1 text-sm text-ink-secondary">
              {f.categoryLabel} — employed there as {f.bannedName}
            </p>
            <p className="mt-2 whitespace-pre-line">{f.account}</p>
            <p className="mt-2 text-sm text-ink-muted">
              {f.exact ? "Matched exactly on" : "Looks like the same person from"}:{" "}
              {f.signalLabels.join(", ")}
              {f.exact ? "" : " — this may be somebody else with a similar name."}
            </p>
          </li>
        ))}
      </ul>

      <div className="mt-5 flex flex-wrap items-center gap-3">
        <button
          type="button"
          disabled={busy}
          onClick={onProceed}
          className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60"
        >
          Hire them anyway
        </button>
        <button
          type="button"
          disabled={busy}
          onClick={onStop}
          className="min-h-touch rounded border border-hairline px-4 hover:bg-sunken disabled:opacity-60"
        >
          Don&rsquo;t hire them
        </button>
        <span className="text-sm text-ink-muted">Whichever you choose is recorded.</span>
      </div>
    </section>
  );
}
