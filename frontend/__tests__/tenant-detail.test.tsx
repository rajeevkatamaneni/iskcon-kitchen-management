import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { TempleTemplateStatusView, TenantDetail } from "@/lib/api";
import { moment } from "@/lib/format";

// The view page reads one temple via useAuthedQuery, can export it, and can delete it. Mock the
// route param, auth, the query, and the two calls so we can drive export-then-confirm precisely.
// Since T-178 it also has a collapsed WhatsApp templates section, which calls the API itself when
// opened, so those two calls are spied as well.
const { pushMock, deleteSpy, exportSpy, statusSpy, refreshSpy, reloadMock, queryRef, authRef } = vi.hoisted(() => {
  const reload = vi.fn();
  // One object, replaced only when the role changes: a fresh getToken on every render is harmless
  // here only because nothing lists it as an effect dependency, and a stable one keeps it that way.
  const getToken = async () => "token";
  return {
    pushMock: vi.fn(),
    deleteSpy: vi.fn(async () => undefined),
    exportSpy: vi.fn(async () => ({
      blob: new Blob(["x"]),
      filename: "iskcon-south-bangalore-ikms-data-export.xlsx",
    })),
    statusSpy: vi.fn(),
    refreshSpy: vi.fn(),
    reloadMock: reload,
    queryRef: {
      current: {
        data: null as TenantDetail | null,
        error: null as unknown,
        loading: false,
        reload,
      },
    },
    authRef: {
      current: {
        status: "signed-in",
        appUser: { role: "SUPER_ADMIN", fullName: "Test Person" },
        getToken,
      } as { status: string; appUser: { role: string; fullName?: string } | null; getToken: () => Promise<string> },
    },
  };
});

vi.mock("next/navigation", () => ({
  useParams: () => ({ id: "t1" }),
  useRouter: () => ({ push: pushMock, replace: vi.fn() }),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => authRef.current,
}));
vi.mock("@/lib/use-authed-query", () => ({ useAuthedQuery: () => queryRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      deleteTenant: deleteSpy,
      exportTenant: exportSpy,
      getTenant: vi.fn(),
      templeTemplateStatus: statusSpy,
      refreshTempleTemplateStatus: refreshSpy,
    },
  };
});

import TenantDetailPage from "@/app/tenants/[id]/page";

const TENANT: TenantDetail = {
  id: "t1",
  slug: "iskcon-south-bangalore",
  name: "ISKCON South Bangalore",
  address: "Kumaraswamy Layout, Bengaluru",
  // Carried since T-008: the endpoint has always had them and the correction screen opens on them,
  // so `TenantDetail` names them as required and a fixture without them no longer compiles.
  latitude: 12.9716,
  longitude: 77.5946,
  timezone: "Asia/Kolkata",
  currency: "INR",
  is_80g_approved: true,
  created_at: "2026-08-11T00:00:00Z",
  user_count: 1,
  last_export_at: null,
};

/** A temple exported a moment ago — recent enough for the deletion guard. */
const exportedJustNow = (): TenantDetail => ({
  ...TENANT,
  last_export_at: new Date().toISOString(),
});

describe("temple view, export + delete", () => {
  beforeEach(() => {
    pushMock.mockClear();
    deleteSpy.mockClear();
    exportSpy.mockClear();
    reloadMock.mockClear();
    authRef.current = { ...authRef.current, appUser: { role: "SUPER_ADMIN", fullName: "Test Person" } };
    queryRef.current = { data: TENANT, error: null, loading: false, reload: reloadMock };
    // jsdom has no object URLs; the page only needs them to trigger the download.
    URL.createObjectURL = vi.fn(() => "blob:export");
    URL.revokeObjectURL = vi.fn();
  });

  it("shows the temple's details", () => {
    render(<TenantDetailPage />);

    expect(screen.getByRole("heading", { name: /ISKCON South Bangalore/i })).toBeInTheDocument();
    expect(screen.getByText("Approved")).toBeInTheDocument();
  });

  it("offers no public web address, because the temple has none here", () => {
    // There was a "Web address" panel with a copy button, captioned "Where devotees find this
    // temple's donations and wish list". Both halves stopped being true on 2026-08-29: giving moved
    // behind a sign-in, and this product never had a public page for a temple to be found at — the
    // address it offered had 404'd since it was written. Temples have their own websites.
    render(<TenantDetailPage />);

    expect(screen.queryByText(/web address/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/\/t\//)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /copy/i })).not.toBeInTheDocument();
  });

  it("says when the temple was last exported, and never lies about it", () => {
    render(<TenantDetailPage />);
    expect(screen.getByText(/never exported/i)).toBeInTheDocument();

    queryRef.current = { ...queryRef.current, data: exportedJustNow() };
    render(<TenantDetailPage />);
    expect(screen.getAllByText(/last exported/i).length).toBeGreaterThan(0);
  });

  it("downloads the export and refreshes, so the page reflects that a copy now exists", async () => {
    render(<TenantDetailPage />);

    fireEvent.click(screen.getByRole("button", { name: /download data export/i }));

    await waitFor(() =>
      expect(exportSpy).toHaveBeenCalledWith("t1", "iskcon-south-bangalore", "token")
    );
    await waitFor(() => expect(reloadMock).toHaveBeenCalled());
  });

  it("will not arm Delete without a recent export, however correctly the name is typed", () => {
    render(<TenantDetailPage />);

    fireEvent.click(screen.getByRole("button", { name: /delete temple/i }));

    const dialog = screen.getByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/type the temple’s name/i), {
      target: { value: "ISKCON South Bangalore" },
    });

    expect(within(dialog).getByRole("button", { name: /^delete temple$/i })).toBeDisabled();
    // And it says plainly what is at stake, rather than just refusing.
    expect(within(dialog).getByText(/haven’t exported this temple’s data/i)).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: /download data export/i })).toBeInTheDocument();
  });

  it("only enables Delete once exported and the exact name is typed, then deletes and returns to the list", async () => {
    queryRef.current = { ...queryRef.current, data: exportedJustNow() };
    render(<TenantDetailPage />);

    fireEvent.click(screen.getByRole("button", { name: /delete temple/i }));

    const dialog = screen.getByRole("dialog");
    const input = within(dialog).getByLabelText(/type the temple’s name/i);
    const confirmButton = within(dialog).getByRole("button", { name: /^delete temple$/i });

    expect(confirmButton).toBeDisabled();

    fireEvent.change(input, { target: { value: "iskcon south bangalore" } }); // wrong case
    expect(confirmButton).toBeDisabled();

    fireEvent.change(input, { target: { value: "ISKCON South Bangalore" } });
    expect(confirmButton).toBeEnabled();

    fireEvent.click(confirmButton);

    await waitFor(() => expect(deleteSpy).toHaveBeenCalledWith("t1", "token"));
    await waitFor(() =>
      expect(pushMock).toHaveBeenCalledWith("/tenants?deleted=ISKCON%20South%20Bangalore")
    );
  });

  it("treats a stale export as no export at all", () => {
    const twoDaysAgo = new Date(Date.now() - 48 * 60 * 60 * 1000).toISOString();
    queryRef.current = { ...queryRef.current, data: { ...TENANT, last_export_at: twoDaysAgo } };
    render(<TenantDetailPage />);

    fireEvent.click(screen.getByRole("button", { name: /delete temple/i }));

    const dialog = screen.getByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/type the temple’s name/i), {
      target: { value: "ISKCON South Bangalore" },
    });

    expect(within(dialog).getByRole("button", { name: /^delete temple$/i })).toBeDisabled();
    expect(within(dialog).getByText(/haven’t exported this temple’s data/i)).toBeInTheDocument();
  });
});

// ---- T-178: the temple's WhatsApp templates as Meta holds them ------------------------------------

const AS_OF = "2026-09-13T06:15:00Z";
const REFRESHED_AT = "2026-09-13T09:40:00Z";

/** One of each way a row can read. */
const STATUS: TempleTemplateStatusView = {
  tenantId: "t1",
  asOf: AS_OF,
  templates: [
    { name: "shift_reminder", ourCategory: "UTILITY", metaStatus: "APPROVED", metaCategory: "UTILITY", held: true, wordingMatches: true, lookupProblem: null },
    { name: "donation_thank_you", ourCategory: "UTILITY", metaStatus: "PENDING", metaCategory: "MARKETING", held: true, wordingMatches: false, lookupProblem: null },
    { name: "po_delivery", ourCategory: "UTILITY", metaStatus: "REJECTED", metaCategory: "UTILITY", held: true, wordingMatches: true, lookupProblem: null },
    { name: "leave_revoked", ourCategory: "UTILITY", metaStatus: null, metaCategory: null, held: false, wordingMatches: null, lookupProblem: null },
    { name: "low_stock_digest", ourCategory: "UTILITY", metaStatus: null, metaCategory: null, held: null, wordingMatches: null, lookupProblem: "Meta could not be reached for this message." },
  ],
};

function sectionToggle() {
  return screen.getByRole("button", { name: "WhatsApp templates" });
}

describe("temple view, WhatsApp templates", () => {
  beforeEach(() => {
    statusSpy.mockReset();
    refreshSpy.mockReset();
    statusSpy.mockResolvedValue(STATUS);
    refreshSpy.mockResolvedValue({ ...STATUS, asOf: REFRESHED_AT });
    authRef.current = { ...authRef.current, appUser: { role: "SUPER_ADMIN", fullName: "Test Person" } };
    queryRef.current = { data: TENANT, error: null, loading: false, reload: reloadMock };
  });

  it("is collapsed by default, so export and delete stay in view, and asks for nothing until opened", () => {
    render(<TenantDetailPage />);

    expect(sectionToggle()).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.queryByText(/As of/)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Refresh from Meta" })).not.toBeInTheDocument();
    expect(statusSpy).not.toHaveBeenCalled();
    // The two acts the page exists for are still right there.
    expect(screen.getByRole("button", { name: /download data export/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /delete temple/i })).toBeInTheDocument();
  });

  it("opening it shows each template's Meta status, Meta's category and the wording, with the as-of time", async () => {
    render(<TenantDetailPage />);

    fireEvent.click(sectionToggle());

    await waitFor(() => expect(statusSpy).toHaveBeenCalledWith("t1", "token"));
    expect(await screen.findByText(`As of ${moment(AS_OF)}.`)).toBeInTheDocument();
    expect(sectionToggle()).toHaveAttribute("aria-expanded", "true");

    const table = within(screen.getByRole("table"));
    const row = (name: string) => within(table.getByText(name).closest("tr") as HTMLElement);
    expect(row("shift_reminder").getByText("Approved")).toBeInTheDocument();
    expect(row("shift_reminder").getByText("Utility")).toBeInTheDocument();
    expect(row("shift_reminder").getByText("Matches")).toBeInTheDocument();
    expect(row("donation_thank_you").getByText("Pending")).toBeInTheDocument();
    expect(row("donation_thank_you").getByText("Marketing, app sends Utility")).toBeInTheDocument();
    expect(row("donation_thank_you").getByText("Differs")).toBeInTheDocument();
    expect(row("po_delivery").getByText("Refused")).toBeInTheDocument();
    expect(row("leave_revoked").getByText("Not held by Meta")).toBeInTheDocument();
    expect(row("low_stock_digest").getByText("Meta could not be reached for this message.")).toBeInTheDocument();

    // No stored value printed as it is, and no word that claims somebody authored a template.
    expect(screen.queryByText(/APPROVED|PENDING|REJECTED|UTILITY|MARKETING/)).not.toBeInTheDocument();
    const section = sectionToggle().closest("section") as HTMLElement;
    expect(section.textContent).not.toMatch(/created/i);
  });

  it("Refresh calls the reserved endpoint for this temple and shows the new copy's time", async () => {
    render(<TenantDetailPage />);
    fireEvent.click(sectionToggle());
    await screen.findByText(`As of ${moment(AS_OF)}.`);

    fireEvent.click(screen.getByRole("button", { name: "Refresh from Meta" }));

    await waitFor(() => expect(refreshSpy).toHaveBeenCalledWith("t1", "token"));
    expect(await screen.findByText(`As of ${moment(REFRESHED_AT)}.`)).toBeInTheDocument();
    expect(refreshSpy).toHaveBeenCalledTimes(1);
    expect(screen.getByText("Refresh uses this temple’s own WhatsApp token. The temple’s audit log records it.")).toBeInTheDocument();
  });

  it("a temple never checked says so in words, and still offers Refresh", async () => {
    statusSpy.mockResolvedValue({ tenantId: "t1", asOf: null, templates: [] });
    render(<TenantDetailPage />);

    fireEvent.click(sectionToggle());

    expect(await screen.findByText("Meta has not been asked for this temple yet.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Refresh from Meta" })).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("refuses somebody who is not the platform operator, section and all", () => {
    authRef.current = { ...authRef.current, appUser: { role: "TEMPLE_ADMIN", fullName: "Radharani Devi" } };
    render(<TenantDetailPage />);

    expect(screen.getByRole("heading", { level: 1, name: "Not your page" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "WhatsApp templates" })).not.toBeInTheDocument();
    expect(statusSpy).not.toHaveBeenCalled();
  });
});
