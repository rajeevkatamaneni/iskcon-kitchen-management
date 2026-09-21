import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { JobTitleOption, Kitchen, UserSummary } from "@/lib/api";
import { TITLES, kitchen } from "./staff-fixtures";

/**
 * Hiring somebody (E6-S8), on the screen it moved to on 2026-08-21.
 *
 * <p>The two fields that must not be merged are the reason this screen is worth its own test: a
 * <b>job title</b> is what somebody is called and grants nothing, <b>access</b> is what they may do.
 * The title suggests the access and never locks it, so the common case is one choice and the unusual
 * one — a driver with no login, a head cook who is also an administrator — is still possible.
 *
 * <p>The rest is the focus-screen pattern, asserted once here and once on each of its siblings: one
 * place to commit, in the header, beside the name of what is being committed.
 */

const { authRef, titlesRef, devoteesRef, kitchensRef, pushMock, hireMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  titlesRef: { current: { data: [] as JobTitleOption[], error: null, loading: false } },
  devoteesRef: { current: { data: [] as UserSummary[], error: null, loading: false } },
  // One kitchen, so the form has already chosen it (Epic 12) and these tests are about what they say.
  kitchensRef: { current: { data: [] as Kitchen[], error: null, loading: false } },
  pushMock: vi.fn(),
  hireMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: pushMock }),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fn: (t: string | undefined) => Promise<unknown>) => {
    const source = fn.toString();
    const ref = source.includes("jobTitles") ? titlesRef : source.includes("listKitchens") ? kitchensRef : devoteesRef;
    return { ...ref.current, reload: vi.fn() };
  },
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, hireStaff: hireMock } };
});

import HireStaffPage from "@/app/staff/new/page";

describe("hiring somebody", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    titlesRef.current = { data: TITLES, error: null, loading: false };
    devoteesRef.current = { data: [], error: null, loading: false };
    kitchensRef.current = { data: [kitchen()], error: null, loading: false };
    pushMock.mockReset();
    hireMock.mockReset().mockResolvedValue({ id: "new" });
  });

  it("is one screen for one task, committed from the header", () => {
    render(<HireStaffPage />);
    expect(screen.getByRole("heading", { name: "Hire someone" })).toBeInTheDocument();

    // Secondary first, primary last, and one of each — not a second copy at the foot of the form.
    expect(screen.getAllByRole("button", { name: "Hire" })).toHaveLength(1);
    expect(screen.getByRole("link", { name: "Cancel" })).toHaveAttribute("href", "/staff");
    expect(screen.queryByText(/←/)).not.toBeInTheDocument();
  });

  it("suggests the access a job title usually needs, and lets it be overridden", () => {
    render(<HireStaffPage />);
    const form = screen.getByRole("form", { name: /hire a staff member/i });
    const title = form.querySelector('select[name="jobTitle"]') as HTMLSelectElement;
    const access = form.querySelector('select[name="systemAccess"]') as HTMLSelectElement;

    fireEvent.change(title, { target: { value: "TEMPLE_ADMINISTRATOR" } });
    expect(access).toHaveValue("TEMPLE_ADMIN");

    // A driver needs no login, and choosing one should stop offering an account.
    fireEvent.change(title, { target: { value: "DRIVER" } });
    expect(access).toHaveValue("");

    // Once the admin says otherwise, the title stops overruling them.
    fireEvent.change(access, { target: { value: "KITCHEN_STAFF" } });
    fireEvent.change(title, { target: { value: "COOK" } });
    expect(access).toHaveValue("KITCHEN_STAFF");
  });

  it("asks for the temple's own words when the title is Other", () => {
    render(<HireStaffPage />);
    const form = screen.getByRole("form", { name: /hire a staff member/i });
    expect(form.querySelector('input[name="jobTitleOther"]')).toBeNull();

    fireEvent.change(form.querySelector('select[name="jobTitle"]')!, { target: { value: "OTHER" } });
    expect(
      screen.getByRole("form", { name: /hire a staff member/i }).querySelector('input[name="jobTitleOther"]')
    ).toBeTruthy();
  });

  it("hires, sending no PAN when the box was left empty, and goes back with the confirmation", async () => {
    render(<HireStaffPage />);
    const form = screen.getByRole("form", { name: /hire a staff member/i });

    fireEvent.change(form.querySelector('input[name="fullName"]')!, { target: { value: "Ramesh Kumar" } });
    fireEvent.change(form.querySelector('input[name="dateOfJoining"]')!, { target: { value: "2026-03-01" } });
    fireEvent.submit(form);

    await waitFor(() => expect(hireMock).toHaveBeenCalled());
    const [input] = hireMock.mock.calls[0];
    expect(input.fullName).toBe("Ramesh Kumar");
    expect(input.dateOfJoining).toBe("2026-03-01");
    // Absent, not "" — an empty string clears a stored PAN and this is a fresh hire either way.
    expect(input.pan).toBeUndefined();

    // The register is where it came from, and the confirmation waits there rather than on a screen
    // that is about to close.
    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/staff?hired=Ramesh%20Kumar"));
  });

  it("sends both numbers bare when they were typed with spaces and hyphens", async () => {
    // T-157: the way KMS-400003 writes a number is a way the server now accepts, and stores bare.
    render(<HireStaffPage />);
    const form = screen.getByRole("form", { name: /hire a staff member/i });

    fireEvent.change(form.querySelector('input[name="fullName"]')!, { target: { value: "Ramesh Kumar" } });
    fireEvent.change(form.querySelector('input[name="dateOfJoining"]')!, { target: { value: "2026-03-01" } });
    fireEvent.change(form.querySelector('input[name="phone"]')!, { target: { value: "+91 98765 43210" } });
    fireEvent.change(form.querySelector('input[name="emergencyContactPhone"]')!, {
      target: { value: "+91-98765-43211" },
    });
    fireEvent.submit(form);

    await waitFor(() => expect(hireMock).toHaveBeenCalled());
    const [input] = hireMock.mock.calls[0];
    expect(input.phone).toBe("+919876543210");
    expect(input.emergencyContactPhone).toBe("+919876543211");
  });

  it("commits from the header button, which reaches the form it is not inside", async () => {
    render(<HireStaffPage />);
    const form = screen.getByRole("form", { name: /hire a staff member/i });
    fireEvent.change(form.querySelector('input[name="fullName"]')!, { target: { value: "Priya Sharma" } });
    fireEvent.change(form.querySelector('input[name="dateOfJoining"]')!, { target: { value: "2026-03-01" } });

    expect(screen.getByRole("button", { name: "Hire" })).toHaveAttribute("form", form.id);
    expect(form.id).not.toBe("");
  });

  it("does not ask whether the person is already registered here", () => {
    // Removed on Rajeev's instruction, 2026-09-21: "Remove 'Already registered here' and the
    // dropdown. That was NEVER a requirement." The backend's `existingUserId` road is untouched;
    // nothing in the UI takes it any more, so a hire always creates the account.
    render(<HireStaffPage />);
    const form = screen.getByRole("form", { name: /hire a staff member/i });
    expect(form.querySelector('select[name="existingUserId"]')).toBeNull();
    expect(screen.queryByText(/already registered/i)).not.toBeInTheDocument();
  });

  it("refuses kitchen staff", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<HireStaffPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
