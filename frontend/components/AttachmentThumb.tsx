"use client";

import { useCallback, useEffect, useId, useRef, useState } from "react";

import { Button } from "@/components/ds/Button";
import { ErrorNotice } from "@/components/ErrorNotice";
import { toApiError, type ApiError } from "@/lib/api";

/**
 * A small picture of an uploaded file — the copy of a bill, a payment's proof — that opens the file
 * when pressed (R-INV-2, R-PAY-3; T-267). The mock's `BillThumb` and `BillView`, made real.
 *
 * <p><strong>Why it loads through a function and never a URL.</strong> An upload is only ever
 * reached through an endpoint that checks the permission, with the sign-in token in a header
 * (`api.invoiceBill`, `api.paymentFile`). A plain `<img src>` cannot send that header. So the caller
 * passes `load`, which returns the file as a Blob, and this component turns it into an object URL of
 * its own — and revokes it when the file changes or the thumbnail goes, because an object URL holds
 * the whole file in memory until it is revoked, and a payment list of forty thumbnails would
 * otherwise keep forty photos alive for as long as the tab is open.
 *
 * <p>A photo is fetched as soon as the thumbnail appears, because the picture is the point of it. A
 * PDF is fetched only when it is opened: its thumbnail is an icon either way, and fetching every PDF
 * on a list to draw the same icon would be the whole list's files for nothing.
 *
 * <p>HEIC is accepted by the server because it is what an iPhone takes, and most browsers cannot draw
 * it. So an image that fails to draw falls back to a photo icon here, and the opened view says it
 * cannot be shown and offers to save it, rather than showing a broken picture.
 *
 * <p>Opened, the file is shown in a layer over the page, as the mock shows it: the photo at the
 * layer's width, or the PDF in the browser's own viewer. Escape and the Close button shut it, and
 * focus goes back to the thumbnail that opened it.
 */
export interface AttachmentThumbProps {
  /**
   * Which file this is — the attachment's id, or any key unique to a file chosen on this device. The
   * picture is fetched again only when this changes, so `load` can be written inline and change on
   * every render without the file being fetched on every render.
   */
  fileId: string;
  /** The file's name, as the device called it: the layer's title and part of the button's name. */
  name: string;
  /** What the server found the file to be (`AttachmentView.contentType`). */
  contentType: string;
  /** Fetches the file. Called once per `fileId` for a photo, and on first opening for a PDF. */
  load: () => Promise<Blob>;
  /** The payment list's size (32 × 44) rather than the form's (56 × 80). */
  small?: boolean;
  /**
   * Whether pressing it opens the file. Off where the thumbnail only shows what was chosen, as in the
   * upload box itself, which the mock draws as a picture and not a button.
   */
  openable?: boolean;
  /**
   * Whether a photo is fetched as soon as the thumbnail appears, to draw the picture. True by
   * default, which is what a bill and a payment's proof want.
   *
   * <p>**False where fetching the file is itself an event.** A staff member's PAN or Aadhaar scan is
   * read only through an endpoint that writes an audit row for the read (T-428), so drawing forty
   * little pictures of identity documents would record forty reads nobody asked for — the same
   * mistake as fetching a PAN on page load and hiding it behind CSS. With this off the thumbnail is
   * the file-type icon until somebody opens it, and opening it is the read.
   */
  preview?: boolean;
}

export function AttachmentThumb({
  fileId,
  name,
  contentType,
  load,
  small = false,
  openable = true,
  preview = true,
}: AttachmentThumbProps) {
  const isPdf = contentType === "application/pdf";
  const isImage = contentType.startsWith("image/");

  const [url, setUrl] = useState<string | null>(null);
  const [broken, setBroken] = useState(false);
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  // The load in flight, so opening a PDF twice, or opening a photo still loading, fetches once.
  const pending = useRef<Promise<string> | null>(null);
  // The newest `load`, read when a fetch starts; the fetch itself is keyed on `fileId` alone.
  const loadRef = useRef(load);
  loadRef.current = load;

  // A different file, or this one going away: forget the old picture and give its memory back.
  useEffect(() => {
    pending.current = null;
    setUrl(null);
    setBroken(false);
    setError(null);
    return () => {
      pending.current = null;
    };
  }, [fileId]);

  useEffect(() => {
    if (!url) return;
    return () => URL.revokeObjectURL(url);
  }, [url]);

  const fetchFile = useCallback((): Promise<string> => {
    if (!pending.current) {
      const mine = loadRef.current().then((blob) => URL.createObjectURL(blob));
      pending.current = mine;
      mine.then(
        (made) => {
          // Only if nothing has replaced this load since; otherwise nobody will ever revoke it.
          if (pending.current === mine) setUrl(made);
          else URL.revokeObjectURL(made);
        },
        (caught) => {
          if (pending.current === mine) {
            pending.current = null;
            setError(toApiError(caught, "We couldn't open that file."));
          }
        },
      );
    }
    return pending.current;
    // fileId, not load: a new file is a new fetch, a new function for the same file is not.
  }, [fileId]);

  useEffect(() => {
    if (isImage && preview) fetchFile().catch(() => undefined);
  }, [isImage, preview, fetchFile]);

  function openFile() {
    setError(null);
    setOpen(true);
    if (!url) fetchFile().catch(() => undefined);
  }

  const close = useCallback(() => {
    setOpen(false);
    trigger.current?.focus();
  }, []);

  const picture =
    isImage && url && !broken ? (
      <img src={url} alt="" className="h-full w-full object-cover" onError={() => setBroken(true)} />
    ) : (
      <span className="flex h-full w-full items-center justify-center bg-sunken">
        <i
          className={`ti ${isPdf ? "ti-file-type-pdf" : "ti-photo"} ${small ? "text-xl" : "text-3xl"} text-ink-secondary`}
          aria-hidden="true"
        />
      </span>
    );

  const box = `${small ? "inline-block h-11 w-8 align-middle" : "block h-20 w-14"} flex-none overflow-hidden rounded-control border border-hairline`;

  if (!openable) {
    return (
      <span className={box} data-attachment-thumb="">
        {picture}
      </span>
    );
  }

  return (
    <>
      <button
        ref={trigger}
        type="button"
        onClick={openFile}
        className={`${box} transition-shadow duration-state hover:shadow-lift`}
        aria-label={`Open ${name}`}
        data-attachment-thumb=""
      >
        {picture}
      </button>
      {open && (
        <AttachmentView
          name={name}
          isPdf={isPdf}
          isImage={isImage}
          url={url}
          broken={broken}
          error={error}
          onBroken={() => setBroken(true)}
          onClose={close}
        />
      )}
    </>
  );
}

/**
 * The file full size, in a layer over the page: the mock's `BillView` inside the mock's viewing
 * layer. A photo at the layer's width; a PDF in the browser's own viewer, which brings its own
 * zoom, pages and download; anything the browser cannot draw, a sentence and a way to save it.
 */
function AttachmentView({
  name,
  isPdf,
  isImage,
  url,
  broken,
  error,
  onBroken,
  onClose,
}: {
  name: string;
  isPdf: boolean;
  isImage: boolean;
  url: string | null;
  broken: boolean;
  error: ApiError | null;
  onBroken: () => void;
  onClose: () => void;
}) {
  const titleId = useId();
  const panel = useRef<HTMLDivElement>(null);

  // The layer is the one thing on the screen now, so focus belongs in it: on Close, which is the one
  // control it always has.
  useEffect(() => {
    panel.current?.querySelector<HTMLButtonElement>("[data-close]")?.focus();
  }, []);

  // Escape closes, as it does on every layer in the app; Tab stays inside the layer while it is open.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") {
        e.preventDefault();
        onClose();
        return;
      }
      if (e.key !== "Tab" || !panel.current) return;
      const stops = Array.from(
        panel.current.querySelectorAll<HTMLElement>("button, a[href], iframe"),
      );
      if (stops.length === 0) return;
      const first = stops[0];
      const last = stops[stops.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  let body;
  if (error) {
    body = <ErrorNotice error={error} />;
  } else if (!url) {
    body = (
      <p role="status" className="py-8 text-center text-sm text-ink-secondary">
        Opening the file…
      </p>
    );
  } else if (isImage && !broken) {
    body = (
      <img
        src={url}
        alt={name}
        onError={onBroken}
        className="w-full rounded-control border border-hairline"
      />
    );
  } else if (isPdf) {
    body = (
      <iframe
        src={url}
        title={name}
        className="h-[36rem] max-h-[70vh] w-full rounded-control border border-hairline"
      />
    );
  } else {
    body = (
      <div className="grid gap-3 rounded-control border border-hairline bg-sunken px-4 py-4">
        <p className="text-ink">This photo can’t be shown in the browser.</p>
        <a href={url} download={name} className="justify-self-start text-accent-text hover:underline">
          Save {name}
        </a>
      </div>
    );
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center overscroll-contain bg-ink/40 px-4 py-6"
      role="dialog"
      aria-modal="true"
      aria-labelledby={titleId}
      onClick={onClose}
    >
      <div
        ref={panel}
        className="modal grid max-h-full w-full max-w-md gap-3 overflow-y-auto px-5 py-5"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between gap-3">
          <span id={titleId} className="min-w-0 font-semibold text-ink [overflow-wrap:anywhere]">
            {name}
          </span>
          <Button variant="ghost" onClick={onClose} data-close="">
            Close
          </Button>
        </div>
        {body}
      </div>
    </div>
  );
}
