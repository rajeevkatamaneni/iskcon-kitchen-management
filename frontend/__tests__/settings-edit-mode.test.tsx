import { describe, expect, it, vi, beforeEach } from "vitest";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ApiError, type PaymentSettingsView, type WhatsAppSettingsView } from "@/lib/api";

/**
 * The Settings screen opens read-only, and the WhatsApp templates button (T-169b).
 *
 * <p>Rajeev, 2026-09-13: *"The default state of the screen shuld be read only to avoid accidental
 * mistakes. The way to get it to edit is using the 'Edit' button. When clicked the fields become
 * editable and the button reads 'Save'. This applies for all sections on the settinsg scree, For
 * Watts App specifically, we need a new button Reload Wattsapp Templates."* On the button's three
 * shapes: *"I like option 2. I tis clean and honest."* And Appearance is left as it was: *"No, leave
 * the color picker as is."* Appearance's own tests are in `settings-payments.test.tsx`, untouched.
 *
 * <p>Its own file, rather than more of `settings-payments.test.tsx`, because every test here is about
 * the edit mode or the templates button and none is about payments. The mock bag is the same shape.
 */

const {
  paymentSettings,
  paymentProviders,
  paymentEvents,
  whatsappSettings,
  templeContactEmail,
  templeSettings,
  savePaymentSettings,
  testPaymentSettings,
  saveWhatsAppSettings,
  reloadWhatsAppTemplates,
  sendWhatsAppTestMessage,
  saveTempleContactEmail,
  setBroadcastLimit,
  setWarningHorizons,
} = vi.hoisted(() => ({
  paymentSettings: vi.fn(),
  paymentProviders: vi.fn(),
  paymentEvents: vi.fn(),
  whatsappSettings: vi.fn(),
  templeContactEmail: vi.fn(),
  templeSettings: vi.fn(),
  savePaymentSettings: vi.fn(),
  testPaymentSettings: vi.fn(),
  saveWhatsAppSettings: vi.fn(),
  reloadWhatsAppTemplates: vi.fn(),
  sendWhatsAppTestMessage: vi.fn(),
  saveTempleContactEmail: vi.fn(),
  setBroadcastLimit: vi.fn(),
  setWarningHorizons: vi.fn(),
}));

/** One `getToken` for the life of the file, as the real one is (see settings-payments.test.tsx). */
const { getToken } = vi.hoisted(() => ({ getToken: async () => "token-abc" }));

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
      savePaymentSettings,
      testPaymentSettings,
      saveWhatsAppSettings,
      reloadWhatsAppTemplates,
      sendWhatsAppTestMessage,
      saveTempleContactEmail,
      setBroadcastLimit,
      setWarningHorizons,
    },
  };
});

vi.mock("@/components/RequireRole", () => ({
  RequireRole: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

vi.mock("@/components/Sidebar", () => ({ Sidebar: () => <nav aria-label="Main" /> }));

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ getToken, appUser: { role: "TEMPLE_ADMIN" } }),
}));

import SettingsRoute from "@/app/settings/page";

const CONFIGURED: PaymentSettingsView = {
  configured: true,
  provider: "RAZORPAY",
  keyId: "rzp_test_abc123",
  keySecretSavedAt: "2026-08-10T08:08:39Z",
  webhookUrl: "https://kms.example/api/v1/public/webhooks/payments/7f3c9a12",
  verifiedAt: "2026-08-15T11:22:00Z",
  webhookSeenAt: null,
  webhookRegisteredAt: "2026-08-16T09:00:00Z",
};

const UNCONFIGURED: PaymentSettingsView = {
  configured: false,
  provider: null,
  keyId: null,
  keySecretSavedAt: null,
  webhookUrl: null,
  verifiedAt: null,
  webhookSeenAt: null,
  webhookRegisteredAt: null,
};

const NOTHING_WAITING = { changed: 0, refused: 0, accountChanged: false, unchecked: 0 };

const CONNECTED: WhatsAppSettingsView = {
  connected: true,
  phoneNumberId: "pn-123",
  wabaId: "waba-456",
  appId: "1234567890123456",
  displayNumber: "Temple Kitchen (+91 80 1234 5678)",
  webhookUrl: "https://kms.example/api/v1/public/webhooks/whatsapp/wa-token",
  verifiedAt: "2026-08-16T10:00:00Z",
  webhookSeenAt: null,
  templatesSubmittedAt: "2026-08-16T10:00:05Z",
  refusedTemplates: [],
  templatesPending: NOTHING_WAITING,
};

const NOT_CONNECTED: WhatsAppSettingsView = {
  connected: false,
  phoneNumberId: null,
  wabaId: null,
  appId: null,
  displayNumber: null,
  webhookUrl: null,
  verifiedAt: null,
  webhookSeenAt: null,
  templatesSubmittedAt: null,
  refusedTemplates: [],
  templatesPending: NOTHING_WAITING,
};

const TEMPLE_SETTINGS = {
  volunteerBroadcastDailyLimit: 3,
  locale: "en-IN",
  themeId: null as string | null,
  stockExpiryWarningDays: 7,
  contractEndWarningDays: 30,
  equipmentServiceWarningDays: 30,
};

/** The five sections under the rule. Appearance is the exception, and Language is its own component. */
const SECTIONS = ["Payment gateway", "WhatsApp", "Email", "Volunteer messages", "Warnings"];

type Control = HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;

const region = (name: string) => screen.getByRole("region", { name });
const regionLoaded = (name: string) => screen.findByRole("region", { name });

function controlsIn(section: HTMLElement): Control[] {
  return Array.from(section.querySelectorAll<Control>("input, select, textarea"));
}

/** A control a person could type into or change. A `<select>` has no read-only state, only disabled. */
function editableIn(section: HTMLElement): Control[] {
  return controlsIn(section).filter((c) => !c.disabled && !("readOnly" in c && c.readOnly));
}

/** Lets a handler that awaits a token reach its API call, so "not called" is not merely "not yet". */
function settle() {
  return act(() => new Promise((resolve) => setTimeout(resolve, 0)));
}

/**
 * The sentence Form puts beside a refused box, checked three ways so "beside" means something: the
 * box is marked invalid, it is described by that very sentence, and the sentence's slot sits straight
 * after the box. The same helper as tenant-edit.test.tsx.
 */
function expectSaidBeside(box: HTMLElement, sentence: string) {
  const said = screen.getByText(sentence);
  expect(box).toHaveAttribute("aria-invalid", "true");
  expect(box.getAttribute("aria-describedby")?.split(" ")).toContain(said.id);
  expect((box.closest("label") ?? box).nextElementSibling).toBe(said.parentElement);
}

beforeEach(() => {
  vi.resetAllMocks();
  paymentSettings.mockResolvedValue(CONFIGURED);
  paymentProviders.mockResolvedValue([{ value: "RAZORPAY", label: "Razorpay — India" }]);
  paymentEvents.mockResolvedValue([]);
  whatsappSettings.mockResolvedValue(CONNECTED);
  templeContactEmail.mockResolvedValue({ contactEmail: "kitchen@temple.org" });
  templeSettings.mockResolvedValue({ ...TEMPLE_SETTINGS });
  savePaymentSettings.mockResolvedValue({ ...CONFIGURED, keyId: "rzp_test_new" });
  saveWhatsAppSettings.mockResolvedValue({ ...CONNECTED, wabaId: "waba-789" });
  saveTempleContactEmail.mockResolvedValue({ contactEmail: "office@temple.org" });
  setBroadcastLimit.mockResolvedValue(undefined);
  setWarningHorizons.mockResolvedValue(undefined);
});

describe("every section but Appearance opens read-only", () => {
  it.each(SECTIONS)("%s opens with nothing to type into, an Edit button, and no Save", async (name) => {
    render(<SettingsRoute />);
    const section = await regionLoaded(name);

    // Not vacuous: the section does show its boxes, and every one of them is read-only.
    expect(controlsIn(section).length).toBeGreaterThan(0);
    expect(editableIn(section)).toEqual([]);
    expect(within(section).getByRole("button", { name: "Edit" })).toBeEnabled();
    expect(within(section).queryByRole("button", { name: /^(Save|Connect|Cancel)$/ })).not.toBeInTheDocument();
    // Replacing a stored secret is an edit too.
    expect(within(section).queryByRole("button", { name: "Replace" })).not.toBeInTheDocument();
  });

  it("opens read-only before anything is connected too, with the boxes it will ask for", async () => {
    paymentSettings.mockResolvedValue(UNCONFIGURED);
    whatsappSettings.mockResolvedValue(NOT_CONNECTED);
    render(<SettingsRoute />);

    for (const name of ["Payment gateway", "WhatsApp"]) {
      const section = await regionLoaded(name);
      expect(section.querySelectorAll('input[type="password"]').length).toBeGreaterThan(0);
      expect(editableIn(section)).toEqual([]);
      expect(within(section).getByRole("button", { name: "Edit" })).toBeInTheDocument();
    }
  });

  it.each(SECTIONS)("Edit opens %s alone, with Cancel and Save beside each other", async (name) => {
    render(<SettingsRoute />);
    const section = await regionLoaded(name);

    fireEvent.click(within(section).getByRole("button", { name: "Edit" }));

    expect(editableIn(section).length).toBeGreaterThan(0);
    const cancel = within(section).getByRole("button", { name: "Cancel" });
    const save = within(section).getByRole("button", { name: "Save" });
    // Secondary first, then the primary (§4).
    expect(cancel.compareDocumentPosition(save) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(within(section).queryByRole("button", { name: "Edit" })).not.toBeInTheDocument();

    for (const other of SECTIONS.filter((n) => n !== name)) {
      expect(editableIn(region(other))).toEqual([]);
      expect(within(region(other)).getByRole("button", { name: "Edit" })).toBeInTheDocument();
    }
  });

  it("lets two sections be open at once, and each Save sends only its own", async () => {
    render(<SettingsRoute />);
    const email = await regionLoaded("Email");
    const warnings = region("Warnings");

    fireEvent.click(within(email).getByRole("button", { name: "Edit" }));
    fireEvent.change(within(email).getByLabelText("Your temple’s email address", { selector: "input" }), {
      target: { value: "office@temple.org" },
    });
    fireEvent.click(within(warnings).getByRole("button", { name: "Edit" }));
    fireEvent.change(within(warnings).getByLabelText("Notice before a vendor contract ends"), {
      target: { value: "45" },
    });
    fireEvent.click(within(warnings).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(setWarningHorizons).toHaveBeenCalledTimes(1));
    await settle();
    expect(saveTempleContactEmail).not.toHaveBeenCalled();
    // Email is still open, still holding what was typed.
    expect(within(email).getByLabelText("Your temple’s email address", { selector: "input" })).toHaveValue(
      "office@temple.org"
    );
    expect(editableIn(email).length).toBeGreaterThan(0);
  });
});

/** One row per section: what to type, which call Save makes, and with what. */
const EDITS: {
  name: string;
  box: (section: HTMLElement) => HTMLElement;
  typed: string;
  before: string | number;
  call: () => ReturnType<typeof vi.fn>;
  sent: unknown[];
}[] = [
  {
    name: "Payment gateway",
    box: (s) => within(s).getByLabelText("Key ID", { selector: "input" }),
    typed: "rzp_test_new",
    before: "rzp_test_abc123",
    call: () => savePaymentSettings,
    sent: [{ provider: "RAZORPAY", keyId: "rzp_test_new", keySecret: undefined }, "token-abc"],
  },
  {
    name: "WhatsApp",
    box: (s) => within(s).getByLabelText("WhatsApp Business Account ID", { selector: "input" }),
    typed: "waba-789",
    before: "waba-456",
    call: () => saveWhatsAppSettings,
    sent: [
      { phoneNumberId: "pn-123", wabaId: "waba-789", appId: "1234567890123456", accessToken: undefined, appSecret: undefined },
      "token-abc",
    ],
  },
  {
    name: "Email",
    box: (s) => within(s).getByLabelText("Your temple’s email address", { selector: "input" }),
    typed: "office@temple.org",
    before: "kitchen@temple.org",
    call: () => saveTempleContactEmail,
    sent: ["office@temple.org", "token-abc"],
  },
  {
    name: "Volunteer messages",
    box: (s) => within(s).getByRole("spinbutton"),
    typed: "6",
    before: 3,
    call: () => setBroadcastLimit,
    sent: [6, "token-abc"],
  },
  {
    name: "Warnings",
    box: (s) => within(s).getByLabelText("Notice before a vendor contract ends"),
    typed: "45",
    before: 30,
    call: () => setWarningHorizons,
    sent: [
      { stockExpiryWarningDays: 7, contractEndWarningDays: 45, equipmentServiceWarningDays: 30 },
      "token-abc",
    ],
  },
];

describe("Save and Cancel", () => {
  it.each(EDITS)("$name: Save sends its call once, and the section is read-only again", async (row) => {
    render(<SettingsRoute />);
    const section = await regionLoaded(row.name);

    fireEvent.click(within(section).getByRole("button", { name: "Edit" }));
    fireEvent.change(row.box(section), { target: { value: row.typed } });
    fireEvent.click(within(section).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(row.call()).toHaveBeenCalledTimes(1));
    expect(row.call()).toHaveBeenCalledWith(...row.sent);
    expect(await within(section).findByRole("button", { name: "Edit" })).toBeInTheDocument();
    expect(editableIn(section)).toEqual([]);
    await settle();
    expect(row.call()).toHaveBeenCalledTimes(1);
  });

  it.each(EDITS)("$name: Cancel puts back what it showed before Edit, and sends nothing", async (row) => {
    render(<SettingsRoute />);
    const section = await regionLoaded(row.name);

    fireEvent.click(within(section).getByRole("button", { name: "Edit" }));
    fireEvent.change(row.box(section), { target: { value: row.typed } });
    expect(row.box(section)).toHaveValue(row.typed === "6" || row.typed === "45" ? Number(row.typed) : row.typed);
    fireEvent.click(within(section).getByRole("button", { name: "Cancel" }));

    expect(row.box(section)).toHaveValue(row.before);
    expect(editableIn(section)).toEqual([]);
    // And it is the value held, not only the value drawn: opening the section again starts from it.
    fireEvent.click(within(section).getByRole("button", { name: "Edit" }));
    expect(row.box(section)).toHaveValue(row.before);
    await settle();
    expect(row.call()).not.toHaveBeenCalled();
  });

  it("Cancel takes back a half-typed replacement of a stored secret, on both sections that hold one", async () => {
    render(<SettingsRoute />);

    for (const name of ["Payment gateway", "WhatsApp"]) {
      const section = await regionLoaded(name);
      fireEvent.click(within(section).getByRole("button", { name: "Edit" }));
      fireEvent.click(within(section).getByRole("button", { name: "Replace" }));
      const typed = section.querySelectorAll<HTMLInputElement>('input[type="password"]');
      expect(typed.length).toBeGreaterThan(0);
      typed.forEach((box) => fireEvent.change(box, { target: { value: "half-typed" } }));

      fireEvent.click(within(section).getByRole("button", { name: "Cancel" }));
      expect(section.querySelector('input[type="password"]')).toBeNull();
      // "…never shown again." on the gateway, "Neither is ever shown again." on WhatsApp.
      expect(within(section).getByText(/(never|Neither is ever) shown again/)).toBeInTheDocument();
    }
    await settle();
    expect(savePaymentSettings).not.toHaveBeenCalled();
    expect(saveWhatsAppSettings).not.toHaveBeenCalled();
  });

  it("a connected temple's Save only saves: it never sends the templates", async () => {
    render(<SettingsRoute />);
    const section = await regionLoaded("WhatsApp");

    fireEvent.click(within(section).getByRole("button", { name: "Edit" }));
    // Said where Save is about to be pressed.
    expect(within(section).getByText("Saving does not send templates to Meta.")).toBeInTheDocument();
    fireEvent.click(within(section).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(saveWhatsAppSettings).toHaveBeenCalledTimes(1));
    await settle();
    expect(reloadWhatsAppTemplates).not.toHaveBeenCalled();
  });
});

/**
 * The Meta App ID box (T-200). The purchase-order message is registered with a sample PDF, and Meta issues
 * that sample's handle to an app, so each temple enters its App ID. It is not a secret, so it is an ordinary
 * box, shown in full and read-only until Edit, like the two ids beside it.
 */
describe("the WhatsApp App ID box", () => {
  const appIdBox = (section: HTMLElement) => within(section).getByLabelText("App ID", { selector: "input" });

  it("shows the stored App ID in full, read-only, until Edit", async () => {
    render(<SettingsRoute />);
    const section = await regionLoaded("WhatsApp");

    const box = appIdBox(section);
    expect(box).toHaveValue("1234567890123456");
    expect(box).toHaveAttribute("readonly");
    expect(box).not.toHaveAttribute("type", "password");

    fireEvent.click(within(section).getByRole("button", { name: "Edit" }));
    expect(appIdBox(section)).not.toHaveAttribute("readonly");
  });

  it("Cancel puts the stored App ID back and sends nothing", async () => {
    render(<SettingsRoute />);
    const section = await regionLoaded("WhatsApp");

    fireEvent.click(within(section).getByRole("button", { name: "Edit" }));
    fireEvent.change(appIdBox(section), { target: { value: "999" } });
    expect(appIdBox(section)).toHaveValue("999");
    fireEvent.click(within(section).getByRole("button", { name: "Cancel" }));

    expect(appIdBox(section)).toHaveValue("1234567890123456");
    expect(appIdBox(section)).toHaveAttribute("readonly");
    await settle();
    expect(saveWhatsAppSettings).not.toHaveBeenCalled();
  });

  it("Save sends the App ID typed, and the saved answer is what the box shows, now and after a reload", async () => {
    const stored = { ...CONNECTED, appId: "7654321098765432" };
    saveWhatsAppSettings.mockResolvedValue(stored);
    const { unmount } = render(<SettingsRoute />);
    const section = await regionLoaded("WhatsApp");

    fireEvent.click(within(section).getByRole("button", { name: "Edit" }));
    fireEvent.change(appIdBox(section), { target: { value: " 7654321098765432 " } });
    fireEvent.click(within(section).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(saveWhatsAppSettings).toHaveBeenCalledTimes(1));
    expect(saveWhatsAppSettings).toHaveBeenCalledWith(
      { phoneNumberId: "pn-123", wabaId: "waba-456", appId: "7654321098765432", accessToken: undefined, appSecret: undefined },
      "token-abc"
    );
    expect(await within(section).findByRole("button", { name: "Edit" })).toBeInTheDocument();
    expect(appIdBox(section)).toHaveValue("7654321098765432");
    expect(appIdBox(section)).toHaveAttribute("readonly");

    // A reload reads the settings again, and the server now answers with what was stored.
    unmount();
    whatsappSettings.mockResolvedValue(stored);
    render(<SettingsRoute />);
    expect(appIdBox(await regionLoaded("WhatsApp"))).toHaveValue("7654321098765432");
  });

  it("is empty, and not required, on a temple that has none yet", async () => {
    whatsappSettings.mockResolvedValue({ ...CONNECTED, appId: null });
    render(<SettingsRoute />);
    const section = await regionLoaded("WhatsApp");

    expect(appIdBox(section)).toHaveValue("");
    fireEvent.click(within(section).getByRole("button", { name: "Edit" }));
    expect(appIdBox(section)).not.toBeRequired();
    fireEvent.click(within(section).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(saveWhatsAppSettings).toHaveBeenCalledTimes(1));
    expect(saveWhatsAppSettings.mock.calls[0][0]).toMatchObject({ appId: "" });
  });
});

describe("a box that is wrong is named in red beside it, and nothing is sent", () => {
  it("Payment gateway: a cleared Key ID, then a blank replacement secret", async () => {
    render(<SettingsRoute />);
    const section = await regionLoaded("Payment gateway");
    fireEvent.click(within(section).getByRole("button", { name: "Edit" }));

    const keyId = within(section).getByLabelText("Key ID", { selector: "input" });
    fireEvent.change(keyId, { target: { value: "" } });
    const save = within(section).getByRole("button", { name: "Save" });
    // Not greyed out with no reason: pressable, and pressing it says why.
    expect(save).toBeEnabled();
    fireEvent.click(save);
    expectSaidBeside(keyId, "Key ID is required");
    await waitFor(() => expect(keyId).toHaveFocus());

    fireEvent.change(keyId, { target: { value: "rzp_test_abc123" } });
    fireEvent.click(within(section).getByRole("button", { name: "Replace" }));
    const secret = within(section).getByLabelText("Key secret");
    expect(secret).toBeRequired();
    fireEvent.click(within(section).getByRole("button", { name: "Save" }));
    expectSaidBeside(secret, "Key secret is required");

    await settle();
    expect(savePaymentSettings).not.toHaveBeenCalled();
  });

  it("WhatsApp: replacing the credentials needs both the token and the app secret", async () => {
    render(<SettingsRoute />);
    const section = await regionLoaded("WhatsApp");
    fireEvent.click(within(section).getByRole("button", { name: "Edit" }));
    fireEvent.click(within(section).getByRole("button", { name: "Replace" }));

    const token = within(section).getByLabelText("Permanent access token", { selector: "input" });
    const secret = within(section).getByLabelText("App secret", { selector: "input" });
    expect(token).toBeRequired();
    expect(secret).toBeRequired();
    // Only one of the two typed is still refused, by name.
    fireEvent.change(token, { target: { value: "tok" } });
    fireEvent.click(within(section).getByRole("button", { name: "Save" }));

    expectSaidBeside(secret, "App secret is required");
    expect(screen.queryByText("Permanent access token is required")).not.toBeInTheDocument();
    await settle();
    expect(saveWhatsAppSettings).not.toHaveBeenCalled();
  });

  it("WhatsApp: connecting with nothing typed names all four boxes", async () => {
    whatsappSettings.mockResolvedValue(NOT_CONNECTED);
    render(<SettingsRoute />);
    const section = await regionLoaded("WhatsApp");
    fireEvent.click(within(section).getByRole("button", { name: "Edit" }));
    expect(within(section).getByText("Connecting also sends your message templates to Meta.")).toBeInTheDocument();

    fireEvent.click(within(section).getByRole("button", { name: "Connect" }));

    for (const label of ["Phone number ID", "WhatsApp Business Account ID", "Permanent access token", "App secret"]) {
      expectSaidBeside(within(section).getByLabelText(label, { selector: "input" }), `${label} is required`);
    }
    await settle();
    expect(saveWhatsAppSettings).not.toHaveBeenCalled();
  });

  it("Email: the box may be left empty, but not hold something that is not an address", async () => {
    render(<SettingsRoute />);
    const section = await regionLoaded("Email");
    fireEvent.click(within(section).getByRole("button", { name: "Edit" }));

    const box = within(section).getByLabelText("Your temple’s email address", { selector: "input" });
    expect(box).not.toBeRequired();
    fireEvent.change(box, { target: { value: "not an address" } });
    fireEvent.click(within(section).getByRole("button", { name: "Save" }));

    expectSaidBeside(box, "Enter an email address like name@example.com");
    await settle();
    expect(saveTempleContactEmail).not.toHaveBeenCalled();
  });

  it("Volunteer messages: a cleared cap", async () => {
    render(<SettingsRoute />);
    const section = await regionLoaded("Volunteer messages");
    fireEvent.click(within(section).getByRole("button", { name: "Edit" }));

    const box = within(section).getByRole("spinbutton");
    fireEvent.change(box, { target: { value: "" } });
    fireEvent.click(within(section).getByRole("button", { name: "Save" }));

    expectSaidBeside(box, "Update messages per shift, per day is required");
    await settle();
    expect(setBroadcastLimit).not.toHaveBeenCalled();
  });

  it("Warnings: a cleared horizon, and Cancel takes the sentence away with the typing", async () => {
    render(<SettingsRoute />);
    const section = await regionLoaded("Warnings");
    fireEvent.click(within(section).getByRole("button", { name: "Edit" }));

    const box = within(section).getByLabelText("Notice before stock expires");
    fireEvent.change(box, { target: { value: "" } });
    fireEvent.click(within(section).getByRole("button", { name: "Save" }));
    expectSaidBeside(box, "Notice before stock expires is required");

    fireEvent.click(within(section).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByText("Notice before stock expires is required")).not.toBeInTheDocument();
    expect(within(section).getByLabelText("Notice before stock expires")).toHaveValue(7);
    await settle();
    expect(setWarningHorizons).not.toHaveBeenCalled();
  });
});

describe("the WhatsApp templates button (option 2)", () => {
  const templatesButton = (section: HTMLElement) =>
    within(section).getByRole("button", { name: /^(Templates|\d+ templates?)/ });

  it("is a quiet button giving the date they went, when nothing is waiting", async () => {
    render(<SettingsRoute />);
    const section = await regionLoaded("WhatsApp");

    const button = within(section).getByRole("button", { name: "Templates last sent to Meta on 16 Aug 2026" });
    expect(button).toHaveClass("btn-quiet");
    expect(button).not.toHaveClass("btn-primary");
    expect(button).toBeEnabled();
  });

  it.each([
    [{ changed: 3, refused: 0, accountChanged: false, unchecked: 0 }, "3 templates changed since they were last sent"],
    [{ changed: 1, refused: 0, accountChanged: false, unchecked: 0 }, "1 template changed since it was last sent"],
    [{ changed: 0, refused: 2, accountChanged: false, unchecked: 0 }, "2 templates Meta did not accept last time"],
    [{ changed: 0, refused: 1, accountChanged: false, unchecked: 0 }, "1 template Meta did not accept last time"],
    [{ changed: 2, refused: 1, accountChanged: false, unchecked: 0 }, "3 templates waiting to go to Meta"],
    [{ changed: 0, refused: 0, accountChanged: true, unchecked: 0 }, "Templates not yet sent to your new WhatsApp account"],
    [{ changed: 4, refused: 2, accountChanged: true, unchecked: 0 }, "Templates not yet sent to your new WhatsApp account"],
    // T-188: wording nobody has recorded at Meta is waiting too. It outranks the counts, and a changed
    // account outranks it.
    [{ changed: 0, refused: 0, accountChanged: false, unchecked: 19 }, "Current template wording waiting to go to Meta"],
    [{ changed: 1, refused: 2, accountChanged: false, unchecked: 16 }, "Current template wording waiting to go to Meta"],
    [{ changed: 0, refused: 0, accountChanged: true, unchecked: 19 }, "Templates not yet sent to your new WhatsApp account"],
  ])("becomes the primary button saying what is waiting: %o", async (pending, words) => {
    whatsappSettings.mockResolvedValue({ ...CONNECTED, templatesPending: pending });
    render(<SettingsRoute />);
    const section = await regionLoaded("WhatsApp");

    const button = within(section).getByRole("button", { name: words });
    expect(button).toHaveClass("btn-primary");
    expect(button).not.toHaveClass("btn-quiet");
    // Twelve words or fewer (§9).
    expect(words.split(" ").length).toBeLessThanOrEqual(12);
  });

  it("says the current wording is waiting, as the primary button, when nothing records what Meta holds (T-188)", async () => {
    // South Bengaluru on staging, 2026-09-13: it sent before the app kept a record, so all nineteen are
    // unknown, and the button used to read "Templates last sent to Meta on 16 Aug 2026" here.
    whatsappSettings.mockResolvedValue({
      ...CONNECTED,
      templatesPending: { changed: 0, refused: 0, accountChanged: false, unchecked: 19 },
    });
    render(<SettingsRoute />);
    const section = await regionLoaded("WhatsApp");

    const button = within(section).getByRole("button", { name: "Current template wording waiting to go to Meta" });
    expect(button).toHaveClass("btn-primary");
    expect(button).not.toHaveClass("btn-quiet");
    expect(within(section).queryByRole("button", { name: /^Templates last sent/ })).not.toBeInTheDocument();
  });

  it("says not yet sent, rather than waiting wording, when unknown templates were never sent at all", async () => {
    whatsappSettings.mockResolvedValue({
      ...CONNECTED,
      templatesSubmittedAt: null,
      templatesPending: { changed: 0, refused: 0, accountChanged: false, unchecked: 19 },
    });
    render(<SettingsRoute />);
    const section = await regionLoaded("WhatsApp");

    expect(within(section).getByRole("button", { name: "Templates not yet sent to Meta" })).toHaveClass("btn-primary");
  });

  it("is not shown before WhatsApp is connected, since sending would be refused", async () => {
    whatsappSettings.mockResolvedValue(NOT_CONNECTED);
    render(<SettingsRoute />);
    const section = await regionLoaded("WhatsApp");

    expect(within(section).queryByRole("button", { name: /templates/i })).not.toBeInTheDocument();
    expect(within(section).queryByText("Message templates")).not.toBeInTheDocument();
    // The section itself is there, so the absence above is not the section missing.
    expect(within(section).getByRole("button", { name: "Edit" })).toBeInTheDocument();
  });

  it("steps aside while the section is being edited, and comes back on Cancel", async () => {
    render(<SettingsRoute />);
    const section = await regionLoaded("WhatsApp");
    expect(templatesButton(section)).toBeInTheDocument();

    fireEvent.click(within(section).getByRole("button", { name: "Edit" }));
    expect(within(section).queryByRole("button", { name: /^Templates last sent/ })).not.toBeInTheDocument();

    fireEvent.click(within(section).getByRole("button", { name: "Cancel" }));
    expect(templatesButton(section)).toBeInTheDocument();
  });

  it("sends once however often it is pressed, says it is working, then shows Meta's answer", async () => {
    whatsappSettings.mockResolvedValue({ ...CONNECTED, templatesPending: { changed: 2, refused: 0, accountChanged: false, unchecked: 0 } });
    let answer!: (view: WhatsAppSettingsView) => void;
    reloadWhatsAppTemplates.mockImplementation(
      () => new Promise<WhatsAppSettingsView>((resolve) => (answer = resolve))
    );
    render(<SettingsRoute />);
    const section = await regionLoaded("WhatsApp");

    const button = within(section).getByRole("button", { name: "2 templates changed since they were last sent" });
    fireEvent.click(button);
    fireEvent.click(button);

    const working = await within(section).findByRole("button", { name: "Sending templates to Meta…" });
    expect(working).toBeDisabled();
    expect(working).toHaveAttribute("aria-busy", "true");
    expect(within(section).getByText("This can take a minute or two.")).toBeInTheDocument();
    // Nothing else in the section can start while it works.
    expect(within(section).getByRole("button", { name: "Edit" })).toBeDisabled();
    expect(within(section).getByRole("button", { name: "Send a test message" })).toBeDisabled();
    fireEvent.click(working);
    await settle();
    expect(reloadWhatsAppTemplates).toHaveBeenCalledTimes(1);
    expect(reloadWhatsAppTemplates).toHaveBeenCalledWith("token-abc");

    await act(async () =>
      answer({ ...CONNECTED, templatesSubmittedAt: "2026-10-02T06:00:00Z", templatesPending: NOTHING_WAITING })
    );

    const after = await within(section).findByRole("button", { name: "Templates last sent to Meta on 2 Oct 2026" });
    expect(after).toHaveClass("btn-quiet");
    expect(after).toBeEnabled();
    expect(within(section).getByText("Templates sent to Meta.")).toBeInTheDocument();
    expect(within(section).queryByText("This can take a minute or two.")).not.toBeInTheDocument();
    expect(reloadWhatsAppTemplates).toHaveBeenCalledTimes(1);
  });

  it("says so when some templates still need attention after it has run", async () => {
    whatsappSettings.mockResolvedValue({ ...CONNECTED, templatesPending: { changed: 1, refused: 0, accountChanged: false, unchecked: 0 } });
    reloadWhatsAppTemplates.mockResolvedValue({
      ...CONNECTED,
      refusedTemplates: [
        {
          name: "shift_reminder",
          reason: "Meta allows a message to be reworded only once a day and ten times a month. Use the templates button in the WhatsApp section of Settings tomorrow.",
          kind: "REFUSED",
        },
      ],
      templatesPending: { changed: 0, refused: 1, accountChanged: false, unchecked: 0 },
    });
    render(<SettingsRoute />);
    const section = await regionLoaded("WhatsApp");

    fireEvent.click(within(section).getByRole("button", { name: "1 template changed since it was last sent" }));

    expect(await within(section).findByText("Sent to Meta. Some templates still need attention.")).toBeInTheDocument();
    expect(within(section).getByRole("button", { name: "1 template Meta did not accept last time" })).toHaveClass(
      "btn-primary"
    );
    expect(within(section).getByText(/only once a day and ten times a month/)).toBeInTheDocument();
  });

  it("shows a refusal to send in plain words, and leaves the button as it was", async () => {
    reloadWhatsAppTemplates.mockRejectedValue(
      new ApiError({
        code: "KMS-400001",
        message: "Some of the details are missing.",
        action: "Connect WhatsApp first.",
        fieldErrors: [],
      })
    );
    render(<SettingsRoute />);
    const section = await regionLoaded("WhatsApp");

    fireEvent.click(within(section).getByRole("button", { name: "Templates last sent to Meta on 16 Aug 2026" }));

    expect(await within(section).findByRole("alert")).toHaveTextContent("Some of the details are missing.");
    expect(within(section).getByRole("button", { name: "Templates last sent to Meta on 16 Aug 2026" })).toBeEnabled();
    expect(within(section).queryByText("Templates sent to Meta.")).not.toBeInTheDocument();
  });
});

describe("what Meta said about each template", () => {
  const HELD = {
    name: "donation_thank_you",
    reason: "Meta holds this message as marketing, which some countries do not deliver.",
    kind: "HELD_UNDER_ANOTHER_CATEGORY" as const,
  };
  const REFUSED = {
    name: "shift_reminder",
    reason: "Meta refused this message because it starts with a placeholder.",
    kind: "REFUSED" as const,
  };
  const NOT_REACHED = {
    name: "volunteer_welcome",
    reason: "Meta did not say which wording it holds for this message. Try again with the templates button in the WhatsApp section of Settings.",
    kind: "NOT_REACHED" as const,
  };

  const list = (section: HTMLElement) =>
    within(within(section).getByRole("list", { name: "What Meta said about each template" }));

  it("gives a refusal and a template Meta never answered for their stored reasons, first", async () => {
    whatsappSettings.mockResolvedValue({
      ...CONNECTED,
      refusedTemplates: [HELD, REFUSED, NOT_REACHED],
      templatesPending: { changed: 0, refused: 2, accountChanged: false, unchecked: 0 },
    });
    render(<SettingsRoute />);
    const section = await regionLoaded("WhatsApp");

    const rows = list(section).getAllByRole("listitem");
    expect(rows.map((r) => r.textContent)).toEqual([
      `Refused${REFUSED.name}${REFUSED.reason}`,
      `Not reached${NOT_REACHED.name}${NOT_REACHED.reason}`,
      `Note${HELD.name}${HELD.reason} Sending again cannot change this.`,
    ]);
    expect(within(section).getByRole("button", { name: "2 templates Meta did not accept last time" })).toHaveClass(
      "btn-primary"
    );
  });

  it("treats a template Meta holds under another category as a note, not as something waiting", async () => {
    whatsappSettings.mockResolvedValue({
      ...CONNECTED,
      refusedTemplates: [HELD, { ...HELD, name: "wishlist_gift_split" }],
      templatesPending: NOTHING_WAITING,
    });
    render(<SettingsRoute />);
    const section = await regionLoaded("WhatsApp");

    const rows = list(section).getAllByRole("listitem");
    expect(rows).toHaveLength(2);
    for (const row of rows) {
      expect(row).toHaveTextContent(/^Note/);
      expect(row).toHaveTextContent("Sending again cannot change this.");
      expect(row).not.toHaveTextContent("Refused");
    }
    // Two notes and nothing waiting: the button stays quiet.
    expect(within(section).getByRole("button", { name: "Templates last sent to Meta on 16 Aug 2026" })).toHaveClass(
      "btn-quiet"
    );
  });

  it("lists nothing when Meta took every template", async () => {
    render(<SettingsRoute />);
    const section = await regionLoaded("WhatsApp");

    expect(within(section).getByText("Message templates")).toBeInTheDocument();
    expect(within(section).queryByRole("list", { name: "What Meta said about each template" })).not.toBeInTheDocument();
  });
});
