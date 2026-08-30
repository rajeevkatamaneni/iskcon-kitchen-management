"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import { homeForRole } from "@/lib/routes";
import { Loading } from "@/components/Loading";
import { ServerUnreachable } from "@/components/ServerUnreachable";

/**
 * The landing router. Where you go after signing in depends on who you are, so this is a small
 * client-side switch rather than a page:
 *
 * - signed out → the sign-in screen
 * - signed in → the home for your role
 * - signed in with Firebase but no account here → a plain explanation, not a dead end
 * - signed in, but our own server did not answer → said plainly, and stays put
 *
 * Route-level guards on each protected screen come next; this just gets people to the right place
 * from the front door.
 */
export default function Home() {
  const router = useRouter();
  const { status, appUser, refresh } = useAuth();

  useEffect(() => {
    if (status === "signed-out") {
      router.replace("/sign-in");
    } else if (status === "no-account") {
      // Verified by Firebase, a member of no temple. That is a question, not a dead end.
      router.replace("/choose-temple");
    } else if (status === "signed-in" && appUser) {
      router.replace(homeForRole(appUser.role));
    }
  }, [status, appUser, router]);

  if (status === "unreachable") {
    return <ServerUnreachable onRetry={refresh} />;
  }

  // Loading, or redirecting to the sign-in screen / role home.
  return (
    <main className="flex min-h-screen items-center justify-center px-6">
      <Loading />
    </main>
  );
}
