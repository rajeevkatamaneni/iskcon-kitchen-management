"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { Button } from "@/components/ds/Button";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { JoinTempleForm } from "@/components/JoinTempleForm";
import { Loading } from "@/components/Loading";
import { useAuth } from "@/lib/auth-context";

/**
 * Where a devotee lands when Google has vouched for them and no temple has.
 *
 * <p>There is no way past it, and that is the point: every screen in this product belongs to a
 * temple, so until one is chosen there is nothing to show. They are not stuck — they are one
 * question from being somewhere.
 *
 * <p><b>Why the reason is now stated (T-116).</b> Nobody chooses to come here. A person arrives
 * because `/whoami` refused them and `auth-context` redirected, and until today the redirect threw
 * away the server's explanation — so the screen opened on a question with no account of itself, and
 * the one thing the reader wanted to know was the one thing nobody said. That explanation is
 * `KMS-400020`'s message, which `ErrorCodeTest` has been proving exists since the code was written
 * and which had never once been on a screen.
 *
 * <p><b>The message and not the next step.</b> `KMS-400020`'s next step reads *"Choose your temple
 * to join it."* — which is the heading below it, asked as a question, above the form that does it.
 * Printing it here would be the same instruction three times in four inches. The reader is not
 * short of a next step on this screen; they were short of a reason, and that is the half that is
 * rendered. This is the one exemption `__tests__/refusal-words-reach-the-reader.test.tsx` carries,
 * and it is written down there rather than left as an omission somebody has to guess at.
 *
 * <p><b>And only when the server actually said it.</b> `no-account` is also where an unexplained
 * 401 lands — an expired or unverifiable token, which `TokenVerifier` deliberately refuses to
 * account for — and that person has no refusal, so no sentence appears. This screen telling them
 * they have no account here would be a statement about them that nobody has made, which is the
 * same lie `unreachable` was split out of the status to stop.
 */
export default function ChooseTemplePage() {
  const router = useRouter();
  const { user, status, signOut, refusal } = useAuth();

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
      {/* Above the question, because it is the answer to "why am I being asked this?" and that
          question comes first. `info` and not `danger`: nothing has gone wrong and nobody has done
          anything wrong — for most people who read it this is the first screen of joining a temple,
          and a red box would be a poor way to meet them. The reference code is deliberately not
          shown beside it either; there is nothing here to telephone anybody about, and a KMS number
          stamped on somebody's first screen buys support nothing and costs the welcome. */}
      {refusal && (
        <InlineNotice tone="info">{refusal.message}</InlineNotice>
      )}

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
