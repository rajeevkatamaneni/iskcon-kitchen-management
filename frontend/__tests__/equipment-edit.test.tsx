import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ApiError, type EquipmentView } from "@/lib/api";

/**
 * Correcting an equipment record, and the question asked before one is scrapped (docket M7).
 *
 * <p>Two faults, one register. A serial number typed with a transposed digit was permanent, because
 * nothing had ever called the `PUT` that has always accepted exactly those descriptive fields. And
 * `SCRAPPED` sat one row below *In repair* in a plain dropdown while being the only value of the
 * four that the service refuses to move off again — so a misclick ended a machine's record.
 *
 * <p>What is asserted here is the pair of them and, just as hard, their limits: the edit screen
 * offers no condition and no service interval, because the server refuses both on that endpoint;
 * and the three reversible conditions are still sent on one press, because a dialog in front of a
 * reversible act is a dialog people learn to click past.
 */

const { authRef, queryRef, updateMock, conditionMock, pushMock, replaceMock, paramsRef } =
  vi.hoisted(() => ({
    authRef: {
      current: { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } } as {
        status: string;
        appUser: { role: string; userId: string } | null;
      },
    },
    queryRef: {
      current: {
        data: null as { equipment: EquipmentView; services: unknown[]; history: unknown[] } | null,
        error: null as ApiError | null,
        loading: false,
      },
    },
    updateMock: vi.fn(),
    conditionMock: vi.fn(),
    pushMock: vi.fn(),
    replaceMock: vi.fn(),
    paramsRef: { current: new URLSearchParams() },
  }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
  useSearchParams: () => paramsRef.current,
  useParams: () => ({ id: "eq-1" }),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ ...queryRef.current, reload: vi.fn() }),
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      updateEquipment: updateMock,
      changeEquipmentCondition: conditionMock,
    },
  };
});

import EditEquipmentPage from "@/app/equipment/[id]/edit/page";
import EquipmentItemPage from "@/app/equipment/[id]/page";

function machine(o: Partial<EquipmentView> = {}): EquipmentView {
  return {
    id: "eq-1",
    name: "Wet Grinder 10L",
    storageLocation: "Main kitchen",
    condition: "GOOD",
    acquisitionDate: "2024-01-10",
    source: "PURCHASED",
    notes: "Belt replaced in 2025.",
    createdAt: "2026-08-01T00:00:00Z",
    serialNumber: "WG-4471",
    purchaseCostInr: 48000,
    warrantyExpiry: "2027-01-10",
    serviceIntervalDays: 180,
    serviceIntervalUnit: "MONTHS",
    serviceIntervalCount: 6,
    serviceCompany: "Sharma Engineering",
    serviceCompanyPhone: "+919876500011",
    lastServicedOn: "2026-01-01",
    nextServiceOn: "2026-07-01",
    nextServiceBasis: "SERVICED",
    serviceStatus: "OVERDUE",
    ...o,
  };
}

/** What `api.getEquipment` hands back: the record and its two histories. */
function record(o: Partial<EquipmentView> = {}) {
  return { equipment: machine(o), services: [], history: [] };
}

beforeEach(() => {
  authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
  queryRef.current = { data: record(), error: null, loading: false };
  paramsRef.current = new URLSearchParams();
  updateMock.mockReset().mockResolvedValue(undefined);
  conditionMock.mockReset().mockResolvedValue(undefined);
  pushMock.mockReset();
  replaceMock.mockReset();
});

describe("editing an equipment record", () => {
  it("opens on what the register already says", () => {
    render(<EditEquipmentPage />);

    expect(screen.getByRole("heading", { name: "Edit equipment" })).toBeInTheDocument();
    expect(screen.getByLabelText(/^name$/i)).toHaveValue("Wet Grinder 10L");
    expect(screen.getByLabelText(/serial number/i)).toHaveValue("WG-4471");
    expect(screen.getByLabelText(/where it lives/i)).toHaveValue("Main kitchen");
    expect(screen.getByLabelText(/warranty runs to/i)).toHaveValue("2027-01-10");
    expect(screen.getByLabelText(/^notes$/i)).toHaveValue("Belt replaced in 2025.");
  });

  it("corrects a serial number and sends it back to the record", async () => {
    render(<EditEquipmentPage />);

    fireEvent.change(screen.getByLabelText(/serial number/i), { target: { value: "WG-4417" } });
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(updateMock).toHaveBeenCalledTimes(1));
    expect(updateMock.mock.calls[0][0]).toBe("eq-1");
    expect(updateMock.mock.calls[0][1]).toEqual({
      name: "Wet Grinder 10L",
      storageLocation: "Main kitchen",
      acquisitionDate: "2024-01-10",
      source: "PURCHASED",
      notes: "Belt replaced in 2025.",
      serialNumber: "WG-4417",
      purchaseCostInr: 48000,
      warrantyExpiry: "2027-01-10",
    });

    // Rule 8: the confirmation waits on the record, which is where the corrected number shows.
    await waitFor(() =>
      expect(pushMock).toHaveBeenCalledWith("/equipment/eq-1?saved=Wet%20Grinder%2010L")
    );
  });

  it("clears a field somebody empties, rather than sending an empty string", async () => {
    render(<EditEquipmentPage />);

    fireEvent.change(screen.getByLabelText(/serial number/i), { target: { value: "  " } });
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(updateMock).toHaveBeenCalledTimes(1));
    expect(updateMock.mock.calls[0][1]).toMatchObject({ serialNumber: null });
  });

  it("offers neither condition nor the service schedule, because the endpoint refuses both", () => {
    // Condition moves only through a recorded change with a reason; the interval, the company and
    // its phone number are the administrator's and travel on their own endpoint (E3-S10 D10).
    // UpdateEquipmentRequest names both exclusions and its reasons for them.
    render(<EditEquipmentPage />);

    expect(screen.queryByLabelText(/^condition$/i)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/how often/i)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/interval unit/i)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/service company/i)).not.toBeInTheDocument();
  });

  it("offers Cancel back to the record, and no way out that is not Cancel", () => {
    render(<EditEquipmentPage />);
    expect(screen.getByRole("link", { name: /^cancel$/i })).toHaveAttribute(
      "href",
      "/equipment/eq-1"
    );
    expect(screen.queryByRole("button", { name: /close/i })).not.toBeInTheDocument();
  });

  it("says so in plain words when the save is refused, and stays put", async () => {
    updateMock.mockRejectedValue(
      new ApiError(
        {
          code: "KMS-400011",
          message: "That name is already on the register.",
          action: "Give it a name that tells the two apart.",
          fieldErrors: [],
        },
        409
      )
    );
    render(<EditEquipmentPage />);

    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    // The server's own words, and its code, rather than anything technical.
    await waitFor(() =>
      expect(screen.getByText(/already on the register/i)).toBeInTheDocument()
    );
    expect(screen.getByText("KMS-400011")).toBeInTheDocument();
    expect(pushMock).not.toHaveBeenCalled();
  });

  it("refuses a role the register is not for", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } };
    render(<EditEquipmentPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});

describe("the record page, after an edit", () => {
  it("offers the way in to the edit screen", () => {
    render(<EquipmentItemPage />);
    expect(screen.getByRole("link", { name: /edit details/i })).toHaveAttribute(
      "href",
      "/equipment/eq-1/edit"
    );
  });

  it("carries the confirmation back from the edit screen and takes it out of the URL", async () => {
    paramsRef.current = new URLSearchParams("saved=Wet+Grinder+10L");
    render(<EquipmentItemPage />);

    expect(await screen.findByText(/Wet Grinder 10L has been updated/i)).toBeInTheDocument();
    // Captured once and replaced, so a reload does not show it again.
    await waitFor(() => expect(replaceMock).toHaveBeenCalledWith("/equipment/eq-1"));
  });
});

describe("scrapping a machine", () => {
  /** Open *Change condition*, pick a value, give the reason, and press the commit button. */
  function commitCondition(condition: string, reason: string) {
    fireEvent.click(screen.getByRole("button", { name: /change condition/i }));
    fireEvent.change(screen.getByLabelText(/new condition/i), { target: { value: condition } });
    fireEvent.change(screen.getByLabelText(/^why$/i), { target: { value: reason } });
    fireEvent.click(screen.getByRole("button", { name: /record the change/i }));
  }

  it("asks before scrapping, and names the consequence", () => {
    render(<EquipmentItemPage />);
    commitCondition("SCRAPPED", "Motor burnt out, beyond repair");

    // Nothing has been sent yet — the question comes first.
    expect(conditionMock).not.toHaveBeenCalled();

    const dialog = screen.getByRole("dialog");
    expect(dialog).toHaveAttribute("aria-modal", "true");
    // The consequence in the words the reader needs, not "are you sure".
    expect(dialog).toHaveTextContent(/takes it off the equipment list/i);
    expect(dialog).toHaveTextContent(/condition can no longer be changed here/i);
    expect(dialog).toHaveTextContent(/Scrap Wet Grinder 10L\?/i);

    // What it must NOT say any more, since D-15 shipped the way back. These two claims were true
    // when the dialog was written and stopped being true the day reinstatement landed, and a
    // sentence that is wrong is worse than one that is missing, because the reader acts on it.
    expect(dialog).not.toHaveTextContent(/cannot be undone/i);
    expect(dialog).not.toHaveTextContent(/never be changed again/i);
  });

  it("sends the change only once it has been confirmed, reason and all", async () => {
    render(<EquipmentItemPage />);
    commitCondition("SCRAPPED", "Motor burnt out, beyond repair");

    fireEvent.click(screen.getByRole("button", { name: /^scrap it$/i }));

    await waitFor(() => expect(conditionMock).toHaveBeenCalledTimes(1));
    expect(conditionMock.mock.calls[0][0]).toBe("eq-1");
    expect(conditionMock.mock.calls[0][1]).toEqual({
      condition: "SCRAPPED",
      reason: "Motor burnt out, beyond repair",
    });
  });

  it("sends nothing at all when the question is answered no", () => {
    render(<EquipmentItemPage />);
    commitCondition("SCRAPPED", "Misread the row");

    // Scoped to the dialog: the form behind it has a Cancel of its own, which is the point —
    // answering the question no must not throw away what was typed.
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: /^cancel$/i }));

    expect(conditionMock).not.toHaveBeenCalled();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    // What was typed is still there: cancelling the question costs the click and nothing else.
    expect(screen.getByLabelText(/^why$/i)).toHaveValue("Misread the row");
  });

  it("names the way back without offering it", () => {
    // Since D-15 there is a way back, and the dialog is where that fact has to land honestly. It
    // says the way back exists and that it is not the reader's to take, and it offers no control
    // that would take it: an undo reachable from inside the act it reverses is an undo nobody
    // would think twice before pressing, which is exactly what this dialog exists to prevent.
    render(<EquipmentItemPage />);
    commitCondition("SCRAPPED", "Motor burnt out, beyond repair");

    const dialog = screen.getByRole("dialog");
    expect(dialog).toHaveTextContent(/only a temple admin can bring it back/i);
    expect(dialog).toHaveTextContent(/must record why/i);

    // Two ways out and no third: answer the question, or do not.
    expect(within(dialog).getAllByRole("button").map((b) => b.textContent)).toEqual([
      "Cancel",
      "Scrap it",
    ]);
  });

  it("still steers a merely broken machine to Needs repair", () => {
    // The sentence that stops most wrong scrappings before they happen. It survived the D-15
    // rewrite word for word, and it is the last thing in the dialog on purpose.
    render(<EquipmentItemPage />);
    commitCondition("SCRAPPED", "Motor burnt out, beyond repair");

    expect(screen.getByRole("dialog")).toHaveTextContent(
      /if the machine is only broken, choose\s+Needs repair\s+instead — that one can be taken back/i
    );
  });

  it("asks nothing before a condition that can be taken back", async () => {
    // Three of the four values are reversible by choosing another one tomorrow. A dialog in front
    // of those is a dialog people learn to dismiss without reading, which would blunt the one that
    // matters.
    for (const condition of ["GOOD", "NEEDS_REPAIR", "IN_REPAIR"]) {
      conditionMock.mockReset().mockResolvedValue(undefined);
      const view = render(<EquipmentItemPage />);
      commitCondition(condition, "Checked it over");

      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      await waitFor(() => expect(conditionMock).toHaveBeenCalledTimes(1));
      expect(conditionMock.mock.calls[0][1]).toMatchObject({ condition });
      view.unmount();
    }
  });
});
