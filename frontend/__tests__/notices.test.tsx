import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ApiError, PlatformNotice } from "@/lib/api";

/**
 * The platform notice board, as it is received and as it is administered (E9-S1).
 *
 * <p>Three things are asserted, and each of them is a rule from build brief §11 rather than a
 * detail of the markup:
 *
 * <ul>
 *   <li><strong>Only urgent is loud.</strong> The rule the whole design rests on — a board where
 *       everything shouts is a board nobody reads — so it is worth a test that fails the moment
 *       somebody gives "important" the danger colour to make it stand out.
 *   <li><strong>Dismissing clears it from the person's own feed</strong>, immediately, without
 *       waiting on the round trip.
 *   <li><strong>The withdraw control follows ownership.</strong> A temple may take down what it
 *       posted and may not touch another temple's — and the client never works that out for itself;
 *       it renders what the server decided.
 * </ul>
 */

const { authRef, feedRef, boardRef, reloadMock, dismissMock, withdrawMock, raiseMock } = vi.hoisted(
  () => ({
    authRef: {
      current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
        status: string;
        appUser: { role: string; userId: string } | null;
      },
    },
    feedRef: { current: { data: [] as PlatformNotice[], error: null as ApiError | null, loading: false } },
    boardRef: { current: { data: [] as PlatformNotice[], error: null as ApiError | null, loading: false } },
    reloadMock: vi.fn(),
    dismissMock: vi.fn(),
    withdrawMock: vi.fn(),
    raiseMock: vi.fn(),
  })
);

// The screen reads its own address bar now (item 22), so the stub has to answer both halves of
// next/navigation: what the URL says, and what a click asks the router to do with it.
const { pushMock, replaceMock, paramsRef } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  replaceMock: vi.fn(),
  paramsRef: { current: new URLSearchParams() },
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
  useSearchParams: () => paramsRef.current,
  useParams: () => ({ id: "id-1" }),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  // The band and the board each fetch their own list; which one is asking is legible from the
  // fetcher itself, the same trick the communications tests use.
  useAuthedQuery: (fn: (t: string | undefined) => Promise<unknown>) => {
    const ref = fn.toString().includes("noticeFeed") ? feedRef : boardRef;
    return { ...ref.current, reload: reloadMock };
  },
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      dismissNotice: dismissMock,
      withdrawNotice: withdrawMock,
      raiseNotice: raiseMock,
    },
  };
});

import { PlatformNotices } from "@/components/PlatformNotices";
import NoticesPage from "@/app/notices/page";
import NewNoticePage from "@/app/notices/new/page";

function notice(o: Partial<PlatformNotice> = {}): PlatformNotice {
  return {
    id: "n1",
    severity: "INFORMATION",
    subject: "Platform maintenance on Sunday",
    body: "The app will be unavailable between 2am and 4am.",
    raisedBy: "the platform",
    raisedAt: "2026-08-19T09:00:00+05:30",
    withdrawn: false,
    withdrawnBy: null,
    withdrawnAt: null,
    withdrawnReason: null,
    mine: false,
    canWithdraw: false,
    ...o,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
  feedRef.current = { data: [], error: null, loading: false };
  boardRef.current = { data: [], error: null, loading: false };
});

describe("the notices band on Today", () => {
  it("draws the three severities apart, and only lets urgent be loud", () => {
    feedRef.current = {
      data: [
        notice({ id: "u", severity: "URGENT", subject: "Recall: adulterated ghee" }),
        notice({ id: "i", severity: "IMPORTANT", subject: "Supplier has stopped delivering" }),
        notice({ id: "n", severity: "INFORMATION", subject: "Janmashtami advisory" }),
      ],
      error: null,
      loading: false,
    };

    const { container } = render(<PlatformNotices />);

    const urgent = container.querySelector('[data-severity="URGENT"]')!;
    const important = container.querySelector('[data-severity="IMPORTANT"]')!;
    const information = container.querySelector('[data-severity="INFORMATION"]')!;

    // Each is distinguishable, and each says which it is in words as well as in colour — colour
    // alone is not a label anybody can read out.
    expect(within(urgent as HTMLElement).getByText("Urgent")).toBeInTheDocument();
    expect(within(important as HTMLElement).getByText("Important")).toBeInTheDocument();
    expect(within(information as HTMLElement).getByText("Information")).toBeInTheDocument();

    // Loud means the danger wash. Exactly one severity gets it.
    expect(urgent.className).toContain("bg-danger-bg");
    expect(important.className).not.toContain("bg-danger-bg");
    expect(information.className).not.toContain("bg-danger-bg");

    // And important is marked without being loud: a rule down the edge, not a colour field.
    expect(important.className).toContain("border-warning");
    expect(information.className).not.toContain("border-warning");
  });

  it("shows nothing at all when there is nothing outstanding", () => {
    const { container } = render(<PlatformNotices />);
    expect(container).toBeEmptyDOMElement();
  });

  it("clears a dismissed notice from this person's feed and tells the server", async () => {
    dismissMock.mockResolvedValue(undefined);
    feedRef.current = {
      data: [notice({ id: "n1", subject: "Janmashtami advisory" })],
      error: null,
      loading: false,
    };

    render(<PlatformNotices />);
    expect(screen.getByText("Janmashtami advisory")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Dismiss" }));

    await waitFor(() => expect(screen.queryByText("Janmashtami advisory")).not.toBeInTheDocument());
    expect(dismissMock).toHaveBeenCalledWith("n1", "test-token");
  });

  it("shows a withdrawn notice as a retraction rather than dropping it silently", () => {
    feedRef.current = {
      data: [
        notice({
          id: "w",
          severity: "URGENT",
          subject: "Recall: adulterated ghee",
          withdrawn: true,
          withdrawnBy: "ISKCON South Bengaluru",
          withdrawnAt: "2026-08-19T18:00:00+05:30",
          withdrawnReason: "The batch numbers were ours alone; no other temple is affected.",
        }),
      ],
      error: null,
      loading: false,
    };

    const { container } = render(<PlatformNotices />);

    expect(screen.getByText(/The batch numbers were ours alone/)).toBeInTheDocument();
    // A retracted recall stops shouting. It was raised urgent; withdrawn, it is drawn quiet.
    expect(container.querySelector('[data-withdrawn="true"]')!.className).not.toContain(
      "bg-danger-bg"
    );
  });
});

describe("the notices board", () => {
  it("offers the withdraw control on this temple's own notice and not on another's", () => {
    boardRef.current = {
      data: [
        notice({
          id: "ours",
          subject: "Recall: adulterated ghee",
          raisedBy: "ISKCON South Bengaluru",
          mine: true,
          canWithdraw: true,
        }),
        notice({
          id: "theirs",
          subject: "Water supply interrupted",
          raisedBy: "ISKCON Mayapur",
          mine: false,
          canWithdraw: false,
        }),
      ],
      error: null,
      loading: false,
    };

    render(<NoticesPage />);

    const ours = screen.getByText("Recall: adulterated ghee").closest("article")!;
    const theirs = screen.getByText("Water supply interrupted").closest("article")!;

    expect(within(ours).getByRole("button", { name: "Withdraw" })).toBeInTheDocument();
    expect(within(theirs).queryByRole("button", { name: "Withdraw" })).not.toBeInTheDocument();
  });

  it("will not withdraw without a reason, and sends the one it is given", async () => {
    withdrawMock.mockResolvedValue(undefined);
    boardRef.current = {
      data: [notice({ id: "ours", subject: "Recall: adulterated ghee", mine: true, canWithdraw: true })],
      error: null,
      loading: false,
    };

    render(<NoticesPage />);
    fireEvent.click(screen.getByRole("button", { name: "Withdraw" }));

    const reason = screen.getByPlaceholderText("Why is it being withdrawn?");
    expect(reason).toBeRequired();

    fireEvent.change(reason, { target: { value: "Wrong batch number." } });
    fireEvent.submit(screen.getByRole("form", { name: /Withdraw Recall/ }));

    await waitFor(() =>
      expect(withdrawMock).toHaveBeenCalledWith("ours", "Wrong batch number.", "test-token")
    );
  });
});

describe("raising a notice", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    boardRef.current = { data: [], error: null, loading: false };
    paramsRef.current = new URLSearchParams();
    pushMock.mockReset();
    replaceMock.mockReset();
  });

  it("is a screen of its own, reached from the board", () => {
    render(<NoticesPage />);
    expect(screen.getByRole("link", { name: /raise a notice/i })).toHaveAttribute(
      "href",
      "/notices/new"
    );
  });

  // Not only the field count: what is written here goes out under this temple's name, to every
  // temple, with nobody reviewing it. A panel over the board makes that read like adding a row.
  it("says plainly, before anything is typed, that nobody reviews it", () => {
    render(<NewNoticePage />);
    expect(screen.getByText(/goes out immediately/i)).toBeInTheDocument();
    expect(screen.getByText(/nobody reviews it first/i)).toBeInTheDocument();
  });

  it("commits from the header, with Cancel beside it and no back-link", () => {
    render(<NewNoticePage />);
    expect(screen.getByRole("form", { name: /raise a platform notice/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /post to every temple/i })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Cancel" })).toHaveAttribute("href", "/notices");
    expect(screen.queryByText(/←/)).not.toBeInTheDocument();
  });

  it("shows the confirmation a raised notice comes back with", () => {
    paramsRef.current = new URLSearchParams("raised=Recall%3A%20adulterated%20ghee");
    render(<NoticesPage />);
    expect(screen.getByText(/Recall: adulterated ghee went out to every temple\./i)).toBeInTheDocument();
    expect(replaceMock).toHaveBeenCalledWith("/notices");
  });
});
