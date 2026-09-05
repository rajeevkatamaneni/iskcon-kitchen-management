import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { WhatsAppSettingsView } from "@/lib/api";

/** The section under test. Every other section on this screen has a Save button too. */
const warnings = () => within(screen.getByRole("region", { name: "Warnings" }));

const WHATSAPP_NONE: WhatsAppSettingsView = {
  connected: false,
  phoneNumberId: null,
  wabaId: null,
  displayNumber: null,
  webhookUrl: null,
  verifiedAt: null,
  webhookSeenAt: null,
  templatesSubmittedAt: null,
};

const {
  paymentSettings,
  paymentProviders,
  paymentEvents,
  whatsappSettings,
  templeContactEmail,
  templeSettings,
  setWarningHorizons,
} = vi.hoisted(() => ({
  paymentSettings: vi.fn(),
  paymentProviders: vi.fn(),
  paymentEvents: vi.fn(),
  whatsappSettings: vi.fn(),
  templeContactEmail: vi.fn(),
  templeSettings: vi.fn(),
  setWarningHorizons: vi.fn(),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      paymentSettings,
      paymentProviders,
      paymentEvents,
      whatsappSettings,
      templeContactEmail,
      templeSettings,
      setWarningHorizons,
    },
  };
});

vi.mock("@/components/RequireRole", () => ({
  RequireRole: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

vi.mock("@/components/Sidebar", () => ({ Sidebar: () => <nav aria-label="Main" /> }));

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ getToken: async () => "token-abc", appUser: { role: "TEMPLE_ADMIN" } }),
}));

import SettingsRoute from "@/app/settings/page";

/**
 * How much notice a temple gets, on the three things that warn ahead of a date.
 *
 * <p>The first two were constants, both seven days, because the contract one borrowed the stock
 * one. Seven days is enough to cook a sack of flour before it turns and is not enough to
 * renegotiate an agreement, so they part company here — together, in one section, because the thing
 * worth preventing is a temple finding one of them settable and the other not.
 *
 * <p>The servicing horizon (E3-S10 D5) is the third, and the case below that matters most is the
 * one asserting all three go in every request. Its endpoint accepts it as optional, so a screen
 * that posted two would leave the server writing the old third back on every save — which is the
 * hazard E3-S10 named and left for this story to close.
 */
describe("the warning horizons", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    paymentSettings.mockResolvedValue({
      configured: false,
      provider: null,
      keyId: null,
      keySecretSavedAt: null,
      webhookUrl: null,
      verifiedAt: null,
      webhookSeenAt: null,
      webhookRegisteredAt: null,
    });
    paymentProviders.mockResolvedValue([{ value: "RAZORPAY", label: "Razorpay — India" }]);
    paymentEvents.mockResolvedValue([]);
    whatsappSettings.mockResolvedValue(WHATSAPP_NONE);
    templeContactEmail.mockResolvedValue({ contactEmail: null });
    templeSettings.mockResolvedValue({
      volunteerBroadcastDailyLimit: 3,
      locale: "en-IN",
      themeId: null,
      stockExpiryWarningDays: 7,
      contractEndWarningDays: 30,
      equipmentServiceWarningDays: 30,
    });
  });

  it("shows what this temple has, in days, one field each", async () => {
    render(<SettingsRoute />);

    await waitFor(() => expect(screen.getByRole("region", { name: "Warnings" })).toBeInTheDocument());
    expect(warnings().getByLabelText("Notice before stock expires")).toHaveValue(7);
    expect(warnings().getByLabelText("Notice before a vendor contract ends")).toHaveValue(30);
    expect(warnings().getByLabelText("Notice before a machine is due a service")).toHaveValue(30);
    // Neither number does anything but put a badge on a screen, and the section says so.
    expect(warnings().getByText(/Nothing is dropped or written off/)).toBeInTheDocument();
  });

  it("sends all three together, because they are one decision", async () => {
    setWarningHorizons.mockResolvedValue(undefined);
    render(<SettingsRoute />);

    await waitFor(() => expect(warnings().getByLabelText("Notice before stock expires")).toBeInTheDocument());
    fireEvent.change(warnings().getByLabelText("Notice before a vendor contract ends"), {
      target: { value: "45" },
    });
    fireEvent.click(warnings().getByRole("button", { name: "Save" }));

    // The other two ride along untouched rather than being left to drift — and the servicing one
    // has to be in the request at all, or the server writes back whatever it already held.
    await waitFor(() =>
      expect(setWarningHorizons).toHaveBeenCalledWith(
        {
          stockExpiryWarningDays: 7,
          contractEndWarningDays: 45,
          equipmentServiceWarningDays: 30,
        },
        "token-abc"
      )
    );
    expect(await warnings().findByText("Saved.")).toBeInTheDocument();
  });

  it("refuses a horizon no warning could survive, before anything is sent", async () => {
    render(<SettingsRoute />);

    await waitFor(() => expect(warnings().getByLabelText("Notice before stock expires")).toBeInTheDocument());

    for (const bad of ["0", "-1", "366", ""]) {
      fireEvent.change(warnings().getByLabelText("Notice before stock expires"), {
        target: { value: bad },
      });
      expect(warnings().getAllByText("A warning is between 1 and 365 days.").length).toBeGreaterThan(0);
      expect(warnings().getByRole("button", { name: "Save" })).toBeDisabled();
    }

    fireEvent.change(warnings().getByLabelText("Notice before stock expires"), {
      target: { value: "365" },
    });
    expect(warnings().queryByText("A warning is between 1 and 365 days.")).not.toBeInTheDocument();
    expect(warnings().getByRole("button", { name: "Save" })).toBeEnabled();
    expect(setWarningHorizons).not.toHaveBeenCalled();
  });
});
