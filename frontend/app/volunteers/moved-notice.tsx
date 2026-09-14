"use client";

import Link from "next/link";
import { InlineNotice } from "@/components/ds/InlineNotice";
import type { ShiftView } from "@/lib/api";

/**
 * "That shift moved, and the 2 volunteers already signed up have not been told." — said on the
 * Volunteer shifts list after a **shift not for a meal had its date changed** while volunteers were
 * signed up (`dateChangedUnderRoster`).
 *
 * <p>Narrowed by D-27. Answer 6 replaced this notice for one case only: new times on the same day, where
 * the server now sends each signed-up volunteer the approved `shift_broadcast` ("The times changed to
 * …") and the edit screen warns before saving instead. A new date is not that case. The server sends
 * nothing when a shift's date changes, with or without new times, so the reminders move and nobody is
 * told — and the one thing the screen owes the admin is to say so, and point at the roster page where he
 * writes that message himself. The planner no longer shows this (its shift is saved with the meal, and
 * warned about there), and a meal shift cannot change its date at all (answer 4). A warning, so it stays
 * until the reader leaves: it does not clear itself.
 *
 * <p>The count is the shift as re-read after the save, which is what the list has always used.
 */
export function MovedNotice({ shift }: { shift: Pick<ShiftView, "id" | "signedUpCount"> }) {
  return (
    <InlineNotice tone="warning">
      That shift moved, and the {shift.signedUpCount} volunteer
      {shift.signedUpCount === 1 ? "" : "s"} already signed up have not been told.
      Their reminders now fire at the new time.{" "}
      <Link href={`/volunteers/${shift.id}`} className="underline">
        Send them an update
      </Link>
      .
    </InlineNotice>
  );
}
