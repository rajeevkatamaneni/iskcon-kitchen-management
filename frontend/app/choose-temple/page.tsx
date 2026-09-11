"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ds/Button";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { JoinTempleForm } from "@/components/JoinTempleForm";
import { Loading } from "@/components/Loading";
import { api, type TempleSummary } from "@/lib/api";
import { takeChosenTemple, useAuth } from "@/lib/auth-context";

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
 *
 * <p><b>And the question is not always new (T-118).</b> Most people who land here have just come
 * from `/register`, where the first field on the form is this same question — they answered it,
 * Firebase told them their email already had an account, and `emailAlreadyInUse` sent them to sign
 * in with the promise that signing in would ask which temple they serve at. It does, and until
 * today it asked as though they had never said. The answer is carried here in `sessionStorage` (see
 * `rememberChosenTemple`) and offered back **pre-selected, never pre-joined**: joining a temple is a
 * real act and the person still presses the button. Anyone who reached this screen by another road
 * finds it exactly as it was.
 */
export default function ChooseTemplePage() {
  const router = useRouter();
  const { user, status, signOut, refusal } = useAuth();

  useEffect(() => {
    if (status === "signed-out") router.replace("/sign-in");
    if (status === "signed-in") router.replace("/");
  }, [status, router]);

  const prefill = useRememberedTemple();

  if (status === "loading" || !user || !prefill.settled) {
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

      {/* Only when there is one, and it says where it came from. A box that fills itself in is a
          small mystery on a screen somebody has arrived at confused; one sentence turns it into a
          thing they recognise, and the picker's own "Change" is right beside it. */}
      {prefill.temple && (
        <p className="-mb-2 text-sm text-ink-secondary">
          This is the temple you chose when you registered.
        </p>
      )}

      <JoinTempleForm
        onJoined={() => router.replace("/")}
        pickerLabel="Search for your temple"
        initialTemple={prefill.temple}
      />
    </main>
  );
}

/**
 * The temple they chose while registering, if there is one and it is still on offer.
 *
 * <p><b>The note holds an id, and this turns it into a temple — from the server, now.</b> It could
 * have been renamed, closed to new devotees or withdrawn between the two screens, and the picker's
 * own list is the definition of what may be chosen here. So the id is looked up in that same list
 * rather than trusted: what reaches the screen is the server's current record, and an id the list
 * does not contain is dropped and the question asked plainly. That is the whole answer to a
 * remembered value going stale — nothing stale is ever shown, because nothing remembered is ever
 * displayed.
 *
 * <p><b>`settled` is not `temple !== null`.</b> The two are different states — "we have not worked
 * out whether there is one" and "there is not one" — and the page waits on the first, because
 * `JoinTempleForm` reads its opening temple once when it mounts. Mounting the form and filling it
 * in afterwards would either do nothing or overwrite a choice already being made. For the ordinary
 * arrival with no note at all this settles within the mount effect, so nothing is delayed and no
 * request is made.
 *
 * <p>The ref is not caution. The note is read-once by design, and React remounts effects in
 * development; without it the second pass reads an empty note and races the first pass's lookup to
 * decide what the screen shows.
 */
function useRememberedTemple(): { settled: boolean; temple: TempleSummary | null } {
  const [prefill, setPrefill] = useState<{ settled: boolean; temple: TempleSummary | null }>({
    settled: false,
    temple: null,
  });
  const asked = useRef(false);

  useEffect(() => {
    if (asked.current) return;
    asked.current = true;

    const remembered = takeChosenTemple();
    if (!remembered) {
      setPrefill({ settled: true, temple: null });
      return;
    }

    api
      .temples({ q: remembered.name })
      // The name was only ever the search term. The match is on the id, and the record shown is the
      // one that came back — so a temple renamed since they picked it is simply not found, which is
      // the right outcome: we cannot show a name we have not been given.
      .then((found) =>
        setPrefill({
          settled: true,
          temple: found.find((temple) => temple.id === remembered.id) ?? null,
        })
      )
      // A search that fails is not evidence about the temple, and the screen has a perfectly good
      // answer without it: ask. Nothing is said about the failure because nothing was lost — the
      // question underneath is the one this screen exists to ask.
      .catch(() => setPrefill({ settled: true, temple: null }));

    // No cleanup, on purpose. The guard above means exactly one lookup is ever started, and
    // cancelling it on the effect's *re-run* — which is what development's remount does, on the
    // same component, with the ref already set — would leave the page waiting on a lookup nothing
    // is left to answer.
  }, []);

  return prefill;
}
