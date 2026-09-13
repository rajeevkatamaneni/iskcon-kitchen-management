"use client";

import Link from "next/link";
import { InlineNotice } from "@/components/ds/InlineNotice";
import type { ShiftView } from "@/lib/api";

/**
 * "That shift moved, and the 2 volunteers already signed up have not been told." — said wherever a
 * save has just moved a shift people had claimed (`movedUnderRoster`).
 *
 * <p>Saving reschedules everyone's reminders and sends nobody a word, on purpose: the admin writes
 * that message himself, from the roster page this links to. So the one thing the screen owes him is
 * to say so. It is shown on the volunteers list after the edit screen saves, and on the meal planner
 * after its layer saves (T-155). One component, so the two places cannot drift into saying it two
 * ways. A warning, so it stays until the reader leaves: it does not clear itself.
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
