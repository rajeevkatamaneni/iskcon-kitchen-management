"use client";

import { useEffect, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import type { PrincipalRole } from "@/lib/api";
import { Loading } from "@/components/Loading";
import { ServerUnreachable } from "@/components/ServerUnreachable";

/**
 * Gates a page on being signed in and holding an allowed role.
 *
 * <p>Navigation is not the security boundary — the API enforces every permission on every request —
 * but a screen should never render for someone who will only be refused, or leak its shape to a
 * role that shouldn't see it. A signed-out visitor is sent to the front door and one with no temple
 * to the choice that gives them one;
 * a signed-in user with the wrong role is told plainly rather than shown a broken page.
 */
/**
 * What somebody sees when their account here has been switched off.
 *
 * <p>The words are `KMS-400019`'s own — "This account has been disabled." and "Ask your temple
 * administrator to restore access." — so that what a person reads on the screen is what support
 * finds written beside that code, and the code itself is offered quietly for them to quote. It
 * exists because the alternative was worse than saying nothing: before the server sent a code, this
 * arrived as "no account", which sent a volunteer whose access an administrator had just withdrawn
 * to a picker offering to sign them up somewhere. Nothing they can do on any screen will help until
 * it is given back, so there is no button and no retry — only who to ask.
 *
 * <p>It lives beside {@link RequireRole} rather than in a file of its own because both places that
 * handle the `disabled` status need exactly these words, and user-facing copy kept in two places is
 * copy that will eventually say two things. A file of its own in `components/` would be the tidier
 * home and is worth moving it to.
 */
export function AccountDisabled() {
  return (
    <main className="mx-auto flex min-h-screen max-w-prose flex-col justify-center px-6 py-12">
      <h1>This account has been disabled</h1>
      <p className="mt-2 text-ink-secondary">
        Ask your temple administrator to restore access.
      </p>
      <p className="mt-6 text-xs text-ink-secondary">
        If you need help, quote <span className="font-mono font-medium">KMS-400019</span>
      </p>
    </main>
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
  // a statement about them and not about the network.
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
      return (
        <main className="mx-auto flex min-h-screen max-w-prose flex-col justify-center px-6 py-12">
          <h1>Not your page</h1>
          <p className="mt-2 text-ink-secondary">
            You don’t have access to this part of the app. Ask your temple administrator.
          </p>
        </main>
      );
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
