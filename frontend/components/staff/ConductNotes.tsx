"use client";

import { useCallback, useState } from "react";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Button } from "@/components/ds/Button";
import { Card } from "@/components/ds/Card";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { moment } from "@/lib/format";
import { api, toApiError, type ApiError, type PrincipalRole } from "@/lib/api";

/**
 * Dated, attributed, permanent notes about how somebody has behaved (E6-S16).
 *
 * <p><b>Why this is not the Notes field.</b> `staff_profiles.notes` is one line with no author and
 * no date, and the next person to press Save destroys what was there. It stays, for the reminders
 * and preferences it has always held. Conduct is a different thing: it is a record about a real
 * person that somebody may have to stand behind, so it carries who wrote it and when, and it cannot
 * be changed afterwards.
 *
 * <p><b>Three fields and no fourth.</b> No rating, no severity, no category, no formal-warning type,
 * no acknowledgement. Each of those is a structured judgement about a person that some screen would
 * eventually sort or total, and nobody has named the reader who would act on the total.
 *
 * <p><b>The permanence is said before the button, not after.</b> Somebody writing about a colleague
 * should know it is permanent while they are choosing their words, not once they have pressed Save.
 *
 * <p><b>The gate.</b> The endpoint is the real guard — it is `MANAGE_STAFF_CONDUCT_NOTES`, which a
 * kitchen manager and kitchen staff do not hold. This renders nothing at all for anybody else, so
 * the panel is absent rather than hidden: nothing is fetched, and there is no markup to read.
 */

/**
 * The roles holding `MANAGE_STAFF_CONDUCT_NOTES`, mirroring `RolePermissions.java`.
 *
 * <p>Written out rather than inferred from the page's own guard, which today happens to be the same
 * set by coincidence. When BL-4 widens the staff screens to a kitchen manager, this list is what has
 * to stay narrow, and it will only stay narrow if it is stated in its own right.
 */
export const CONDUCT_NOTE_ROLES: PrincipalRole[] = ["TEMPLE_ADMIN"];

export function ConductNotes({ staffId }: { staffId: string }) {
  const { appUser, getToken } = useAuth();
  const mayRead = appUser != null && CONDUCT_NOTE_ROLES.includes(appUser.role);

  if (!mayRead) return null;
  return <ConductNotesPanel staffId={staffId} getToken={getToken} />;
}

function ConductNotesPanel({
  staffId,
  getToken,
}: {
  staffId: string;
  getToken: () => Promise<string | undefined>;
}) {
  const notes = useAuthedQuery(
    useCallback((t: string | undefined) => api.staffConductNotes(staffId, t), [staffId])
  );

  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const body = draft.trim();
    if (body === "") return;

    setBusy(true);
    setError(null);
    try {
      await api.addStaffConductNote(staffId, body, await getToken());
      setDraft("");
      notes.reload();
    } catch (caught) {
      setError(toApiError(caught, "We couldn’t save that note."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title="Conduct notes">
      {error && <ErrorNotice error={error} />}
      {notes.error && <ErrorNotice error={notes.error} />}

      {notes.data && notes.data.length > 0 ? (
        <ol className="mb-6 flex flex-col gap-4">
          {notes.data.map((note) => (
            <li key={note.id} className="border-l-2 border-hairline pl-4">
              {/* Who and when first. It is what makes this a record rather than a remark. */}
              <p className="text-xs text-ink-muted">
                {note.authorName} · {moment(note.createdAt)}
              </p>
              <p className="mt-1 whitespace-pre-wrap text-sm text-ink">{note.body}</p>
            </li>
          ))}
        </ol>
      ) : (
        <p className="mb-6 text-sm text-ink-muted">No conduct notes on this record.</p>
      )}

      <form onSubmit={submit} aria-label="Add a conduct note">
        <label className="flex flex-col gap-1 text-sm">
          <span className="pl-field-inset font-medium text-ink">Add a note</span>
          <textarea
            name="body"
            rows={3}
            maxLength={4000}
            required
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            className="rounded border border-hairline bg-canvas px-3 py-2"
          />
        </label>

        {/* Said here, above the button, because this is the moment it changes what somebody writes. */}
        <p className="mt-2 text-sm text-ink-secondary">
          Once saved, a note can’t be edited or deleted.
          <span className="block">Your name and the date are saved with it.</span>
        </p>

        <div className="mt-4">
          <Button type="submit" busy={busy} disabled={busy || draft.trim() === ""}>
            Save note
          </Button>
        </div>
      </form>
    </Card>
  );
}

