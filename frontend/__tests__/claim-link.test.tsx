import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

/**
 * `/c/[token]` — the web copy of a message a temple sent (E8-S2), and the second of the two screens
 * the product serves to someone with no session.
 *
 * <p>It is what a WhatsApp communication actually points at, because Meta will not carry a letter,
 * and what the "read this in your browser" line in every email opens. It had no test.
 *
 * <p>Three things are held still. That it renders with **no auth at all** — these tests mount it
 * bare, with no `AuthProvider` and no auth mock, so a `useAuth()` quietly appearing in it would fail
 * here rather than in front of a devotee halfway through a newsletter. That a token which does not
 * resolve says *not found* rather than confirming that something exists at that address, which is
 * what keeps a draft unreadable. And that the date shown is the temple's day and not the reader's —
 * `templeDay` is deliberate here, since there is no session zone to fall back on.
 *
 * <p>The body is injected with `dangerouslySetInnerHTML`, safe only because the server sanitised it
 * on the way in. The test below asserts that the stored markup is rendered as markup, which is the
 * behaviour; it is not, and cannot be, a test of the sanitiser, which lives on the server.
 */

const { publicCommunication, paramsRef } = vi.hoisted(() => ({
  publicCommunication: vi.fn(),
  paramsRef: { current: { token: "pub-token-abc" } },
}));

vi.mock("next/navigation", () => ({
  useParams: () => paramsRef.current,
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, publicCommunication },
  };
});

import PublicCommunicationPage from "@/app/c/[token]/page";

const LETTER = {
  templeName: "Sri Sri Radha Govinda Temple",
  subject: "Janmashtami prasadam seva",
  bodyHtml: "<p>Please join us for <strong>midnight arati</strong>.</p>",
  sentAt: "2026-08-25T04:30:00Z",
};

describe("reading a temple's letter on the web, with no session", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    paramsRef.current = { token: "pub-token-abc" };
  });

  it("says it is loading before the letter arrives", () => {
    publicCommunication.mockReturnValue(new Promise(() => {}));
    render(<PublicCommunicationPage />);

    expect(screen.getByText(/loading…/i)).toBeInTheDocument();
  });

  it("shows the temple, the subject and the letter, with nothing to sign into", async () => {
    publicCommunication.mockResolvedValue(LETTER);
    render(<PublicCommunicationPage />);

    expect(
      await screen.findByRole("heading", { name: /janmashtami prasadam seva/i })
    ).toBeInTheDocument();
    expect(screen.getByText("Sri Sri Radha Govinda Temple")).toBeInTheDocument();

    // The stored markup is rendered as markup — sanitised on the way in, on the server.
    expect(screen.getByText("midnight arati").tagName).toBe("STRONG");

    expect(publicCommunication).toHaveBeenCalledWith("pub-token-abc");
    // Nothing here asks anybody to identify themselves.
    expect(screen.queryByRole("link", { name: /sign in/i })).not.toBeInTheDocument();
  });

  it("dates it in the temple's day, spelled out rather than numbered", async () => {
    publicCommunication.mockResolvedValue(LETTER);
    render(<PublicCommunicationPage />);

    // 25 Aug 2026 in Asia/Kolkata — the same instant is still the 25th there, and a month name
    // cannot be read as either 08/25 or 25/08.
    expect(await screen.findByText("25 Aug 2026")).toBeInTheDocument();
  });

  it("omits the date entirely for a letter with no sent time", async () => {
    publicCommunication.mockResolvedValue({ ...LETTER, sentAt: null });
    render(<PublicCommunicationPage />);

    expect(await screen.findByRole("heading", { name: /janmashtami/i })).toBeInTheDocument();
    expect(screen.queryByText(/\d{1,2} \w{3} \d{4}/)).not.toBeInTheDocument();
  });

  it("renders the header and an empty body for a letter with no text", async () => {
    publicCommunication.mockResolvedValue({ ...LETTER, bodyHtml: null });
    render(<PublicCommunicationPage />);

    expect(await screen.findByRole("heading", { name: /janmashtami/i })).toBeInTheDocument();
    expect(screen.getByText("Sri Sri Radha Govinda Temple")).toBeInTheDocument();
  });

  it("says not found for an address that does not resolve", async () => {
    const { ApiError } = await import("@/lib/api");
    publicCommunication.mockRejectedValue(
      new ApiError(
        {
          code: "KMS-404010",
          message: "We couldn’t find that message.",
          action: "Check the link.",
          fieldErrors: [],
        },
        404
      )
    );
    render(<PublicCommunicationPage />);

    expect(await screen.findByRole("heading", { name: /not found/i })).toBeInTheDocument();
    expect(screen.getByText(/may have been removed, or the address may be incomplete/i))
      .toBeInTheDocument();
    // A draft has no address here at all, so a wrong one is *not found* rather than *not yours*.
    expect(screen.queryByText(/sign in/i)).not.toBeInTheDocument();
  });

  it("reads whichever token is in the address", async () => {
    paramsRef.current = { token: "another-public-token" };
    publicCommunication.mockResolvedValue(LETTER);
    render(<PublicCommunicationPage />);

    await screen.findByRole("heading", { name: /janmashtami/i });
    expect(publicCommunication).toHaveBeenCalledWith("another-public-token");
  });
});
