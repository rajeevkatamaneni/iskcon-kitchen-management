"use client";

import { useEffect, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import type { PrincipalRole } from "@/lib/api";
import { Loading } from "@/components/Loading";

/**
 * Gates a page on being signed in and holding an allowed role.
 *
 * <p>Navigation is not the security boundary — the API enforces every permission on every request —
 * but a screen should never render for someone who will only be refused, or leak its shape to a
 * role that shouldn't see it. A signed-out visitor is sent to the front door and one with no temple
 * to the choice that gives them one;
 * a signed-in user with the wrong role is told plainly rather than shown a broken page.
 */
export function RequireRole({
  roles,
  children,
}: {
  roles: PrincipalRole[];
  children: ReactNode;
}) {
  const { status, appUser } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (status === "signed-out") {
      router.replace("/sign-in");
    } else if (status === "no-account") {
      router.replace("/choose-temple");
    }
  }, [status, router]);

  if (status === "signed-in" && appUser) {
    if (!roles.includes(appUser.role)) {
      return (
        <main className="mx-auto flex min-h-screen max-w-prose flex-col justify-center px-6 py-12">
          <h1>Not your page</h1>
          <p className="mt-2 text-ink-secondary">
            You don&rsquo;t have access to this part of the app. If you think you should, ask your
            temple administrator.
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
