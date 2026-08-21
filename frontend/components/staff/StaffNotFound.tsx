"use client";

import Link from "next/link";
import { InlineNotice } from "@/components/ds/InlineNotice";

/**
 * An id that belongs to nobody.
 *
 * <p>Said plainly rather than shown as a failure, because nothing went wrong: the likeliest cause is
 * a stale link to somebody another administrator has since removed, or an address typed by hand.
 * Every screen that is about one member of staff can land on this, so they say it the same way.
 */
export function StaffNotFound() {
  return (
    <InlineNotice tone="warning" title="We can’t find that person">
      Nobody on your temple’s staff register has this record.{" "}
      <Link href="/staff" className="underline">
        Go back to staff
      </Link>
      .
    </InlineNotice>
  );
}
