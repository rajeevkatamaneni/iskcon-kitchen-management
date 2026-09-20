"use client";

import { useEffect, useLayoutEffect, useState, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import type { PrincipalRole } from "@/lib/api";
import { isMealPlannerPath, navForRole, plannerRefused } from "@/lib/nav";
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
 * <p>The words are `KMS-400019`'s own — so that what a person reads on the screen is what support
 * finds written beside that code, and the code itself is offered quietly for them to quote. It
 * exists because the alternative was worse than saying nothing: before the server sent a code, this
 * arrived as "no account", which sent a volunteer whose access an administrator had just withdrawn
 * to a picker offering to sign them up somewhere.
 *
 * <p><b>They are now read from the refusal rather than retyped here (T-116).</b> They used to be
 * two string literals in the JSX below that happened to match `ErrorCode.java`, which is not a
 * mechanism — it is a coincidence maintained by hand, and the neighbouring code proves how that
 * ends: T-114 reworded `KMS-400020`'s next step on 2026-09-10 and no screen moved, because no
 * screen was reading it. `auth-context` now carries the server's own message and next step through
 * as a {@link Refusal} and this renders them, so the catalogue and the screen cannot drift apart
 * again.
 *
 * <p>The heading above them is the screen's own and stays a literal: it is a title, not the error's
 * copy, and it is short and unpunctuated like every other h1 here. That split is what lets the
 * catalogue's sentence be rendered exactly as it is stored, full stop included, without a heading
 * anywhere in the application gaining one.
 *
 * <p>The literals survive only as a fallback for a `disabled` status reached without a refusal —
 * which no path produces today, since the status is set from the refusal that carries them. It is
 * there for the same reason `WrongRole` falls back on `/` below: this is the last screen a broken
 * session meets, and the last screen in the application is the one that may not itself break. The
 * heading alone would leave somebody locked out with a two-word title and no idea who can undo it.
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
  const { signOut, refusal } = useAuth();

  // `ErrorCode.java`'s own sentences when the server sent them, which on every live path it did.
  // The literals are the fallback described above and nothing more; they are not a second copy of
  // the contract, and nothing but a `disabled` status arriving without a refusal can reach them.
  const message = refusal?.message ?? "This account has been disabled.";
  const nextStep = refusal?.action ?? "Ask your temple administrator to restore access.";
  const code = refusal?.code ?? "KMS-400019";

  return (
    <main className="mx-auto flex min-h-screen max-w-prose flex-col justify-center px-6 py-12">
      {/* A short heading, then the catalogue's sentence as body copy under it, then the next step.
          Three things settle that arrangement:

          The heading is the screen's own title and is not the error's words. It is short and
          unpunctuated like every other h1 in the application — "We can't reach the server", "Not
          your page" — and it is deliberately *not* a second rendering of the sentence below, or the
          reader would meet the same words twice in two sizes.

          The sentence keeps its full stop, because it is the catalogue's and is rendered as
          written. Trimming the stop to make it fit a heading would put a transformation between the
          stored text and the text on the screen, which is a smaller version of exactly the gap this
          change closes — and would leave the guard test having to normalise punctuation to check
          anything.

          Message above next step, the more prominent of the two, which is `ErrorNotice`'s order for
          every other failure in the product. */}
      <h1>Account disabled</h1>
      <p className="mt-2 text-ink">{message}</p>
      <p className="mt-1 text-sm text-ink-secondary">{nextStep}</p>
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
        If you need help, quote <span className="font-mono font-medium">{code}</span>
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
 *
 * <p><b>`reason` is for a refusal that has a sentence of its own (T-363).</b> Without one the reader
 * gets the generic sentence below, which is all that is true when the only thing wrong is the role:
 * we do not know which door they tried and there is nothing to say about it beyond who can open it.
 * The planner's refusal is not like that — the server has a written reason for it, and a next step
 * that is not "ask your temple administrator" but a specific thing an administrator does on a
 * specific screen — so it passes that reason in and it is rendered instead.
 */
function WrongRole({
  role,
  reason,
}: {
  role: PrincipalRole;
  /** The catalogue's own words for this refusal, where the refusal has some. See {@link PLANNER_REFUSAL}. */
  reason?: { code: string; message: string; action: string };
}) {
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
          {/* The heading is the screen's own and stays a literal whichever sentence follows it,
              exactly as `AccountDisabled` above splits the two: an h1 here is short and
              unpunctuated, and a catalogue sentence is a sentence and keeps its full stop. Rendering
              the reason as the heading would force a choice between a punctuated heading and
              trimming the stored text on its way to the screen, and the guard test in
              `refusal-words-reach-the-reader.test.tsx` asserts against exactly that. */}
          <h1>Not your page</h1>
          {reason ? (
            <>
              <p className="mt-2 text-ink">{reason.message}</p>
              <p className="mt-1 text-sm text-ink-secondary">{reason.action}</p>
            </>
          ) : (
            <p className="mt-2 text-ink-secondary">
              You don’t have access to this part of the app. Ask your temple administrator.
            </p>
          )}
          <div className="mt-6">
            <ButtonLink href={home} variant="ghost">
              Go to {label}
            </ButtonLink>
          </div>
          {/* Offered as quietly as the disabled screen offers `KMS-400019`, and for the same reason:
              the reader does not need it, and the one person who does — whoever they ring — needs it
              to be the same string support has written down. Only where there is a code; the generic
              refusal above has none, because it is not one failure but any door a role cannot open. */}
          {reason && (
            <p className="mt-6 text-xs text-ink-secondary">
              If you need help, quote <span className="font-mono font-medium">{reason.code}</span>
            </p>
          )}
        </main>
      </div>
    </div>
  );
}

/**
 * `KMS-400183`'s own words, for the reader the meal planner is shut to (T-363).
 *
 * <p><b>Why they are written here and not read off the wire.</b> Every other refusal in this file
 * carries the server's sentence through, because the server said it: `/whoami` answers a refusal with
 * the whole contract and `auth-context` hands it on. This one is never said by the server to this
 * reader at all. The guard below refuses the planner *before any request is made* — that is the
 * point of it, so a planner page does not start asking for a week it would be refused — and
 * `/whoami` carries only the `canPlanMeals` boolean, not the text. There is nothing to read.
 *
 * <p>So this is a hand-kept copy of `PLANNER_NOT_FOR_YOUR_KITCHEN` in `ErrorCode.java`, and a
 * hand-kept copy is a coincidence rather than a mechanism — which is the whole defect T-116 was
 * written to stop. What makes it safe is the mechanism put around it instead:
 * `refusal-words-reach-the-reader.test.tsx` parses `ErrorCode.java`'s source and asserts this screen
 * renders that code's message and next step exactly. Reword the catalogue and the test goes red the
 * same day. **So change these strings by changing `ErrorCode.java`, never here.**
 *
 * <p>Somebody who reaches the planner's API anyway — by hand, or from an older tab — still gets the
 * server's own `KMS-400183`, because the server is the rule and this is only what it looks like.
 */
const PLANNER_REFUSAL = {
  code: "KMS-400183",
  message: "Your kitchen doesn't plan its meals here, so the meal planner isn't open to you.",
  action: "If you have moved kitchens, ask a Temple Admin to change your kitchen on the Staff page.",
} as const;

/** A layout effect in the browser and a plain one on the server, where there is no layout. */
const useBrowserLayoutEffect = typeof window === "undefined" ? useEffect : useLayoutEffect;

export function RequireRole({
  roles,
  children,
}: {
  roles: PrincipalRole[];
  children: ReactNode;
}) {
  const { status, appUser, refresh } = useAuth();
  const router = useRouter();

  // The address this guard is standing at, for the one refusal a role cannot decide (Epic 12): the
  // meal planner is shut to somebody whose kitchen does not plan its meals here, whatever their role.
  // Every planner page already mounts this guard, so refusing here covers all of them — the week, a
  // day, a meal, compose, reuse, catch-up — and any added later, without each page having to
  // remember.
  //
  // Read from `window.location` after the commit rather than from `usePathname()`, for two reasons.
  // On a client-side navigation the router writes the new URL in an insertion effect, so a read
  // during render still sees the page being left; after the commit it sees the page arrived at. And
  // `usePathname` would be a new import that every test mocking `next/navigation` without it would
  // fail on — over a hundred of them — for a guard none of them is about. Setting the same string
  // again is a no-op, so running after every commit cannot loop.
  const [path, setPath] = useState<string | null>(null);
  useBrowserLayoutEffect(() => {
    setPath(window.location.pathname);
  });

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
    // Only a person the planner is shut to waits for the address; everybody else renders exactly as
    // before. They see the spinner below for one commit, which is before the first paint, and the
    // page's children never mount — so a planner page does not start asking the server for a week
    // it would refuse them with `KMS-400183`.
    if (plannerRefused(appUser)) {
      if (path === null) {
        return (
          <main className="flex min-h-screen items-center justify-center px-6">
            <Loading />
          </main>
        );
      }
      // The same screen as a wrong role, deliberately: to the reader it is the same fact — this part
      // of the app is not theirs — and the menu beside it no longer offers the planner either. But
      // not the same sentence any more (T-363). "You don't have access to this part of the app. Ask
      // your temple administrator." is true of it and says nothing: it leaves somebody who has just
      // moved kitchens with no idea that that is why, and sends them to an administrator who is not
      // told what to change. `KMS-400183` says both, and it is the sentence the server would give
      // them if the request were ever made.
      if (isMealPlannerPath(path)) {
        return <WrongRole role={appUser.role} reason={PLANNER_REFUSAL} />;
      }
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
