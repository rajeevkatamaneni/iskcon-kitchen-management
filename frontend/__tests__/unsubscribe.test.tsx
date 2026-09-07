import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

/**
 * The one screen in the application reached from an email by somebody who is not signed in — and,
 * until this file, the one screen with no test at all. The UAT docket (P9) made exactly that point.
 *
 * <p>Three properties are worth holding still here, and only one of them is about layout.
 *
 * <p>First, and most important: **nothing happens on load.** Mail scanners and link previewers
 * follow every URL in an email before a human sees it, so a page that unsubscribed on arrival would
 * quietly remove people who never clicked anything. The page therefore describes, and waits. A test
 * that only asserted the confirmation screen would pass just as happily if the GET had been swapped
 * for the POST, so `unsubscribe` being *un*called is asserted directly.
 *
 * <p>Second: it renders with no session. These tests deliberately mount the page bare — no
 * `AuthProvider`, no auth mock, nothing standing in for a signed-in user — because that is the
 * arrangement a devotee arrives in, and a page that had quietly grown a `useAuth()` would fail here
 * rather than in production.
 *
 * <p>Third: the sentence about what is *not* being turned off. Without it people assume the worst
 * and stay subscribed to everything out of fear of losing shift reminders, so it is treated as part
 * of the contract of the screen rather than as decoration.
 */

const { describeUnsubscribe, unsubscribe, searchParamsRef } = vi.hoisted(() => ({
  describeUnsubscribe: vi.fn(),
  unsubscribe: vi.fn(),
  searchParamsRef: { current: new URLSearchParams("token=signed-token-abc") },
}));

vi.mock("next/navigation", () => ({
  useSearchParams: () => searchParamsRef.current,
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, describeUnsubscribe, unsubscribe },
  };
});

import UnsubscribePage from "@/app/unsubscribe/page";
import { ApiError } from "@/lib/api";

/** A link that the server recognises, for the category most of these tests are about. */
function aValidLink(label = "the weekly newsletter") {
  describeUnsubscribe.mockResolvedValue({ valid: true, allOptional: false, label });
}

describe("unsubscribing from an email link, with no session", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    searchParamsRef.current = new URLSearchParams("token=signed-token-abc");
  });

  it("says it is checking while the link is being described", () => {
    // A promise that never settles: the state under test is the one before the answer arrives.
    describeUnsubscribe.mockReturnValue(new Promise(() => {}));
    render(<UnsubscribePage />);

    expect(screen.getByText(/checking that link/i)).toBeInTheDocument();
  });

  it("describes what the link would stop, and does not stop it", async () => {
    aValidLink();
    render(<UnsubscribePage />);

    expect(
      await screen.findByRole("heading", { name: /stop receiving these\?/i })
    ).toBeInTheDocument();
    expect(screen.getByText("the weekly newsletter")).toBeInTheDocument();

    // The whole point of the screen. A mail scanner that followed this URL has now loaded the page
    // and changed nothing.
    expect(describeUnsubscribe).toHaveBeenCalledWith("signed-token-abc");
    expect(unsubscribe).not.toHaveBeenCalled();
  });

  it("promises, before anyone presses anything, that reminders and receipts keep coming", async () => {
    aValidLink();
    render(<UnsubscribePage />);

    expect(
      await screen.findByText(/shift reminders and giving confirmations keep reaching you/i)
    ).toBeInTheDocument();
  });

  it("stops the messages only when the button is pressed, and says so afterwards", async () => {
    aValidLink();
    unsubscribe.mockResolvedValue({ done: true, label: "the weekly newsletter" });
    render(<UnsubscribePage />);

    fireEvent.click(await screen.findByRole("button", { name: /yes, stop these/i }));

    expect(await screen.findByRole("heading", { name: /^done$/i })).toBeInTheDocument();
    expect(unsubscribe).toHaveBeenCalledWith("signed-token-abc");
    expect(screen.getByText(/you will no longer receive/i)).toBeInTheDocument();
    // And the same promise again, in the past tense — this is the screen somebody keeps open.
    expect(screen.getByText(/shift reminders and giving confirmations always reach you/i)).toBeInTheDocument();
  });

  it("keeps the confirmation on screen and explains itself when the change fails", async () => {
    aValidLink();
    unsubscribe.mockRejectedValue(
      new ApiError({
        code: "KMS-500001",
        message: "We couldn’t change that just now.",
        action: "Try again in a moment.",
        fieldErrors: [],
      })
    );
    render(<UnsubscribePage />);

    fireEvent.click(await screen.findByRole("button", { name: /yes, stop these/i }));

    expect(await screen.findByText(/couldn’t change that just now/i)).toBeInTheDocument();
    // Not told it worked, and still able to try.
    expect(screen.queryByRole("heading", { name: /^done$/i })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /yes, stop these/i })).toBeInTheDocument();
  });

  it("tells somebody with a broken link where to go instead, rather than failing silently", async () => {
    describeUnsubscribe.mockResolvedValue({ valid: false });
    render(<UnsubscribePage />);

    expect(
      await screen.findByRole("heading", { name: /that link doesn’t work/i })
    ).toBeInTheDocument();
    expect(screen.getByText(/copied incompletely/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /yes, stop these/i })).not.toBeInTheDocument();
  });

  it("treats a link the server refuses to describe as a broken one", async () => {
    // The server answering 404 or 400 reaches the page as a thrown ApiError, and the person on the
    // other end of it needs the same sentence either way.
    describeUnsubscribe.mockRejectedValue(new Error("network"));
    render(<UnsubscribePage />);

    expect(
      await screen.findByRole("heading", { name: /that link doesn’t work/i })
    ).toBeInTheDocument();
  });

  it("asks the server about an absent token rather than assuming it is bad", async () => {
    // Characterising, not endorsing: with no ?token= the page sends "" and lets the server judge.
    searchParamsRef.current = new URLSearchParams();
    describeUnsubscribe.mockResolvedValue({ valid: false });
    render(<UnsubscribePage />);

    await waitFor(() => expect(describeUnsubscribe).toHaveBeenCalledWith(""));
    expect(await screen.findByRole("heading", { name: /that link doesn’t work/i })).toBeInTheDocument();
  });
});
