"use client";

import { useEffect, useId, useRef, useState, type ChangeEvent } from "react";

import { AttachmentThumb } from "@/components/AttachmentThumb";
import { Button, BUTTON_CLASSES } from "@/components/ds/Button";
import { required as requiredMessage } from "@/components/ds/formMessages";
import { ErrorNotice } from "@/components/ErrorNotice";
import { FIELD_ERROR, FIELD_LABEL } from "@/components/Field";
import { toApiError, type ApiError, type AttachmentView } from "@/lib/api";

/**
 * A required upload: the copy of a bill on an invoice (R-INV-2), and the proof on a payment (R-PAY-2).
 * The mock's `UploadBill` and `ChooseFile` (T-247), made real and reusable (T-267). One box for every
 * upload, so a person who has attached a bill knows how to attach a UPI screenshot.
 *
 * <h3>Uploaded as soon as it is chosen</h3>
 *
 * <p>The file goes up the moment it is picked, through the caller's `upload` (`api.uploadBill`, or
 * `api.uploadPaymentFile` with its kind), and what comes back — an `AttachmentView` — is the value.
 * The form sends only its `id` on Save, and the server claims it then. So a phone photo on a slow
 * connection is on its way while the rest of the form is typed, and a refusal (a file that is not a
 * photo or a PDF, KMS-400165; one over 10 MB, KMS-400166) is shown beside the box straight away, not
 * after the person has pressed Save.
 *
 * <h3>Choosing: camera, photos or files, in one sheet</h3>
 *
 * <p>`accept="image/*,application/pdf"`, so a phone offers the camera, the photo library and its files
 * together. There is deliberately no `capture` attribute, exactly as the mock has it: `capture` sends
 * a phone straight to the camera and takes away the PDF the vendor sent on WhatsApp.
 *
 * <p>The file input is visually hidden inside a `<label>` drawn as a secondary button, so the whole
 * button is the target, a keyboard reaches the input itself (Space or Enter opens the chooser), and
 * the focus ring is drawn on the button around it. The chooser's value is cleared after each pick so
 * that choosing the same file again, after removing it, still counts as a choice.
 *
 * <h3>Required, in the form's own words</h3>
 *
 * <p>Nothing about a file input can say "an upload has finished", so this cannot lean on the
 * browser's `required` the way `Form` does for a text box. Instead it listens for its form's submit,
 * and if nothing has been uploaded by then it shows `formMessages.required(label)` — "Copy of the bill
 * is required" — in the same place and style `Form` uses for a box. The caller must still refuse to
 * save without a value, as every form here keeps its own check as the twin guard; `invalid` lets it
 * show the message itself too, when it has decided the form is incomplete some other way.
 */
export interface AttachmentUploadProps {
  /** The field's name, shown above the box: "Copy of the bill", "Proof of payment". */
  label: string;
  /**
   * The line inside the empty box saying what to upload. For a bill it is exactly R-INV-2's
   * "Upload a copy of the bill — a photo, PDF or scan."
   */
  hint: string;
  /** Marks the field "(required)" and shows the required message on an empty submit. */
  required?: boolean;
  /** The finished upload, or null. */
  value: AttachmentView | null;
  onChange: (value: AttachmentView | null) => void;
  /** Sends the file to the server: `(f) => api.uploadBill(f, token)`. */
  upload: (file: File) => Promise<AttachmentView>;
  /** Show the required message now, whatever the form has done. */
  invalid?: boolean;
  /**
   * Called with `true` the moment a file starts going up and `false` the moment it is finished,
   * failed or removed (T-370).
   *
   * <p><strong>Why the caller has to know.</strong> A file uploads on its own clock while the rest
   * of the form is typed, and until it lands `value` is still null. A form whose Save reads only
   * `value` therefore refuses a bill that is on the screen, with its name, its thumbnail and its
   * Replace button all visible, and tells the person to upload the thing they have just uploaded.
   * That was seen once on staging on 2026-09-19 with a 603 KB photo and could not be reproduced,
   * because it needs Save to land inside the second or so the upload takes — which is exactly the
   * kind of defect that cannot be closed by trying again. So the box says when it is working, the
   * form puts its Save into that state, and the required-check never runs against an unfinished
   * upload at all.
   *
   * <p>It is a report, not a request: this box keeps its own `busy` either way, and a caller that
   * does not pass this behaves as before.
   */
  onUploadingChange?: (uploading: boolean) => void;
  /**
   * Fetches the stored file, for a value that did not come from this device — an invoice being
   * corrected, say. Without it such a value shows an icon for its type rather than a picture.
   */
  loadStored?: (value: AttachmentView) => Promise<Blob>;
}

/** What was chosen on this device, kept so the picture can be drawn without asking the server. */
interface Chosen {
  key: string;
  file: File;
}

let chosenSeq = 0;

export function AttachmentUpload({
  label,
  hint,
  required = false,
  value,
  onChange,
  upload,
  invalid = false,
  loadStored,
  onUploadingChange,
}: AttachmentUploadProps) {
  const ids = useId();
  const labelId = `${ids}-label`;
  const hintId = `${ids}-hint`;
  const errorId = `${ids}-error`;
  const root = useRef<HTMLDivElement>(null);

  const [chosen, setChosen] = useState<Chosen | null>(null);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<ApiError | null>(null);
  const [submitted, setSubmitted] = useState(false);
  // Which pick is the latest. A photo chosen and then replaced before it finished uploading must
  // not come back and overwrite the one that replaced it.
  const latest = useRef(0);

  // "When the form is submitted empty": the form this box sits in, heard directly, so that no caller
  // has to remember to pass the moment in. Heard on every submit, including one `Form` stops for
  // another box, because this box is empty either way.
  useEffect(() => {
    const form = root.current?.closest("form");
    if (!form) return;
    const onSubmit = () => setSubmitted(true);
    form.addEventListener("submit", onSubmit);
    return () => form.removeEventListener("submit", onSubmit);
  }, []);

  const missing = required && !value && !busy && (invalid || submitted);

  /**
   * The one place `busy` moves, so the caller's copy of it cannot drift from this box's own.
   * Every path that starts or ends an upload goes through here.
   */
  function setUploading(next: boolean) {
    setBusy(next);
    onUploadingChange?.(next);
  }

  async function pick(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    // Cleared so the same file can be chosen again after being removed.
    event.target.value = "";
    if (!file) return;

    const mine = ++latest.current;
    const before = chosen;
    setChosen({ key: `chosen-${++chosenSeq}`, file });
    setFailure(null);
    setUploading(true);
    try {
      const stored = await upload(file);
      if (mine !== latest.current) return;
      onChange(stored);
    } catch (caught) {
      if (mine !== latest.current) return;
      // Whatever was there before stays; only the file that failed goes.
      setChosen(before);
      setFailure(toApiError(caught, "We couldn't upload that file."));
    } finally {
      if (mine === latest.current) setUploading(false);
    }
  }

  function remove() {
    latest.current++;
    setChosen(null);
    setUploading(false);
    setFailure(null);
    onChange(null);
  }

  // The picture: the file on this device while it uploads and after; the stored one otherwise.
  // While uploading, what is on the screen is the file just chosen; once stored, the server's word
  // on its name and type replaces the browser's.
  const shown = busy || value ? chosen : null;
  const stored = busy ? null : value;
  const name = stored?.originalName ?? shown?.file.name ?? "The uploaded file";
  const type = stored?.contentType ?? (shown ? typeOf(shown.file) : "");
  const describedBy = [hintId, missing ? errorId : null].filter(Boolean).join(" ");

  const chooser = (text: string) => (
    <label
      className={BUTTON_CLASSES({
        variant: "ghost",
        // The input inside is visually hidden, so its focus ring would be invisible. The ring is
        // drawn on the button around it instead, with the global :focus-visible ring's own values.
        className:
          "cursor-pointer has-[:focus-visible]:[outline:2px_solid_var(--focus-ring)] has-[:focus-visible]:[outline-offset:2px]",
      })}
    >
      <input
        type="file"
        accept="image/*,application/pdf"
        className="sr-only"
        onChange={pick}
        aria-describedby={describedBy || undefined}
        aria-invalid={missing || undefined}
      />
      <i className="ti ti-camera text-lg" aria-hidden="true" />
      <span>{text}</span>
    </label>
  );

  return (
    <div ref={root} className="grid gap-1" role="group" aria-labelledby={labelId}>
      <span id={labelId} className={FIELD_LABEL}>
        {label}
        {required && <span className="ml-1 text-ink-muted">(required)</span>}
      </span>

      {shown || value ? (
        <div className="flex flex-wrap items-center gap-x-4 gap-y-3 rounded-control border border-hairline bg-canvas px-4 py-3">
          {shown ? (
            <AttachmentThumb
              fileId={shown.key}
              name={name}
              contentType={type}
              load={() => Promise.resolve(shown.file)}
              openable={false}
            />
          ) : value && loadStored ? (
            <AttachmentThumb
              fileId={value.id}
              name={name}
              contentType={type}
              load={() => loadStored(value)}
              openable={false}
            />
          ) : (
            <AttachmentThumb
              fileId={value?.id ?? "none"}
              name={name}
              contentType={type}
              load={() => Promise.reject(new Error("not loaded"))}
              openable={false}
            />
          )}
          <div className="grid min-w-0 grow basis-40 gap-0.5">
            <span className="font-medium text-ink [overflow-wrap:anywhere]">{name}</span>
            <span className="text-sm text-ink-secondary" role="status">
              {busy ? "Uploading…" : type === "application/pdf" ? "PDF" : "Photo"}
            </span>
          </div>
          <div className="flex flex-wrap gap-3">
            {chooser("Replace")}
            <Button variant="ghost" onClick={remove}>
              Remove
            </Button>
          </div>
        </div>
      ) : (
        <div
          className={`flex flex-wrap items-center gap-x-6 gap-y-3 rounded-control border border-dashed px-4 py-4 ${
            missing ? "border-danger bg-danger-bg" : "border-ink-muted bg-canvas"
          }`}
        >
          <div className="flex min-w-0 grow basis-60 items-center gap-3">
            <i className="ti ti-file-upload text-2xl text-ink-secondary" aria-hidden="true" />
            <span id={hintId} className="text-ink">
              {hint}
            </span>
          </div>
          <div className="flex flex-wrap gap-3">{chooser("Choose a file")}</div>
        </div>
      )}

      {/* The hint is read with the chooser even once a file is in, where the box no longer shows it. */}
      {(shown || value) && (
        <span id={hintId} className="sr-only">
          {hint}
        </span>
      )}

      {missing && (
        <span id={errorId} className={FIELD_ERROR}>
          {requiredMessage(label)}
        </span>
      )}

      {failure && (
        <div className="mt-1">
          <ErrorNotice error={failure} />
        </div>
      )}
    </div>
  );
}

/**
 * What a chosen file looks like before the server has said. The browser's type where it has one,
 * and the name's extension where it does not (some phones send HEIC with no type at all). The
 * server decides from the bytes and its answer replaces this as soon as it arrives.
 */
function typeOf(file: File): string {
  if (file.type) return file.type;
  return /\.pdf$/i.test(file.name) ? "application/pdf" : "image/*";
}
