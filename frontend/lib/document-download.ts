import { ApiError, type DocumentView } from "./api";

// A cold worker has a JVM and a browser to start before it can lay out a page, so the wait is
// generous — but bounded, because a button that spins forever tells the user nothing.
const POLL_INTERVAL_MS = 1000;
const POLL_ATTEMPTS = 90;

/**
 * The one route a generated document takes to the user: ask for it, wait for the worker, hand the
 * browser the bytes. Every screen with a "Generate PDF" button goes through here, so the button
 * means the same thing everywhere — a file in your downloads, not an entry in a queue you have to
 * come back to. The three steps differ per document kind (a recipe card and a PO sheet sit behind
 * different permissions and different URLs); the waiting does not.
 */
export async function generateAndDownload(steps: {
  request: () => Promise<{ documentId: string }>;
  status: (documentId: string) => Promise<DocumentView>;
  download: (documentId: string) => Promise<Blob>;
  filename: string;
}): Promise<void> {
  const { documentId } = await steps.request();

  let ready = false;
  for (let i = 0; i < POLL_ATTEMPTS && !ready; i++) {
    const doc = await steps.status(documentId);
    if (doc.status === "READY") ready = true;
    else if (doc.status === "FAILED") {
      throw new ApiError({
        code: "KMS-0000",
        message: "The PDF couldn't be generated.",
        action: "Try again. If it keeps failing, ask your administrator to check with support.",
        fieldErrors: [],
      });
    } else await sleep(POLL_INTERVAL_MS);
  }
  // Downloading one that is still being made only produces a puzzling "we couldn't find it".
  if (!ready) {
    throw new ApiError({
      code: "KMS-0000",
      message: "The PDF is taking longer than usual to prepare.",
      action: "It is still being made. Try the download again in a minute.",
      fieldErrors: [],
    });
  }

  triggerDownload(await steps.download(documentId), steps.filename);
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function triggerDownload(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
