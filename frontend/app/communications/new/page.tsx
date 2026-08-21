"use client";

import { RequireRole } from "@/components/RequireRole";
import { Composer } from "../composer";

/** Write a message — five fields and an act that cannot be unsent, so a screen of its own. */
export default function NewCommunicationPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <Composer existing={null} />
    </RequireRole>
  );
}
