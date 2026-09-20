"use client";

import { useEffect, useRef, useState } from "react";

import type { StaffDocumentView } from "@/lib/api";

/**
 * The person's photograph, at the top right of their record (T-428). Rajeev, 2026-09-20: "their
 * photo sits top right and their name is prominent".
 *
 * <p><b>Why it is not an `<img src>`.</b> Nothing in this application hands out a URL to a stored
 * file. The bytes come back only from an endpoint that checks MANAGE_STAFF and writes an audit row,
 * with the sign-in token in a header, and a plain `src` cannot send a header. So the photograph is
 * fetched as a Blob and turned into an object URL here — and revoked when it changes or the tile
 * goes, because an object URL holds the whole file in memory until it is. `AttachmentThumb` does the
 * same thing for the same reason; this is not that component because it is not a button, has no
 * viewer behind it, and is a portrait rather than a thumbnail of a document.
 *
 * <p><b>When there is none, it says so.</b> An empty square with a person icon, named "No photo" for
 * anybody reading with their ears. Not initials in a circle: initials look like a photograph that
 * failed to load, and the honest answer is that the temple has not added one.
 */
export function StaffPhoto({
  photo,
  load,
  /** The person's name, so the picture is described as theirs rather than as "photo". */
  name,
}: {
  photo: StaffDocumentView | null;
  load: (photo: StaffDocumentView) => Promise<Blob>;
  name: string;
}) {
  const [url, setUrl] = useState<string | null>(null);
  const [broken, setBroken] = useState(false);
  const fileId = photo?.id ?? null;

  // The newest `load` and the newest photo, held in refs and read when a fetch starts. The effect
  // below depends on the photo's *id* alone, so a caller writing `load` inline — which every caller
  // does — gets one fetch per photograph rather than one per render. Kept in a ref and not in state
  // on purpose: setting state from an effect that depends on a value changing every render is the
  // loop that has already cost this project a morning.
  const loadRef = useRef(load);
  loadRef.current = load;
  const photoRef = useRef(photo);
  photoRef.current = photo;

  useEffect(() => {
    let live = true;
    let made: string | null = null;
    setUrl(null);
    setBroken(false);
    const wanted = photoRef.current;
    if (!fileId || !wanted) return;
    loadRef.current(wanted).then(
      (blob) => {
        made = URL.createObjectURL(blob);
        if (live) setUrl(made);
        else URL.revokeObjectURL(made);
      },
      () => {
        // A photograph that will not load is drawn as no photograph. There is nothing for the reader
        // to do about it, and an error notice beside somebody's name would be noise on every open.
        if (live) setBroken(true);
      },
    );
    return () => {
      live = false;
      if (made) URL.revokeObjectURL(made);
    };
  }, [fileId]);

  // 64px on a phone, 80px from the small breakpoint: big enough to recognise a face, small enough
  // that it does not push the name and the Edit button onto three rows at 390.
  const box =
    "block h-16 w-16 flex-none overflow-hidden rounded-card border border-hairline sm:h-20 sm:w-20";

  if (!photo || broken || !url) {
    return (
      <span className={`${box} flex items-center justify-center bg-sunken`} role="img" aria-label="No photo">
        <i className="ti ti-user text-3xl text-ink-muted" aria-hidden="true" />
      </span>
    );
  }

  return (
    <span className={box}>
      <img
        src={url}
        alt={name}
        className="h-full w-full object-cover"
        onError={() => setBroken(true)}
      />
    </span>
  );
}
