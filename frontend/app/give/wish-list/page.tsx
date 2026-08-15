"use client";

import { RequireRole } from "@/components/RequireRole";
import { Sidebar } from "@/components/Sidebar";
import { WishListView } from "@/components/give/WishListView";
import { Loading } from "@/components/Loading";
import { useAuth } from "@/lib/auth-context";

/** The temple's wish list, inside the app, with the menu where it was. */
export default function WishListPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_STAFF", "VOLUNTEER"]}>
      <WishListPageView />
    </RequireRole>
  );
}

function WishListPageView() {
  const { appUser } = useAuth();

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/give/wish-list" />
      <main className="min-w-0 flex-1">
        {appUser?.tenantSlug ? (
          <WishListView slug={appUser.tenantSlug} />
        ) : (
          <Loading label="Opening your temple's wish list…" />
        )}
      </main>
    </div>
  );
}
