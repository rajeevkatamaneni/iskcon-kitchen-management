"use client";

import { RequireRole } from "@/components/RequireRole";
import { Sidebar } from "@/components/Sidebar";
import { DonatePage } from "@/components/give/DonatePage";
import { Loading } from "@/components/Loading";
import { useAuth } from "@/lib/auth-context";

/**
 * Giving, from inside the app: the same page a shared link opens, with the menu still beside it.
 * A devotee who presses Donate has not left the app — they are doing something in it.
 */
export default function DonateRoute() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF", "VOLUNTEER"]}>
      <DonateView />
    </RequireRole>
  );
}

function DonateView() {
  const { appUser } = useAuth();

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/donate" />
      {/* A div, not a main: the page below carries its own main landmark, and two of them in one
          document leaves a screen reader with no single "the content starts here". */}
      <div className="min-w-0 flex-1">
        {/* Not standalone: the menu beside this already carries the mark and the temple's name. */}
        {appUser?.tenantSlug ? (
          <DonatePage slug={appUser.tenantSlug} standalone={false} />
        ) : (
          <Loading />
        )}
      </div>
    </div>
  );
}
