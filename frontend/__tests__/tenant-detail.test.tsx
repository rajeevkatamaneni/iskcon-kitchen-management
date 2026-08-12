import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { TenantDetail } from "@/lib/api";

// The view page reads one temple via useAuthedQuery, can export it, and can delete it. Mock the
// route param, auth, the query, and the two calls so we can drive export-then-confirm precisely.
const { pushMock, deleteSpy, exportSpy, reloadMock, queryRef } = vi.hoisted(() => {
  const reload = vi.fn();
  return {
    pushMock: vi.fn(),
    deleteSpy: vi.fn(async () => undefined),
    exportSpy: vi.fn(async () => ({
      blob: new Blob(["x"]),
      filename: "iskcon-south-bangalore-ikms-data-export.xlsx",
    })),
    reloadMock: reload,
    queryRef: {
      current: {
        data: null as TenantDetail | null,
        error: null as unknown,
        loading: false,
        reload,
      },
    },
  };
});

vi.mock("next/navigation", () => ({
  useParams: () => ({ id: "t1" }),
  useRouter: () => ({ push: pushMock }),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({
    status: "signed-in",
    appUser: { role: "SUPER_ADMIN" },
    getToken: async () => "token",
  }),
}));
vi.mock("@/lib/use-authed-query", () => ({ useAuthedQuery: () => queryRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, deleteTenant: deleteSpy, exportTenant: exportSpy, getTenant: vi.fn() },
  };
});

import TenantDetailPage from "@/app/tenants/[id]/page";

const TENANT: TenantDetail = {
  id: "t1",
  slug: "iskcon-south-bangalore",
  name: "ISKCON South Bangalore",
  address: "Kumaraswamy Layout, Bengaluru",
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
    queryRef.current = { data: TENANT, error: null, loading: false, reload: reloadMock };
    // jsdom has no object URLs; the page only needs them to trigger the download.
    URL.createObjectURL = vi.fn(() => "blob:export");
    URL.revokeObjectURL = vi.fn();
  });

  it("shows the temple's details and shareable public address", () => {
    render(<TenantDetailPage />);

    expect(screen.getByRole("heading", { name: /ISKCON South Bangalore/i })).toBeInTheDocument();
    expect(screen.getByText(/\/t\/iskcon-south-bangalore$/)).toBeInTheDocument();
    expect(screen.getByText("Approved")).toBeInTheDocument();
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
    fireEvent.change(within(dialog).getByLabelText(/type the temple's name/i), {
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
    const input = within(dialog).getByLabelText(/type the temple's name/i);
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
    fireEvent.change(within(dialog).getByLabelText(/type the temple's name/i), {
      target: { value: "ISKCON South Bangalore" },
    });

    expect(within(dialog).getByRole("button", { name: /^delete temple$/i })).toBeDisabled();
    expect(within(dialog).getByText(/haven’t exported this temple’s data/i)).toBeInTheDocument();
  });
});
