import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type {
  ApiError,
  JobTitleOption,
  StaffConductNoteView,
  StaffPayView,
  StaffRegisterView,
} from "@/lib/api";
import { TITLES, member, pay } from "./staff-fixtures";

/**
 * Updating one person's record (E6-S8), on the screen it moved to on 2026-08-21.
 *
 * <p>This screen is the reason current staff have no separate View: it <em>is</em> the whole record,
 * in a form. So the two things worth asserting are that it arrives filled in — including the salary,
 * which comes from a different request than the rest of it — and that it never sends which devotee
 * account the record belongs to, because that cannot change after the hire.
 */

const {
  authRef,
  paramsRef,
  registerRef,
  titlesRef,
  payRef,
  conductRef,
  pushMock,
  updateMock,
  revealMock,
} =
  vi.hoisted(() => ({
    authRef: {
      current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
        status: string;
        appUser: { role: string; userId: string } | null;
      },
    },
    paramsRef: { current: { id: "s1" } },
    registerRef: {
      current: { data: null as StaffRegisterView | null, error: null as ApiError | null, loading: false },
    },
    titlesRef: { current: { data: [] as JobTitleOption[], error: null, loading: false } },
    payRef: { current: { data: null as StaffPayView | null, error: null as ApiError | null, loading: false } },
    // The conduct-notes panel reads through the same hook (E6-S16), and needs its own branch.
    conductRef: { current: { data: [] as StaffConductNoteView[], error: null, loading: false } },
    pushMock: vi.fn(),
    updateMock: vi.fn(),
    revealMock: vi.fn(),
  }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: pushMock }),
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
        : titlesRef;
    return { ...ref.current, reload: vi.fn() };
  },
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, updateStaffMember: updateMock, revealStaffPan: revealMock },
  };
});

import EditStaffPage from "@/app/staff/[id]/edit/page";

describe("updating a staff record", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    paramsRef.current = { id: "s1" };
    registerRef.current = { data: { current: [member()], former: [] }, error: null, loading: false };
    titlesRef.current = { data: TITLES, error: null, loading: false };
    payRef.current = { data: pay(), error: null, loading: false };
    conductRef.current = { data: [], error: null, loading: false };
    pushMock.mockReset();
    updateMock.mockReset().mockResolvedValue(undefined);
    revealMock.mockReset().mockResolvedValue({ pan: "ABCDE1234F" });
  });

  it("says whose record it is, under the task and not instead of it", () => {
    render(<EditStaffPage />);
    expect(screen.getByRole("heading", { name: "Update staff" })).toBeInTheDocument();
    expect(screen.getByText(/Gopal Das · Head Cook · joined/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Cancel" })).toHaveAttribute("href", "/staff");
    expect(screen.queryByText(/←/)).not.toBeInTheDocument();
  });

  it("arrives filled in, salary included", () => {
    render(<EditStaffPage />);
    const form = screen.getByRole("form", { name: /edit a staff member/i });
    expect(form.querySelector('input[name="fullName"]')).toHaveValue("Gopal Das");
    expect(form.querySelector('select[name="jobTitle"]')).toHaveValue("HEAD_COOK");
    // The salary comes from the pay request, not from the register row.
    expect(form.querySelector('input[name="monthlySalary"]')).toHaveValue(18000);
  });

  it("saves without ever sending which account the record belongs to", async () => {
    render(<EditStaffPage />);
    const form = screen.getByRole("form", { name: /edit a staff member/i });
    fireEvent.change(form.querySelector('select[name="jobTitle"]')!, { target: { value: "COOK" } });
    fireEvent.submit(form);

    await waitFor(() => expect(updateMock).toHaveBeenCalled());
    const [id, input] = updateMock.mock.calls[0];
    expect(id).toBe("s1");
    expect(input.jobTitle).toBe("COOK");
    expect("existingUserId" in input).toBe(false);

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/staff?updated=Gopal%20Das"));
  });

  it("reads a PAN only when asked, because reading one is recorded", async () => {
    registerRef.current = {
      data: { current: [member({ panLast4: "234F" })], former: [] },
      error: null,
      loading: false,
    };
    render(<EditStaffPage />);
    expect(screen.getByText("••••••234F")).toBeInTheDocument();
    expect(screen.queryByText("ABCDE1234F")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /reveal/i }));
    await waitFor(() => expect(screen.getByText("ABCDE1234F")).toBeInTheDocument());
    expect(revealMock).toHaveBeenCalledWith("s1", "test-token");
  });

  it("says so plainly when the address belongs to nobody on the register", () => {
    paramsRef.current = { id: "gone" };
    render(<EditStaffPage />);
    expect(screen.getByText(/can’t find that person/i)).toBeInTheDocument();
    expect(screen.queryByRole("form", { name: /edit a staff member/i })).not.toBeInTheDocument();
  });
});
