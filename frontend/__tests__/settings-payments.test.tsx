import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

const { paymentSettings, paymentProviders, savePaymentSettings, testPaymentSettings, revealWebhookSecret } =
  vi.hoisted(() => ({
    paymentSettings: vi.fn(),
    paymentProviders: vi.fn(async () => [{ value: "RAZORPAY", label: "Razorpay — India" }]),
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
  });

  it("says the keys work and, separately, that nothing has called back yet", async () => {
    paymentSettings.mockResolvedValue(CONFIGURED);
    render(<SettingsRoute />);

    await waitFor(() => expect(screen.getByText(/Your keys reach Razorpay/)).toBeInTheDocument());
    expect(screen.getByText("Working")).toBeInTheDocument();
    expect(screen.getByText(/has not called us back yet/)).toBeInTheDocument();
    expect(screen.getByText("Not yet")).toBeInTheDocument();
    expect(
      screen.getByText(/donations will be taken but never confirmed/)
    ).toBeInTheDocument();
  });

  it("never renders the key secret, only dots and a way to replace it", async () => {
    paymentSettings.mockResolvedValue(CONFIGURED);
    render(<SettingsRoute />);

    await waitFor(() => expect(screen.getByRole("button", { name: "Replace" })).toBeInTheDocument());
    expect(screen.getByText(/never shown again/)).toBeInTheDocument();
    // No password field at all until an admin asks to replace it.
    expect(document.querySelector('input[type="password"]')).toBeNull();
  });

  it("lets the key id be corrected without retyping a secret nobody can see", async () => {
    paymentSettings.mockResolvedValue(CONFIGURED);
    savePaymentSettings.mockResolvedValue({ ...CONFIGURED, keyId: "rzp_test_corrected" });
    render(<SettingsRoute />);

    await waitFor(() => expect(screen.getByDisplayValue("rzp_test_abc123")).toBeInTheDocument());
    fireEvent.change(screen.getByDisplayValue("rzp_test_abc123"), {
      target: { value: "rzp_test_corrected" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

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

    fireEvent.click(screen.getByRole("button", { name: "Reveal" }));
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
    });
    render(<SettingsRoute />);

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Test connection" })).toBeDisabled()
    );
    // A greyed-out button with no reason reads as broken. It has to say why, and what to do.
    expect(screen.getByText(/press save first/i)).toBeInTheDocument();
    expect(screen.getByText(/checks your keys with Razorpay/i)).toBeInTheDocument();
    // Nothing to paste into a provider's dashboard until there is a provider.
    expect(screen.queryByText(/Events to subscribe to/)).not.toBeInTheDocument();
  });
});
