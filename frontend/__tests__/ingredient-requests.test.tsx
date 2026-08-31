import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import type { IngredientRequestSummary } from "@/lib/api";

const { authRef, listMock } = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "KITCHEN_STAFF", userId: "u1", fullName: "Radha" },
      getToken: async () => "token",
      refresh: () => {},
    } as {
      status: string;
      appUser: { role: string; userId?: string; fullName?: string } | null;
      getToken: () => Promise<string>;
      refresh: () => void;
    },
  },
  listMock: vi.fn(),
}));

const { pushMock, replaceMock, paramsRef } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  replaceMock: vi.fn(),
  paramsRef: { current: new URLSearchParams() },
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
  useSearchParams: () => paramsRef.current,
  useParams: () => ({ id: "ir1" }),
}));
// A stable object, not a fresh one per render: useAuthedQuery depends on getToken's identity.
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, listIngredientRequests: listMock } };
});

import IngredientRequestsPage from "@/app/ingredient-requests/page";

function row(overrides: Partial<IngredientRequestSummary> = {}): IngredientRequestSummary {
  return {
    id: "ir1",
    reference: "IR-2026-0041",
    kitchenId: "k1",
    kitchenName: "Prasadam kitchen",
    neededOn: "2026-09-04",
    purpose: "Janmashtami feast",
    status: "SUBMITTED",
    requestedBy: "u1",
    requestedByName: "Radha",
    submittedAt: "2026-08-30T09:00:00Z",
    decidedByName: null,
    decidedAt: null,
    issuedAt: null,
    lineCount: 3,
    dishCount: 2,
    ...overrides,
  };
}

function staff() {
  return {
    status: "signed-in",
    appUser: { role: "KITCHEN_STAFF", userId: "u1", fullName: "Radha" },
    getToken: async () => "token",
    refresh: () => {},
  };
}

describe("the ingredient requests list", () => {
  beforeEach(() => {
    authRef.current = staff();
    paramsRef.current = new URLSearchParams();
    pushMock.mockReset();
    replaceMock.mockReset();
    listMock.mockReset().mockResolvedValue([row(), row({ id: "ir2", reference: "IR-2026-0040" })]);
  });

  it("lists every request with its kitchen, date, requester and status", async () => {
    render(<IngredientRequestsPage />);

    expect(await screen.findByText("IR-2026-0041")).toBeInTheDocument();
    const table = within(screen.getByRole("table"));
    expect(table.getByText("IR-2026-0040")).toBeInTheDocument();
    expect(table.getAllByText("Prasadam kitchen").length).toBe(2);
    expect(table.getAllByText("Radha").length).toBe(2);
    // The stored status is never what a person reads.
    expect(table.getAllByText("Awaiting review").length).toBe(2);
    expect(screen.queryByText("SUBMITTED")).not.toBeInTheDocument();
  });

  it("opens a request on its own screen", async () => {
    render(<IngredientRequestsPage />);
    expect((await screen.findByText("IR-2026-0041")).closest("a")).toHaveAttribute(
      "href",
      "/ingredient-requests/ir1"
    );
  });

  it("asks the server for everything when no filter is on", async () => {
    render(<IngredientRequestsPage />);
    await vi.waitFor(() => expect(listMock).toHaveBeenCalledWith(null, "token"));
  });

  it("puts the chosen filter in the address bar", async () => {
    render(<IngredientRequestsPage />);
    await screen.findByText("IR-2026-0041");

    fireEvent.click(screen.getByRole("tab", { name: "Awaiting review" }));
    expect(replaceMock).toHaveBeenCalledWith("/ingredient-requests?status=SUBMITTED");

    fireEvent.click(screen.getByRole("tab", { name: "Denied" }));
    expect(replaceMock).toHaveBeenCalledWith("/ingredient-requests?status=DENIED");
  });

  it("drops the parameter altogether on All, rather than writing status=ALL", async () => {
    paramsRef.current = new URLSearchParams("status=DENIED");
    render(<IngredientRequestsPage />);
    await vi.waitFor(() => expect(listMock).toHaveBeenCalledWith("DENIED", "token"));

    fireEvent.click(screen.getByRole("tab", { name: "All" }));
    expect(replaceMock).toHaveBeenCalledWith("/ingredient-requests");
  });

  it("opens on the filter a deep link names, and marks that tab as the one showing", async () => {
    paramsRef.current = new URLSearchParams("status=APPROVED");
    render(<IngredientRequestsPage />);

    await vi.waitFor(() => expect(listMock).toHaveBeenCalledWith("APPROVED", "token"));
    expect(screen.getByRole("tab", { name: "Approved" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: "All" })).toHaveAttribute("aria-selected", "false");
  });

  it("falls back to everything when the address names a status that is not one", async () => {
    paramsRef.current = new URLSearchParams("status=BANANA");
    render(<IngredientRequestsPage />);

    await vi.waitFor(() => expect(listMock).toHaveBeenCalledWith(null, "token"));
    expect(screen.getByRole("tab", { name: "All" })).toHaveAttribute("aria-selected", "true");
  });

  it("says something different for each empty filter", async () => {
    listMock.mockResolvedValue([]);
    render(<IngredientRequestsPage />);
    expect(await screen.findByText(/no requests yet/i)).toBeInTheDocument();
  });

  it("explains an empty review queue in terms of what would be in it", async () => {
    paramsRef.current = new URLSearchParams("status=SUBMITTED");
    listMock.mockResolvedValue([]);
    render(<IngredientRequestsPage />);

    expect(await screen.findByText(/nothing waiting for an answer/i)).toBeInTheDocument();
    // Raising one is not the answer to an empty review queue, so the empty state repeats no
    // button — only the one that stands in the header of every view.
    expect(screen.getAllByRole("link", { name: /new request/i }).length).toBe(1);
  });

  it("offers a way to raise one where an empty list is the reader's own to fill", async () => {
    listMock.mockResolvedValue([]);
    render(<IngredientRequestsPage />);

    await screen.findByText(/no requests yet/i);
    expect(screen.getAllByRole("link", { name: /new request/i }).length).toBe(2);
  });

  it("refuses a volunteer the page", () => {
    authRef.current = {
      ...staff(),
      appUser: { role: "VOLUNTEER", userId: "u9", fullName: "Guest" },
    };
    render(<IngredientRequestsPage />);

    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /ingredient requests/i })).not.toBeInTheDocument();
  });
});
