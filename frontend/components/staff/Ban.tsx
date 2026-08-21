"use client";

import { useState } from "react";
import { Badge } from "@/components/ds/Badge";
import { Button } from "@/components/ds/Button";
import { InlineNotice } from "@/components/ds/InlineNotice";
import type { BanCategoryOption, BanFinding, EmploymentBanView } from "@/lib/api";

/**
 * The three ban surfaces (B9), kept out of the staff screens so that the gravest thing this product
 * lets an administrator do is somewhere a person can find and read whole: the option on the
 * termination screen, what a check found at a hire, and a record read back afterwards.
 *
 * <p>None of these is ever shown to the person a record is about. They are not told the reason in
 * the app at all: they lose access at the dismissal anyway, and telling somebody at the moment they
 * are fired invites retaliation, which is a real risk borne by the people this is built for. The
 * consequence shapes every component below — because the subject is not there to correct a wrong
 * entry, the raising temple's name, the ten-year life and the ability to take it back have to do the
 * whole job between them, so all three are on the screen and none of them is in small print.
 */

const FIELD = "min-h-touch rounded border border-hairline bg-canvas px-3";

/**
 * The option on the termination screen.
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
            Only if you would actually warn another temple.
          </span>
        </span>
      </label>

      {recording && (
        <div className="mt-4 grid gap-4">
          {/* One of the four texts exempt from the copy cut, and the gravest of them (Q7). The
              focus-screen mock draws a two-line version of this, because the mock had a 440px frame
              to fit it in; Q7 settles the text itself and says this one is barely shortened at all.
              Where the two disagree, Q7 governs, because it is the decision about these words and
              the mock is the decision about the shape around them.

              Every clause here is a fact somebody needs before they tick the box, and the second is
              the one the whole design rests on: the subject is not there to correct a wrong entry,
              so the raising temple’s name, the ten-year life and the ability to take it back have to
              do the whole job between them. It is read once. Twelve words cannot carry it. */}
          <InlineNotice tone="warning">
            Your temple’s name and what you write here are shown to any temple that tries to
            hire this person, for the next ten years. They are not told about it and cannot answer
            it, so write only what you would be willing to say to them &mdash; and to the other
            temple &mdash; out loud. You can take it back at any time, and you should if you turn out
            to be wrong.
          </InlineNotice>

          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">What kind of thing was it?</span>
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
            <span className="pl-field-inset text-xs text-ink-muted">
              Another temple can compare this with what they recorded.
            </span>
          </label>

          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">What happened, in your own words?</span>
            <textarea name="banAccount" required rows={4} className={`${FIELD} py-2`} />
            <span className="pl-field-inset text-xs text-ink-muted">
              Facts and dates are worth more than adjectives.
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
        This is not a decision and it does not stop you. It is one temple’s account, and the
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
          Don’t hire them
        </button>
        <span className="text-sm text-ink-muted">Whichever you choose is recorded.</span>
      </div>
    </section>
  );
}

/**
 * One record this temple raised, as its own temple reads it back (B9).
 *
 * <p>Used in two places, which is the point of it being a component. On {@code /staff/bans} it is a
 * row of a list somebody is auditing, and nothing on it can be changed. On the former employee's own
 * record it is the same words with the two remedies attached — correcting what it says, and taking
 * it back. <b>Only there</b>, so that one record cannot be changed from two screens (Q5).
 *
 * <p>It does not render the person's name. On the list the name is the row's own heading and links
 * to them; on their record the whole page is already about them, and a second copy of the name in
 * the middle of it would read as though it were about somebody else.
 */
export function BanRecord({
  ban,
  categories,
  busy = false,
  onSubmitAmend,
  onSubmitRetract,
}: {
  ban: EmploymentBanView;
  /** Passed only where the record may be changed. Its absence is what makes this read-only. */
  categories?: BanCategoryOption[];
  busy?: boolean;
  onSubmitAmend?: (e: React.FormEvent<HTMLFormElement>) => void;
  onSubmitRetract?: (e: React.FormEvent<HTMLFormElement>) => void;
}) {
  const [panel, setPanel] = useState<"none" | "amend" | "retract">("none");
  const changeable = Boolean(categories && onSubmitAmend && onSubmitRetract) && !ban.retracted;

  return (
    <>
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <p className="flex items-center gap-2 text-ink">
          {ban.categoryLabel}
          {ban.retracted && <Badge>Taken back</Badge>}
        </p>
        <span className="text-sm text-ink-muted tabular-nums">
          Recorded {ban.raisedAt.slice(0, 10)}
          {ban.raisedBy ? ` by ${ban.raisedBy}` : ""}
        </span>
      </div>

      <p className="mt-2 whitespace-pre-line">{ban.account}</p>

      <p className="mt-2 text-sm text-ink-muted">
        {ban.retracted
          ? `Taken back${ban.retractedAt ? ` on ${ban.retractedAt.slice(0, 10)}` : ""}. It is no longer shown at any hire.`
          : `Shown to hiring temples until ${ban.fadesOn}.`}
      </p>
      {ban.retracted && ban.retractionReason && (
        <p className="mt-1 text-sm text-ink-secondary">{ban.retractionReason}</p>
      )}

      {changeable && (
        <div className="mt-4 flex flex-wrap items-center gap-2">
          <Button
            variant="secondary"
            size="sm"
            disabled={busy}
            onClick={() => setPanel((p) => (p === "amend" ? "none" : "amend"))}
          >
            Correct what it says
          </Button>
          <Button
            variant="danger"
            size="sm"
            disabled={busy}
            onClick={() => setPanel((p) => (p === "retract" ? "none" : "retract"))}
          >
            Take it back
          </Button>
        </div>
      )}

      {/* Two fields and one field: both stay inline, which is where the threshold settled in Q1.
          Sending somebody to another screen to correct a sentence is friction, not focus. */}
      {changeable && panel === "amend" && (
        <form className="mt-4 grid gap-3" aria-label="Correct this record" onSubmit={onSubmitAmend}>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">What kind of thing was it?</span>
            <select name="category" defaultValue={ban.category} required className={FIELD}>
              {(categories ?? []).map((c) => (
                <option key={c.value} value={c.value}>
                  {c.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">What happened, in your own words?</span>
            <textarea name="account" required rows={4} defaultValue={ban.account} className={`${FIELD} py-2`} />
          </label>
          <div>
            <Button type="submit" disabled={busy}>
              Save the correction
            </Button>
          </div>
        </form>
      )}

      {changeable && panel === "retract" && (
        <form className="mt-4 grid gap-3" aria-label="Take this record back" onSubmit={onSubmitRetract}>
          <InlineNotice tone="warning">
            <p>It stops being shown at hires straight away.</p>
            <p>The record stays on file with your reason on it.</p>
          </InlineNotice>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Why are you taking it back?</span>
            <input name="reason" className={FIELD} />
            <span className="pl-field-inset text-xs text-ink-muted">Optional</span>
          </label>
          <div>
            <Button type="submit" variant="danger" disabled={busy}>
              Take it back
            </Button>
          </div>
        </form>
      )}
    </>
  );
}
