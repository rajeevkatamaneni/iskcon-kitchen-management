"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Loading } from "@/components/Loading";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { EmptyState } from "@/components/ds/EmptyState";
import { InlineNotice } from "@/components/ds/InlineNotice";
import {
  IngredientMergeGroup,
  draftFromProposal,
  type MergeDraft,
} from "@/components/IngredientMergeGroup";
import { api } from "@/lib/api";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * Merge duplicate ingredients (R-DUP-3, T-276) — the screen for the one-time clean-up T-270 built
 * the server for.
 *
 * <p><strong>Why it exists.</strong> The recipe library import turned preparations into ingredients
 * of their own, so a temple has "Curd", "Curd, fresh", "Curd, sour" and "Curd, whisked" as four rows,
 * with stock, prices and shopping-list lines split four ways. The server proposes groups whose names
 * are the same once the preparation word is set aside (exact matches only — "Rice" and "Rice,
 * basmati" are varieties, not duplicates, so they are never proposed). The admin approves or edits
 * each group, previews it, confirms, and it merges in one transaction.
 *
 * <p><strong>Temple Admin only.</strong> All three endpoints sit behind `MERGE_INGREDIENTS`, which
 * only the Temple Admin holds, so everyone else gets the app's usual "Not your page" from
 * `RequireRole` rather than a screen that would only be refused. It has no menu item: it is reached
 * from the Ingredients list, where the link is shown to the same people.
 *
 * <p><strong>No mock was drawn for this screen.</strong> It is built from the app's own parts: the
 * ruled table (§5), the page header of `/ingredients`, the confirm layer of
 * `DuplicateIngredientPrompt`, `InlineNotice` for the one green confirmation, `ErrorNotice` for
 * refusals. Rajeev reviews it.
 *
 * <p><strong>One group at a time.</strong> The server merges each group in its own transaction, and
 * this screen sends one at a time: while a merge is in flight every group's controls are disabled.
 * A merged group leaves the list here rather than by reloading the proposals, so the groups the
 * admin has already edited keep their edits.
 */
export default function MergeIngredientsPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <MergeView />
    </RequireRole>
  );
}

function MergeView() {
  const { data, error, loading } = useAuthedQuery(api.listMergeProposals);
  // The whole catalogue, for adding an ingredient to a group by hand. A catalogue that fails to load
  // leaves the picker empty rather than replacing the proposals with an error.
  const { data: catalogue, reload: reloadCatalogue } = useAuthedQuery(api.listIngredients);

  const [drafts, setDrafts] = useState<MergeDraft[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [merged, setMerged] = useState<{ keep: string; names: string[] } | null>(null);

  // Seeded once from the proposals. Held behind a ref so a later refetch cannot throw away edits.
  const seeded = useRef(false);
  useEffect(() => {
    if (seeded.current || !data) return;
    seeded.current = true;
    setDrafts(data.map(draftFromProposal));
  }, [data]);

  const sorted = [...(catalogue ?? [])].sort((a, b) => a.name.localeCompare(b.name));

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/ingredients" />
      <main className="min-w-0 flex-1 px-4 py-10 sm:px-8">
        <div className="mx-auto max-w-content">
          <Link href="/ingredients" className="text-sm link">← Ingredients</Link>
          <header className="mb-6 mt-3">
            <h1>Merge duplicate ingredients</h1>
            <p className="mt-1 max-w-prose text-ink-secondary">
              These ingredients look like the same thing with a preparation added, like “Curd, sour”
              and Curd. Merging moves everything onto the one you keep, and puts the preparation on
              the recipe lines as a note.
            </p>
          </header>

          {merged && (
            <div className="mb-6">
              <InlineNotice tone="success" autoDismiss title={`Merged into ${merged.keep}.`}>
                {merged.names.join(" / ")} {merged.names.length === 1 ? "is" : "are"} now{" "}
                {merged.names.length === 1 ? "an alias" : "aliases"} of {merged.keep}.
              </InlineNotice>
            </div>
          )}

          {loading || (data && !drafts) ? (
            <Loading label="Looking for duplicates…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : !drafts || drafts.length === 0 ? (
            <EmptyState
              title="No duplicates found"
              action={<ButtonLink href="/ingredients" variant="secondary">Back to ingredients</ButtonLink>}
            >
              No two ingredients have the same name once a preparation like “sour” or “halved” is
              set aside.
            </EmptyState>
          ) : (
            drafts.map((draft) => (
              <IngredientMergeGroup
                key={draft.key}
                draft={draft}
                catalogue={sorted}
                busy={busy}
                onBusy={setBusy}
                onChange={(next) => setDrafts((all) => all!.map((d) => (d.key === next.key ? next : d)))}
                onMerged={(done) => {
                  setMerged({
                    keep: done.members.find((m) => m.ingredientId === done.keepId)!.name,
                    names: done.members.filter((m) => m.ingredientId !== done.keepId).map((m) => m.name),
                  });
                  setDrafts((all) => all!.filter((d) => d.key !== done.key));
                  // The merged-away ingredients are gone; the picker must not offer them.
                  reloadCatalogue();
                }}
              />
            ))
          )}
        </div>
      </main>
    </div>
  );
}
