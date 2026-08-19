"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { api, type PublicCommunication } from "@/lib/api";

/**
 * The web copy of a message a temple sent (E8-S2).
 *
 * <p>This is what the WhatsApp form of a communication actually points at — Meta will not carry a
 * letter, so the letter lives here — and what the "read this in your browser" line in every email
 * opens. Public, because a devotee halfway through a newsletter on a phone should not meet a sign-in
 * screen.
 *
 * <p>Rendered with {@code dangerouslySetInnerHTML}, and the name is doing its job: that is only safe
 * because the server sanitised this letter when it was saved, against a small allow-list, and stores
 * it already clean. If that ever stops being true, this line is the hole.
 *
 * <p>A draft has no address here at all — only a sent communication resolves — so nothing
 * half-written is ever readable, and a wrong address is not found rather than being told it exists.
 */
export default function PublicCommunicationPage() {
  const token = useParams<{ token: string }>().token;
  const [letter, setLetter] = useState<PublicCommunication | null>(null);
  const [missing, setMissing] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const found = await api.publicCommunication(token);
        if (!cancelled) setLetter(found);
      } catch {
        if (!cancelled) setMissing(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [token]);

  if (missing) {
    return (
      <main className="mx-auto flex min-h-screen max-w-xl flex-col justify-center px-6 py-16 text-center">
        <h1 className="text-2xl font-semibold text-ink">Not found</h1>
        <p className="mt-3 text-ink-secondary">
          This message may have been removed, or the address may be incomplete.
        </p>
      </main>
    );
  }

  if (!letter) {
    return (
      <main className="mx-auto max-w-2xl px-6 py-16 text-ink-secondary">Loading…</main>
    );
  }

  return (
    <main className="mx-auto max-w-2xl px-6 py-12">
      <header className="mb-8 border-b border-hairline pb-6">
        <p className="text-sm text-accent-text">{letter.templeName}</p>
        <h1 className="mt-1 text-3xl font-semibold text-ink">{letter.subject}</h1>
        {letter.sentAt && (
          <p className="mt-2 text-sm text-ink-muted">
            {new Date(letter.sentAt).toLocaleDateString(undefined, {
              day: "numeric",
              month: "long",
              year: "numeric",
            })}
          </p>
        )}
      </header>

      {/* Sanitised on the way in — see the class comment. */}
      <div
        className="prose-temple text-base leading-relaxed text-ink"
        dangerouslySetInnerHTML={{ __html: letter.bodyHtml ?? "" }}
      />
    </main>
  );
}
