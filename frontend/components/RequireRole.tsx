"use client";

import { useEffect, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import type { PrincipalRole } from "@/lib/api";
import { navForRole } from "@/lib/nav";
import { homeForRole } from "@/lib/routes";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { Loading } from "@/components/Loading";
import { ServerUnreachable } from "@/components/ServerUnreachable";
import { Sidebar } from "@/components/Sidebar";

/**
 * Gates a page on being signed in and holding an allowed role.
 *
 * <p>Navigation is not the security boundary — the API enforces every permission on every request —
 * but a screen should never render for someone who will only be refused, or leak its shape to a
 * role that shouldn't see it. A signed-out visitor is sent to the front door and one with no temple
 * to the choice that gives them one;
 * a signed-in user with the wrong role is told plainly rather than shown a broken page.
 *
 * <p><b>Why the three refusals below do not look alike, and must not be made to.</b> This component
 * can end a render in three ways — the server did not answer, this account has been switched off,
 * and this person holds the wrong role — and only the last of them gains the application's chrome.
 * That is a deliberate three-way split and not an oversight, so before making them consistent,
 * read why they differ:
 *
 * <ul>
 *   <li><b>Wrong role — sidebar, and a link home.</b> Everything else in the application still
 *       works for this reader; they are simply standing at one door that is not theirs. Refusing
 *       them on a bare white page with no link anywhere left the browser's back button as the only
 *       way out, which Rajeev hit on four surfaces on staging (2026-09-07): `/donate`, `/shifts`
 *       and `/my-shifts` as a cook, and the disabled screen below.</li>
 *   <li><b>Disabled — no sidebar, but a sign-out.</b> A menu is a list of things to do, and there
 *       is nothing this person can do here until their access is given back; drawing it would offer
 *       twenty doors that all refuse them. Signing out is the exception, and the reason is in the
 *       same sentence: the one useful act left to somebody who cannot use this account is to stop
 *       using it. A shared temple tablet, a second account, a person disabled at one temple and
 *       active at another — all of them sat in a dead tab before.</li>
 *   <li><b>Unreachable — neither.</b> A working menu painted over an application whose server is
 *       not answering is decoration on top of a dead app: every destination in it would land on
 *       this same screen. And a sign-out helps nobody when signing back in needs the server that
 *       just failed to answer, so {@link ServerUnreachable} keeps its "Try again" and gains
 *       nothing.</li>
 * </ul>
 *
 * <p>The chrome is rendered here rather than by each page, and the survey that makes that safe is
 * worth writing down. Of the 81 page components that mount this guard, 54 put `<Sidebar>` inside it
 * themselves, 24 get one from `FocusScreen` inside it, and the remaining 3 hand the whole body to a
 * component that does the same — and **not one of them draws a sidebar outside the guard**. So the
 * refusal branch can carry chrome without any page anywhere ending up with two menus, and every
 * guarded route written after today inherits the fix. Per-page it would have been the same edit 54
 * times over, and the 55th page would still have been free to reintroduce it.
 *
 * <p>(The task brief for this change described those 24 + 3 as rendering "no sidebar at all". They
 * do render one — `FocusScreen` draws it, and rule 2 of the eight it enforces is that the sidebar
 * stays. Nothing about the shape of the fix changes: a refused reader never reaches any of that
 * chrome, because all of it sits below the guard.)
 */
/**
 * What somebody sees when their account here has been switched off.
 *
 * <p>The words are `KMS-400019`'s own — "This account has been disabled." and "Ask your temple
 * administrator to restore access." — so that what a person reads on the screen is what support
 * finds written beside that code, and the code itself is offered quietly for them to quote. It
 * exists because the alternative was worse than saying nothing: before the server sent a code, this
 * arrived as "no account", which sent a volunteer whose access an administrator had just withdrawn
 * to a picker offering to sign them up somewhere.
 *
 * <p>Still no menu and still no retry — nothing they can do *in this account* on any screen will
 * help until it is given back. The one thing that is not true of is leaving it, so there is a sign
 * out, and it is the app's own: it clears the Firebase session, the temple and the clock, and lands
 * on the sign-in screen where another account can be used. Without it the tab was simply dead, and
 * on a shared machine so was the next person's turn at it.
 *
 * <p>It lives beside {@link RequireRole} rather than in a file of its own because both places that
 * handle the `disabled` status need exactly these words, and user-facing copy kept in two places is
 * copy that will eventually say two things. A file of its own in `components/` would be the tidier
 * home and is worth moving it to.
 */
export function AccountDisabled() {
  const { signOut } = useAuth();
  return (
    <main className="mx-auto flex min-h-screen max-w-prose flex-col justify-center px-6 py-12">
      <h1>This account has been disabled</h1>
      <p className="mt-2 text-ink-secondary">
        Ask your temple administrator to restore access.
      </p>
      {/* Deliberately quiet, and deliberately worded as choosing an account rather than as leaving:
          a person who has just been told they are locked out is not looking for the exit, they are
          looking for the account that still works. Same words as the temple picker's, which is the
          other screen in the app where signing out is the way forward rather than the way home. */}
      <p className="mt-6 text-sm text-ink-secondary">
        Signed in with the wrong account?{" "}
        <Button variant="ghost" size="sm" onClick={() => signOut()}>
          Use a different account
        </Button>
      </p>
      <p className="mt-6 text-xs text-ink-secondary">
        If you need help, quote <span className="font-mono font-medium">KMS-400019</span>
      </p>
    </main>
  );
}

/**
 * The wrong-role refusal: the application's own chrome, the plain sentence, and a way out of it.
 *
 * <p>The link is not made redundant by the sidebar beside it. On a narrow viewport the menu is not
 * necessarily in front of the reader, and somebody who has just been told they are in the wrong
 * place should not have to go looking for a menu to leave it. It points at *this reader's* home,
 * from {@link homeForRole} — the same switch the front door uses — rather than at a fixed `/today`,
 * because a volunteer is refused `/today` as surely as a cook is refused `/donate`, and a way out
 * that lands on a second "Not your page" would be the very defect this fixes, twice. Its label is
 * that destination's own name from `nav.ts`, so the button and the menu row above it can never come
 * to call the same screen two different things.
 */
function WrongRole({ role }: { role: PrincipalRole }) {
  // Both fall back, and the reason is the same in both: this is the screen a reader lands on when
  // something about them does not fit, so it is the last screen in the application that may itself
  // break. `homeForRole` is exhaustive over the roles we know, and a server that begins sending one
  // we do not — a role added ahead of a frontend deploy — would otherwise hand `<Link>` an
  // undefined href and replace the refusal with a blank page. `/` is the landing router, which
  // knows what to do with anybody.
  const home: string = homeForRole(role) ?? "/";
  const label =
    navForRole(role)
      .flatMap((group) => group.items)
      .find((item) => item.href === home)?.label ?? "the home page";

  return (
    <div className="flex min-h-screen">
      {/* No `activeHref`: this page is not in the menu — that is what the reader is being told —
          and highlighting a row they are not on would be a small lie in a screen whose whole job is
          to be plain. */}
      <Sidebar activeHref="" />
      {/* A div rather than a second main, matching every page that renders this chrome: the panel
          below carries the document's one main landmark. */}
      <div className="min-w-0 flex-1">
        <main className="mx-auto flex min-h-screen max-w-prose flex-col justify-center px-6 py-12">
          <h1>Not your page</h1>
          <p className="mt-2 text-ink-secondary">
            You don’t have access to this part of the app. Ask your temple administrator.
          </p>
          <div className="mt-6">
            <ButtonLink href={home} variant="ghost">
              Go to {label}
            </ButtonLink>
          </div>
        </main>
      </div>
    </div>
  );
}

export function RequireRole({
  roles,
  children,
}: {
  roles: PrincipalRole[];
  children: ReactNode;
}) {
  const { status, appUser, refresh } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (status === "signed-out") {
      router.replace("/sign-in");
    } else if (status === "no-account") {
      router.replace("/choose-temple");
    }
  }, [status, router]);

  // Not a redirect. Sending somebody to the temple picker because the server did not answer is
  // what this state exists to stop — the picker would tell them they belong to no temple, which is
  // a statement about them and not about the network. No chrome and no sign-out either; see the
  // three-way split documented on this file's guard above.
  if (status === "unreachable") {
    return <ServerUnreachable onRetry={refresh} />;
  }

  // Also not a redirect, and for a related reason: this person does belong to a temple, and their
  // access to it has been withdrawn. The picker would offer to sign them up elsewhere. Handled
  // here as well as on the landing route because a disabled person may arrive on any deep link,
  // and a status this component does not name renders the spinner below and never stops.
  if (status === "disabled") {
    return <AccountDisabled />;
  }

  if (status === "signed-in" && appUser) {
    if (!roles.includes(appUser.role)) {
      return <WrongRole role={appUser.role} />;
    }
    return <>{children}</>;
  }

  // Loading, or being redirected to sign-in / the landing.
  return (
    <main className="flex min-h-screen items-center justify-center px-6">
      <Loading />
    </main>
  );
}
