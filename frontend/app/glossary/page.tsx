"use client";

import { useCallback, useState } from "react";
import Link from "next/link";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

const LANGUAGES: { code: string; label: string }[] = [
  { code: "hi", label: "Hindi" },
  { code: "kn", label: "Kannada" },
  { code: "te", label: "Telugu" },
  { code: "ta", label: "Tamil" },
  { code: "mr", label: "Marathi" },
];

export default function GlossaryPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_STAFF"]}>
      <GlossaryView />
    </RequireRole>
  );
}

function GlossaryView() {
  const { getToken } = useAuth();
  const fetchGlossary = useCallback((t: string | undefined) => api.listGlossary(undefined, t), []);
  const { data, error, loading, reload } = useAuthedQuery(fetchGlossary);
  const entries = data ?? [];

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);

  async function run(mutation: (token: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    setActionError(null);
    try {
      await mutation(await getToken());
      reload();
      return true;
    } catch (e) {
      setActionError(toApiError(e, failure));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function add(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const f = new FormData(form);
    const ok = await run(
      (token) =>
        api.addGlossaryEntry(
          {
            language: String(f.get("language") ?? "hi"),
            sourceTerm: String(f.get("sourceTerm") ?? ""),
            targetTerm: String(f.get("targetTerm") ?? ""),
          },
          token
        ),
      "We couldn't save that term."
    );
    if (ok) form.reset();
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/recipes" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-8">
            <Link href="/recipes" className="text-sm text-ink-secondary hover:text-ink">← Recipes</Link>
            <h1 className="mt-2">Translation glossary</h1>
            <p className="mt-1 text-ink-secondary">
              Preferred translations for culinary terms — used before machine translation, so a term
              like Toor Dal is always rendered the way your temple says it.
            </p>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

          <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="add-heading">
            <h2 id="add-heading" className="text-lg">Add a term</h2>
            <form className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-[8rem_1fr_1fr_auto]" aria-label="Add a glossary term" onSubmit={add}>
              <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                Language
                <select name="language" className="min-h-touch rounded border border-hairline bg-canvas px-3">
                  {LANGUAGES.map((l) => <option key={l.code} value={l.code}>{l.label}</option>)}
                </select>
              </label>
              <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                English term
                <input name="sourceTerm" required placeholder="Toor Dal" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
              </label>
              <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                Preferred translation
                <input name="targetTerm" required placeholder="तूर दाल" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
              </label>
              <div className="flex items-end">
                <button type="submit" disabled={busy} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">
                  Add
                </button>
              </div>
            </form>
          </section>

          {loading ? (
            <p className="text-ink-secondary">Loading glossary…</p>
          ) : error ? (
            <ErrorNotice error={error} />
          ) : entries.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">No terms yet</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                Add the terms machine translation gets wrong, and they&rsquo;ll always render your way.
              </p>
            </div>
          ) : (
            <div className="overflow-hidden rounded-lg bg-raised">
              <table className="w-full text-left">
                <thead className="bg-sunken text-sm text-ink-secondary">
                  <tr>
                    <th className="px-5 py-3 font-medium">Language</th>
                    <th className="px-5 py-3 font-medium">English</th>
                    <th className="px-5 py-3 font-medium">Preferred</th>
                    <th className="px-5 py-3 font-medium">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {entries.map((e) => (
                    <tr key={e.id} className="border-t border-hairline">
                      <td className="px-5 py-3 text-ink-secondary">
                        {LANGUAGES.find((l) => l.code === e.language)?.label ?? e.language}
                      </td>
                      <td className="px-5 py-3">{e.sourceTerm}</td>
                      <td className="px-5 py-3">{e.targetTerm}</td>
                      <td className="px-5 py-3 text-sm">
                        <button type="button" disabled={busy}
                          onClick={() => run((t) => api.deleteGlossaryEntry(e.id, t), "We couldn't remove that term.")}
                          className="text-danger hover:underline">
                          Delete
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </main>
    </div>
  );
}
