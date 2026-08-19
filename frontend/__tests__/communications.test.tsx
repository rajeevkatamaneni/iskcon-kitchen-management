import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type {
  ApiError,
  CommunicationCategoryOption,
  CommunicationPreview,
  CommunicationView,
} from "@/lib/api";

const {
  authRef,
  listRef,
  categoriesRef,
  deliveriesRef,
  reloadMock,
  createMock,
  updateMock,
  previewMock,
  audienceMock,
  testMock,
  sendMock,
} = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  listRef: { current: { data: [] as CommunicationView[], error: null as ApiError | null, loading: false } },
  categoriesRef: { current: { data: [] as CommunicationCategoryOption[], error: null, loading: false } },
  deliveriesRef: { current: { data: [] as unknown[], error: null, loading: false } },
  reloadMock: vi.fn(),
  createMock: vi.fn(),
  updateMock: vi.fn(),
  previewMock: vi.fn(),
  audienceMock: vi.fn(),
  testMock: vi.fn(),
  sendMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fn: (t: string | undefined) => Promise<unknown>) => {
    const source = fn.toString();
    const ref = source.includes("listCommunications")
      ? listRef
      : source.includes("communicationCategories")
        ? categoriesRef
        : deliveriesRef;
    return { ...ref.current, reload: reloadMock };
  },
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      createCommunication: createMock,
      updateCommunication: updateMock,
      previewCommunication: previewMock,
      communicationAudience: audienceMock,
      testCommunication: testMock,
      sendCommunication: sendMock,
    },
  };
});

import CommunicationsPage from "@/app/communications/page";

const CATEGORIES: CommunicationCategoryOption[] = [
  { value: "NEWSLETTER", label: "Newsletter", description: "The temple's regular letter." },
  { value: "TEMPLE_NOTICE", label: "Temple notices", description: "Closures and timings." },
];

function communication(o: Partial<CommunicationView> = {}): CommunicationView {
  return {
    id: "c1",
    category: "NEWSLETTER",
    channel: "EMAIL",
    subject: "Janmashtami at the temple",
    bodyHtml: "<p>Hare Krishna</p>",
    bodyText: "Hare Krishna",
    whatsappSummary: null,
    status: "DRAFT",
    audienceCount: null,
    publicToken: "abcdef1234567890",
    author: "Temple Admin",
    createdAt: "2026-08-19T00:00:00Z",
    sentAt: null,
    ...o,
  };
}

const PREVIEW: CommunicationPreview = {
  subject: "Janmashtami at the temple",
  emailHtml: "<html><body>framed</body></html>",
  whatsappText: "A message from Bengaluru Temple — Janmashtami: come early. Read it here: https://x/c/ab",
  plainText: "Hare Krishna",
};

describe("writing to the community", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    listRef.current = { data: [], error: null, loading: false };
    categoriesRef.current = { data: CATEGORIES, error: null, loading: false };
    deliveriesRef.current = { data: [], error: null, loading: false };
    reloadMock.mockReset();
    createMock.mockReset().mockResolvedValue({ id: "new-1" });
    updateMock.mockReset().mockResolvedValue(undefined);
    previewMock.mockReset().mockResolvedValue(PREVIEW);
    audienceMock.mockReset().mockResolvedValue({ count: 42 });
    testMock.mockReset().mockResolvedValue(undefined);
    sendMock.mockReset().mockResolvedValue({ audience: 42, queued: 42 });
  });

  it("says what this screen is for when nothing has been written", () => {
    render(<CommunicationsPage />);
    expect(screen.getByRole("heading", { name: "Communications" })).toBeInTheDocument();
    expect(screen.getByText(/nothing written yet/i)).toBeInTheDocument();
  });

  it("offers only the kinds a person may write — never reminders and receipts", () => {
    render(<CommunicationsPage />);
    fireEvent.click(screen.getByRole("button", { name: /write a message/i }));
    const form = screen.getByRole("form", { name: /write a communication/i });
    const kinds = within(form.querySelector("select")!).getAllByRole("option").map((o) => o.textContent);
    expect(kinds).toEqual(["Newsletter", "Temple notices"]);
    expect(kinds).not.toContain("Reminders and receipts");
  });

  it("warns, before a letter is written, that WhatsApp cannot carry one", () => {
    render(<CommunicationsPage />);
    fireEvent.click(screen.getByRole("button", { name: /write a message/i }));
    expect(screen.queryByText(/can't carry a letter/i)).not.toBeInTheDocument();

    const form = screen.getByRole("form", { name: /write a communication/i });
    const channel = form.querySelectorAll("select")[1];
    fireEvent.change(channel, { target: { value: "WHATSAPP" } });

    expect(screen.getByText(/can't carry a letter/i)).toBeInTheDocument();
    // And it asks for the one line it can carry.
    expect(screen.getByText(/the one line whatsapp carries/i)).toBeInTheDocument();
  });

  it("saves then previews, and shows the email and the WhatsApp line apart", async () => {
    render(<CommunicationsPage />);
    fireEvent.click(screen.getByRole("button", { name: /write a message/i }));
    const form = screen.getByRole("form", { name: /write a communication/i });
    fireEvent.change(form.querySelector("input")!, { target: { value: "Janmashtami" } });

    fireEvent.click(screen.getByRole("button", { name: /save and preview/i }));
    await waitFor(() => expect(previewMock).toHaveBeenCalledWith("new-1", "test-token"));

    expect(await screen.findByTitle("Email preview")).toBeInTheDocument();
    expect(screen.getByText(/A message from Bengaluru Temple/)).toBeInTheDocument();
  });

  it("never sends without saying how many people it will reach", async () => {
    render(<CommunicationsPage />);
    fireEvent.click(screen.getByRole("button", { name: /write a message/i }));
    const form = screen.getByRole("form", { name: /write a communication/i });
    fireEvent.change(form.querySelector("input")!, { target: { value: "Janmashtami" } });

    fireEvent.click(screen.getByRole("button", { name: /send to everyone/i }));
    await waitFor(() => expect(audienceMock).toHaveBeenCalled());

    // The count, and no send yet.
    expect(await screen.findByText(/this will reach 42 devotees/i)).toBeInTheDocument();
    expect(sendMock).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: /send it/i }));
    await waitFor(() => expect(sendMock).toHaveBeenCalledWith("new-1", "test-token"));
  });

  it("backs out of a send without sending", async () => {
    render(<CommunicationsPage />);
    fireEvent.click(screen.getByRole("button", { name: /write a message/i }));
    const form = screen.getByRole("form", { name: /write a communication/i });
    fireEvent.change(form.querySelector("input")!, { target: { value: "Janmashtami" } });

    fireEvent.click(screen.getByRole("button", { name: /send to everyone/i }));
    await screen.findByText(/this will reach 42 devotees/i);
    fireEvent.click(screen.getByRole("button", { name: /not yet/i }));

    await waitFor(() => expect(screen.queryByText(/this will reach/i)).not.toBeInTheDocument());
    expect(sendMock).not.toHaveBeenCalled();
  });

  it("sends the author a copy without sending it to anybody else", async () => {
    render(<CommunicationsPage />);
    fireEvent.click(screen.getByRole("button", { name: /write a message/i }));
    const form = screen.getByRole("form", { name: /write a communication/i });
    fireEvent.change(form.querySelector("input")!, { target: { value: "Janmashtami" } });

    fireEvent.click(screen.getByRole("button", { name: /send myself a copy/i }));
    await waitFor(() => expect(testMock).toHaveBeenCalledWith("new-1", "test-token"));
    expect(sendMock).not.toHaveBeenCalled();
    expect(await screen.findByText(/on its way to your own address/i)).toBeInTheDocument();
  });

  it("keeps drafts and sent messages in separate lists", () => {
    listRef.current = {
      data: [
        communication(),
        communication({
          id: "c2",
          subject: "Kitchen closed Tuesday",
          category: "TEMPLE_NOTICE",
          status: "SENT",
          audienceCount: 40,
          sentAt: "2026-08-18T10:00:00Z",
        }),
      ],
      error: null,
      loading: false,
    };
    render(<CommunicationsPage />);
    const drafts = screen.getByRole("region", { name: /drafts/i });
    const sent = screen.getByRole("region", { name: /^sent/i });
    expect(within(drafts).getByText("Janmashtami at the temple")).toBeInTheDocument();
    expect(within(sent).getByText("Kitchen closed Tuesday")).toBeInTheDocument();
    expect(within(sent).getByText("40")).toBeInTheDocument();
  });

  it("opens a sent message read-only, with its recipients and its web copy", () => {
    listRef.current = {
      data: [
        communication({
          status: "SENT",
          audienceCount: 2,
          sentAt: "2026-08-18T10:00:00Z",
        }),
      ],
      error: null,
      loading: false,
    };
    deliveriesRef.current = {
      data: [
        { recipientName: "Nitai Das", status: "SENT", channel: "EMAIL", suppressedReason: null },
        { recipientName: "Gaura Das", status: "SUPPRESSED", channel: "EMAIL", suppressedReason: "OPTED_OUT" },
      ],
      error: null,
      loading: false,
    };
    render(<CommunicationsPage />);
    fireEvent.click(screen.getByRole("button", { name: "Janmashtami at the temple" }));

    expect(screen.getByText("Nitai Das")).toBeInTheDocument();
    expect(screen.getByText(/they turned this kind off/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /\/c\/abcdef12/ })).toBeInTheDocument();
    // A sent message offers no way to change or resend it.
    expect(screen.queryByRole("button", { name: /send to everyone/i })).not.toBeInTheDocument();
  });

  it("refuses kitchen staff", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<CommunicationsPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
