import { ApiError } from "./api";

/**
 * The one route a generated document takes to the user: ask for it, hand the browser the bytes.
 * Every screen with a "Generate PDF" button goes through here, so the button means the same thing
 * everywhere — a file in your downloads, not an entry in a queue you have to come back to. The two
 * steps differ per document kind (a recipe card and a PO sheet sit behind different permissions and
 * different URLs).
 *
 * <p>The server makes the PDF inside the request and answers once it is READY or FAILED, so there
 * is nothing to wait for here. Until 2026-10-02 it was queued for a worker and this asked every
 * second whether it was ready yet.
 */
export async function generateAndDownload(steps: {
  request: () => Promise<{ documentId: string; status: string }>;
  download: (documentId: string) => Promise<Blob>;
  filename: string;
}): Promise<void> {
  const { documentId, status } = await steps.request();
  if (status !== "READY") {
    throw new ApiError({
      code: "KMS-0000",
      message: "The PDF couldn't be generated.",
      action: "Try again. If it keeps failing, ask your administrator to check with support.",
      fieldErrors: [],
    });
  }
  triggerDownload(await steps.download(documentId), steps.filename);
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
