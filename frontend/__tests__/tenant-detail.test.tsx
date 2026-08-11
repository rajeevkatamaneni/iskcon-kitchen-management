import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { TenantDetail } from "@/lib/api";

// The view page reads one temple via useAuthedQuery and can delete it. Mock the route param, auth,
// the query, and the delete call so we can drive the confirm-to-delete flow precisely.
const { pushMock, deleteSpy, queryRef } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  deleteSpy: vi.fn(async () => undefined),
  queryRef: {
    current: { data: null as TenantDetail | null, error: null as unknown, loading: false },
  },
}));

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
  return { ...actual, api: { ...actual.api, deleteTenant: deleteSpy, getTenant: vi.fn() } };
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
  status: "ACTIVE",
  created_at: "2026-08-11T00:00:00Z",
  user_count: 1,
};

describe("temple view + delete", () => {
  beforeEach(() => {
    pushMock.mockClear();
    deleteSpy.mockClear();
    queryRef.current = { data: TENANT, error: null, loading: false };
  });

  it("shows the temple's details and shareable public address", () => {
    render(<TenantDetailPage />);

    expect(screen.getByRole("heading", { name: /ISKCON South Bangalore/i })).toBeInTheDocument();
    expect(screen.getByText(/\/t\/iskcon-south-bangalore$/)).toBeInTheDocument();
    expect(screen.getByText("Approved")).toBeInTheDocument();
  });

  it("only enables Delete once the temple's exact name is typed, then deletes and returns to the list", async () => {
    render(<TenantDetailPage />);

    // Open the confirm dialog from the danger zone.
    fireEvent.click(screen.getByRole("button", { name: /delete temple/i }));

    const dialog = screen.getByRole("dialog");
    const input = within(dialog).getByLabelText(/type the temple's name/i);
    const confirmButton = within(dialog).getByRole("button", { name: /delete temple/i });

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
});
