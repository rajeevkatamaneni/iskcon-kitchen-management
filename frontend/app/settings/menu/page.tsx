"use client";

import { Loading } from "@/components/Loading";
import { MenuArranger } from "@/components/MenuArranger";
import { RequireRole } from "@/components/RequireRole";
import { Sidebar } from "@/components/Sidebar";
import { useAuth } from "@/lib/auth-context";

/**
 * Settings → Menu: where a temple arranges its own left-hand menu (Rajeev, 2026-09-19).
 *
 * <p>Temple Admin only, matching the server exactly: both endpoints behind this screen declare
 * `MANAGE_TEMPLE_SETTINGS`, which is the administrator's alone. What the menu looks like is a
 * standing decision about the temple, in the same family as what it calls its meals and which
 * festival days it observes, and those two screens are its siblings.
 *
 * <p><b>It has no row of its own in the left-hand menu, and that is deliberate (D-M6).</b> Rajeev
 * settled the seven groups item by item on 2026-09-19 and "Menu" is not among them, `nav.test.ts`
 * asserts that list precisely so a well-meant addition fails rather than ships, and a menu row for
 * arranging the menu is faintly recursive besides. It is reached from the Settings page, which is
 * where the rest of a temple's standing decisions are made. One line in `nav.ts` reverses this if he
 * ever wants it.
 *
 * <p>The sidebar is drawn with `/settings` as the active destination, because that is the row in the
 * menu this screen sits under. It is also the preview: the arrangement being edited is applied to the
 * real column as soon as Save has run, because saving refreshes the session the column reads.
 */
export default function MenuLayoutPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <div className="flex min-h-screen">
        <Sidebar activeHref="/settings" />
        <div className="min-w-0 flex-1">
          <MenuLayoutView />
        </div>
      </div>
    </RequireRole>
  );
}

/**
 * Waits for the session before drawing the board.
 *
 * <p>`RequireRole` has already established that somebody is signed in and holds the role, so this is
 * all but unreachable — but the arrangement itself rides on the session, and a board built from an
 * absent one would be the standard menu shown as though it were the temple's.
 */
function MenuLayoutView() {
  const { appUser } = useAuth();
  if (!appUser) {
    return <Loading />;
  }
  return <MenuArranger />;
}
