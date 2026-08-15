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

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({ useAuthedQuery: () => queryRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, giveConsent: giveConsentMock, updatePreferredChannel: updateChannelMock },
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
