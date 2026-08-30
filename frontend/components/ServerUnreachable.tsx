"use client";

/**
 * What a person sees when we cannot reach the server to find out who they are.
 *
 * <p>It exists because the alternative was a lie. Every failure of {@code /whoami} used to be read
 * as "this person has no account here", which sent them to the temple picker and told them, in as
 * many words, that they belong to nowhere. Most of the time that was true. But it was equally what
 * a deploy looked like, and a dropped connection, and a cold start — so during any release, every
 * person with the application open was informed that their account had gone.
 *
 * <p>The distinction now comes from the HTTP status: a 401 means the server answered and does not
 * know this person, anything else means it did not answer. Only the first is about them.
 *
 * <p>Deliberately not styled as an error. Nothing has gone wrong with their account, nothing is
 * lost, and in nine cases out of ten the next attempt succeeds — so it says what happened, offers
 * the button, and does not alarm anybody.
 */
export function ServerUnreachable({ onRetry }: { onRetry: () => void }) {
  return (
    <main className="mx-auto flex min-h-screen max-w-prose flex-col justify-center px-6 py-12">
      <h1>We can’t reach the server</h1>
      <p className="mt-2 text-ink-secondary">
        Your account is fine. This is usually a moment’s interruption — the application may be
        being updated, or the connection may have dropped.
      </p>
      <div className="mt-6">
        <button
          type="button"
          onClick={onRetry}
          className="min-h-touch rounded-lg bg-accent px-6 text-sm text-ink-inverse transition-colors duration-state hover:bg-accent-hover"
        >
          Try again
        </button>
      </div>
    </main>
  );
}
