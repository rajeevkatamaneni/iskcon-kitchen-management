import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, type DocumentView } from "@/lib/api";
import { generateAndDownload } from "@/lib/document-download";

function doc(status: string): DocumentView {
  return { id: "d1", kind: "RECIPE_PDF", status } as DocumentView;
}

describe("generating a document", () => {
  beforeEach(() => {
    URL.createObjectURL = vi.fn(() => "blob:kms");
    URL.revokeObjectURL = vi.fn();
  });

  it("waits for the worker and then hands over the file", async () => {
    const blob = new Blob(["%PDF"]);
    const statuses = [doc("PENDING"), doc("PENDING"), doc("READY")];
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});

    vi.useFakeTimers();
    const finished = generateAndDownload({
      request: async () => ({ documentId: "d1" }),
      status: async () => statuses.shift()!,
      download: async () => blob,
      filename: "Khichdi.pdf",
    });
    await vi.runAllTimersAsync();
    await finished;
    vi.useRealTimers();

    // The point of the whole exercise: the user gets a download, not a row to come back to.
    expect(click).toHaveBeenCalledOnce();
    expect(URL.createObjectURL).toHaveBeenCalledWith(blob);
    expect(statuses).toHaveLength(0);
  });

  it("says so plainly when the render fails, and downloads nothing", async () => {
    const download = vi.fn();

    await expect(
      generateAndDownload({
        request: async () => ({ documentId: "d1" }),
        status: async () => doc("FAILED"),
        download,
        filename: "Khichdi.pdf",
      })
    ).rejects.toBeInstanceOf(ApiError);

    expect(download).not.toHaveBeenCalled();
  });
});
