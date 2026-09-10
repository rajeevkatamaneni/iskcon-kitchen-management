import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { DocumentView, DonationDetail, LedgerRow } from "@/lib/api";

/**
 * The donation screen (T-110): the receipt, and what else this donor has given.
 *
 * <p>What is asserted here is the half a person can see. That the receipt control is offered on a
 * gift that stands and withheld on one already struck; that the sentence about what the receipt can
 * claim follows the temple's 80G status and the kind of gift, rather than saying "80G" whatever is
 * true; that the history opens on good gifts alone and the toggle reveals the rest without a second
 * request; and — the one that must never be dropped — that the page says in as many words that this
 * is a likeness rather than a confirmed identity.
 *
 * <p>Whether re-sending really produces one document is `DonationReceiptIT`'s business, against a
 * real database and a real unique index. A screen cannot prove that and should not pretend to.
 */
const { authRef, returnsRef, reloadMock, issueMock, sendMock, downloadMock } = vi.hoisted(() => ({
  issueMock: vi.fn(),
  sendMock: vi.fn(),
  downloadMock: vi.fn(),
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  returnsRef: {
    current: [] as Array<{ data: unknown; error: unknown; loading: boolean }>,
    slots: new Map<unknown, number>(),
  },
  reloadMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useParams: () => ({ id: "d-1" }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
// The same fixture the ledger's own tests use: each fetcher keeps the slot it was first seen in.
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fetcher: unknown) => {
    if (!returnsRef.slots.has(fetcher)) {
      returnsRef.slots.set(fetcher, returnsRef.slots.size);
    }
    const list = returnsRef.current;
    const slot = Math.min(returnsRef.slots.get(fetcher) as number, list.length - 1);
    return { ...list[slot], reload: reloadMock };
  },
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      issueDonationReceipt: issueMock,
      sendDonationReceipt: sendMock,
      downloadDonationReceipt: downloadMock,
    },
  };
});

import DonationPage from "@/app/donations/[id]/page";

const GIFT: DonationDetail = {
  id: "d-1",
  type: "ONE_TIME",
  category: "ONE_TIME",
  donatedOn: "2026-08-16",
  status: "COMPLETED",
  voided: false,
  voidReason: null,
  amountInr: 14000,
  wishlistApplied: null,
  currency: "INR",
  paymentMode: "CARD",
  providerRef: "pay_abc",
  linkedTo: "General kitchen",
  anonymous: false,
  donorName: "Gopal Das",
  donorPhone: "+919812345678",
  donorEmail: "gopal@example.com",
  donorAddress: "12 Temple Road, Bengaluru",
  wants80g: true,
  hasPan: true,
  notes: null,
  acknowledgedAt: null,
  receiptNumber: null,
  receiptIssuedAt: null,
  temple80gApproved: true,
  canBeReceipted: true,
};

const READY: DocumentView = {
  id: "doc-1",
  kind: "DONATION_RECEIPT_PDF",
  recipeId: null,
  purchaseOrderId: null,
  version: 1,
  language: "en",
  targetYield: null,
  status: "READY",
  error: null,
  createdAt: "2026-08-16T09:00:00Z",
  readyAt: "2026-08-16T09:00:05Z",
};

function row(over: Partial<LedgerRow>): LedgerRow {
  return {
    id: "x",
    donatedOn: "2026-05-02",
    category: "ONE_TIME",
    donorDisplay: "Gopal Das",
    amountInr: 1000,
    currency: "INR",
    paymentMode: "CARD",
    providerRef: null,
    status: "COMPLETED",
    linkedTo: "General kitchen",
    voided: false,
    voidReason: null,
    ...over,
  };
}

const GOOD = row({ id: "h-good", amountInr: 2500 });
const FAILED = row({ id: "h-failed", amountInr: 1000, status: "FAILED" });
const EXPIRED = row({ id: "h-expired", amountInr: 2000, status: "EXPIRED" });
const STRUCK = row({ id: "h-struck", amountInr: 3000, voided: true, voidReason: "Entered twice." });

function withScreen(
  donation: DonationDetail,
  receipt: DocumentView | null,
  history: LedgerRow[]
) {
  returnsRef.current = [
    { data: donation, error: null, loading: false },
    { data: receipt, error: null, loading: false },
    { data: history, error: null, loading: false },
  ];
  returnsRef.slots.clear();
}

describe("one donation, its receipt, and what else this donor has given", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    withScreen(GIFT, null, [GOOD, FAILED, EXPIRED, STRUCK]);
    reloadMock.mockReset();
    issueMock.mockReset().mockResolvedValue({
      documentId: "doc-1",
      status: "PENDING",
      receiptNumber: "R-2026-0001",
    });
    sendMock.mockReset().mockResolvedValue({ sent: true });
    downloadMock.mockReset().mockResolvedValue(new Blob(["%PDF"]));
  });

  it("issues the receipt and reloads, so the number reaches the screen", async () => {
    render(<DonationPage />);
    fireEvent.click(screen.getByRole("button", { name: /issue the receipt/i }));

    await waitFor(() => expect(issueMock).toHaveBeenCalledWith("d-1", "test-token"));
    await waitFor(() => expect(reloadMock).toHaveBeenCalled());
    expect(await screen.findByText(/Receipt R-2026-0001 issued\./)).toBeInTheDocument();
  });

  it("offers downloading and re-sending once a receipt exists, and never a second issue", () => {
    withScreen(
      { ...GIFT, receiptNumber: "R-2026-0001", receiptIssuedAt: "2026-08-16T09:00:00Z" },
      READY,
      []
    );
    render(<DonationPage />);

    expect(screen.getByRole("button", { name: /download the receipt/i })).toBeEnabled();
    expect(screen.getByRole("button", { name: /send it to the donor/i })).toBeEnabled();
    // There is no second issuing. Pressing it again would be asking for a second receipt, which is
    // the one thing this feature must never look willing to do.
    expect(screen.queryByRole("button", { name: /issue the receipt/i })).not.toBeInTheDocument();
  });

  it("says a re-send is the same receipt, not a new one", async () => {
    withScreen({ ...GIFT, receiptNumber: "R-2026-0001", receiptIssuedAt: "2026-08-16T09:00:00Z" },
      READY, []);
    render(<DonationPage />);
    fireEvent.click(screen.getByRole("button", { name: /send it to the donor/i }));

    await waitFor(() => expect(sendMock).toHaveBeenCalledWith("d-1", "test-token"));
    expect(await screen.findByText(/The same receipt, not a new one\./)).toBeInTheDocument();
  });

  it("withholds the receipt from a struck gift and says what to do instead", () => {
    withScreen(
      { ...GIFT, voided: true, voidReason: "Entered twice at the gate.", canBeReceipted: false },
      null,
      []
    );
    render(<DonationPage />);

    // Withheld rather than offered and refused — the same call the ledger makes about Void.
    expect(screen.queryByRole("button", { name: /issue the receipt/i })).not.toBeInTheDocument();
    expect(screen.getByText(/No receipt can be issued for a gift the temple has struck/i))
      .toBeInTheDocument();
    expect(screen.getByText(/Entered twice at the gate\./)).toBeInTheDocument();
  });

  it("cannot be sent where there is nobody to send it to, and says so rather than failing", () => {
    withScreen(
      {
        ...GIFT,
        receiptNumber: "R-2026-0001",
        receiptIssuedAt: "2026-08-16T09:00:00Z",
        donorPhone: null,
        donorEmail: null,
      },
      READY,
      []
    );
    render(<DonationPage />);

    expect(screen.getByRole("button", { name: /send it to the donor/i })).toBeDisabled();
    expect(screen.getByText(/no phone number and no email address/i)).toBeInTheDocument();
  });

  /**
   * Three temples, three sentences. A receipt headed 80G that supports no deduction is the worst
   * outcome this feature has, because the donor only finds out at assessment.
   */
  it("claims exactly what the temple's 80G status and the kind of gift allow", () => {
    render(<DonationPage />);
    expect(screen.getByText(/registered under Section 80G, so the receipt states/i)).toBeInTheDocument();

    withScreen({ ...GIFT, temple80gApproved: false }, null, []);
    render(<DonationPage />);
    expect(screen.getByText(/no 80G registration recorded/i)).toBeInTheDocument();

    withScreen({ ...GIFT, type: "IN_KIND", category: "IN_KIND" }, null, []);
    render(<DonationPage />);
    expect(screen.getByText(/does not qualify for deduction under Section 80G/i)).toBeInTheDocument();
  });

  it("shows the whole payment, and explains the split rather than putting it on the receipt", () => {
    withScreen({ ...GIFT, wishlistApplied: 4000, linkedTo: "Wish list: A wet grinder" }, null, []);
    render(<DonationPage />);

    expect(screen.getByText(/The receipt reports the whole payment/i)).toBeInTheDocument();
    // The payment, on the screen, is the payment — the thing the receipt will report.
    expect(screen.getAllByText("₹14,000").length).toBeGreaterThan(0);
  });

  describe("what else this donor has given", () => {
    it("says this is a likeness and not a confirmed identity", () => {
      render(<DonationPage />);
      // Not optional, and not to be dropped in a later copy pass: a page implying certainty about
      // identity next to a tax document will eventually be quoted back at somebody.
      expect(
        screen.getByText(/matched on donor account, PAN, phone or email/i)
      ).toBeInTheDocument();
      expect(
        screen.getByText(/a likeness, not a confirmed identity/i)
      ).toBeInTheDocument();
    });

    it("opens on the good gifts alone, and the toggle reveals the rest", () => {
      render(<DonationPage />);
      const history = screen.getByRole("region", { name: /what else this donor has given/i });

      expect(within(history).getByText("₹2,500")).toBeInTheDocument();
      expect(within(history).queryByText("₹1,000")).not.toBeInTheDocument();
      expect(within(history).queryByText("₹2,000")).not.toBeInTheDocument();
      expect(within(history).queryByText("₹3,000")).not.toBeInTheDocument();

      // Nothing is deleted and nothing is hidden permanently, which is what the toggle is for.
      fireEvent.click(within(history).getByLabelText(/failed, ran out of time, or were struck/i));

      expect(within(history).getByText("₹1,000")).toBeInTheDocument();
      expect(within(history).getByText("₹2,000")).toBeInTheDocument();
      expect(within(history).getByText("₹3,000")).toBeInTheDocument();
      // A struck gift is marked and struck through, not silently listed as if it counted.
      expect(within(history).getByText("Voided")).toBeInTheDocument();
    });

    it("offers no toggle when there is nothing behind it", () => {
      withScreen(GIFT, null, [GOOD]);
      render(<DonationPage />);
      const history = screen.getByRole("region", { name: /what else this donor has given/i });

      expect(within(history).queryByLabelText(/failed, ran out of time, or were struck/i))
        .not.toBeInTheDocument();
    });

    it("explains an empty list rather than leaving a blank", () => {
      withScreen(GIFT, null, []);
      render(<DonationPage />);

      expect(
        screen.getByText(/Nothing else here looks like it came from this person/i)
      ).toBeInTheDocument();
    });
  });
});

/**
 * The wait between issuing a receipt and being able to download it.
 *
 * <p>Driven on staging on 2026-09-09, signed in as the Temple Admin: *Issue the receipt* left
 * Download disabled under "The receipt is being prepared", and it stayed disabled for ever. The
 * document's own `readyAt` said the PDF had been written twenty-four seconds later — the page
 * simply never asked again, and only a hand reload told it. Issuing and rendering are two steps on
 * purpose, so the fix is not to make issuing wait; it is for the screen to find out.
 */
describe("the screen notices when the receipt is ready, without being reloaded", () => {
  const ISSUED = { ...GIFT, receiptNumber: "R-2026-0001", receiptIssuedAt: "2026-08-16T09:00:00Z" };
  const PREPARING: DocumentView = { ...READY, status: "PENDING", readyAt: null };

  beforeEach(() => {
    vi.useFakeTimers();
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    reloadMock.mockReset();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("asks again while the PDF is being written, and stops the moment it is ready", () => {
    withScreen(ISSUED, PREPARING, []);
    const { rerender } = render(<DonationPage />);

    expect(screen.getByRole("button", { name: /download the receipt/i })).toBeDisabled();
    expect(screen.getByText(/The receipt is being prepared/i)).toBeInTheDocument();
    // Nothing is asked before the first interval elapses: the answer that has just arrived is the
    // answer, and re-asking immediately would only spend a request to hear it again.
    expect(reloadMock).not.toHaveBeenCalled();

    act(() => void vi.advanceTimersByTime(4000));
    expect(reloadMock).toHaveBeenCalledTimes(1);
    act(() => void vi.advanceTimersByTime(8000));
    expect(reloadMock).toHaveBeenCalledTimes(3);

    // The PDF lands, exactly as it would have on the twenty-fourth second.
    withScreen(ISSUED, READY, []);
    rerender(<DonationPage />);
    expect(screen.getByRole("button", { name: /download the receipt/i })).toBeEnabled();
    expect(screen.queryByText(/The receipt is being prepared/i)).not.toBeInTheDocument();

    // And it stops asking. A page that goes on polling a question it has the answer to is the
    // half of this fix that nobody would ever notice was missing.
    const asked = reloadMock.mock.calls.length;
    act(() => void vi.advanceTimersByTime(60000));
    expect(reloadMock).toHaveBeenCalledTimes(asked);
  });

  it("does not run in a tab nobody is looking at", () => {
    withScreen(ISSUED, PREPARING, []);
    render(<DonationPage />);

    act(() => void vi.advanceTimersByTime(4000));
    expect(reloadMock).toHaveBeenCalledTimes(1);

    const hidden = vi.spyOn(document, "hidden", "get").mockReturnValue(true);
    act(() => void document.dispatchEvent(new Event("visibilitychange")));
    act(() => void vi.advanceTimersByTime(60000));
    expect(reloadMock).toHaveBeenCalledTimes(1);

    // Back in front, and it picks the wait up where it left off.
    hidden.mockReturnValue(false);
    act(() => void document.dispatchEvent(new Event("visibilitychange")));
    act(() => void vi.advanceTimersByTime(4000));
    expect(reloadMock).toHaveBeenCalledTimes(2);
    hidden.mockRestore();
  });

  it("never starts on a gift with no receipt yet, or on one that was struck", () => {
    // The two states where "being prepared" is not what is on the screen at all. A timer running
    // behind either of them would be asking the server about a document that does not exist.
    withScreen(GIFT, null, []);
    const { unmount } = render(<DonationPage />);
    act(() => void vi.advanceTimersByTime(60000));
    expect(reloadMock).not.toHaveBeenCalled();
    unmount();

    withScreen({ ...ISSUED, voided: true, voidReason: "Entered twice.", canBeReceipted: false }, PREPARING, []);
    render(<DonationPage />);
    act(() => void vi.advanceTimersByTime(60000));
    expect(reloadMock).not.toHaveBeenCalled();
  });
});
