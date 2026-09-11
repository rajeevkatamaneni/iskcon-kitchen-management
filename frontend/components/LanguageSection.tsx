"use client";

import { useState } from "react";
import { HintedField } from "@/components/ds/InfoHint";
import { ALL_LANGUAGES } from "@/lib/languages";
import { api, toApiError, type ApiError } from "@/lib/api";

/**
 * The language the temple works in.
 *
 * <p>Its one job today is the job card: the sheet goes to the kitchen, so it prints in the temple's
 * own language unless the person at the printer chooses otherwise (build brief §3). The setting has
 * existed on the temple record since the first migration and has never been writable, so every
 * temple has quietly been English — which mattered to nobody until something started reading it.
 *
 * <p><strong>Its own file since T-077, and the move is the point rather than tidiness.</strong> It
 * lived inside `app/settings/page.tsx` and could only ever be rendered by loading that whole screen,
 * which meant the one thing worth asserting about it — what it reads when the temple's saved
 * language arrives after the first paint — could not be asserted at all.
 */
export function LanguageSection({
  initial,
  getToken,
}: {
  /** The temple's stored locale, region-qualified ("kn-IN"), or null until it has been loaded. */
  initial: string | null;
  getToken: () => Promise<string | undefined>;
}) {
  /**
   * What the person has picked, and nothing else — null until they pick something.
   *
   * <p><strong>Derived, not seeded, and that is T-077.</strong> This was
   * `useState((initial ?? "en-IN").split("-")[0])`, which reads the prop exactly once, on the first
   * render. A prop that arrives later — the temple's own language coming back from the server after
   * the first paint — would never be read, and the picker would sit on English permanently while
   * the temple's setting said Kannada. Pressing Save then wrote English over it, silently, and
   * nothing on the screen would have looked wrong.
   *
   * <p>The repair is to hold only the answer this component actually owns and work the rest out on
   * every render. Deliberately **not** a `useEffect` copying the prop into state: that is the same
   * bug with more steps — it renders once with the wrong value before correcting itself, and it
   * will happily overwrite a choice somebody has already made the moment the prop changes again.
   *
   * <p>Null also means the person's pick outlasts a later refetch, which is the behaviour you want:
   * once they have chosen Kannada, nothing arriving from the server puts English back under their
   * cursor.
   *
   * <p>The same shape as the two other language pickers in the application — the job card's on the
   * planner and the work order's on an ingredient request — both of which already write
   * `language ?? somethingFromTheServer ?? English`.
   */
  const [picked, setPicked] = useState<string | null>(null);
  // Stored region-qualified ("kn-IN"); chosen as a bare language, which is what a person picks.
  const language = picked ?? (initial ?? "en-IN").split("-")[0];

  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function save() {
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      await api.setTempleLanguage(language, await getToken());
      setSaved(true);
    } catch (e) {
      setError(toApiError(e, "We couldn’t save that."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card mt-6 px-7 py-7" aria-label="Language">
      <h2 className="text-lg font-semibold text-ink">Language</h2>
      <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
        The language your kitchen reads. Job cards print in it by default.
      </p>

      {/* The scope of the setting — what it does and does not reach — which is exactly the thing
          somebody wants once, at the moment they are choosing. */}
      <div className="mt-6 max-w-md">
        <HintedField
          label="Your temple’s language"
          hint="This changes what is printed, not what this screen is written in."
        >
          {(id) => (
            <select
              id={id}
              value={language}
              onChange={(e) => setPicked(e.target.value)}
              className="min-h-touch w-full rounded-control border border-hairline px-3 text-ink"
            >
              {ALL_LANGUAGES.map((l) => (
                <option key={l.code} value={l.code}>
                  {l.label}
                </option>
              ))}
            </select>
          )}
        </HintedField>
      </div>

      {error && (
        <div role="alert" className="mt-6 rounded-lg bg-danger-bg px-4 py-3 text-sm text-danger">
          <p className="font-medium">{error.message}</p>
          <p className="mt-0.5">{error.action}</p>
        </div>
      )}
      {saved && !error && <p className="mt-6 text-sm text-success">Saved.</p>}

      <div className="mt-7 flex items-center gap-3 border-t border-hairline pt-6">
        <span className="flex-1" />
        <button
          type="button"
          onClick={save}
          disabled={busy}
          className="btn btn-primary min-h-touch px-6 text-sm transition-colors duration-state disabled:opacity-60"
        >
          {busy ? "Saving…" : "Save"}
        </button>
      </div>
    </section>
  );
}
