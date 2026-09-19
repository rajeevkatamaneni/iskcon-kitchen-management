"use client";

import { useRef, useState } from "react";
import { Form } from "@/components/ds/Form";
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
 *
 * <p><strong>Read-only until Edit, like every other section of Settings but Appearance
 * (T-185).</strong> Rajeev, 2026-09-13: *"The default state of the screen shuld be read only to avoid
 * accidental mistakes. The way to get it to edit is using the 'Edit' button. When clicked the fields
 * become editable and the button reads 'Save'. This applies for all sections on the settinsg
 * scree"*. T-169b put the other five sections under that rule and missed this one, because it lives
 * in its own file. The behaviour, the words and the look are T-169b's, copied rather than imported:
 * `useEditMode`, `EditActions` and the sunken read-only fill are private to `app/settings/page.tsx`,
 * and reaching into a page from a component would make the component depend on the screen that
 * mounts it. If a third copy is ever wanted, that is the moment to move them into `components/ds`.
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

  /**
   * <p><strong>The snapshot is `picked`, not `language`, and that keeps T-077 fixed.</strong> Cancel
   * puts back what the component held when Edit was pressed. Before any pick that is null, so after
   * Cancel the picker is derived from the prop again and still reads a temple language that arrives
   * later. Snapshotting the shown value would copy the prop into state on every Cancel, which is the
   * exact bug the comment above describes.
   */
  const edit = useEditMode(picked, (before) => {
    setPicked(before);
    setError(null);
  });

  async function save() {
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      await api.setTempleLanguage(language, await getToken());
      setSaved(true);
      edit.close();
    } catch (e) {
      setError(toApiError(e, "We couldn’t save that."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card mt-6 px-5 py-6 sm:px-7 sm:py-7" aria-label="Language">
      <h2 className="text-lg font-semibold text-ink">Language</h2>
      <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
        The language your kitchen reads. Job cards print in it by default.
      </p>

      {/* The scope of the setting — what it does and does not reach — which is exactly the thing
          somebody wants once, at the moment they are choosing.

          The shared Form, as on the other sections, so this one reads and behaves like them. Nothing
          on it can be refused: the select always holds one of the languages, so `required` would
          change nothing and is left off rather than implying a blank is possible. */}
      <Form
        key={edit.formKey}
        id="language-form"
        className="mt-6 max-w-md"
        onSubmit={(event) => {
          event.preventDefault();
          if (edit.editing) void save();
        }}
      >
        <HintedField
          label="Your temple’s language"
          hint="This changes what is printed, not what this screen is written in."
        >
          {(id) => (
            // A <select> has no read-only state, so it is disabled until Edit and takes the same
            // sunken fill, exactly as the payment gateway's provider select does.
            <select
              id={id}
              value={language}
              onChange={(e) => setPicked(e.target.value)}
              disabled={!edit.editing}
              className="min-h-touch w-full rounded-control border border-hairline px-3 text-ink disabled:bg-sunken"
            >
              {ALL_LANGUAGES.map((l) => (
                <option key={l.code} value={l.code}>
                  {l.label}
                </option>
              ))}
            </select>
          )}
        </HintedField>
      </Form>

      {error && (
        <div role="alert" className="mt-6 rounded-lg bg-danger-bg px-4 py-3 text-sm text-danger">
          <p className="font-medium">{error.message}</p>
          <p className="mt-0.5">{error.action}</p>
        </div>
      )}
      {saved && !error && <p className="mt-6 text-sm text-success">Saved.</p>}

      <div className="mt-7 flex items-center gap-3 border-t border-hairline pt-6">
        <span className="flex-1" />
        <EditActions
          editing={edit.editing}
          formId="language-form"
          disabled={busy}
          saving={busy}
          onEdit={() => {
            setSaved(false);
            edit.open();
          }}
          onCancel={edit.cancel}
        />
      </div>
    </section>
  );
}

// ---- Editing, copied from app/settings/page.tsx (T-169b) -------------------

/**
 * Read-only until Edit. A copy of `useEditMode` in `app/settings/page.tsx`, line for line; the
 * reasoning is there, and the two must stay the same.
 *
 * <p>Cancel puts back what the section held at the moment Edit was pressed. Closing remounts the
 * section's `Form` through `formKey`, so nothing `Form` has said outlives a Cancel.
 */
function useEditMode<T>(current: T, restore: (values: T) => void) {
  const [editing, setEditing] = useState(false);
  const [formKey, setFormKey] = useState(0);
  const before = useRef(current);

  function close() {
    setEditing(false);
    setFormKey((key) => key + 1);
  }

  return {
    editing,
    formKey,
    open() {
      before.current = current;
      setEditing(true);
    },
    cancel() {
      restore(before.current);
      close();
    },
    /** After a Save the server accepted, when what is on the screen is what is saved. */
    close,
  };
}

/**
 * Edit, or Cancel and Save, at the right-hand end of the footer. A copy of `EditActions` in
 * `app/settings/page.tsx`, with the same classes and words, less the relabelling and extra disabled
 * reason that only the WhatsApp and Volunteer messages sections use.
 *
 * <p>Edit is quiet: it commits nothing. Cancel comes before Save (§4). Save submits the `Form` from
 * outside it with `form=`, and is greyed out only while the save is in flight. The buttons carry
 * different keys so React builds a new element, rather than turning Edit into a submit button under
 * the pointer that has just pressed it.
 */
function EditActions({
  editing,
  formId,
  disabled,
  saving,
  onEdit,
  onCancel,
}: {
  editing: boolean;
  formId: string;
  /** Something in this section is in flight. */
  disabled: boolean;
  saving: boolean;
  onEdit: () => void;
  onCancel: () => void;
}) {
  if (!editing) {
    return (
      <button
        key="edit"
        type="button"
        onClick={onEdit}
        disabled={disabled}
        className="btn btn-quiet min-h-touch px-6 text-sm disabled:opacity-60"
      >
        Edit
      </button>
    );
  }
  return (
    <>
      <button
        key="cancel"
        type="button"
        onClick={onCancel}
        disabled={disabled}
        className="btn btn-quiet min-h-touch px-5 text-sm disabled:opacity-60"
      >
        Cancel
      </button>
      <button
        key="save"
        type="submit"
        form={formId}
        disabled={disabled}
        className="btn btn-primary min-h-touch px-6 text-sm transition-colors duration-state disabled:opacity-60"
      >
        {saving ? "Saving…" : "Save"}
      </button>
    </>
  );
}
