"use client";

import { useState } from "react";

import { AttachmentUpload } from "@/components/AttachmentUpload";
import { ErrorNotice } from "@/components/ErrorNotice";
import { useAuth } from "@/lib/auth-context";
import { api, toApiError, type ApiError, type StaffDocumentKind, type StaffDocumentView } from "@/lib/api";

/**
 * A staff member's photograph and the scans of their PAN and Aadhaar cards (T-428).
 *
 * <p>Rajeev, 2026-09-20: a photo on every staff member's page, and "document uploads — Scanned Copy
 * or photograph of Pan Card, Aadhar card".
 *
 * <h3>On the record, not inside the edit form</h3>
 *
 * <p>An upload takes effect the moment it finishes — the record already exists, so there is nothing
 * to claim on Save, unlike a bill on an invoice that has not been written yet. Putting these boxes
 * inside the edit form would put them beside a Cancel that could not undo them, which is a promise
 * the server does not keep. So they live here, on the record, exactly as `ConductNotes` does and for
 * the same reason: it is written and saved on its own.
 *
 * <h3>Who can see these, said out loud</h3>
 *
 * <p>An Aadhaar card is the most sensitive thing this application stores. The line under the heading
 * says who can open these and that every open is recorded, because the person deciding to upload one
 * should know that before they do, not afterwards. It is stated as a fact in the ordinary ink — not
 * as an amber warning — because nothing here has gone wrong.
 */
export function StaffDocuments({
  staffId,
  documents,
  onChanged,
}: {
  staffId: string;
  documents: StaffDocumentView[];
  /** Re-reads the record, so the photograph at the top right changes with the one chosen here. */
  onChanged: () => void;
}) {
  const { getToken } = useAuth();
  const [failure, setFailure] = useState<ApiError | null>(null);

  const of = (kind: StaffDocumentKind) => documents.find((d) => d.kind === kind) ?? null;

  async function set(kind: StaffDocumentKind, next: StaffDocumentView | null) {
    setFailure(null);
    // Uploading already wrote the row; only a removal has anything left to send.
    if (next === null) {
      const existing = of(kind);
      if (existing) {
        try {
          await api.removeStaffDocument(staffId, existing.id, await getToken());
        } catch (e) {
          setFailure(toApiError(e, "We couldn’t take that file off the record."));
          return;
        }
      }
    }
    onChanged();
  }

  return (
    <div className="grid gap-4">
      <p className="text-sm text-ink-secondary">
        Only people who can manage staff can open these, and every time one is opened is recorded.
      </p>

      {failure && <ErrorNotice error={failure} />}

      {BOXES.map(({ kind, label, hint }) => (
        <AttachmentUpload<StaffDocumentView>
          key={kind}
          label={label}
          hint={hint}
          value={of(kind)}
          onChange={(next) => void set(kind, next)}
          upload={async (file) => api.uploadStaffDocument(staffId, kind, file, await getToken())}
          loadStored={async (value) => api.staffDocument(staffId, value.id, await getToken())}
          // Openable, because this box is the only way a stored scan is read back. Not previewed,
          // because fetching one writes an audit row for the read — drawing three little pictures
          // on every open of a record would record three reads nobody asked for. Pressing the
          // thumbnail is the read, and that is the one that gets logged.
          openable
          preview={false}
        />
      ))}
    </div>
  );
}

/**
 * The three boxes, in the order they are drawn: the photograph first, because it is the one that
 * shows at the top of the record, then the two cards.
 *
 * <p>The hints follow the shape R-INV-2 set for a bill — "Upload a copy of the bill — a photo, PDF
 * or scan" — so a person who has attached a bill recognises the box.
 */
const BOXES: { kind: StaffDocumentKind; label: string; hint: string }[] = [
  { kind: "PHOTO", label: "Photo", hint: "Upload a photo of this person." },
  {
    kind: "PAN_SCAN",
    label: "PAN card",
    hint: "Upload a copy of their PAN card — a photo, PDF or scan.",
  },
  {
    kind: "AADHAAR_SCAN",
    label: "Aadhaar card",
    hint: "Upload a copy of their Aadhaar card — a photo, PDF or scan.",
  },
];
