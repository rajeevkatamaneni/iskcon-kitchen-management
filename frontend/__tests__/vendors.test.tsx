import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type { ApiError, VendorView } from "@/lib/api";

const { authRef, queryRef, reloadMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  queryRef: { current: { data: [] as VendorView[] | null, error: null as ApiError | null, loading: false } },
  reloadMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ ...queryRef.current, reload: reloadMock }),
}));

import VendorsPage from "@/app/vendors/page";

function vendor(o: Partial<VendorView>): VendorView {
  return {
    id: "v1",
    name: "Govind Wholesale",
    contactPerson: null,
    phone: "+919812345678",
    email: null,
    address: null,
    gstin: null,
    preferredLanguage: "hi",
    notes: null,
    active: true,
    whatsappReachable: true,
    createdAt: "2026-08-01T00:00:00Z",
    ...o,
  };
}

describe("vendors", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    queryRef.current = { data: [vendor({})], error: null, loading: false };
    reloadMock.mockReset();
  });

  it("lists vendors with an add control", () => {
    render(<VendorsPage />);
    expect(screen.getByRole("heading", { name: /vendors/i })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Govind Wholesale" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /add a vendor/i })).toBeInTheDocument();
  });

  it("flags a vendor whose WhatsApp needs a recheck", () => {
    queryRef.current = { data: [vendor({ whatsappReachable: false })], error: null, loading: false };
    render(<VendorsPage />);
    expect(screen.getByText(/recheck whatsapp/i)).toBeInTheDocument();
  });

  it("shows an empty state when there are no vendors", () => {
    queryRef.current = { data: [], error: null, loading: false };
    render(<VendorsPage />);
    expect(screen.getByText(/no vendors yet/i)).toBeInTheDocument();
  });

  it("refuses a role without access", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } };
    render(<VendorsPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
