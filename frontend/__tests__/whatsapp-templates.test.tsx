import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { toApiError, type TemplateStatusCounts, type WhatsAppTemplateCatalogue } from "@/lib/api";
import { moment, templeDay } from "@/lib/format";

/**
 * The operator's catalogue of WhatsApp templates (T-177), with Meta's status counted across temples on
 * each card (T-178).
 *
 * <p>The query hook is replaced, as the Operations test does, so each case states exactly what the
 * API answered. The dates on screen are computed here with the same formatters the page uses, so a
 * change of date format fails here only if the page stops using them.
 */
const { catalogueFn, countsFn, catalogueRef, countsRef, authRef } = vi.hoisted(() => ({
  catalogueFn: () => {},
  countsFn: () => {},
  catalogueRef: {
    current: { data: null as WhatsAppTemplateCatalogue | null, error: null as unknown, loading: false },
  },
  countsRef: {
    current: { data: null as TemplateStatusCounts[] | null, error: null as unknown, loading: false },
  },
  authRef: {
    current: { status: "signed-in", appUser: { role: "SUPER_ADMIN", fullName: "Test Person" } } as {
      status: string;
      appUser: { role: string; fullName?: string } | null;
    },
  },
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, whatsappTemplateCatalogue: catalogueFn, whatsappTemplateStatusCounts: countsFn },
  };
});
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fetcher: unknown) => {
    if (fetcher === catalogueFn) return catalogueRef.current;
    if (fetcher === countsFn) return countsRef.current;
    throw new Error("the page asked for something other than the catalogue and the counts");
  },
}));

import WhatsAppTemplatesPage from "@/app/whatsapp-templates/page";

const TRACKING_SINCE = "2026-09-13T04:00:00Z";
const REWORDED = "2026-09-14T05:30:00Z";

const CATALOGUE: WhatsAppTemplateCatalogue = {
  trackingSince: TRACKING_SINCE,
  templates: [
    {
      name: "shift_reminder",
      category: "UTILITY",
      language: "en",
      body: "This is a reminder that your {{1}} shift at {{2}} is scheduled for {{3}} at {{4}}. Thank you for your seva.",
      exampleValues: ["Kitchen seva", "ISKCON South Bengaluru", "12 August", "6:00 am"],
      usedBy: ["Nothing in the app sends this today. Volunteer shift reminders use volunteer_shift_reminder."],
      wordingFirstSeenAt: TRACKING_SINCE,
      wordingLastChangedAt: REWORDED,
    },
    {
      name: "temple_announcement",
      category: "MARKETING",
      language: "en",
      body: "A message from {{1}} — {{2}}: {{3}} You can read the whole message at this link: {{4}} (it opens in your browser).",
      exampleValues: ["ISKCON South Bengaluru", "Janmashtami at the temple", "Kitchen seva starts at 4am.", "https://example.org/c/2f6a1c"],
      usedBy: [
        "A letter the temple writes to devotees, when it goes on WhatsApp as a short notice with a link",
        "The WhatsApp preview on a letter before it is sent",
      ],
      wordingFirstSeenAt: TRACKING_SINCE,
      wordingLastChangedAt: null,
    },
    {
      name: "donation_receipt",
      category: "UTILITY",
      language: "en",
      body: "Dear {{1}}, your receipt {{2}} for the donation you made to {{3}} on {{4}} has been issued.",
      exampleValues: ["Radha Devi", "R-2026-0042", "ISKCON South Bengaluru", "12 August"],
      usedBy: ["Sending a donor their 80G receipt from the donation's page"],
      wordingFirstSeenAt: null,
      wordingLastChangedAt: null,
    },
  ],
};

/** Five temples counted for one, one for another, none for the third. */
const COUNTS: TemplateStatusCounts[] = [
  { name: "shift_reminder", templesCounted: 5, approved: 2, pending: 1, refused: 1, marketing: 0, formattingRefusal: false },
  { name: "temple_announcement", templesCounted: 1, approved: 0, pending: 1, refused: 0, marketing: 1, formattingRefusal: true },
  { name: "donation_receipt", templesCounted: 0, approved: 0, pending: 0, refused: 0, marketing: 0, formattingRefusal: false },
];

const NOT_COUNTED = "Temples without WhatsApp, or never checked, are not counted.";

function card(name: string) {
  return within(screen.getByRole("article", { name }));
}

describe("whatsapp templates", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "SUPER_ADMIN", fullName: "Test Person" } };
    catalogueRef.current = { data: CATALOGUE, error: null, loading: false };
    countsRef.current = { data: COUNTS, error: null, loading: false };
  });

  it("shows the operator every template's name, category, body with examples, and what sends it", () => {
    render(<WhatsAppTemplatesPage />);

    expect(screen.getByRole("heading", { level: 1, name: "WhatsApp templates" })).toBeInTheDocument();
    expect(screen.getAllByRole("article")).toHaveLength(CATALOGUE.templates.length);

    for (const template of CATALOGUE.templates) {
      const c = card(template.name);
      expect(c.getByRole("heading", { level: 2, name: template.name })).toBeInTheDocument();
      expect(c.getByText(template.body)).toBeInTheDocument();
      template.exampleValues.forEach((value, index) => {
        expect(c.getByText(`{{${index + 1}}}`)).toBeInTheDocument();
        expect(c.getByText(value)).toBeInTheDocument();
      });
      for (const use of template.usedBy) {
        expect(c.getByText(use).tagName).toBe("LI");
      }
      expect(c.getByText("Wording first seen by the app")).toBeInTheDocument();
      expect(c.getByText("Wording last changed")).toBeInTheDocument();
    }

    // The stored category is a word on screen, not the upper-case value.
    expect(card("shift_reminder").getByText("Utility · English")).toBeInTheDocument();
    expect(card("temple_announcement").getByText("Marketing · English")).toBeInTheDocument();
    expect(screen.queryByText(/UTILITY|MARKETING/)).not.toBeInTheDocument();
  });

  it("says when tracking began, in the day-first date format", () => {
    render(<WhatsAppTemplatesPage />);

    expect(
      screen.getByText(`Tracking began on ${templeDay(TRACKING_SINCE)}. Nothing earlier was recorded.`)
    ).toBeInTheDocument();
    expect(templeDay(TRACKING_SINCE)).toMatch(/^\d{1,2} [A-Z][a-z]+ \d{4}$/);
  });

  it("shows both dates, and says a missing one in words rather than leaving it blank", () => {
    render(<WhatsAppTemplatesPage />);

    const changed = card("shift_reminder");
    expect(changed.getByText(moment(TRACKING_SINCE))).toBeInTheDocument();
    expect(changed.getByText(moment(REWORDED))).toBeInTheDocument();

    const unchanged = card("temple_announcement");
    expect(unchanged.getByText(moment(TRACKING_SINCE))).toBeInTheDocument();
    expect(unchanged.getByText("Not changed since tracking began")).toBeInTheDocument();

    const unrecorded = card("donation_receipt");
    expect(unrecorded.getAllByText("Not recorded")).toHaveLength(2);
    expect(unrecorded.queryByText("Not changed since tracking began")).not.toBeInTheDocument();

    // Nothing on the screen claims a wording was created, which would imply somebody authored it then.
    // Checked with the counts on screen too (T-178), since they add words to every card.
    expect(document.body.textContent).not.toMatch(/created/i);
  });

  it("says tracking has not begun when nothing has been recorded", () => {
    catalogueRef.current = {
      data: { ...CATALOGUE, trackingSince: null },
      error: null,
      loading: false,
    };
    render(<WhatsAppTemplatesPage />);

    expect(screen.getByText("Tracking has not begun. No wording has been recorded yet.")).toBeInTheDocument();
    expect(screen.queryByText(/Tracking began on/)).not.toBeInTheDocument();
  });

  it("refuses a temple admin", () => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", fullName: "Radharani Devi" } };
    render(<WhatsAppTemplatesPage />);

    expect(screen.getByRole("heading", { level: 1, name: "Not your page" })).toBeInTheDocument();
    expect(screen.queryByText("Wording last changed")).not.toBeInTheDocument();
    expect(screen.queryByText(/Meta’s status across temples/)).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "WhatsApp templates" })).not.toBeInTheDocument();
  });

  // ---- T-178: Meta's status, counted across temples ----------------------------------------------

  it("shows each card's counts across temples, what is left over, and says who is not counted", () => {
    render(<WhatsAppTemplatesPage />);

    // Two approved, one pending and one refused of five leaves one: said, not dropped.
    expect(
      card("shift_reminder").getByText(
        "Approved in 2 of 5 temples. Pending in 1. Refused in 1. Held as marketing in 0. Not held, unanswered or other in 1."
      )
    ).toBeInTheDocument();
    // One temple, singular, and nothing left over, so no remainder sentence.
    expect(
      card("temple_announcement").getByText("Approved in 0 of 1 temple. Pending in 1. Refused in 0. Held as marketing in 1.")
    ).toBeInTheDocument();
    // Counted in no temple at all.
    expect(card("donation_receipt").getByText("No temple has a stored copy of Meta’s status yet.")).toBeInTheDocument();

    for (const template of CATALOGUE.templates) {
      const c = card(template.name);
      expect(c.getByRole("heading", { level: 3, name: "Meta’s status across temples" })).toBeInTheDocument();
      expect(c.getByText(NOT_COUNTED)).toBeInTheDocument();
    }
  });

  it("flags a formatting refusal on its own card only, as something every temple should know", () => {
    render(<WhatsAppTemplatesPage />);

    const warning = /Meta refused this for its formatting in a temple\. That applies to every temple\./;
    expect(card("temple_announcement").getByText(warning)).toBeInTheDocument();
    expect(card("shift_reminder").queryByText(warning)).not.toBeInTheDocument();
    expect(card("donation_receipt").queryByText(warning)).not.toBeInTheDocument();
  });

  it("still shows every template's wording when the counts cannot be loaded", () => {
    countsRef.current = { data: null, error: toApiError(new Error("down"), "We couldn’t load this."), loading: false };
    render(<WhatsAppTemplatesPage />);

    expect(screen.getAllByRole("article")).toHaveLength(CATALOGUE.templates.length);
    expect(card("shift_reminder").getByText(CATALOGUE.templates[0].body)).toBeInTheDocument();
    expect(screen.queryByText(NOT_COUNTED)).not.toBeInTheDocument();
    expect(screen.getByText("We couldn’t load this.")).toBeInTheDocument();
  });
});
