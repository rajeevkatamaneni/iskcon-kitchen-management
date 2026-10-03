import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { generateAndDownload } from "@/lib/document-download";

describe("generating a document", () => {
  beforeEach(() => {
    URL.createObjectURL = vi.fn(() => "blob:kms");
    URL.revokeObjectURL = vi.fn();
  });

  it("hands over the file the moment the server says it is ready", async () => {
    const blob = new Blob(["%PDF"]);
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});

    await generateAndDownload({
      request: async () => ({ documentId: "d1", status: "READY" }),
      download: async () => blob,
      filename: "Khichdi.pdf",
    });

    // The point of the whole exercise: the user gets a download, not a row to come back to.
    expect(click).toHaveBeenCalledOnce();
    expect(URL.createObjectURL).toHaveBeenCalledWith(blob);
  });

  it("says so plainly when the render fails, and downloads nothing", async () => {
    const download = vi.fn();

    await expect(
      generateAndDownload({
        request: async () => ({ documentId: "d1", status: "FAILED" }),
        download,
        filename: "Khichdi.pdf",
      })
    ).rejects.toBeInstanceOf(ApiError);

    expect(download).not.toHaveBeenCalled();
  });
});
