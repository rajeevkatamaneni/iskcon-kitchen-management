"use client";

import { RequireRole } from "@/components/RequireRole";
import { Sidebar } from "@/components/Sidebar";
import { DonatePage } from "@/components/give/DonatePage";

/**
 * Giving, from inside the app — which is the only way to give, as of 2026-08-29. There was a public
 * page a shared link opened; there is not any more, and this route is what replaced it.
 */
export default function DonateRoute() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF", "VOLUNTEER"]}>
      <div className="flex min-h-screen">
        <Sidebar activeHref="/donate" />
        {/* A div, not a main: the page below carries its own main landmark, and two of them in one
            document leaves a screen reader with no single "the content starts here". */}
        <div className="min-w-0 flex-1">
          <DonatePage />
        </div>
      </div>
    </RequireRole>
  );
}
