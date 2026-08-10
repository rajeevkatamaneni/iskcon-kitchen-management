"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import { homeForRole } from "@/lib/routes";

/**
 * The landing router. Where you go after signing in depends on who you are, so this is a small
 * client-side switch rather than a page:
 *
 * - signed out → the sign-in screen
 * - signed in → the home for your role
 * - signed in with Firebase but no account here → a plain explanation, not a dead end
 *
 * Route-level guards on each protected screen come next; this just gets people to the right place
 * from the front door.
 */
export default function Home() {
  const router = useRouter();
  const { status, appUser, signOut } = useAuth();

  useEffect(() => {
    if (status === "signed-out") {
      router.replace("/sign-in");
    } else if (status === "signed-in" && appUser) {
      router.replace(homeForRole(appUser.role));
    }
  }, [status, appUser, router]);

  if (status === "no-account") {
    return (
      <main className="mx-auto flex min-h-screen max-w-prose flex-col justify-center px-6 py-12">
        <h1>You&rsquo;re signed in</h1>
        <p className="mt-2 text-ink-secondary">
          But you don&rsquo;t have an account at a temple yet. Ask your temple administrator to add
          you, then sign in again.
        </p>
        <button
          type="button"
          onClick={() => signOut()}
          className="mt-6 min-h-touch w-fit rounded border border-hairline-strong px-5 transition-colors duration-state hover:bg-raised"
        >
          Sign out
        </button>
      </main>
    );
  }

  // Loading, or redirecting to the sign-in screen / role home.
  return (
    <main className="flex min-h-screen items-center justify-center px-6">
      <p className="text-ink-secondary">Loading…</p>
    </main>
  );
}
