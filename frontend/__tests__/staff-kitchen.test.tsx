import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import {
  ApiError,
  type Kitchen,
  type StaffKitchenCheckView,
  type StaffPayView,
  type StaffRegisterView,
} from "@/lib/api";
import { TITLES, kitchen, kitchenCheck, member } from "./staff-fixtures";

/**
 * Epic 12, wave E12-1 (T-353): every staff member belongs to exactly one kitchen, the Temple Admin
 * gets a "Check these kitchen assignments" list, and the meal planner is shut — in the menu and at
 * the door — to somebody whose kitchen does not plan its meals here.
 *
 * <p>Rajeev, 2026-09-19: "every staff member belongs to exactly one kitchen — required on add and
 * edit. Only people whose kitchen plans its meals here can open the meal planner (server-enforced,
 * and hidden from the menu) … existing staff are put in the main kitchen when the change ships, and
 * the Temple Admin gets a 'check these kitchen assignments' list to move anyone."
 *
 * <p>The backend behind it is wave E12-2, so the api is mocked here throughout.
 */

type Query<T> = { data: T; error: ApiError | null; loading: boolean };
const q = <T,>(data: T): Query<T> => ({ data, error: null, loading: false });

const { authRef, paramsRef, refs, mocks, reloadMock } = vi.hoisted(() => ({
  authRef: {
    current: {} as {
      status: string;
      appUser: { role: string; userId: string; canPlanMeals?: boolean; temples?: unknown[] } | null;
    },
  },
  paramsRef: { current: { id: "s1" } },
  refs: {
    kitchens: { current: null as unknown as Query<Kitchen[]> },
    checks: { current: null as unknown as Query<StaffKitchenCheckView[]> },
    register: { current: null as unknown as Query<StaffRegisterView> },
    pay: { current: null as unknown as Query<StaffPayView | null> },
    titles: { current: null as unknown as Query<unknown[]> },
    other: { current: null as unknown as Query<unknown[]> },
  },
  mocks: {
    hireStaff: vi.fn(),
    updateStaffMember: vi.fn(),
    setStaffKitchen: vi.fn(),
    confirmStaffKitchens: vi.fn(),
  },
  reloadMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => paramsRef.current,
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token", switchTemple: vi.fn(), signOut: vi.fn() }),
}));
// Which query a screen made is read off the fetcher's own source, as the other staff tests do.
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fn: (t: string | undefined) => Promise<unknown>) => {
    const asks = fn.toString();
    const ref = asks.includes("listKitchens")
      ? refs.kitchens
      : asks.includes("staffKitchenChecks")
        ? refs.checks
        : asks.includes("staffRegister")
          ? refs.register
          : asks.includes("staffPay")
            ? refs.pay
            : asks.includes("jobTitles")
              ? refs.titles
              : refs.other;
    return { ...ref.current, reload: reloadMock };
  },
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, ...mocks } };
});

import HireStaffPage from "@/app/staff/new/page";
import EditStaffPage from "@/app/staff/[id]/edit/page";
import StaffRecordPage from "@/app/staff/[id]/page";
import StaffPage from "@/app/staff/page";
import { RequireRole } from "@/components/RequireRole";

const MAIN = kitchen();
const FFL = kitchen({ id: "k2", name: "Food for Life", isMain: false, usesMealPlanner: false });

const admin = () => ({ status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } });

beforeEach(() => {
  authRef.current = admin();
  paramsRef.current = { id: "s1" };
  refs.kitchens.current = q([MAIN, FFL]);
  refs.checks.current = q([]);
  refs.register.current = q({ current: [member()], former: [] });
  refs.pay.current = q(null);
  refs.titles.current = q(TITLES);
  refs.other.current = q([]);
  reloadMock.mockReset();
  mocks.hireStaff.mockReset().mockResolvedValue({ id: "new", checkId: null, findings: [] });
  mocks.updateStaffMember.mockReset().mockResolvedValue({});
  mocks.setStaffKitchen.mockReset().mockResolvedValue(undefined);
  mocks.confirmStaffKitchens.mockReset().mockResolvedValue(undefined);
});

afterEach(() => {
  window.history.pushState({}, "", "/");
});

function kitchenSelect(form: HTMLElement) {
  return form.querySelector('select[name="kitchenId"]') as HTMLSelectElement;
}

function fillHire(form: HTMLElement) {
  fireEvent.change(form.querySelector('input[name="fullName"]')!, { target: { value: "Ramesh Kumar" } });
  fireEvent.change(form.querySelector('input[name="dateOfJoining"]')!, { target: { value: "2026-03-01" } });
}

describe("the Kitchen field on hiring", () => {
  it("starts on “Choose a kitchen” when there is a choice, and hires nobody until one is made", async () => {
    render(<HireStaffPage />);
    const form = screen.getByRole("form", { name: /hire a staff member/i });
    const select = kitchenSelect(form);
    expect(select).toBeRequired();
    expect(select).toHaveValue("");
    // Active kitchens in the order they came, after the placeholder.
    expect(Array.from(select.options).map((o) => o.textContent)).toEqual([
      "Choose a kitchen",
      "Main kitchen",
      "Food for Life",
    ]);

    fillHire(form);
    // The real header button, which is what makes Form read the boxes (see blank-submit tests).
    fireEvent.click(screen.getByRole("button", { name: "Hire" }));
    await waitFor(() => expect(within(form).getByText("Kitchen is required")).toBeInTheDocument());
    expect(mocks.hireStaff).not.toHaveBeenCalled();

    fireEvent.change(select, { target: { value: "k2" } });
    fireEvent.click(screen.getByRole("button", { name: "Hire" }));
    await waitFor(() => expect(mocks.hireStaff).toHaveBeenCalled());
    const [input] = mocks.hireStaff.mock.calls[0];
    expect(Object.keys(input)).toContain("kitchenId");
    expect(input.kitchenId).toBe("k2");
  });

  it("chooses the kitchen itself when the temple has only one", async () => {
    refs.kitchens.current = q([MAIN]);
    render(<HireStaffPage />);
    const form = screen.getByRole("form", { name: /hire a staff member/i });
    expect(kitchenSelect(form)).toHaveValue("k1");
    // Nothing to choose between, so no placeholder either.
    expect(within(kitchenSelect(form)).queryByText("Choose a kitchen")).toBeNull();

    fillHire(form);
    fireEvent.click(screen.getByRole("button", { name: "Hire" }));
    await waitFor(() => expect(mocks.hireStaff).toHaveBeenCalled());
    expect(mocks.hireStaff.mock.calls[0][0].kitchenId).toBe("k1");
  });

  it("never offers an archived kitchen", () => {
    refs.kitchens.current = q([MAIN, kitchen({ id: "k3", name: "Old kitchen", isMain: false, status: "ARCHIVED" })]);
    render(<HireStaffPage />);
    const form = screen.getByRole("form", { name: /hire a staff member/i });
    expect(within(kitchenSelect(form)).queryByText("Old kitchen")).toBeNull();
    // One active kitchen left, so it is chosen.
    expect(kitchenSelect(form)).toHaveValue("k1");
  });
});

describe("the Kitchen field on updating a record", () => {
  it("starts on the saved kitchen and sends the one chosen", async () => {
    refs.register.current = q({ current: [member({ kitchenId: "k2", kitchenName: "Food for Life" })], former: [] });
    render(<EditStaffPage />);
    const form = screen.getByRole("form", { name: /edit a staff member/i });
    expect(kitchenSelect(form)).toHaveValue("k2");

    fireEvent.change(kitchenSelect(form), { target: { value: "k1" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(mocks.updateStaffMember).toHaveBeenCalled());
    const [id, input] = mocks.updateStaffMember.mock.calls[0];
    expect(id).toBe("s1");
    expect(Object.keys(input)).toContain("kitchenId");
    expect(input.kitchenId).toBe("k1");
  });

  it("refuses to save a record with no kitchen", async () => {
    refs.register.current = q({ current: [member({ kitchenId: "", kitchenName: "" })], former: [] });
    render(<EditStaffPage />);
    const form = screen.getByRole("form", { name: /edit a staff member/i });
    expect(kitchenSelect(form)).toHaveValue("");

    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(within(form).getByText("Kitchen is required")).toBeInTheDocument());
    expect(mocks.updateStaffMember).not.toHaveBeenCalled();
  });

  it("says the server's KMS-400184 in the usual error notice", async () => {
    mocks.updateStaffMember.mockRejectedValue(
      new ApiError(
        {
          code: "KMS-400184",
          message: "Every staff member needs a kitchen.",
          action: "Choose the kitchen they work in, then save again.",
          fieldErrors: [],
        },
        400
      )
    );
    render(<EditStaffPage />);
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Every staff member needs a kitchen.");
    expect(alert).toHaveTextContent("KMS-400184");
  });
});

describe("the kitchen on the record and on the register", () => {
  it("shows the person's kitchen on their record", () => {
    render(<StaffRecordPage />);
    const fact = screen.getByText("Kitchen", { selector: "dt" });
    expect(fact.nextElementSibling).toHaveTextContent("Main kitchen");
  });

  it("gives the register a Kitchen column", () => {
    render(<StaffPage />);
    const current = screen.getByRole("region", { name: /current staff/i });
    expect(within(current).getByRole("columnheader", { name: "Kitchen" })).toBeInTheDocument();
    expect(within(current).getByText("Main kitchen")).toBeInTheDocument();
  });

  it("filters by kitchen when the temple has more than one", () => {
    refs.register.current = q({
      current: [
        member(),
        member({ id: "s3", fullName: "Radha Devi", kitchenId: "k2", kitchenName: "Food for Life" }),
      ],
      former: [],
    });
    render(<StaffPage />);
    const filter = screen.getByRole("combobox", { name: "Filter by kitchen" });
    expect(Array.from((filter as HTMLSelectElement).options).map((o) => o.textContent)).toEqual([
      "All kitchens",
      "Main kitchen",
      "Food for Life",
    ]);
    const current = screen.getByRole("region", { name: /current staff/i });
    expect(within(current).getByText("Gopal Das")).toBeInTheDocument();
    expect(within(current).getByText("Radha Devi")).toBeInTheDocument();

    fireEvent.change(filter, { target: { value: "k2" } });
    expect(within(current).queryByText("Gopal Das")).toBeNull();
    expect(within(current).getByText("Radha Devi")).toBeInTheDocument();
  });

  it("draws no kitchen filter when there is only one kitchen", () => {
    refs.kitchens.current = q([MAIN]);
    render(<StaffPage />);
    expect(screen.queryByRole("combobox", { name: "Filter by kitchen" })).toBeNull();
  });
});

describe("Check these kitchen assignments", () => {
  const HEADING = "Check these kitchen assignments";

  it("is shown to the Temple Admin while there are records to check", () => {
    refs.checks.current = q([kitchenCheck(), kitchenCheck({ staffId: "s4", fullName: "Madhava Das", jobTitleLabel: "Cook" })]);
    render(<StaffPage />);
    const card = screen.getByRole("region", { name: HEADING });
    expect(within(card).getByText(/everyone was put in the main kitchen/i)).toBeInTheDocument();
    expect(within(card).getByText("Gopal Das")).toBeInTheDocument();
    expect(within(card).getByText("Madhava Das")).toBeInTheDocument();
    expect(within(card).getByText("Cook")).toBeInTheDocument();
    expect(within(card).getByRole("combobox", { name: "Kitchen for Gopal Das" })).toHaveValue("k1");
  });

  it("is not shown when there is nothing to check", () => {
    render(<StaffPage />);
    expect(screen.queryByText(HEADING)).toBeNull();
  });

  it("is not shown to anybody but the Temple Admin", () => {
    refs.checks.current = q([kitchenCheck()]);
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_MANAGER", userId: "me" } };
    render(<StaffPage />);
    expect(screen.queryByText(HEADING)).toBeNull();
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });

  it("moves somebody when their kitchen is changed, and takes them off the list", async () => {
    refs.checks.current = q([kitchenCheck(), kitchenCheck({ staffId: "s4", fullName: "Madhava Das" })]);
    render(<StaffPage />);
    const card = screen.getByRole("region", { name: HEADING });
    fireEvent.change(within(card).getByRole("combobox", { name: "Kitchen for Gopal Das" }), {
      target: { value: "k2" },
    });
    await waitFor(() => expect(mocks.setStaffKitchen).toHaveBeenCalledWith("s1", "k2", "test-token"));
    await waitFor(() => expect(within(card).queryByText("Gopal Das")).toBeNull());
    expect(within(card).getByText("Madhava Das")).toBeInTheDocument();
    // The register beside it repaints the moved person's kitchen.
    expect(reloadMock).toHaveBeenCalled();
  });

  it("confirms everybody left with “These are right”, and goes away", async () => {
    refs.checks.current = q([kitchenCheck(), kitchenCheck({ staffId: "s4", fullName: "Madhava Das" })]);
    render(<StaffPage />);
    fireEvent.click(screen.getByRole("button", { name: "These are right" }));
    await waitFor(() => expect(mocks.confirmStaffKitchens).toHaveBeenCalledWith(["s1", "s4"], "test-token"));
    await waitFor(() => expect(screen.queryByText(HEADING)).toBeNull());
  });

  it("says a failed move in the usual error notice, and keeps the row", async () => {
    refs.checks.current = q([kitchenCheck()]);
    mocks.setStaffKitchen.mockRejectedValue(
      new ApiError(
        { code: "KMS-400109", message: "That kitchen has been archived.", action: "Choose another.", fieldErrors: [] },
        409
      )
    );
    render(<StaffPage />);
    const card = screen.getByRole("region", { name: HEADING });
    fireEvent.change(within(card).getByRole("combobox", { name: "Kitchen for Gopal Das" }), {
      target: { value: "k2" },
    });
    const alert = await within(card).findByRole("alert");
    expect(alert).toHaveTextContent("KMS-400109");
    expect(within(card).getByText("Gopal Das")).toBeInTheDocument();
  });
});

describe("the planner's door, for somebody whose kitchen does not plan meals here", () => {
  function guarded(canPlanMeals: boolean | undefined) {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "KITCHEN_STAFF", userId: "me", canPlanMeals, temples: [] },
    };
    return render(
      <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
        <p>the planner</p>
      </RequireRole>
    );
  }

  it("refuses every planner address the way it refuses a wrong role", () => {
    for (const path of ["/planner", "/planner/2026-09-19", "/planner/reuse", "/planner/meal/m1"]) {
      window.history.pushState({}, "", path);
      const { unmount } = guarded(false);
      expect(screen.getByRole("heading", { name: "Not your page" })).toBeInTheDocument();
      expect(screen.queryByText("the planner")).toBeNull();
      // And the menu drawn beside the refusal does not offer the planner either.
      expect(screen.queryByRole("link", { name: /meal planner/i })).toBeNull();
      unmount();
    }
  });

  it("lets the same person through everywhere else", () => {
    window.history.pushState({}, "", "/today");
    guarded(false);
    expect(screen.getByText("the planner")).toBeInTheDocument();
  });

  it("lets somebody the planner is open to in, and an older session that never said", () => {
    window.history.pushState({}, "", "/planner");
    const first = guarded(true);
    expect(screen.getByText("the planner")).toBeInTheDocument();
    first.unmount();
    guarded(undefined);
    expect(screen.getByText("the planner")).toBeInTheDocument();
  });
});
