import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type {
  ApiError,
  BanCategoryOption,
  EmploymentBanView,
  StaffConductNoteView,
  StaffPayView,
  StaffRegisterView,
} from "@/lib/api";
import { CATEGORIES, ban, former, member, pay } from "./staff-fixtures";

/**
 * Taking somebody back on, from the record screen (T-014).
 *
 * <p>The panel exists because ending an employment also locked the record: a former employee has no
 * editable form, so until this there was no screen anywhere from which a misclick on the termination
 * form could be corrected.
 *
 * <p>Two things are asserted that are easy to get wrong and cheap to get right. It is offered only
 * where there is something to take back — a current member of staff has not left, and offering it on
 * their record would be offering what the server answers with KMS-400135. And it is <em>not</em>
 * offered where this temple has a live record against the person, because the server refuses that
 * with KMS-400136 rather than warning about it, and a button that cannot work is worse than no
 * button.
 */

const { authRef, paramsRef, registerRef, payRef, bansRef, categoriesRef, conductRef, reinstateMock } =
  vi.hoisted(() => ({
    authRef: {
      current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
        status: string;
        appUser: { role: string; userId: string } | null;
      },
    },
    paramsRef: { current: { id: "s2" } },
    registerRef: {
      current: { data: null as StaffRegisterView | null, error: null as ApiError | null, loading: false },
    },
    payRef: { current: { data: null as StaffPayView | null, error: null as ApiError | null, loading: false } },
    bansRef: { current: { data: [] as EmploymentBanView[], error: null, loading: false } },
    categoriesRef: { current: { data: [] as BanCategoryOption[], error: null, loading: false } },
    conductRef: { current: { data: [] as StaffConductNoteView[], error: null, loading: false } },
    reinstateMock: vi.fn(),
  }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => paramsRef.current,
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fn: (t: string | undefined) => Promise<unknown>) => {
    const source = fn.toString();
    const ref = source.includes("staffConductNotes")
      ? conductRef
      : source.includes("staffRegister")
        ? registerRef
        : source.includes("staffPay")
          ? payRef
          : source.includes("templeBans")
            ? bansRef
            : categoriesRef;
    return { ...ref.current, reload: vi.fn() };
  },
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, reinstateStaff: reinstateMock } };
});

import StaffRecordPage from "@/app/staff/[id]/page";

/** The register the screen reads, with one former employee and one current one. */
function registerWith(bannedFlag: boolean) {
  return {
    data: {
      current: [member()],
      former: [
        former(
          {
            id: "s2",
            fullName: "Madhava Das",
            employmentStatus: "TERMINATED",
            lastWorkingDay: "2026-08-15",
            endReason: "Money missing from the box",
          },
          bannedFlag
        ),
      ],
    },
    error: null,
    loading: false,
  };
}

describe("taking a former member of staff back on", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    paramsRef.current = { id: "s2" };
    registerRef.current = registerWith(false);
    payRef.current = { data: pay({ staffId: "s2" }), error: null, loading: false };
    bansRef.current = { data: [], error: null, loading: false };
    categoriesRef.current = { data: CATEGORIES, error: null, loading: false };
    conductRef.current = { data: [], error: null, loading: false };
    reinstateMock.mockReset().mockResolvedValue(undefined);
  });

  it("sends the day they came back and the access the admin chose", async () => {
    render(<StaffRecordPage />);

    fireEvent.click(screen.getByRole("button", { name: /take them back on/i }));
    const panel = screen.getByRole("group", { name: /take them back on/i });

    fireEvent.change(panel.querySelector('input[name="dateOfRejoining"]')!, {
      target: { value: "2026-09-01" },
    });
    fireEvent.change(panel.querySelector('select[name="systemAccess"]')!, {
      target: { value: "KITCHEN_MANAGER" },
    });
    fireEvent.change(panel.querySelector('input[name="reason"]')!, {
      target: { value: "Dismissed by mistake." },
    });
    fireEvent.click(screen.getByRole("button", { name: /^take them back on$/i }));

    await waitFor(() => expect(reinstateMock).toHaveBeenCalled());
    expect(reinstateMock.mock.calls[0][0]).toBe("s2");
    expect(reinstateMock.mock.calls[0][1]).toEqual({
      dateOfRejoining: "2026-09-01",
      systemAccess: "KITCHEN_MANAGER",
      reason: "Dismissed by mistake.",
    });
  });

  /**
   * `systemAccess` is required-and-nullable on the client type for the reason T-044 established: a
   * field that is merely optional can be omitted, and an omission and an explicit "no login" then
   * read alike. So the key has to be present and null, and `objectContaining` cannot say that — a
   * missing property satisfies it. The keys are inspected first, then the value.
   */
  it("sends no login as an explicit null rather than by leaving the field out", async () => {
    render(<StaffRecordPage />);

    fireEvent.click(screen.getByRole("button", { name: /take them back on/i }));
    const panel = screen.getByRole("group", { name: /take them back on/i });
    fireEvent.change(panel.querySelector('input[name="dateOfRejoining"]')!, {
      target: { value: "2026-09-01" },
    });
    fireEvent.click(screen.getByRole("button", { name: /^take them back on$/i }));

    await waitFor(() => expect(reinstateMock).toHaveBeenCalled());
    const sent = reinstateMock.mock.calls[0][1];
    expect(Object.keys(sent)).toContain("systemAccess");
    expect(sent.systemAccess).toBeNull();
    expect(sent.reason).toBeNull();
  });

  it("offers nothing to take back on somebody who has not left", () => {
    paramsRef.current = { id: "s1" };
    render(<StaffRecordPage />);
    expect(screen.queryByRole("button", { name: /take them back on/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/take them back on/i)).not.toBeInTheDocument();
  });

  it("refuses rather than offers where a record still stands against them", () => {
    registerRef.current = registerWith(true);
    bansRef.current = { data: [ban({ personName: "Madhava Das" })], error: null, loading: false };
    render(<StaffRecordPage />);

    expect(screen.getByText(/there is a record against this person/i)).toBeInTheDocument();
    expect(screen.getByText(/take that record back first/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /take them back on/i })).not.toBeInTheDocument();
  });

  it("says nothing can be sent until the day they came back is given", () => {
    render(<StaffRecordPage />);
    fireEvent.click(screen.getByRole("button", { name: /take them back on/i }));
    expect(screen.getByRole("button", { name: /^take them back on$/i })).toBeDisabled();
  });
});
