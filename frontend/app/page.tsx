"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import { homeForRole } from "@/lib/routes";
import { Loading } from "@/components/Loading";

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
      <main className="flex min-h-screen items-center justify-center bg-canvas px-6 py-12">
        <div className="w-full max-w-md rounded-lg border border-hairline bg-raised px-8 py-10 text-center">
          <p className="text-sm font-medium text-ink-secondary">ISKCON Seva Kitchen</p>
          <h1 className="mt-3">You&rsquo;re signed in</h1>
          <p className="mt-3 text-ink-secondary">
            But this Google account isn&rsquo;t linked to a temple yet. Ask your temple administrator
            to add you with this email address, then sign in again.
          </p>
          <button
            type="button"
            onClick={() => signOut()}
            className="mt-8 min-h-touch rounded bg-accent px-6 text-ink-inverse transition-colors duration-state hover:bg-accent-hover"
          >
            Sign out
          </button>
        </div>
      </main>
    );
  }

  // Loading, or redirecting to the sign-in screen / role home.
  return (
    <main className="flex min-h-screen items-center justify-center px-6">
      <Loading />
    </main>
  );
}
