"use client";

import { RequireRole } from "@/components/RequireRole";
import { Sidebar } from "@/components/Sidebar";
import { DonateFlow } from "@/components/give/DonateFlow";
import { Loading } from "@/components/Loading";
import { useAuth } from "@/lib/auth-context";

/**
 * Giving, from inside the app.
 *
 * <p>The same flow a shared link opens, with the menu still beside it. A devotee who presses Give
 * has not left the app — they are doing something in it — and losing the navigation mid-gesture
 * reads as having been thrown out.
 */
export default function GivePage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_STAFF", "VOLUNTEER"]}>
      <GiveView />
    </RequireRole>
  );
}

function GiveView() {
  const { appUser } = useAuth();

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/give" />
      <main className="min-w-0 flex-1">
        {appUser?.tenantSlug ? (
          <DonateFlow slug={appUser.tenantSlug} />
        ) : (
          <Loading label="Opening your temple's giving page…" />
        )}
      </main>
    </div>
  );
}
