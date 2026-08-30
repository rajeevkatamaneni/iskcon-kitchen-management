"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { Button } from "@/components/ds/Button";
import { JoinTempleForm } from "@/components/JoinTempleForm";
import { Loading } from "@/components/Loading";
import { useAuth } from "@/lib/auth-context";

/**
 * Where a devotee lands when Google has vouched for them and no temple has.
 *
 * <p>There is no way past it, and that is the point: every screen in this product belongs to a
 * temple, so until one is chosen there is nothing to show. They are not stuck — they are one
 * question from being somewhere.
 */
export default function ChooseTemplePage() {
  const router = useRouter();
  const { user, status, signOut } = useAuth();

  useEffect(() => {
    if (status === "signed-out") router.replace("/sign-in");
    if (status === "signed-in") router.replace("/");
  }, [status, router]);

  if (status === "loading" || !user) {
    return (
      <main className="mx-auto grid min-h-screen max-w-prose place-items-center px-6">
        <Loading />
      </main>
    );
  }

  return (
    <main className="mx-auto grid min-h-screen max-w-prose content-start gap-6 px-6 py-14">
      <header className="grid gap-2">
        <h1 className="text-2xl font-semibold text-ink">Which temple do you serve at?</h1>
        {/* Who they are signed in as, at the top and in readable ink.
            It was already on this page — at the foot, below the whole form, in the quietest grey
            the palette has. That is the wrong place for it, because the question somebody arriving
            here actually has is "why am I being asked this?", and for a person with a personal
            Google account and a temple one the answer is nearly always that they picked the wrong
            one. Rajeev lost ten minutes to exactly that on 2026-08-30, with the answer on screen
            the whole time. */}
        <p className="text-ink-secondary">
          Signed in as{" "}
          <span className="font-medium text-ink">{user.email ?? user.phoneNumber}</span>. You can
          join more temples later.
        </p>
        <p className="text-sm text-ink-secondary">
          Not the right account?{" "}
          <Button variant="ghost" size="sm" onClick={() => signOut()}>
            Use a different account
          </Button>
        </p>
      </header>

      <JoinTempleForm onJoined={() => router.replace("/")} pickerLabel="Search for your temple" />
    </main>
  );
}
