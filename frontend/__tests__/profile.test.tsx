import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ApiError, Profile } from "@/lib/api";

// The profile screen is role-gated and reads/writes live data. Drive the guard and the query
// from mutable refs, and let the two mutations be spies so interactions can be asserted without
// touching Firebase or fetch.
const { authRef, queryRef, giveConsentMock, updateChannelMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", fullName: "Test Person" } } as {
      status: string;
      appUser: { role: string; fullName?: string } | null;
    },
  },
  queryRef: {
    current: { data: null as Profile | null, error: null as ApiError | null, loading: false },
  },
  giveConsentMock: vi.fn(),
  updateChannelMock: vi.fn(),
}));

// T-184: the leave section reads and withdraws through these. Until a test says otherwise its list never
// answers, so the section renders nothing and the older tests above see the page they always did.
const { myLeaveMock, withdrawLeaveMock } = vi.hoisted(() => ({
  myLeaveMock: vi.fn((): Promise<import("@/lib/api").LeaveView[]> => new Promise(() => {})),
  withdrawLeaveMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({ useAuthedQuery: () => queryRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      giveConsent: giveConsentMock,
      updatePreferredChannel: updateChannelMock,
      myLeave: myLeaveMock,
      withdrawLeave: withdrawLeaveMock,
    },
  };
});

import ProfilePage from "@/app/profile/page";

const CONSENT_TEXT =
  "I agree that my temple may send me reminders and service messages — such as volunteer shift " +
  "reminders and order updates — by WhatsApp, SMS, or email, using the contact details on my " +
  "account. I can change my preferred channel or withdraw this consent at any time from my profile.";

function profile(overrides: Partial<Profile>): Profile {
  return {
    fullName: "Radha Devi",
    email: "radha@example.com",
    phone: "+919876543210",
    preferredChannel: "WHATSAPP",
    consentAt: null,
    consentVersion: null,
    consentNeeded: true,
    currentConsentVersion: "1",
    consentText: CONSENT_TEXT,
    role: "TEMPLE_ADMIN",
    ...overrides,
  };
}

describe("profile", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", fullName: "Test Person" } };
    queryRef.current = { data: profile({}), error: null, loading: false };
    giveConsentMock.mockReset();
    updateChannelMock.mockReset();
  });

  it("shows contact details, the current channel, and the consent ask", () => {
    render(<ProfilePage />);

    expect(screen.getByRole("heading", { name: /your account/i })).toBeInTheDocument();
    expect(screen.getByText("Radha Devi")).toBeInTheDocument();
    expect(screen.getByText("radha@example.com")).toBeInTheDocument();

    // WhatsApp is the current channel, and the three choices are offered.
    expect(screen.getByRole("radio", { name: /whatsapp/i })).toBeChecked();
    expect(screen.getByRole("radio", { name: /sms/i })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: /email/i })).toBeInTheDocument();

    // Consent is a plain-language ask with an explicit action, from the backend wording.
    expect(screen.getByText(/withdraw this consent at any time/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /i agree/i })).toBeInTheDocument();
  });

  it("presents contact details as read-only, to be changed by an administrator", () => {
    render(<ProfilePage />);

    const contact = screen.getByRole("region", { name: /contact details/i });
    expect(within(contact).queryByRole("textbox")).not.toBeInTheDocument();
    expect(within(contact).getByText(/ask your temple administrator/i)).toBeInTheDocument();
  });

  it("records consent when the user agrees, and confirms it", async () => {
    giveConsentMock.mockResolvedValue(
      profile({ consentNeeded: false, consentAt: "2026-08-10T00:00:00Z", consentVersion: "1" })
    );
    render(<ProfilePage />);

    fireEvent.click(screen.getByRole("button", { name: /i agree/i }));

    // The confirmation only appears once the async save has resolved and re-rendered.
    expect(await screen.findByText(/you agreed/i)).toBeInTheDocument();
    expect(giveConsentMock).toHaveBeenCalledWith("test-token");
    expect(screen.queryByRole("button", { name: /i agree/i })).not.toBeInTheDocument();
  });

  it("confirms a standing consent rather than asking again", () => {
    queryRef.current = {
      data: profile({ consentNeeded: false, consentAt: "2026-08-01T00:00:00Z", consentVersion: "1" }),
      error: null,
      loading: false,
    };
    render(<ProfilePage />);

    expect(screen.getByText(/you agreed/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /i agree/i })).not.toBeInTheDocument();
  });

  it("saves a new preferred channel when one is chosen", async () => {
    updateChannelMock.mockResolvedValue(profile({ preferredChannel: "EMAIL" }));
    render(<ProfilePage />);

    fireEvent.click(screen.getByRole("radio", { name: /email/i }));

    await waitFor(() =>
      expect(updateChannelMock).toHaveBeenCalledWith("EMAIL", "test-token")
    );
  });

  it("shows the error contract when loading the profile fails", () => {
    queryRef.current = {
      data: null,
      loading: false,
      error: {
        code: "KMS-0000",
        message: "We couldn't load this.",
        action: "Try again.",
      } as ApiError,
    };
    render(<ProfilePage />);

    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.getByText("KMS-0000")).toBeInTheDocument();
  });

  it("refuses a platform operator, who has no temple profile", () => {
    authRef.current = { status: "signed-in", appUser: { role: "SUPER_ADMIN", fullName: "Test Person" } };
    render(<ProfilePage />);

    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /your account/i })).not.toBeInTheDocument();
  });

  it("marks profile as the current page in navigation", () => {
    render(<ProfilePage />);

    // Profile is reached from the person at the foot of the menu — it shows who you are rather
    // than the word "Profile" — and that row is now a button that opens the panel in place, so the
    // current page is marked on it rather than on a link.
    const nav = screen.getByRole("navigation", { name: /main/i });
    expect(within(nav).getByRole("button", { current: "page" })).toHaveAccessibleName(/test person/i);
  });
});

describe("your leave (T-184)", () => {
  type Leave = import("@/lib/api").LeaveView;

  function row(overrides: Partial<Leave>): Leave {
    return {
      id: "l1",
      staffProfileId: "p1",
      staffName: "Radha Devi",
      jobTitleLabel: "Cook",
      leaveType: "TIME_OFF",
      leaveTypeLabel: "Time off",
      fromDate: "2026-09-20",
      toDate: "2026-09-21",
      halfDay: false,
      reason: null,
      status: "PENDING",
      canWithdraw: false,
      requestedByName: "Radha Devi",
      requestedAt: "2026-09-10T04:00:00Z",
      decidedByName: null,
      decidedAt: null,
      decisionNote: null,
      ...overrides,
    };
  }

  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", fullName: "Radha Devi" } };
    queryRef.current = {
      data: profile({ role: "KITCHEN_STAFF", consentNeeded: false, consentAt: "2026-08-01T00:00:00Z", consentVersion: "1" }),
      error: null,
      loading: false,
    };
    withdrawLeaveMock.mockReset().mockResolvedValue(undefined);
  });

  it("offers Withdraw only on the rows the server says can still be withdrawn", async () => {
    // Approved and still ahead: withdrawable. Pending but already begun: the server says no, and the
    // screen does not second-guess it with the browser's clock.
    myLeaveMock.mockReset().mockResolvedValue([
      row({ id: "ahead", status: "APPROVED", canWithdraw: true }),
      row({ id: "begun", status: "PENDING", canWithdraw: false, fromDate: "2026-09-01", toDate: "2026-09-01" }),
    ]);
    render(<ProfilePage />);

    const section = await screen.findByRole("region", { name: "Your leave" });
    await within(section).findByText("Approved");
    expect(within(section).getAllByRole("button", { name: "Withdraw" })).toHaveLength(1);
    expect(within(section).getByText("Waiting")).toBeInTheDocument();
  });

  // T-184 rework: one press no longer withdraws. The row's Withdraw asks "Are you sure?" first, because
  // the person cannot take a withdrawal back and their manager is told at once.
  async function openConfirmation(leave: Leave) {
    myLeaveMock.mockReset().mockResolvedValue([leave]);
    render(<ProfilePage />);
    const section = await screen.findByRole("region", { name: "Your leave" });
    fireEvent.click(await within(section).findByRole("button", { name: "Withdraw" }));
    return { section, dialog: await screen.findByRole("alertdialog", { name: "Withdraw this leave" }) };
  }

  it("asks about approved leave in its own words", async () => {
    const { dialog } = await openConfirmation(
      row({ id: "ahead", status: "APPROVED", canWithdraw: true, fromDate: "2026-08-12", toDate: "2026-08-14" })
    );
    expect(dialog).toHaveTextContent("Withdraw your approved leave for 12 to 14 August? Your manager will be told.");
  });

  it("asks about a pending request in its own words", async () => {
    const { dialog } = await openConfirmation(
      row({ id: "waiting", status: "PENDING", canWithdraw: true, fromDate: "2026-08-12", toDate: "2026-08-14" })
    );
    expect(dialog).toHaveTextContent(
      "Withdraw your leave request for 12 to 14 August? Whoever approves leave will be told."
    );
  });

  it("puts focus on Cancel when it asks", async () => {
    const { dialog } = await openConfirmation(row({ id: "ahead", status: "APPROVED", canWithdraw: true }));
    await waitFor(() => expect(within(dialog).getByRole("button", { name: "Cancel" })).toHaveFocus());
  });

  it("withdraws exactly once when Withdraw is pressed in the question, then reads the list again", async () => {
    const { dialog } = await openConfirmation(row({ id: "ahead", status: "APPROVED", canWithdraw: true }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Withdraw" }));

    await waitFor(() => expect(myLeaveMock).toHaveBeenCalledTimes(2));
    expect(withdrawLeaveMock).toHaveBeenCalledTimes(1);
    expect(withdrawLeaveMock).toHaveBeenCalledWith("ahead", "test-token");
  });

  it("changes nothing when Cancel is pressed", async () => {
    const { section, dialog } = await openConfirmation(row({ id: "ahead", status: "APPROVED", canWithdraw: true }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));

    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(withdrawLeaveMock).not.toHaveBeenCalled();
    expect(within(section).getByText("Approved")).toBeInTheDocument();
    expect(within(section).getByRole("button", { name: "Withdraw" })).toBeInTheDocument();
  });

  it("still shows the server's sentence when the withdrawal is refused", async () => {
    const { ApiError } = await import("@/lib/api");
    withdrawLeaveMock.mockReset().mockRejectedValue(
      new ApiError({
        code: "KMS-400151",
        message: "This leave has already begun, so it can't be withdrawn.",
        action: "Ask whoever approves leave to change it.",
        fieldErrors: [],
      })
    );
    const { dialog, section } = await openConfirmation(row({ id: "ahead", status: "APPROVED", canWithdraw: true }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Withdraw" }));

    expect(await within(section).findByText("This leave has already begun, so it can't be withdrawn.")).toBeInTheDocument();
  });

  it("says a withdrawal was the person's own, and a revocation was the temple's", async () => {
    myLeaveMock.mockReset().mockResolvedValue([
      row({ id: "mine", status: "WITHDRAWN" }),
      row({ id: "theirs", status: "REVOKED", fromDate: "2026-10-01", toDate: "2026-10-01" }),
    ]);
    render(<ProfilePage />);

    const section = await screen.findByRole("region", { name: "Your leave" });
    expect(await within(section).findByText("Withdrawn by you")).toBeInTheDocument();
    expect(within(section).getByText("Withdrawn by the temple")).toBeInTheDocument();
    expect(within(section).queryByRole("button", { name: "Withdraw" })).not.toBeInTheDocument();
  });
});
