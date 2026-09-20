"use client";

import { useState } from "react";

/**
 * A value that is hidden until somebody presses the eye inside its own box (T-428).
 *
 * <p>Rajeev, 2026-09-20, on the staff record: the PAN "can be displayed in the same text box with
 * an 'Eye' Icon". It used to be a masked line floating under the PAN box with a text link called
 * *Reveal* beside it, which read as a second field rather than as the field's own control.
 *
 * <h3>Pressing the eye is what fetches it</h3>
 *
 * <p><b>This is the whole point and it is easy to get wrong.</b> Reading a PAN is an audited event —
 * {@code STAFF_PAN_VIEWED}, written by the server the moment the value is decrypted. So the eye must
 * make the request, not merely stop hiding something the page already fetched. Fetching on load and
 * toggling CSS would turn every open of a staff record into a recorded PAN read, and would put the
 * number in the DOM for anybody standing behind the reader. Hence `revealed` is null until `onReveal`
 * resolves, and `onHide` throws the value away again so the next eye press is a fresh, recorded read.
 *
 * <h3>Where else this pattern lives, and where it does not</h3>
 *
 * <p>Nowhere else, yet. Settings does the same job — a masked value, a control to show it, a line
 * saying the reading is recorded — with a text button called *Reveal*
 * (`app/settings/page.tsx`). That screen was deliberately left alone here: this task's contract is
 * the staff record. Two patterns for one idea is a thing to settle, not to spread, and the proof for
 * T-428 puts it to Rajeev.
 */
export function RevealBox({
  /** What the box shows while the value is hidden — a masked PAN, for instance. */
  masked,
  /** The value in clear, once the server has answered. Null while it is hidden. */
  revealed,
  /** Fetches it. Must be the audited request, made at the moment the eye is pressed. */
  onReveal,
  /** Throws the fetched value away, so showing it again is a second recorded read. */
  onHide,
  /**
   * What is being shown, for the button's name: "PAN" gives "Show the PAN" / "Hide the PAN". A
   * screen reader has no eye to look at, so the icon is hidden from it and this is what it reads.
   */
  what,
  /** Said beside the control, because somebody should know before they press it, not after. */
  note,
}: {
  masked: string;
  revealed: string | null;
  onReveal: () => void | Promise<void>;
  onHide: () => void;
  what: string;
  note?: string;
}) {
  const [busy, setBusy] = useState(false);

  async function press() {
    if (revealed) {
      onHide();
      return;
    }
    setBusy(true);
    try {
      await onReveal();
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid gap-1">
      {/* The box is the field's own box — the same height, border and radius as an input, so the
          PAN reads as a value in a field rather than as a sentence under one. The eye sits inside
          it, at the right, which is where a control that belongs to a field belongs. */}
      <div className="flex min-h-touch items-center gap-2 rounded-control border border-hairline bg-canvas px-3">
        <span className="min-w-0 grow tabular-nums text-ink [overflow-wrap:anywhere]">
          {revealed ?? masked}
        </span>
        <button
          type="button"
          onClick={() => void press()}
          disabled={busy}
          aria-pressed={revealed !== null}
          aria-label={revealed ? `Hide the ${what}` : `Show the ${what}`}
          title={note}
          className="-mr-1 flex min-h-touch w-touch flex-none items-center justify-center rounded-control text-ink-secondary hover:text-ink disabled:opacity-60"
        >
          <i className={`ti ${revealed ? "ti-eye-off" : "ti-eye"} text-lg`} aria-hidden="true" />
        </button>
      </div>
      {note && <span className="pl-field-inset text-xs text-ink-muted">{note}</span>}
    </div>
  );
}
