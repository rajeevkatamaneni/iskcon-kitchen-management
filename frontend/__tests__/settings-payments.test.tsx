import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { WhatsAppSettingsView } from "@/lib/api";

/** Both sections have a Test connection button; every query has to say which section it means. */
const gateway = () => within(screen.getByRole("region", { name: "Payment gateway" }));

// Grouped, because a provider only lists subscription events once Subscriptions is switched on.
// A temple that has connected no WhatsApp account, which is every temple until one does.
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

const EVENT_GROUPS = [
  { purpose: "Confirming donations", essential: true, events: ["payment.captured", "payment.failed"] },
  { purpose: "Monthly giving", essential: false, events: ["subscription.charged", "subscription.halted"] },
];

const {
  paymentSettings,
  paymentProviders,
  paymentEvents,
  whatsappSettings,
  saveWhatsAppSettings,
  testWhatsAppSettings,
  revealWhatsAppVerifyToken,
  savePaymentSettings,
  testPaymentSettings,
  revealWebhookSecret,
} = vi.hoisted(() => ({
  paymentSettings: vi.fn(),
  paymentProviders: vi.fn(async () => [{ value: "RAZORPAY", label: "Razorpay — India" }]),
  // Served by the API so the screen and the handlers can never disagree about what to subscribe to.
  paymentEvents: vi.fn(async () => EVENT_GROUPS),
  whatsappSettings: vi.fn(async () => WHATSAPP_NONE),
  saveWhatsAppSettings: vi.fn(),
  testWhatsAppSettings: vi.fn(),
  revealWhatsAppVerifyToken: vi.fn(),
  savePaymentSettings: vi.fn(),
  testPaymentSettings: vi.fn(),
  revealWebhookSecret: vi.fn(async () => ({ webhookSecret: "whsec-abc123" })),
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
      saveWhatsAppSettings,
      testWhatsAppSettings,
      revealWhatsAppVerifyToken,
      savePaymentSettings,
      testPaymentSettings,
      revealWebhookSecret,
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

const CONFIGURED = {
  configured: true,
  provider: "RAZORPAY",
  keyId: "rzp_test_abc123",
  keySecretSavedAt: "2026-08-10T08:08:39Z",
  webhookUrl: "https://kms.example/api/v1/public/webhooks/payments/7f3c9a12",
  verifiedAt: "2026-08-15T11:22:00Z",
  webhookSeenAt: null,
  webhookRegisteredAt: null,
};

/**
 * The two questions this screen answers fail independently — whether the temple's keys reach the
 * provider, and whether the provider ever reaches back — and only the first is one a button can
 * settle. Reporting them as one number is the mistake this screen exists to avoid.
 */
describe("the payment gateway settings", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    paymentProviders.mockResolvedValue([{ value: "RAZORPAY", label: "Razorpay — India" }]);
    paymentEvents.mockResolvedValue(EVENT_GROUPS);
    whatsappSettings.mockResolvedValue(WHATSAPP_NONE);
  });

  it("says the keys work and, separately, that nothing has called back yet", async () => {
    paymentSettings.mockResolvedValue(CONFIGURED);
    render(<SettingsRoute />);

    await waitFor(() => expect(screen.getByText(/Your keys reach Razorpay/)).toBeInTheDocument());
    expect(gateway().getByText("Working")).toBeInTheDocument();
    expect(gateway().getByText(/has not called us back yet/)).toBeInTheDocument();
    expect(gateway().getByText("Not yet")).toBeInTheDocument();
    expect(
      screen.getByText(/donations will be taken but never confirmed/)
    ).toBeInTheDocument();
  });

  it("never renders the key secret, only dots and a way to replace it", async () => {
    paymentSettings.mockResolvedValue(CONFIGURED);
    render(<SettingsRoute />);

    await waitFor(() => expect(gateway().getByRole("button", { name: "Replace" })).toBeInTheDocument());
    expect(screen.getByText(/never shown again/)).toBeInTheDocument();
    // No password field in this section until an admin asks to replace the key secret. Scoped: the
    // WhatsApp section below has its own, and an unconnected temple shows them straight away.
    expect(
      screen.getByRole("region", { name: "Payment gateway" })
        .querySelector('input[type="password"]')
    ).toBeNull();
  });

  it("lets the key id be corrected without retyping a secret nobody can see", async () => {
    paymentSettings.mockResolvedValue(CONFIGURED);
    savePaymentSettings.mockResolvedValue({ ...CONFIGURED, keyId: "rzp_test_corrected" });
    render(<SettingsRoute />);

    await waitFor(() => expect(screen.getByDisplayValue("rzp_test_abc123")).toBeInTheDocument());
    fireEvent.change(screen.getByDisplayValue("rzp_test_abc123"), {
      target: { value: "rzp_test_corrected" },
    });
    fireEvent.click(gateway().getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(savePaymentSettings).toHaveBeenCalledWith(
        { provider: "RAZORPAY", keyId: "rzp_test_corrected", keySecret: undefined },
        "token-abc"
      )
    );
  });

  it("shows the webhook address to copy, and hides its secret until asked", async () => {
    paymentSettings.mockResolvedValue(CONFIGURED);
    render(<SettingsRoute />);

    await waitFor(() => expect(screen.getByText(CONFIGURED.webhookUrl)).toBeInTheDocument());
    expect(screen.queryByText("whsec-abc123")).not.toBeInTheDocument();
    expect(screen.getByText(/recorded in the audit log/)).toBeInTheDocument();

    fireEvent.click(gateway().getByRole("button", { name: "Reveal" }));
    await waitFor(() => expect(screen.getByText("whsec-abc123")).toBeInTheDocument());
  });

  it("cannot test a connection for a temple that has not set one up", async () => {
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
    render(<SettingsRoute />);

    await waitFor(() =>
      expect(gateway().getByRole("button", { name: "Test connection" })).toBeDisabled()
    );
    // A greyed-out button with no reason reads as broken. It has to say why, and what to do.
    expect(screen.getByText(/press save first/i)).toBeInTheDocument();
    expect(screen.getByText(/checks your keys with Razorpay/i)).toBeInTheDocument();
    // Nothing to paste into a provider's dashboard until there is a provider.
    expect(screen.queryByText(/Tell Razorpay where to reach us/)).not.toBeInTheDocument();
  });

  it("gives a provider that cannot self-register the steps, and the events from the server", async () => {
    paymentSettings.mockResolvedValue(CONFIGURED);
    render(<SettingsRoute />);

    await waitFor(() => expect(screen.getByText(/Tell Razorpay where to reach us/)).toBeInTheDocument());
    expect(screen.getByText(/only lets an account holder do this/)).toBeInTheDocument();
    expect(screen.getByText(/Open Webhooks in your Razorpay dashboard/)).toBeInTheDocument();
    expect(screen.getByText(/Tick these events/)).toBeInTheDocument();

    // Grouped by what they are for. The essential group is what every temple must have.
    expect(screen.getByText(/Confirming donations/)).toBeInTheDocument();
    expect(screen.getByText("payment.captured")).toBeInTheDocument();

    // The optional group carries its own caveat: Razorpay lists no subscription event until
    // Subscriptions is switched on, so an admin who cannot find these must not think us broken.
    expect(screen.getByText(/Monthly giving/)).toBeInTheDocument();
    expect(screen.getByText(/if you cannot see them, skip this group/)).toBeInTheDocument();
    expect(screen.getByText("subscription.charged")).toBeInTheDocument();
  });

  it("tells a temple whose webhook we registered that there is nothing to do", async () => {
    paymentSettings.mockResolvedValue({
      ...CONFIGURED,
      webhookRegisteredAt: "2026-08-16T09:00:00Z",
    });
    render(<SettingsRoute />);

    await waitFor(() =>
      expect(screen.getByText(/has been told where to reach us/)).toBeInTheDocument()
    );
    expect(screen.getByText(/nothing for you to do/)).toBeInTheDocument();
    // No instructions, and no secret to reveal — there is nothing to paste anywhere.
    expect(screen.queryByText(/Tell Razorpay where to reach us/)).not.toBeInTheDocument();
    expect(gateway().queryByRole("button", { name: "Reveal" })).not.toBeInTheDocument();
  });
});

/**
 * Connecting a temple's own WhatsApp account. The same shape as the gateway above it, and the same
 * two questions that fail independently: whether we can send, and whether Meta tells us what landed.
 */
describe("the WhatsApp connection", () => {
  const CONNECTED: WhatsAppSettingsView = {
    connected: true,
    phoneNumberId: "pn-123",
    wabaId: "waba-456",
    displayNumber: "Temple Kitchen (+91 80 1234 5678)",
    webhookUrl: "https://kms.example/api/v1/public/webhooks/whatsapp/wa-token",
    verifiedAt: "2026-08-16T10:00:00Z",
    webhookSeenAt: null,
    templatesSubmittedAt: "2026-08-16T10:00:05Z",
  };

  const messaging = () => within(screen.getByRole("region", { name: "WhatsApp" }));

  beforeEach(() => {
    vi.clearAllMocks();
    paymentSettings.mockResolvedValue(CONFIGURED);
    paymentProviders.mockResolvedValue([{ value: "RAZORPAY", label: "Razorpay — India" }]);
    paymentEvents.mockResolvedValue(EVENT_GROUPS);
    whatsappSettings.mockResolvedValue(WHATSAPP_NONE);
  });

  it("asks an unconnected temple for the four things only it can supply", async () => {
    render(<SettingsRoute />);
    await waitFor(() =>
      expect(messaging().getByText(/not connected yet/)).toBeInTheDocument()
    );

    expect(messaging().getByLabelText(/Phone number ID/)).toBeInTheDocument();
    expect(messaging().getByLabelText(/WhatsApp Business Account ID/)).toBeInTheDocument();
    expect(messaging().getByLabelText(/Permanent access token/)).toBeInTheDocument();
    expect(messaging().getByLabelText(/App secret/)).toBeInTheDocument();

    // Nothing to paste into Meta until there is an account to paste it for.
    expect(messaging().queryByText(/Tell Meta where to reach us/)).not.toBeInTheDocument();
  });

  it("connects, and never asks the temple to write a message template", async () => {
    whatsappSettings.mockResolvedValue(WHATSAPP_NONE);
    saveWhatsAppSettings.mockResolvedValue(CONNECTED);
    render(<SettingsRoute />);
    await waitFor(() => expect(messaging().getByLabelText(/Phone number ID/)).toBeInTheDocument());

    fireEvent.change(messaging().getByLabelText(/Phone number ID/), { target: { value: "pn-123" } });
    fireEvent.change(messaging().getByLabelText(/WhatsApp Business Account ID/), {
      target: { value: "waba-456" },
    });
    fireEvent.change(messaging().getByLabelText(/Permanent access token/), {
      target: { value: "tok" },
    });
    fireEvent.change(messaging().getByLabelText(/App secret/), { target: { value: "sec" } });
    fireEvent.click(messaging().getByRole("button", { name: "Connect" }));

    await waitFor(() =>
      expect(saveWhatsAppSettings).toHaveBeenCalledWith(
        { phoneNumberId: "pn-123", wabaId: "waba-456", accessToken: "tok", appSecret: "sec" },
        "token-abc"
      )
    );
    // Templates are ours to register, not the temple's to write.
    expect(await messaging().findByText(/message templates/i)).toBeInTheDocument();
  });

  it("shows a connected temple the callback steps, and hides the verify token until asked", async () => {
    whatsappSettings.mockResolvedValue(CONNECTED);
    render(<SettingsRoute />);

    await waitFor(() =>
      expect(messaging().getByText(/Tell Meta where to reach us/)).toBeInTheDocument()
    );
    expect(messaging().getByText(CONNECTED.webhookUrl!)).toBeInTheDocument();
    expect(messaging().getByText(/Subscribe to the messages field/)).toBeInTheDocument();
    expect(screen.queryByText("wa-verify-secret")).not.toBeInTheDocument();

    revealWhatsAppVerifyToken.mockResolvedValue({ verifyToken: "wa-verify-secret" });
    fireEvent.click(messaging().getByRole("button", { name: "Reveal" }));
    await waitFor(() => expect(screen.getByText("wa-verify-secret")).toBeInTheDocument());
  });

  it("says which number was actually connected, so a wrong one is visible", async () => {
    whatsappSettings.mockResolvedValue(CONNECTED);
    render(<SettingsRoute />);

    await waitFor(() =>
      expect(messaging().getByText(/Temple Kitchen \(\+91 80 1234 5678\)/)).toBeInTheDocument()
    );
    // Sending works; the return path has not been proven and must not claim to be.
    expect(messaging().getByText(/Meta has not called us back yet/)).toBeInTheDocument();
  });
});
