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

// The screen reads its own address bar now (item 22), so the stub has to answer both halves of
// next/navigation: what the URL says, and what a click asks the router to do with it.
const { pushMock, replaceMock, paramsRef } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  replaceMock: vi.fn(),
  paramsRef: { current: new URLSearchParams() },
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
  useSearchParams: () => paramsRef.current,
  useParams: () => ({ id: "id-1" }),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ ...queryRef.current, reload: reloadMock }),
}));

import VendorsPage from "@/app/vendors/page";
import NewVendorPage from "@/app/vendors/new/page";

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
    paramsRef.current = new URLSearchParams();
    pushMock.mockReset();
    replaceMock.mockReset();
  });

  it("lists vendors, and sends adding one to its own screen", () => {
    render(<VendorsPage />);
    expect(screen.getByRole("heading", { name: /vendors/i })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Govind Wholesale" })).toBeInTheDocument();
    // Eight fields, so the form is a screen of its own rather than a panel over this list.
    expect(screen.getByRole("link", { name: /add a vendor/i })).toHaveAttribute("href", "/vendors/new");
  });

  it("shows the confirmation a newly added vendor comes back with", () => {
    paramsRef.current = new URLSearchParams("added=Govind%20Wholesale");
    render(<VendorsPage />);
    expect(screen.getByText(/Govind Wholesale was added\./i)).toBeInTheDocument();
    // …and strips it, so a refresh does not flash it a second time.
    expect(replaceMock).toHaveBeenCalledWith("/vendors");
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

describe("adding a vendor", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    queryRef.current = { data: [], error: null, loading: false };
    pushMock.mockReset();
  });

  it("is one screen doing one task, with the actions together at the top right", () => {
    render(<NewVendorPage />);
    expect(screen.getByRole("heading", { name: "Add a vendor" })).toBeInTheDocument();
    // Rule 3: one line under the task saying whose record this is.
    expect(screen.getByText("New supplier for this temple")).toBeInTheDocument();
    expect(screen.getByRole("form", { name: /add a vendor/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /add vendor/i })).toBeInTheDocument();
    // Rule 7: no back-link. Cancel says what happens to what has been typed.
    expect(screen.getByRole("link", { name: "Cancel" })).toHaveAttribute("href", "/vendors");
    expect(screen.queryByText(/←/)).not.toBeInTheDocument();
  });

  it("keeps the sidebar, rather than trapping somebody on the form", () => {
    render(<NewVendorPage />);
    expect(screen.getByRole("navigation")).toBeInTheDocument();
  });
});
