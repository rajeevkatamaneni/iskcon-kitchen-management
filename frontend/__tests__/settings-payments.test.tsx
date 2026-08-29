import { DEFAULT_PALETTE } from "@/lib/theme";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ApiError, type WhatsAppSettingsView } from "@/lib/api";

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

// Two packs so the picker has something to switch between, and the default among them so the
// "in use" marker has something to mark. The palettes are the real ones — a fixture with eight
// made-up colours would not exercise the completeness check the preview relies on.
const THEME_PACKS = [
  {
    id: "11111111-1111-1111-1111-111111111111",
    slug: "temple-terracotta",
    name: "Temple terracotta",
    family: "MUTED" as const,
    description: "Softened terracotta on warm grey.",
    palette: DEFAULT_PALETTE,
  },
  {
    id: "22222222-2222-2222-2222-222222222222",
    slug: "harbour-blue",
    name: "Harbour blue",
    family: "BALANCED" as const,
    description: "A deep harbour blue on white.",
    palette: { ...DEFAULT_PALETTE, accent: "#2573B3", "accent-hover": "#1265A5" },
  },
];

const EVENT_GROUPS = [
  { purpose: "Confirming donations", essential: true, events: ["payment.captured", "payment.failed"] },
  { purpose: "Monthly giving", essential: false, events: ["subscription.charged", "subscription.halted"] },
];

const {
  paymentSettings,
  paymentProviders,
  paymentEvents,
  whatsappSettings,
  templeContactEmail,
  templeSettings,
  themePacks,
  setTempleTheme,
  setTempleLanguage,
  saveTempleContactEmail,
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
  templeContactEmail: vi.fn(async (): Promise<{ contactEmail: string | null }> => ({ contactEmail: null })),
  templeSettings: vi.fn(async () => ({
    volunteerBroadcastDailyLimit: 3,
    locale: "en-IN",
    themePackSlug: null as string | null,
  })),
  themePacks: vi.fn(async () => THEME_PACKS),
  setTempleTheme: vi.fn(),
  setTempleLanguage: vi.fn(),
  saveTempleContactEmail: vi.fn(),
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
      templeContactEmail,
      templeSettings,
      themePacks,
      setTempleTheme,
      setTempleLanguage,
      saveTempleContactEmail,
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
    templeContactEmail.mockResolvedValue({ contactEmail: null });
  });

  it("says the keys work and, separately, that nothing has called back yet", async () => {
    paymentSettings.mockResolvedValue(CONFIGURED);
    render(<SettingsRoute />);

    await waitFor(() => expect(screen.getByText(/Your keys reach Razorpay/)).toBeInTheDocument());
    expect(gateway().getByText("Working")).toBeInTheDocument();
    expect(gateway().getByText(/has not called us back yet/)).toBeInTheDocument();
    expect(gateway().getByText("Not yet")).toBeInTheDocument();
    expect(
      screen.getByText(/Donations are taken but never confirmed/)
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
    templeContactEmail.mockResolvedValue({ contactEmail: null });
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
    templeContactEmail.mockResolvedValue({ contactEmail: null });
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

/**
 * Email asks a temple for one thing, and it is not a mail account. Sending is the platform's,
 * because a temple cannot pass SPF and DKIM for a domain it does not own; what a temple sets is
 * where a reply lands.
 */
describe("the email section", () => {
  const email = () => within(screen.getByRole("region", { name: "Email" }));

  beforeEach(() => {
    vi.clearAllMocks();
    paymentSettings.mockResolvedValue(CONFIGURED);
    paymentProviders.mockResolvedValue([{ value: "RAZORPAY", label: "Razorpay — India" }]);
    paymentEvents.mockResolvedValue(EVENT_GROUPS);
    whatsappSettings.mockResolvedValue(WHATSAPP_NONE);
    templeContactEmail.mockResolvedValue({ contactEmail: "kitchen@temple.org" });
    saveTempleContactEmail.mockResolvedValue({ contactEmail: "office@temple.org" });
  });

  it("asks for a reply address and nothing else — no account, no credentials", async () => {
    render(<SettingsRoute />);
    await waitFor(() =>
      expect(email().getByDisplayValue("kitchen@temple.org")).toBeInTheDocument()
    );

    // The whole point: a temple is never asked for a mail account or a password here.
    expect(email().queryByLabelText(/password/i)).not.toBeInTheDocument();
    expect(email().getByText(/There is nothing to set up/)).toBeInTheDocument();
  });

  it("shows the temple exactly what a devotee will see", async () => {
    render(<SettingsRoute />);
    await waitFor(() => expect(email().getByText(/via ISKCON Kitchen/)).toBeInTheDocument());
    expect(email().getByText(/Reply-To: kitchen@temple.org/)).toBeInTheDocument();
  });

  it("saves a changed reply address", async () => {
    render(<SettingsRoute />);
    await waitFor(() =>
      expect(email().getByDisplayValue("kitchen@temple.org")).toBeInTheDocument()
    );

    fireEvent.change(email().getByDisplayValue("kitchen@temple.org"), {
      target: { value: "office@temple.org" },
    });
    fireEvent.click(email().getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(saveTempleContactEmail).toHaveBeenCalledWith("office@temple.org", "token-abc")
    );
  });
});

describe("the temple's language", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    paymentSettings.mockResolvedValue(CONFIGURED);
    paymentProviders.mockResolvedValue([{ value: "RAZORPAY", label: "Razorpay — India" }]);
    paymentEvents.mockResolvedValue(EVENT_GROUPS);
    whatsappSettings.mockResolvedValue(WHATSAPP_NONE);
    templeContactEmail.mockResolvedValue({ contactEmail: null });
    templeSettings.mockResolvedValue({ volunteerBroadcastDailyLimit: 3, locale: "en-IN", themePackSlug: null });
  });

  it("offers the language the kitchen reads, and says what it changes", async () => {
    render(<SettingsRoute />);
    const section = await screen.findByRole("region", { name: /language/i });

    // Worth saying out loud: an admin picking Kannada is choosing what the *printer* produces, not
    // translating the app they are standing in.
    expect(within(section).getByText(/job cards print in it by default/i)).toBeInTheDocument();
    expect(
      within(section).getByText(/changes what is printed, not what this screen is written in/i)
    ).toBeInTheDocument();
  });

  it("saves the bare language code, not the region-qualified tag it is stored as", async () => {
    templeSettings.mockResolvedValue({ volunteerBroadcastDailyLimit: 3, locale: "kn-IN", themePackSlug: null });
    render(<SettingsRoute />);
    const section = await screen.findByRole("region", { name: /language/i });

    // Stored region-qualified so the column keeps its shape; chosen as a language, which is what a
    // person actually picks.
    const select = within(section).getByRole("combobox") as HTMLSelectElement;
    expect(select.value).toBe("kn");

    fireEvent.change(select, { target: { value: "hi" } });
    fireEvent.click(within(section).getByRole("button", { name: /^save$/i }));

    await waitFor(() => expect(setTempleLanguage).toHaveBeenCalledWith("hi", "token-abc"));
  });
});


/**
 * The overflow reported on 2026-08-20: the webhook URL, the Copy button and the paragraphs beside
 * them all spilled out of the panel.
 *
 * <p>It was never a text problem. A flex item defaults to `min-width: auto` and so refuses to
 * shrink below its content's intrinsic width; with `whitespace-nowrap` that width is the entire
 * URL. The row could not shrink, `overflow-x-auto` never engaged, and because the steps are a grid
 * the un-shrinkable item widened the whole track — which is why the prose wrapped at a measure
 * wider than the panel and spilled too.
 *
 * <p>jsdom does no layout, so this cannot measure the spill; it was measured in a real browser
 * (step 940px inside an 816px panel before, 760px after). What this pins is the one class that
 * fixes it, because a class that looks redundant is exactly the kind that gets tidied away.
 */
describe("the panel keeps its contents inside it", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    paymentProviders.mockResolvedValue([{ value: "RAZORPAY", label: "Razorpay — India" }]);
    paymentEvents.mockResolvedValue(EVENT_GROUPS);
    whatsappSettings.mockResolvedValue(WHATSAPP_NONE);
    templeContactEmail.mockResolvedValue({ contactEmail: null });
    templeSettings.mockResolvedValue({ volunteerBroadcastDailyLimit: 3, locale: "en-IN", themePackSlug: null });
  });

  it("lets the webhook address shrink, so it scrolls rather than pushing the panel open", async () => {
    paymentSettings.mockResolvedValue({
      ...CONFIGURED,
      webhookRegisteredAt: null,
      webhookUrl:
        "https://kms-staging-api-bnpkv5hfrq-el.a.run.app/api/v1/public/webhooks/payments/khnWjoPzhsEwRGMZf88aLbtO5Tyg021N",
    });
    const { container } = render(<SettingsRoute />);

    await screen.findByText(/where to reach us/i);

    const code = container.querySelector("code");
    expect(code).not.toBeNull();
    // Without this the other two classes on it are decoration.
    expect(code!.className).toContain("min-w-0");
    expect(code!.className).toContain("overflow-x-auto");

    // And the grid item itself, so no future child can widen the track either.
    const step = container.querySelector("ol li");
    expect(step!.className).toContain("min-w-0");
  });
});

/**
 * The theme picker (2026-08-28).
 *
 * <p>Living in this file rather than one of its own because the settings screen loads six
 * endpoints in one `Promise.all`, and a second copy of that mock bag is a second place to forget
 * to add the seventh. The name of the file has lagged behind its subject; the subject is the
 * settings screen.
 */
describe("appearance", () => {
  // Awaited, unlike the payment helpers above: the settings screen renders a loader until all
  // seven of its requests have answered, so there is no region to scope to before that.
  const appearance = async () =>
    within(await screen.findByRole("region", { name: "Appearance" }));

  beforeEach(() => {
    // jsdom keeps one document across renders, so a test that painted the page would otherwise
    // hand its colours to the next one.
    document.documentElement.removeAttribute("style");
    setTempleTheme.mockReset();
    // And the settings fetcher is shared with every describe above, one of which leaves it
    // resolving to a different temple. Every test here starts from a temple that has not chosen.
    templeSettings.mockResolvedValue({
      volunteerBroadcastDailyLimit: 3,
      locale: "en-IN",
      themePackSlug: null,
    });
  });

  it("offers every pack, grouped by how loud it is", async () => {
    render(<SettingsRoute />);
    const section = await appearance();

    expect(section.getByText("Temple terracotta")).toBeInTheDocument();
    expect(section.getByText("Harbour blue")).toBeInTheDocument();
    expect(section.getByText("Soft and muted")).toBeInTheDocument();
    expect(section.getByText("Colourful and calm")).toBeInTheDocument();
  });

  it("marks the one the temple is already wearing", async () => {
    templeSettings.mockResolvedValue({
      volunteerBroadcastDailyLimit: 3,
      locale: "en-IN",
      themePackSlug: "harbour-blue",
    });
    render(<SettingsRoute />);
    const section = await appearance();

    expect(section.getByRole("radio", { name: /Harbour blue/ })).toBeChecked();
    expect(section.getByText("in use")).toBeInTheDocument();
  });

  it("cannot be saved until something changes", async () => {
    render(<SettingsRoute />);
    const section = await appearance();

    expect(section.getByRole("button", { name: "Save" })).toBeDisabled();

    fireEvent.click(section.getByRole("radio", { name: /Harbour blue/ }));
    expect(section.getByRole("button", { name: "Save" })).toBeEnabled();
  });

  it("repaints the whole application the moment a pack is picked, before anything is saved", async () => {
    // The reason the picker is worth building at all: a decision this visible should be made by
    // looking at the application, not at a thumbnail. Nothing has been sent to the server here.
    render(<SettingsRoute />);
    const section = await appearance();

    fireEvent.click(section.getByRole("radio", { name: /Harbour blue/ }));

    // #2573B3, the harbour blue accent, as the channel triple Tailwind's opacity modifier needs.
    expect(document.documentElement.style.getPropertyValue("--kms-accent")).toBe("37 115 179");
    expect(setTempleTheme).not.toHaveBeenCalled();
  });

  it("keeps the new colours after saving rather than snapping back to the old ones", async () => {
    // The bug this exists for: written as one effect that paints on entry and restores on cleanup,
    // saving changes `saved`, re-runs the effect, and fires a cleanup that closed over the pack the
    // temple used to wear. The admin presses Save and watches their new colours vanish.
    // The server keeps what it was told, so the screen's next read of settings says so. Without
    // this the mock would go on reporting a temple that has never chosen, and the assertion below
    // would be measuring the fixture rather than the component.
    setTempleTheme.mockImplementation(async () => {
      templeSettings.mockResolvedValue({
        volunteerBroadcastDailyLimit: 3,
        locale: "en-IN",
        themePackSlug: "harbour-blue",
      });
    });

    const view = render(<SettingsRoute />);
    const section = await appearance();

    fireEvent.click(section.getByRole("radio", { name: /Harbour blue/ }));
    fireEvent.click(section.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(setTempleTheme).toHaveBeenCalled());
    await section.findByText(/Everyone at your temple sees this/);

    expect(document.documentElement.style.getPropertyValue("--kms-accent")).toBe("37 115 179");

    // And it survives leaving the screen, because by then it is what the temple wears.
    view.unmount();
    expect(document.documentElement.style.getPropertyValue("--kms-accent")).toBe("37 115 179");
  });

  it("puts the old palette back when the screen is left without saving", async () => {
    const view = render(<SettingsRoute />);
    const section = await appearance();

    fireEvent.click(section.getByRole("radio", { name: /Harbour blue/ }));
    expect(document.documentElement.style.getPropertyValue("--kms-accent")).toBe("37 115 179");

    // Without the effect's cleanup, a look around the catalogue would follow the admin to every
    // other screen in the application until they next reloaded.
    view.unmount();
    expect(document.documentElement.style.getPropertyValue("--kms-accent")).not.toBe("37 115 179");
  });

  it("saves the pack by its slug and says who else this reaches", async () => {
    render(<SettingsRoute />);
    const section = await appearance();

    fireEvent.click(section.getByRole("radio", { name: /Harbour blue/ }));
    fireEvent.click(section.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(setTempleTheme).toHaveBeenCalledWith("harbour-blue", "token-abc"));
    expect(await section.findByText(/Everyone at your temple sees this/)).toBeInTheDocument();
  });

  it("says so plainly when the save is refused", async () => {
    setTempleTheme.mockRejectedValue(
      new ApiError({
        code: "KMS-4972",
        message: "That theme is no longer one of the choices.",
        action: "Pick another from the list. Your temple is still on the one it was.",
        fieldErrors: [],
      })
    );
    render(<SettingsRoute />);
    const section = await appearance();

    fireEvent.click(section.getByRole("radio", { name: /Harbour blue/ }));
    fireEvent.click(section.getByRole("button", { name: "Save" }));

    expect(await section.findByText("That theme is no longer one of the choices.")).toBeInTheDocument();
    expect(section.getByText(/Pick another from the list/)).toBeInTheDocument();
  });
});
