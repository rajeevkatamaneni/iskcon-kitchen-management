import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ApiError, CommunicationDelivery, CommunicationView } from "@/lib/api";

/**
 * Sending a message again to the addresses it failed for (B6, T-015).
 *
 * <p>The retry lives on the sent message's record, inside the list page, because that is where the
 * failures are already listed — a person reading "Failed" beside four names should not have to
 * write the letter again to reach those four. What is worth proving here is therefore mostly about
 * *restraint*: the control appears only where something actually failed, it sends nothing but an
 * id, and a refusal from the server is shown with its code rather than swallowed.
 */

const { authRef, listRef, deliveriesRef, reloadMock, retryMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  listRef: { current: { data: [] as CommunicationView[], error: null as ApiError | null, loading: false } },
  deliveriesRef: { current: { data: [] as CommunicationDelivery[], error: null, loading: false } },
  reloadMock: vi.fn(),
  retryMock: vi.fn(),
}));

const { pushMock, replaceMock, paramsRef } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  replaceMock: vi.fn(),
  paramsRef: { current: new URLSearchParams() },
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
  useSearchParams: () => paramsRef.current,
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fn: (t: string | undefined) => Promise<unknown>) => {
    const ref = fn.toString().includes("listCommunications") ? listRef : deliveriesRef;
    return { ...ref.current, reload: reloadMock };
  },
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, retryFailedDeliveries: retryMock },
  };
});

import { ApiError as ApiErrorClass } from "@/lib/api";
import CommunicationsPage from "@/app/communications/page";

function sent(): CommunicationView {
  return {
    id: "c1",
    category: "NEWSLETTER",
    channel: "WHATSAPP",
    subject: "Janmashtami at the temple",
    bodyHtml: "<p>Hare Krishna</p>",
    bodyText: "Hare Krishna",
    whatsappSummary: "Come early",
    status: "SENT",
    audienceCount: 4,
    publicToken: "abcdef1234567890",
    author: "Temple Admin",
    createdAt: "2026-08-19T00:00:00Z",
    sentAt: "2026-08-19T04:30:00Z",
  };
}

function delivery(o: Partial<CommunicationDelivery> = {}): CommunicationDelivery {
  return {
    recipientName: "Nitai Das",
    status: "DELIVERED",
    channel: "WHATSAPP",
    suppressedReason: null,
    ...o,
  };
}

describe("sending a message again to the ones it failed for", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    listRef.current = { data: [sent()], error: null, loading: false };
    deliveriesRef.current = { data: [], error: null, loading: false };
    reloadMock.mockReset();
    retryMock.mockReset().mockResolvedValue({ retried: 2 });
    // The record is opened by ?message=, not by a route of its own.
    paramsRef.current = new URLSearchParams("message=c1");
    pushMock.mockReset();
    replaceMock.mockReset();
  });

  it("offers nothing to retry when every copy arrived", () => {
    deliveriesRef.current = {
      data: [
        delivery({ recipientName: "Nitai Das", status: "DELIVERED" }),
        delivery({ recipientName: "Gaura Das", status: "SENT" }),
        delivery({
          recipientName: "Yamuna Devi",
          status: "SUPPRESSED",
          suppressedReason: "OPTED_OUT",
        }),
      ],
      error: null,
      loading: false,
    };
    render(<CommunicationsPage />);

    // A devotee who turned this kind off is a decision, not a failure, so she does not put a
    // "something went wrong" button on a message that went out perfectly.
    expect(screen.getByText(/they turned this kind off/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /send it to them again/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/didn’t arrive/i)).not.toBeInTheDocument();
  });

  it("offers the retry where the failures are listed, and says how many", () => {
    deliveriesRef.current = {
      data: [
        delivery({ recipientName: "Nitai Das", status: "FAILED" }),
        delivery({ recipientName: "Gaura Das", status: "FAILED" }),
        delivery({ recipientName: "Yamuna Devi", status: "DELIVERED" }),
      ],
      error: null,
      loading: false,
    };
    render(<CommunicationsPage />);

    // In the same card as the outcomes it is talking about.
    const record = screen.getByRole("region", { name: /janmashtami at the temple/i });
    expect(within(record).getAllByText("Failed")).toHaveLength(2);
    expect(within(record).getByText(/2 copies didn’t arrive\./i)).toBeInTheDocument();
    expect(
      within(record).getByText(/only those devotees are written to again/i)
    ).toBeInTheDocument();
    expect(within(record).getByRole("button", { name: /send it to them again/i })).toBeEnabled();
  });

  it("counts one failure as one copy rather than as “1 copies”", () => {
    deliveriesRef.current = {
      data: [delivery({ status: "FAILED" }), delivery({ recipientName: "Gaura Das" })],
      error: null,
      loading: false,
    };
    render(<CommunicationsPage />);
    expect(screen.getByText(/^1 copy didn’t arrive\.$/i)).toBeInTheDocument();
    expect(screen.getByText(/only that devotee is written to again/i)).toBeInTheDocument();
  });

  it("sends the message's id and nothing else, then says what it did", async () => {
    deliveriesRef.current = {
      data: [
        delivery({ recipientName: "Nitai Das", status: "FAILED" }),
        delivery({ recipientName: "Gaura Das", status: "FAILED" }),
      ],
      error: null,
      loading: false,
    };
    render(<CommunicationsPage />);

    fireEvent.click(screen.getByRole("button", { name: /send it to them again/i }));
    await waitFor(() => expect(retryMock).toHaveBeenCalledWith("c1", "test-token"));

    // The letter cannot be edited by this path, and the proof is that there is nothing in the call
    // it could be edited with. Read as a list of arguments rather than matched loosely: an
    // objectContaining would pass just as happily against a payload that carried the whole letter.
    const call = retryMock.mock.calls[0];
    expect(call).toHaveLength(2);
    expect(call[0]).toBe("c1");
    expect(call[1]).toBe("test-token");

    // The outcomes are re-read, because they have changed.
    expect(reloadMock).toHaveBeenCalled();
    expect(await screen.findByText(/on its way again to 2 devotees\./i)).toBeInTheDocument();
  });

  it("shows the server's refusal, with the code somebody can quote", async () => {
    deliveriesRef.current = {
      data: [delivery({ status: "FAILED" })],
      error: null,
      loading: false,
    };
    retryMock.mockRejectedValue(
      new ApiErrorClass(
        {
          code: "KMS-400138",
          message: "Every copy of this message was delivered.",
          action: "There is nothing to send again.",
          fieldErrors: [],
        },
        409
      )
    );
    render(<CommunicationsPage />);

    fireEvent.click(screen.getByRole("button", { name: /send it to them again/i }));

    const alert = await screen.findByRole("alert");
    expect(within(alert).getByText(/every copy of this message was delivered\./i)).toBeInTheDocument();
    expect(within(alert).getByText(/there is nothing to send again\./i)).toBeInTheDocument();
    expect(within(alert).getByText("KMS-400138")).toBeInTheDocument();
    expect(screen.queryByText(/on its way again/i)).not.toBeInTheDocument();
  });

  it("still offers no way to rewrite a sent message", () => {
    deliveriesRef.current = {
      data: [delivery({ status: "FAILED" })],
      error: null,
      loading: false,
    };
    render(<CommunicationsPage />);

    const record = screen.getByRole("region", { name: /janmashtami at the temple/i });
    expect(within(record).queryByRole("link", { name: /edit/i })).not.toBeInTheDocument();
    expect(
      within(record).queryByRole("button", { name: /send to everyone/i })
    ).not.toBeInTheDocument();
    expect(within(record).getByRole("link", { name: "Close" })).toHaveAttribute(
      "href",
      "/communications"
    );
  });
});
