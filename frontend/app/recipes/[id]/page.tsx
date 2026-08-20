"use client";

import { useCallback, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { SCHEDULED_LANGUAGES, languageLabel } from "@/lib/languages";
import { api, toApiError, type ApiError, type ScaledRecipe, type TranslatedRecipe } from "@/lib/api";
import { generateAndDownload } from "@/lib/document-download";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { BusyPot, Loading } from "@/components/Loading";

const UNIT_LABEL: Record<string, string> = { KG: "Kg", GM: "gm", L: "L", ML: "ml", PIECES: "pieces" };

export default function RecipeDetailPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <RecipeDetailView />
    </RequireRole>
  );
}

function RecipeDetailView() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const { getToken } = useAuth();
  const fetchRecipe = useCallback((t: string | undefined) => api.getRecipe(id, t), [id]);
  const { data: recipe, error, loading } = useAuthedQuery(fetchRecipe);

  const [scaled, setScaled] = useState<ScaledRecipe | null>(null);
  const [targetYield, setTargetYield] = useState("");
  const [translated, setTranslated] = useState<TranslatedRecipe | null>(null);
  const [language, setLanguage] = useState("hi");
  const [busy, setBusy] = useState<string | null>(null);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  // Asking before removing something, and — when the answer is "this one has been cooked" —
  // offering the thing that does work rather than leaving the person at a refusal.
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [offerArchive, setOfferArchive] = useState(false);

  if (loading) return <Chrome><Loading /></Chrome>;
  if (error) return <Chrome><ErrorNotice error={error} /></Chrome>;
  if (!recipe) return null;

  const method = translated ? translated.method : splitMethod(recipe.method);

  async function applyScale() {
    const target = Number(targetYield);
    if (!Number.isFinite(target) || target <= 0) return;
    await run("scaling", async (token) => setScaled(await api.scaleRecipe(id, target, token)));
  }

  async function applyTranslate() {
    await run("translating", async (token) => setTranslated(await api.translateRecipe(id, language, token)));
  }

  async function downloadPdf() {
    await run("pdf", async (token) =>
      generateAndDownload({
        request: () =>
          api.requestRecipePdf(
            id,
            { targetYield: scaled ? scaled.targetYield : undefined, language: translated ? translated.language : undefined },
            token
          ),
        status: (documentId) => api.getDocument(documentId, token),
        download: (documentId) => api.downloadDocument(documentId, token),
        filename: `${recipe!.name}.pdf`,
      })
    );
  }

  async function deleteRecipe() {
    setBusy("deleting");
    setActionError(null);
    try {
      await api.deleteRecipe(id, await getToken());
      // Nothing to return to: the recipe is gone, so the list is the only honest destination.
      window.location.href = "/recipes";
    } catch (e) {
      const err = toApiError(e, "We couldn't delete that recipe.");
      setActionError(err);
      // KMS-4967 is not a dead end — it is the server saying "archive it instead", so offer that.
      setOfferArchive(err.code === "KMS-4967");
      setConfirmingDelete(false);
      setBusy(null);
    }
  }

  async function archive() {
    await run("archiving", async (token) => {
      await api.archiveRecipe(id, token);
      window.location.href = "/recipes";
    });
  }

  async function restore() {
    await run("restoring", async (token) => {
      await api.restoreRecipe(id, token);
      window.location.reload();
    });
  }

  async function run(kind: string, fn: (token: string | undefined) => Promise<void>) {
    setBusy(kind);
    setActionError(null);
    try {
      fn && (await fn(await getToken()));
    } catch (e) {
      setActionError(toApiError(e, "That didn't work."));
    } finally {
      setBusy(null);
    }
  }

  return (
    <Chrome>
      <div className="flex items-center justify-between">
        <Link href="/recipes" className="text-sm text-ink-secondary hover:text-ink">
          ← Recipes
        </Link>
        <div className="flex items-center gap-2">
          {recipe.status === "ARCHIVED" ? (
            <button
              type="button"
              onClick={restore}
              disabled={busy !== null}
              className="min-h-touch flex items-center rounded border border-hairline-strong px-4 text-sm transition-colors duration-state hover:bg-raised disabled:opacity-60"
            >
              {busy === "restoring" ? "Restoring…" : "Restore"}
            </button>
          ) : (
            <button
              type="button"
              onClick={() => {
                setConfirmingDelete(true);
                setActionError(null);
                setOfferArchive(false);
              }}
              disabled={busy !== null}
              className="min-h-touch flex items-center rounded border border-hairline-strong px-4 text-sm text-danger transition-colors duration-state hover:bg-danger-bg disabled:opacity-60"
            >
              Delete
            </button>
          )}
          <Link
            href={`/recipes/${id}/edit`}
            className="min-h-touch flex items-center rounded border border-hairline-strong px-4 text-sm transition-colors duration-state hover:bg-raised"
          >
            Edit
          </Link>
        </div>
      </div>

      {recipe.status === "ARCHIVED" && (
        <div className="mt-4 rounded-lg bg-sunken px-5 py-4 text-sm text-ink-secondary">
          <p className="font-medium text-ink">This recipe is archived.</p>
          <p className="mt-0.5">
            It stays here, and on every meal that was cooked from it, but it won&rsquo;t appear when
            somebody plans a meal. Restore it to use it again.
          </p>
        </div>
      )}

      {confirmingDelete && (
        <div
          role="alertdialog"
          aria-label="Delete this recipe"
          className="mt-4 rounded-lg bg-danger-bg px-5 py-4"
        >
          <p className="text-sm font-medium text-danger">Delete {recipe.name}?</p>
          <p className="mt-0.5 max-w-[60ch] text-sm text-ink-secondary">
            This removes the recipe, its ingredients and any cards made from it. It cannot be undone.
            If the recipe has ever been cooked we won&rsquo;t delete it — we&rsquo;ll offer to
            archive it instead, so the record of what was served keeps its dish.
          </p>
          <div className="mt-4 flex gap-2">
            <button
              type="button"
              onClick={deleteRecipe}
              disabled={busy !== null}
              className="min-h-touch rounded bg-danger px-5 text-sm text-ink-inverse transition-opacity duration-state hover:opacity-90 disabled:opacity-60"
            >
              {busy === "deleting" ? "Deleting…" : "Delete recipe"}
            </button>
            <button
              type="button"
              onClick={() => setConfirmingDelete(false)}
              className="min-h-touch rounded border border-hairline-strong px-5 text-sm transition-colors duration-state hover:bg-raised"
            >
              Cancel
            </button>
          </div>
        </div>
      )}

      <header className="mt-2 mb-6">
        <div className="flex flex-wrap items-baseline justify-between gap-3">
          <h1>{translated ? translated.name : recipe.name}</h1>
          <span className="text-ink-secondary">{translated ? translated.categoryName : recipe.categoryName}</span>
        </div>
        <div className="mt-2 flex flex-wrap gap-2">
          {recipe.fastingCompatible && (
            <span className="rounded-sm bg-accent-bg px-2 py-0.5 text-xs text-accent-text">Ekadashi-friendly</span>
          )}
          {recipe.sattvicOverrideReason && (
            <span className="rounded-sm bg-warning-bg px-2 py-0.5 text-xs text-warning">
              Sattvic override: {recipe.sattvicOverrideReason}
            </span>
          )}
          {translated && (
            <span className="rounded-sm bg-sunken px-2 py-0.5 text-xs text-ink-secondary">
              {languageLabel(translated.language)} · via {translated.provider}
            </span>
          )}
        </div>
        <p className="mt-3 text-ink-secondary">
          {scaled
            ? `Scaled to ${scaled.targetYield} ${recipe.baseYieldUnit.toLowerCase()} (base ${recipe.baseYieldQty})`
            : `Base yield ${recipe.baseYieldQty} ${recipe.baseYieldUnit.toLowerCase()}`}
        </p>
      </header>

      {actionError && (
        <div className="mb-4">
          <ErrorNotice error={actionError} />
          {offerArchive && (
            <button
              type="button"
              onClick={archive}
              disabled={busy !== null}
              className="mt-3 min-h-touch rounded border border-hairline-strong px-5 text-sm transition-colors duration-state hover:bg-raised disabled:opacity-60"
            >
              {busy === "archiving" ? "Archiving…" : "Archive it instead"}
            </button>
          )}
        </div>
      )}

      {/* Controls */}
      <section className="mb-6 flex flex-wrap items-end gap-4 rounded-lg bg-raised px-5 py-4">
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          Scale to
          <span className="flex gap-2">
            <input
              type="number"
              min="1"
              value={targetYield}
              onChange={(e) => setTargetYield(e.target.value)}
              placeholder={String(recipe.baseYieldQty)}
              className="min-h-touch w-28 rounded border border-hairline bg-canvas px-3"
            />
            <button
              type="button"
              disabled={busy !== null}
              onClick={applyScale}
              className="min-h-touch rounded bg-accent px-4 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60"
            >
              {busy === "scaling" ? (<span className="inline-flex items-center gap-2"><BusyPot />Scaling…</span>) : "Scale"}
            </button>
            {scaled && (
              <button type="button" onClick={() => setScaled(null)} className="min-h-touch rounded border border-hairline-strong px-3 text-sm">
                Reset
              </button>
            )}
          </span>
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          Translate
          <span className="flex gap-2">
            <select
              value={language}
              onChange={(e) => setLanguage(e.target.value)}
              className="min-h-touch rounded border border-hairline bg-canvas px-3"
            >
              {SCHEDULED_LANGUAGES.map((l) => (
                <option key={l.code} value={l.code}>{l.label}</option>
              ))}
            </select>
            <button
              type="button"
              disabled={busy !== null}
              onClick={applyTranslate}
              className="min-h-touch rounded border border-accent-border bg-accent-bg px-4 text-accent-text transition-colors duration-state hover:bg-accent-border disabled:opacity-60"
            >
              {busy === "translating" ? (<span className="inline-flex items-center gap-2"><BusyPot />Translating…</span>) : "Translate"}
            </button>
            {translated && (
              <button type="button" onClick={() => setTranslated(null)} className="min-h-touch rounded border border-hairline-strong px-3 text-sm">
                Original
              </button>
            )}
          </span>
        </label>

        <button
          type="button"
          disabled={busy !== null}
          onClick={downloadPdf}
          className="min-h-touch rounded border border-hairline-strong px-5 transition-colors duration-state hover:bg-sunken disabled:opacity-60"
        >
          {busy === "pdf" ? (<span className="inline-flex items-center gap-2"><BusyPot />Preparing PDF…</span>) : "Download PDF"}
        </button>
      </section>

      <div className="overflow-hidden rounded-lg bg-raised">
        <table className="w-full text-left">
          <thead className="bg-sunken text-sm text-ink-secondary">
            <tr>
              <th className="px-5 py-3 font-medium">Ingredient</th>
              <th className="px-5 py-3 text-right font-medium">Quantity</th>
            </tr>
          </thead>
          <tbody>
            {recipe.ingredients.map((line, i) => (
              <tr key={line.ingredientId} className="border-t border-hairline">
                <td className="px-5 py-3">
                  {translated?.ingredients[i]?.name ?? line.ingredientName}
                  {line.sattvicProhibited && (
                    <span className="ml-2 rounded-sm bg-warning-bg px-1.5 py-0.5 text-xs text-warning">prohibited</span>
                  )}
                </td>
                <td className="px-5 py-3 text-right tabular-nums">
                  {scaled
                    ? `${scaled.ingredients[i]?.displayQuantity} ${scaled.ingredients[i]?.displayUnit}`
                    : `${line.quantity} ${UNIT_LABEL[line.unit] ?? line.unit}`}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {method.length > 0 && (
        <section className="mt-6">
          <h2 className="text-lg">Method</h2>
          <ol className="mt-2 list-decimal space-y-2 pl-5">
            {method.map((step, i) => (
              <li key={i}>{step}</li>
            ))}
          </ol>
        </section>
      )}

      {recipe.notes && <p className="mt-6 max-w-prose text-ink-secondary">{recipe.notes}</p>}
    </Chrome>
  );
}

function Chrome({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/recipes" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">{children}</div>
      </main>
    </div>
  );
}

function splitMethod(method: string | null): string[] {
  if (!method) return [];
  return method.split(/\r?\n/).map((s) => s.trim()).filter(Boolean);
}

