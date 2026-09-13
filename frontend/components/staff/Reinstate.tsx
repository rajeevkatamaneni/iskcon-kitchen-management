"use client";

import { useState } from "react";
import { Button } from "@/components/ds/Button";
import { Form } from "@/components/ds/Form";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { ACCESS_LABELS, dayMonthYear } from "@/components/staff/labels";
import type { api, StaffProfileView, SystemAccess } from "@/lib/api";

/** The control shape the ban panel on the record screen uses, so the two read as one screen. */
const FIELD = "min-h-touch rounded-control border border-hairline px-3";

/** What a reinstatement sends, as the API call declares it. */
export type ReinstateInput = Parameters<typeof api.reinstateStaff>[1];

/**
 * Taking a former member of staff back on (T-014), as a form of its own (T-172).
 *
 * <p><b>Why its own file.</b> It lived inline in `app/staff/[id]/page.tsx`, as a `<div role="group">`
 * whose button called the server from its click and stayed disabled until a date was typed — so the
 * required date was never named. T-172 made it a `Form`, so that a press on a blank date says "What
 * day did they come back? is required" beside the box. But the record screen is a read-only focus
 * screen whose header says Close, and the design rules (DESIGN_SYSTEM items 5–7, checked by
 * `design-system.test.ts`) keep a screen's commit buttons in its header and its Close for screens
 * that commit nothing. The record's other two acts, correcting a ban and adding a conduct note, were
 * already forms in their own components (`BanRecord`, `ConductNotes`) mounted by the page, and this
 * now lives the same way. The page keeps the decision of *whether* it is offered and makes the call;
 * this keeps the questions.
 *
 * <p><b>Closed until asked for.</b> The screen is a record to read, and bringing somebody back is a
 * deliberate act rather than something to fall into while reading.
 *
 * <p><b>No login is where it starts.</b> "" is no login at all, an ordinary answer for a cook. A value
 * nobody chose must not be an access level somebody did not mean to grant, and nothing remembers what
 * their access was before they left.
 */
export function Reinstate({
  lastWorkingDay,
  busy,
  onReinstate,
}: {
  lastWorkingDay: StaffProfileView["lastWorkingDay"];
  /** Something on the record screen is in flight, this or another act. */
  busy: boolean;
  /** Called once the date has been given. The page makes the call and reloads the record. */
  onReinstate: (input: ReinstateInput) => void;
}) {
  const [takingBack, setTakingBack] = useState(false);
  const [rejoinedOn, setRejoinedOn] = useState("");
  const [comingBackAs, setComingBackAs] = useState<SystemAccess | "">("");
  const [takeBackReason, setTakeBackReason] = useState("");

  if (!takingBack) {
    return (
      <div className="flex flex-wrap items-center gap-3">
        <p className="text-sm text-ink-secondary">
          They left on {lastWorkingDay ? dayMonthYear(lastWorkingDay) : "a day nobody recorded"}.
        </p>
        <Button variant="secondary" size="sm" disabled={busy} onClick={() => setTakingBack(true)}>
          Take them back on
        </Button>
      </div>
    );
  }

  return (
    <Form
      className="grid gap-3"
      aria-label="Take them back on"
      onSubmit={(event) => {
        event.preventDefault();
        // `Form` has refused a blank date before this runs. Kept as the old disabled button's twin.
        if (rejoinedOn === "") return;
        onReinstate({
          dateOfRejoining: rejoinedOn,
          systemAccess: comingBackAs === "" ? null : comingBackAs,
          reason: takeBackReason.trim() === "" ? null : takeBackReason.trim(),
        });
      }}
    >
      <InlineNotice tone="info">
        <p>Their record can be edited again, and the ending comes off it.</p>
        <p>What they can do in the app is set here, because nothing remembers what it was.</p>
      </InlineNotice>

      <label className="flex flex-col gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">What day did they come back?</span>
        <input
          type="date"
          name="dateOfRejoining"
          required
          value={rejoinedOn}
          onChange={(e) => setRejoinedOn(e.target.value)}
          className={FIELD}
        />
      </label>

      <label className="flex flex-col gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">What can they do in the app?</span>
        <select
          name="systemAccess"
          value={comingBackAs}
          onChange={(e) => setComingBackAs(e.target.value as SystemAccess | "")}
          className={FIELD}
        >
          <option value="">No login</option>
          {(Object.keys(ACCESS_LABELS) as SystemAccess[]).map((a) => (
            <option key={a} value={a}>
              {ACCESS_LABELS[a]}
            </option>
          ))}
        </select>
      </label>

      <label className="flex flex-col gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Why are they coming back?</span>
        <input
          name="reason"
          value={takeBackReason}
          onChange={(e) => setTakeBackReason(e.target.value)}
          className={FIELD}
        />
      </label>

      <div className="flex flex-wrap items-center gap-2">
        {/* Pressable before a date is given (T-172): the press is what names the blank date. */}
        <Button type="submit" disabled={busy}>
          Take them back on
        </Button>
        <Button variant="ghost" size="sm" disabled={busy} onClick={() => setTakingBack(false)}>
          Leave it
        </Button>
      </div>
    </Form>
  );
}
