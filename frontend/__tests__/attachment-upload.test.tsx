import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { AttachmentThumb } from "@/components/AttachmentThumb";
import { AttachmentUpload } from "@/components/AttachmentUpload";
import { Form } from "@/components/ds/Form";
import { ApiError, type AttachmentView } from "@/lib/api";

/**
 * The shared upload box and thumbnail (T-267): the copy of a bill (R-INV-2) and the proof on a
 * payment (R-PAY-2, R-PAY-3). What these hold is the behaviour the invoice and payment screens of the
 * next wave will lean on without re-testing: a chosen file goes up at once and shows, it can be
 * removed, an empty submit says so in the form's own words, a refusal from the server is shown with
 * its code, and a thumbnail opens the file it was given.
 */

const BILL_HINT = "Upload a copy of the bill — a photo, PDF or scan.";

function stored(overrides: Partial<AttachmentView> = {}): AttachmentView {
  return {
    id: "att-1",
    kind: "INVOICE_BILL",
    contentType: "image/jpeg",
    sizeBytes: 182_000,
    originalName: "KVM-0917.jpg",
    uploadedAt: "2026-09-19T05:30:00Z",
    ...overrides,
  };
}

/** The box as a screen holds it: the value in the screen's state, inside the app's own Form. */
function Harness({
  upload,
  onSubmit = () => undefined,
  onValue = () => undefined,
}: {
  upload: (f: File) => Promise<AttachmentView>;
  onSubmit?: () => void;
  onValue?: (v: AttachmentView | null) => void;
}) {
  const [value, setValue] = useState<AttachmentView | null>(null);
  return (
    <Form aria-label="Create an invoice" onSubmit={(e) => { e.preventDefault(); onSubmit(); }}>
      <AttachmentUpload
        label="Copy of the bill"
        hint={BILL_HINT}
        required
        value={value}
        onChange={(v) => {
          setValue(v);
          onValue(v);
        }}
        upload={upload}
      />
      <button type="submit">Save invoice</button>
    </Form>
  );
}

function chooser(): HTMLInputElement {
  const input = document.querySelector<HTMLInputElement>('input[type="file"]');
  if (!input) throw new Error("no file input on the page");
  return input;
}

function choose(file: File) {
  fireEvent.change(chooser(), { target: { files: [file] } });
}

const photo = () => new File([new Uint8Array([0xff, 0xd8, 0xff, 0xe0])], "KVM-0917.jpg", { type: "image/jpeg" });

let created: string[];
let revoked: string[];

beforeEach(() => {
  created = [];
  revoked = [];
  let n = 0;
  URL.createObjectURL = vi.fn(() => {
    const url = `blob:t267-${++n}`;
    created.push(url);
    return url;
  });
  URL.revokeObjectURL = vi.fn((url: string) => {
    revoked.push(url);
  });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("AttachmentUpload", () => {
  it("offers images and PDFs with no capture attribute, and shows the bill's hint word for word", () => {
    render(<Harness upload={vi.fn()} />);

    expect(chooser()).toHaveAttribute("accept", "image/*,application/pdf");
    // capture would send a phone straight to the camera and take away the vendor's PDF.
    expect(chooser()).not.toHaveAttribute("capture");
    expect(screen.getByText(BILL_HINT)).toBeInTheDocument();
    expect(screen.getByRole("group", { name: /Copy of the bill/ })).toBeInTheDocument();
    expect(screen.getByText("(required)")).toBeInTheDocument();
  });

  it("uploads as soon as a file is chosen, then shows it with its name and a picture", async () => {
    let finish: (v: AttachmentView) => void = () => undefined;
    const upload = vi.fn(() => new Promise<AttachmentView>((resolve) => (finish = resolve)));
    const onValue = vi.fn();
    render(<Harness upload={upload} onValue={onValue} />);

    const file = photo();
    choose(file);

    expect(upload).toHaveBeenCalledTimes(1);
    expect(upload).toHaveBeenCalledWith(file);
    // While it goes up: the file is already on screen, and says so.
    expect(await screen.findByText("Uploading…")).toBeInTheDocument();
    expect(screen.getByText("KVM-0917.jpg")).toBeInTheDocument();

    await act(async () => finish(stored()));

    expect(onValue).toHaveBeenLastCalledWith(stored());
    expect(screen.getByText("Photo")).toBeInTheDocument();
    expect(screen.queryByText("Uploading…")).not.toBeInTheDocument();
    // The picture is drawn from the file on this device, not fetched back from the server.
    await waitFor(() =>
      expect(document.querySelector("[data-attachment-thumb] img")).toHaveAttribute("src", created[0]),
    );
    expect(screen.getByText("Replace")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Remove" })).toBeInTheDocument();
  });

  it("removes the file: the value goes, the empty box and its hint come back", async () => {
    const onValue = vi.fn();
    render(<Harness upload={vi.fn(async () => stored())} onValue={onValue} />);

    choose(photo());
    await screen.findByText("Photo");

    fireEvent.click(screen.getByRole("button", { name: "Remove" }));

    expect(onValue).toHaveBeenLastCalledWith(null);
    expect(screen.queryByText("KVM-0917.jpg")).not.toBeInTheDocument();
    expect(screen.getByText(BILL_HINT)).toBeVisible();
    expect(screen.getByText("Choose a file")).toBeInTheDocument();
    // The chosen file's object URL is given back once nothing shows it.
    await waitFor(() => expect(revoked).toContain(created[0]));
  });

  it("says the standard required message when the form is submitted with nothing uploaded", async () => {
    const onSubmit = vi.fn();
    render(<Harness upload={vi.fn(async () => stored())} onSubmit={onSubmit} />);

    expect(screen.queryByText("Copy of the bill is required")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Save invoice" }));

    const message = await screen.findByText("Copy of the bill is required");
    expect(chooser()).toHaveAttribute("aria-invalid", "true");
    expect(chooser().getAttribute("aria-describedby")).toContain(message.id);

    // Choosing a file puts it right.
    choose(photo());
    await screen.findByText("Photo");
    expect(screen.queryByText("Copy of the bill is required")).not.toBeInTheDocument();
  });

  it("shows a server refusal the app's usual way, with its code, and keeps nothing", async () => {
    const refusal = new ApiError(
      {
        code: "KMS-400165",
        message: "That file isn't a photo or a PDF.",
        action: "Upload a photo (JPG, PNG, WebP or HEIC) or a PDF.",
        fieldErrors: [],
      },
      400,
    );
    const onValue = vi.fn();
    render(<Harness upload={vi.fn(async () => { throw refusal; })} onValue={onValue} />);

    choose(new File(["MZ"], "bill.pdf", { type: "application/pdf" }));

    const alert = await screen.findByRole("alert");
    expect(within(alert).getByText("That file isn't a photo or a PDF.")).toBeInTheDocument();
    expect(within(alert).getByText("Upload a photo (JPG, PNG, WebP or HEIC) or a PDF.")).toBeInTheDocument();
    expect(within(alert).getByText("KMS-400165")).toBeInTheDocument();
    expect(onValue).not.toHaveBeenCalled();
    expect(screen.queryByText("bill.pdf")).not.toBeInTheDocument();
    expect(screen.getByText(BILL_HINT)).toBeInTheDocument();
  });

  it("a file replaced while it was still uploading does not come back over its replacement", async () => {
    const finishes: ((v: AttachmentView) => void)[] = [];
    const upload = vi.fn(() => new Promise<AttachmentView>((resolve) => finishes.push(resolve)));
    const onValue = vi.fn();
    render(<Harness upload={upload} onValue={onValue} />);

    choose(photo());
    choose(new File(["%PDF-"], "second.pdf", { type: "application/pdf" }));
    await act(async () => finishes[1](stored({ id: "att-2", contentType: "application/pdf", originalName: "second.pdf" })));
    await act(async () => finishes[0](stored()));

    expect(onValue).toHaveBeenCalledTimes(1);
    expect(onValue).toHaveBeenLastCalledWith(expect.objectContaining({ id: "att-2" }));
    expect(screen.getByText("second.pdf")).toBeInTheDocument();
    expect(screen.getByText("PDF")).toBeInTheDocument();
  });
});

describe("AttachmentThumb", () => {
  it("draws a photo from the blob it loads, and revokes it when it goes", async () => {
    const load = vi.fn(async () => new Blob(["jpeg"], { type: "image/jpeg" }));
    const { unmount } = render(
      <AttachmentThumb fileId="att-1" name="KVM-0917.jpg" contentType="image/jpeg" load={load} />,
    );

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Open KVM-0917.jpg" }).querySelector("img")).toHaveAttribute(
        "src",
        created[0],
      ),
    );
    expect(load).toHaveBeenCalledTimes(1);

    unmount();
    expect(revoked).toContain(created[0]);
  });

  it("opens a PDF from the blob when pressed, and Escape closes it and returns focus", async () => {
    const load = vi.fn(async () => new Blob(["%PDF-1.7"], { type: "application/pdf" }));
    render(
      <AttachmentThumb fileId="att-9" name="Kalasipalya bill.pdf" contentType="application/pdf" load={load} />,
    );

    // A PDF's thumbnail is an icon; nothing is fetched until it is opened.
    expect(load).not.toHaveBeenCalled();
    const thumb = screen.getByRole("button", { name: "Open Kalasipalya bill.pdf" });
    fireEvent.click(thumb);

    const dialog = await screen.findByRole("dialog", { name: "Kalasipalya bill.pdf" });
    const frame = await within(dialog).findByTitle("Kalasipalya bill.pdf");
    expect(frame.tagName).toBe("IFRAME");
    expect(frame).toHaveAttribute("src", created[0]);
    expect(load).toHaveBeenCalledTimes(1);
    expect(within(dialog).getByRole("button", { name: "Close" })).toHaveFocus();

    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(thumb).toHaveFocus();

    // Opened again, the file already in hand is shown without fetching it twice.
    fireEvent.click(thumb);
    await screen.findByRole("dialog");
    expect(load).toHaveBeenCalledTimes(1);
  });

  it("works from the keyboard: it is a real button", () => {
    render(
      <AttachmentThumb fileId="att-3" name="upi.png" contentType="image/png" load={async () => new Blob()} />,
    );
    const thumb = screen.getByRole("button", { name: "Open upi.png" });
    expect(thumb.tagName).toBe("BUTTON");
    expect(thumb).toHaveAttribute("type", "button");
  });

  it("shows the server's refusal in the layer when the file cannot be fetched", async () => {
    const refusal = new ApiError(
      { code: "KMS-400030", message: "We couldn't find that.", action: "Go back and try again.", fieldErrors: [] },
      404,
    );
    render(
      <AttachmentThumb fileId="att-4" name="bill.pdf" contentType="application/pdf" load={async () => { throw refusal; }} />,
    );
    fireEvent.click(screen.getByRole("button", { name: "Open bill.pdf" }));
    const alert = await screen.findByRole("alert");
    expect(within(alert).getByText("KMS-400030")).toBeInTheDocument();
  });
});
