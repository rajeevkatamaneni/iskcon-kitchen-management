"use client";

import { Screen } from "@/components/ds/Screen";
import { Suspense, useCallback, useRef, useState } from "react";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { useParams } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { BackToRecipes } from "@/components/BackToRecipes";
import { ALL_LANGUAGES, ENGLISH, languageLabel } from "@/lib/languages";
import { api, toApiError, type ApiError, type TranslatedRecipe } from "@/lib/api";
import { generateAndDownload } from "@/lib/document-download";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { batchCost, cooksQuantity } from "@/lib/format";
import { BusyPot, Loading } from "@/components/Loading";
import { RULED_TABLE, THEAD, TR, TH_PRIMARY, TD_PRIMARY, TH_FIXED, TD_FIXED_NUM } from "@/components/ds/table";
import { EKADASHI_FRIENDLY, recipeTagLabels } from "@/lib/vaishnava-day";


export default function RecipeDetailPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      {/* The back link reads the search out of the address, and that needs a boundary. */}
      <Suspense>
        <RecipeDetailView />
      </Suspense>
    </RequireRole>
  );
}

function RecipeDetailView() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const { getToken } = useAuth();
  const fetchRecipe = useCallback((t: string | undefined) => api.getRecipe(id, t), [id]);
  const { data: recipe, error, loading } = useAuthedQuery(fetchRecipe);

  const [translated, setTranslated] = useState<TranslatedRecipe | null>(null);
  // What the picker shows. English is the recipe as the temple wrote it, so it is the default and
  // choosing it asks the server for nothing.
  const [language, setLanguage] = useState(ENGLISH.code);
  // Set when a translation failed and the page went back to English, so the error can say so.
  const [translationFellBack, setTranslationFellBack] = useState(false);
  // Choosing Hindi and then Kannada before Hindi has come back must end on Kannada: each request
  // takes a number, and an answer that is no longer the latest is dropped rather than shown.
  const translateRequest = useRef(0);
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

  /*
    Choosing a language translates at once; there is no Translate button any more (Rajeev,
    2026-09-19). English goes straight back to the recipe as written, without a request. A failure
    puts the page back in English and says so, rather than leaving a language picked that the
    screen is not showing.
  */
  async function chooseLanguage(code: string) {
    const request = ++translateRequest.current;
    setLanguage(code);
    setTranslationFellBack(false);
    setActionError(null);
    if (code === ENGLISH.code) {
      setTranslated(null);
      setBusy((b) => (b === "translating" ? null : b));
      return;
    }
    setBusy("translating");
    try {
      const result = await api.translateRecipe(id, code, await getToken());
      if (request !== translateRequest.current) return;
      setTranslated(result);
    } catch (e) {
      if (request !== translateRequest.current) return;
      setTranslated(null);
      setLanguage(ENGLISH.code);
      setTranslationFellBack(true);
      setActionError(toApiError(e, "We couldn’t translate this recipe."));
    } finally {
      if (request === translateRequest.current) setBusy((b) => (b === "translating" ? null : b));
    }
  }

  async function downloadPdf() {
    await run("pdf", async (token) =>
      generateAndDownload({
        request: () =>
          api.requestRecipePdf(
            id,
            // The PDF is in the language on screen: the translation showing, or English.
            { language: translated ? translated.language : undefined },
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
      const err = toApiError(e, "We couldn’t delete that recipe.");
      setActionError(err);
      // KMS-400102 is not a dead end — it is the server saying "archive it instead", so offer that.
      setOfferArchive(err.code === "KMS-400102");
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
      setActionError(toApiError(e, "Couldn’t finish that. Try again."));
    } finally {
      setBusy(null);
    }
  }

  return (
    <Chrome>
      <div className="flex items-center justify-between">
        <BackToRecipes />
        <div className="flex items-center gap-2">
          <ButtonLink href={`/recipes/${id}/edit`} variant="secondary">
            Edit
          </ButtonLink>
          {recipe.status === "ARCHIVED" ? (
            <Button variant="secondary" onClick={restore} disabled={busy !== null}>
              {busy === "restoring" ? "Restoring…" : "Restore"}
            </Button>
          ) : (
            <Button
              variant="danger"
              onClick={() => {
                setConfirmingDelete(true);
                setActionError(null);
                setOfferArchive(false);
              }}
              disabled={busy !== null}
            >
              Delete
            </Button>
          )}
        </div>
      </div>

      {recipe.status === "ARCHIVED" && (
        <div className="mt-4 rounded-lg bg-sunken px-5 py-4 text-sm text-ink-secondary">
          <p className="font-medium text-ink">This recipe is archived.</p>
          <p className="mt-0.5">
            It stays on every meal cooked from it. Restore it to plan with it again.
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
            {/* True to RecipeService.delete: the server refuses a recipe that is on any meal, planned
                or cooked, and this page then offers "Archive it instead". Nothing is archived on
                its own. Its translations and printed cards go too, but a cook does not need telling. */}
            This deletes the recipe and its ingredient list. You can’t undo this. If it is on any
            meal, planned or cooked, you’ll be offered Archive instead.
          </p>
          <div className="mt-4 flex gap-2">
            <Button variant="danger" onClick={deleteRecipe} busy={busy === "deleting"} disabled={busy !== null}>
              {busy === "deleting" ? "Deleting…" : "Delete recipe"}
            </Button>
            <Button variant="secondary" onClick={() => setConfirmingDelete(false)}>
              Cancel
            </Button>
          </div>
        </div>
      )}

      <header className="mt-2 mb-6">
        {/* The name, then what kind of dish it is and its tags, on one line beside it, rather than
            the category alone at the far right edge and the tags on a row of their own (Rajeev,
            2026-09-18: use the width, don't add rows). They wrap under the name only when the
            screen is too narrow for both. The subtitle stays under the name. */}
        <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
          <h1 className="min-w-0">{translated ? translated.name : recipe.name}</h1>
          <span className="rounded-control bg-sunken px-2 py-0.5 text-xs font-semibold text-ink-secondary">
            {translated ? translated.categoryName : recipe.categoryName}
          </span>
          {recipe.fastingCompatible && (
            <span className="rounded-control bg-accent-bg px-2 py-0.5 text-xs text-accent-text font-semibold">{EKADASHI_FRIENDLY}</span>
          )}
          {translated && (
            <span className="rounded-control bg-sunken px-2 py-0.5 text-xs text-ink-secondary font-semibold">
              {languageLabel(translated.language)} · machine translation
            </span>
          )}
        </div>
        {recipe.subtitle && <p className="mt-1 text-ink-secondary">{recipe.subtitle}</p>}
        {/*
          The two figures a planner actually reads, as labelled facts rather than buried at the end
          of one grey sentence — which is where "0.2 litres a head" was, and where nobody found it.
          The library's own screen has always shown them this way; a temple's recipe is the same kind
          of thing and now reads the same.
        */}
        {/* The batch cost is the third fact (Rajeev, 2026-09-18, T-233), for everyone who can
            open the recipe, written as the library writes it ("₹8,000 per batch (270 L)") and
            absent when nobody has saved one. On a phone the facts pack along one line rather than
            sitting two to a row, and their figures are one size smaller, so the third fits beside
            the first two and the page is no taller than it was — measured across the temple's
            recipes at 390, where "Makes 200 pieces · Per person 2 pieces · ₹2,650 per batch
            (200 pieces)" otherwise took a second row. The four even columns wait for `xl`:
            at 768 a quarter of the width was 164px and the cost broke onto a second line in 11 of
            the 38 recipes, so between the two the facts stay packed along the line. */}
        <dl className="mt-4 flex flex-wrap gap-4 sm:gap-x-8 xl:grid xl:grid-cols-4 xl:gap-4">
          <Fact label="Makes" value={cooksQuantity(recipe.baseYieldQty, recipe.baseYieldUnit)} />
          <Fact
            label="Per person"
            value={
              recipe.perHeadQty != null && recipe.perHeadUnit
                ? cooksQuantity(recipe.perHeadQty, recipe.perHeadUnit)
                : "Not set"
            }
          />
          {recipe.indicativeCost != null && (
            <Fact label="Rough cost" value={batchCost(recipe.indicativeCost, recipe.baseYieldQty, recipe.baseYieldUnit)} />
          )}
        </dl>

        {/* What the source said, verbatim. "839 pieces" tells a cook nothing; "300 idlis
            (3 per devotee)" tells them everything. */}
        {recipe.yieldNote && recipe.yieldNote !== cooksQuantity(recipe.baseYieldQty, recipe.baseYieldUnit) && (
          <p className="mt-3 text-ink-secondary">{recipe.yieldNote}</p>
        )}

        {recipe.tags.length > 0 && (
          <ul className="mt-3 flex flex-wrap gap-2">
            {/* The library's "Ekadashi-safe" tag reads as the one name, and not twice beside the badge. */}
            {recipeTagLabels(recipe.tags, recipe.fastingCompatible ? [EKADASHI_FRIENDLY] : []).map((tag) => (
              <li key={tag} className="rounded-control bg-sunken px-2 py-0.5 text-xs text-ink-secondary">
                {tag}
              </li>
            ))}
          </ul>
        )}
      </header>

      {actionError && (
        <div className="mb-4">
          <ErrorNotice error={actionError} />
          {translationFellBack && (
            <p className="mt-2 text-sm text-ink-secondary">The recipe is shown in English.</p>
          )}
          {offerArchive && (
            <Button variant="secondary" onClick={archive} disabled={busy !== null} className="mt-3">
              {busy === "archiving" ? "Archiving…" : "Archive it instead"}
            </Button>
          )}
        </div>
      )}

      {/*
        The language picker and the PDF sit on the ingredients' own heading line, at the right above
        Quantity (Rajeev, 2026-09-19: "right on top of the ingredients table above Quantity"). They
        replace a card of their own that held Scale to, Translate and Download PDF, so the page is
        shorter by that card. Scale went with it: a planner scales a dish on the meal, and the job
        card prints the scaled amounts, so the recipe page shows the recipe as written.

        The download is an icon, which DESIGN_SYSTEM §6 otherwise keeps to navigation: it is his
        decision for this one place, and it carries its name for a screen reader and as a tooltip.
      */}
      <div className="mb-2 flex items-center justify-between gap-3">
        <h2 className="text-lg">Ingredients</h2>
        <div className="flex shrink-0 items-center gap-2">
          {/* Kept in the page while empty so the change is announced when a translation starts. */}
          <span role="status" className="flex items-center">
            {busy === "translating" && (
              <>
                <BusyPot />
                <span className="sr-only">Translating…</span>
              </>
            )}
          </span>
          <label className="flex items-center">
            <span className="sr-only">Language</span>
            <select
              value={language}
              onChange={(e) => chooseLanguage(e.target.value)}
              className="min-h-touch max-w-[11rem] rounded-control border border-hairline px-3 text-sm"
            >
              {ALL_LANGUAGES.map((l) => (
                <option key={l.code} value={l.code}>{l.label}</option>
              ))}
            </select>
          </label>
          <button
            type="button"
            disabled={busy === "pdf"}
            onClick={downloadPdf}
            aria-label="Download recipe as PDF"
            title="Download recipe as PDF"
            // Stays an icon — DESIGN_SYSTEM §6 allows this one download to — but takes the secondary
            // button material and its press, like every other button (T-242).
            className="btn btn-secondary flex min-h-touch min-w-touch items-center justify-center disabled:opacity-60"
          >
            {busy === "pdf" ? <BusyPot /> : <i className="ti ti-download text-lg" aria-hidden="true" />}
          </button>
        </div>
      </div>

      <div className="table-wrap overflow-x-auto">
        <table className={RULED_TABLE}>
          <thead className={THEAD}>
            <tr>
              <th className={TH_PRIMARY}>Ingredient</th>
              <th className={TH_FIXED}>Quantity</th>
            </tr>
          </thead>
          <tbody>
            {/* Keyed by position as well as ingredient: since the library import stopped making
                "Coconut, grated" an ingredient of its own, one recipe can hold Coconut twice with
                two different preparation notes (R-DUP-1). */}
            {recipe.ingredients.map((line, i) => (
              <tr key={`${line.ingredientId}-${i}`} className={TR}>
                <td className={TD_PRIMARY}>
                  {withPreparation(
                    translated?.ingredients[i]?.name ?? line.ingredientName,
                    translated?.ingredients[i]?.preparationNote ?? line.preparationNote
                  )}
                </td>
                <td className={TD_FIXED_NUM}>
                  {cooksQuantity(line.quantity, line.unit)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {method.length > 0 && (
        <section className="mt-6">
          <h2 className="text-lg">Method</h2>
          <ol className="mt-2 max-w-prose list-decimal space-y-2 pl-5">
            {method.map((step, i) => (
              <li key={i}>{step}</li>
            ))}
          </ol>
        </section>
      )}

      {recipe.notes && <p className="mt-6 max-w-prose text-ink-secondary">{recipe.notes}</p>}

      {/* Everything a recipe book carries beyond the cooking. Each heading appears only where it has
          something under it — an empty heading is worse than an absent one. */}
      <RecipeNote heading="Why this dish" body={recipe.why} />
      <RecipeNote heading="Start" body={recipe.noteStart} />
      <RecipeNote heading="Vessel" body={recipe.noteVessel} />
      <RecipeNote heading="Season" body={recipe.noteSeason} />
      {/* No "Catering" note: catering is out of the product (E4-S15), and Rajeev had the heading
          taken off every recipe screen on 2026-09-18. The stored value is left as it was. */}
      <RecipeNote
        heading="Serve with"
        body={recipe.serveWith.length > 0 ? recipe.serveWith.join(" · ") : null}
      />
    </Chrome>
  );
}

function Chrome({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/recipes" />
      {/* The shared page frame, so this page starts where every other screen does. One wrapper
          inside it keeps this page's own spacing between its blocks. */}
      <main className="min-w-0 flex-1">
        <Screen>
          <div>{children}</div>
        </Screen>
      </main>
    </div>
  );
}

/**
 * A line's name with its preparation note, "Green chilli · slit" (R-DUP-1) — the form the job card,
 * the recipe PDF and the recipe peek print too. The name alone when there is no note.
 */
function withPreparation(name: string, note: string | null | undefined): string {
  return note ? `${name} · ${note}` : name;
}

function splitMethod(method: string | null): string[] {
  if (!method) return [];
  return method.split(/\r?\n/).map((s) => s.trim()).filter(Boolean);
}

/** One of the book's notes, or nothing at all where the recipe never carried it. */
function RecipeNote({ heading, body }: { heading: string; body: string | null }) {
  if (!body) return null;
  return (
    <section className="mt-6">
      <h2 className="text-xs uppercase tracking-eyebrow text-ink-muted">{heading}</h2>
      <p className="mt-1 max-w-prose text-ink-secondary">{body}</p>
    </section>
  );
}

/** One labelled figure, written the way the shared library writes it. */
function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs uppercase tracking-eyebrow text-ink-muted">{label}</dt>
      <dd className="mt-1 text-sm sm:text-base">{value}</dd>
    </div>
  );
}


