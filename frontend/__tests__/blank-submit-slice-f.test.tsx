import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { AuditPage as AuditPageData, Profile } from "@/lib/api";

/*
 * T-166, slice F of the blank-required-fields wave. Two forms whose screen tests stub the one query
 * they need and nothing else, so their Form tests live here with the stubs they need.
 *
 * Rajeev's ruling, 2026-09-11: "Required fields should carry `required` on the element and if left
 * unfilled, we should at least show 'Required' in red on form submit. Ideally, we should say
 * 'Quantity is required' OR 'Note is required'."
 *
 * - Asking for time off, on the profile page: both days carry `required`, so a blank one is named.
 * - The audit log's filter: no box carries `required`, since every filter is optional. What it does
 *   check is the date range's own rule, a last date no earlier than the first, which `DateRange`
 *   writes as `min` on the second box.
 *
 * The query stub keeps the last fetcher it was handed, so a test can ask which filters the audit
 * page would now fetch with, rather than inferring it from a re-render.
 */
const { authRef, queryRef, lastFetcher, myLeave, requestLeave, communicationPreferences, listAuditEvents } =
  vi.hoisted(() => ({
    authRef: {
      current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", fullName: "Test Person" } },
    },
    queryRef: { current: { data: null as unknown, error: null, loading: false } },
    lastFetcher: { current: null as ((token: string | undefined) => Promise<unknown>) | null },
    myLeave: vi.fn(),
    requestLeave: vi.fn(),
    communicationPreferences: vi.fn(),
    listAuditEvents: vi.fn(),
  }));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fetcher: (token: string | undefined) => Promise<unknown>) => {
    lastFetcher.current = fetcher;
    return queryRef.current;
  },
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, myLeave, requestLeave, communicationPreferences, listAuditEvents },
  };
});

import ProfilePage from "@/app/profile/page";
import AuditPage from "@/app/audit/page";

function profile(): Profile {
  return {
    fullName: "Radha Devi",
    email: "radha@example.com",
    phone: "+919876543210",
    preferredChannel: "WHATSAPP",
    consentAt: null,
    consentVersion: null,
    consentNeeded: true,
    currentConsentVersion: "1",
    consentText: "I agree that my temple may send me reminders.",
    role: "TEMPLE_ADMIN",
  };
}

describe("asking for time off, on the profile page (T-166)", () => {
  beforeEach(() => {
    queryRef.current = { data: profile(), error: null, loading: false };
    myLeave.mockReset().mockResolvedValue([]);
    requestLeave.mockReset().mockResolvedValue(undefined);
    // Never answers: the messages section is not what is under test, and it renders nothing until it has.
    communicationPreferences.mockReset().mockReturnValue(new Promise(() => {}));
  });

  async function openTheForm() {
    render(<ProfilePage />);
    fireEvent.click(await screen.findByRole("button", { name: /ask for time off/i }));
    return screen.getByRole("form", { name: /ask for time off/i });
  }

  it("names both blank days in red beside their boxes, and asks for nothing", async () => {
    const form = await openTheForm();

    fireEvent.click(within(form).getByRole("button", { name: /send it/i }));

    expectSaidBeside(within(form).getByLabelText(/first day/i), "First day is required");
    expectSaidBeside(within(form).getByLabelText(/last day/i), "Last day is required");
    await settle();
    expect(requestLeave).not.toHaveBeenCalled();
  });

  it("sends the request once both days are given, so the refusal is not a dead end", async () => {
    const form = await openTheForm();

    fireEvent.change(within(form).getByLabelText(/first day/i), { target: { value: "2026-09-20" } });
    fireEvent.change(within(form).getByLabelText(/last day/i), { target: { value: "2026-09-21" } });
    fireEvent.click(within(form).getByRole("button", { name: /send it/i }));

    await waitFor(() =>
      expect(requestLeave).toHaveBeenCalledWith(
        expect.objectContaining({ fromDate: "2026-09-20", toDate: "2026-09-21", leaveType: "TIME_OFF" }),
        "test-token"
      )
    );
  });
});

describe("the audit log's filter (T-166)", () => {
  beforeEach(() => {
    const empty: AuditPageData = { events: [], nextCursor: null };
    queryRef.current = { data: empty, error: null, loading: false };
    listAuditEvents.mockReset().mockResolvedValue(empty);
  });

  it("has no required box, so an empty Apply shows no sentence and applies the empty filters", async () => {
    render(<AuditPage />);
    const form = screen.getByRole("form", { name: /filter the audit log/i });

    fireEvent.click(within(form).getByRole("button", { name: /apply/i }));

    expect(screen.queryByText(/is required|must be/i)).not.toBeInTheDocument();
    await lastFetcher.current!("test-token");
    expect(listAuditEvents).toHaveBeenLastCalledWith({ from: "", to: "", action: "" }, "test-token");
  });

  it("names a To date before the From date, and applies nothing", async () => {
    render(<AuditPage />);
    const form = screen.getByRole("form", { name: /filter the audit log/i });

    // From first: moving From past an existing To carries To along with it, which is DateRange's
    // other rule, so a backwards range can only be typed in this order.
    fireEvent.change(within(form).getByLabelText(/^from$/i), { target: { value: "2026-09-10" } });
    fireEvent.change(within(form).getByLabelText(/^to$/i), { target: { value: "2026-09-01" } });
    fireEvent.click(within(form).getByRole("button", { name: /apply/i }));

    expectSaidBeside(within(form).getByLabelText(/^to$/i), /^To must be on or after 10 Sept? 2026$/);
    await lastFetcher.current!("test-token");
    expect(listAuditEvents).toHaveBeenLastCalledWith({}, "test-token");
  });
});

/**
 * The sentence Form puts beside a refused box. Checked three ways so that "beside" means something:
 * the box is marked invalid, it is described by that very sentence, and the sentence's slot sits
 * straight after the box, or after the label wrapping it.
 */
function expectSaidBeside(box: HTMLElement, sentence: string | RegExp) {
  const said = screen.getByText(sentence);
  expect(box).toHaveAttribute("aria-invalid", "true");
  expect(box.getAttribute("aria-describedby")?.split(" ")).toContain(said.id);
  expect((box.closest("label") ?? box).nextElementSibling).toBe(said.parentElement);
}

/** Lets a handler that awaits a token reach its API call, so "not called" is not merely "not yet". */
function settle() {
  return new Promise((resolve) => setTimeout(resolve, 0));
}
