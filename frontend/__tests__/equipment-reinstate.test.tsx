import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ApiError, type EquipmentView } from "@/lib/api";

/**
 * Bringing a scrapped machine back (D-15), and the sentence that stopped being true when it shipped.
 *
 * <p>Rajeev's rule reads as one thing rather than two: the ordinary path stays closed, and there is
 * exactly one explicit, named, audited way back. On screen that means a scrapped item is offered no
 * condition change at all — the server refuses it, and a control that always errors teaches people
 * to distrust the screen — and is offered *Bring it back* instead, to the Temple Admin alone.
 *
 * <p>The other half of this file is copy, and it is not decoration. The scrapping confirmation said
 * "Scrapping cannot be undone. Its condition can never be changed again" — verbatim, and Rajeev read
 * it on staging on 2026-09-07. Both sentences became false the day reinstatement shipped, so they
 * were rewritten by the task that made them false, on the precedent set by MEAL_ALREADY_RECORDED: a
 * sentence that is wrong is worse than one that is missing, because the reader acts on it. What is
 * asserted here is that neither claim survives anywhere on the page, that what replaced them still
 * discourages a casual scrapping by naming the friction, and that the *Needs repair* steer — the
 * sentence that stops most wrong scrappings before they happen — is untouched.
 */

const { authRef, queryRef, reinstateMock, conditionMock, pushMock, replaceMock } = vi.hoisted(
  () => ({
    authRef: {
      current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
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
    reinstateMock: vi.fn(),
    conditionMock: vi.fn(),
    pushMock: vi.fn(),
    replaceMock: vi.fn(),
  })
);

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
  useSearchParams: () => new URLSearchParams(),
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
      reinstateEquipment: reinstateMock,
      changeEquipmentCondition: conditionMock,
    },
  };
});

import EquipmentItemPage from "@/app/equipment/[id]/page";

function machine(o: Partial<EquipmentView> = {}): EquipmentView {
  return {
    id: "eq-1",
    name: "Wet grinder, 10 litre",
    storageLocation: "Main kitchen",
    condition: "GOOD",
    acquisitionDate: "2024-01-10",
    source: "PURCHASED",
    notes: null,
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

function record(o: Partial<EquipmentView> = {}) {
  return { equipment: machine(o), services: [], history: [] };
}

/** A machine that has been written off — the only state from which the way back is offered. */
function scrapped() {
  return record({ condition: "SCRAPPED" });
}

beforeEach(() => {
  authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
  queryRef.current = { data: scrapped(), error: null, loading: false };
  reinstateMock.mockReset().mockResolvedValue(undefined);
  conditionMock.mockReset().mockResolvedValue(undefined);
  pushMock.mockReset();
  replaceMock.mockReset();
});

/** Open the panel and fill it in, the way somebody who found the machine under the filter would. */
function openReinstate() {
  fireEvent.click(screen.getByRole("button", { name: /bring it back/i }));
  return screen.getByRole("form", { name: /bring it back/i });
}

describe("bringing a scrapped machine back", () => {
  it("offers the way back on a scrapped machine, and no condition change", () => {
    render(<EquipmentItemPage />);

    expect(screen.getByRole("button", { name: /bring it back/i })).toBeInTheDocument();
    // The server refuses a condition change on a scrapped item with KMS-400043, and that has not
    // moved. A button that can only ever produce that refusal does not belong on the screen.
    expect(screen.queryByRole("button", { name: /change condition/i })).not.toBeInTheDocument();
  });

  it("offers no way back on a machine that is still in use", () => {
    // Nothing to reinstate: the server answers KMS-400124, and the screen should never ask it.
    queryRef.current = { data: record({ condition: "NEEDS_REPAIR" }), error: null, loading: false };
    render(<EquipmentItemPage />);

    expect(screen.queryByRole("button", { name: /bring it back/i })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /change condition/i })).toBeInTheDocument();
  });

  it("sends the condition it comes back in and the reason, once", async () => {
    render(<EquipmentItemPage />);
    const form = openReinstate();

    fireEvent.change(within(form).getByLabelText(/comes back as/i), {
      target: { value: "IN_REPAIR" },
    });
    fireEvent.change(within(form).getByLabelText(/why it is coming back/i), {
      target: { value: "No replacement to be had; the motor is being rewound" },
    });
    fireEvent.click(within(form).getByRole("button", { name: /put it back on the register/i }));

    await waitFor(() => expect(reinstateMock).toHaveBeenCalledTimes(1));
    expect(reinstateMock.mock.calls[0][0]).toBe("eq-1");
    expect(reinstateMock.mock.calls[0][1]).toEqual({
      condition: "IN_REPAIR",
      reason: "No replacement to be had; the motor is being rewound",
    });
    // Never through the condition endpoint. Two different acts, two different audit actions.
    expect(conditionMock).not.toHaveBeenCalled();
  });

  it("insists on a reason, because the audit trail is the whole point of the feature", () => {
    render(<EquipmentItemPage />);
    const form = openReinstate();

    expect(within(form).getByLabelText(/why it is coming back/i)).toBeRequired();
    expect(within(form).getByLabelText(/why it is coming back/i)).toHaveAttribute(
      "maxlength",
      "500"
    );
  });

  it("does not offer scrapped as a condition to come back in", () => {
    // It is what the item is coming *from*, the server refuses it, and a picker that offers a value
    // the server will reject is a picker people stop trusting.
    render(<EquipmentItemPage />);
    const form = openReinstate();

    const options = within(form)
      .getAllByRole("option")
      .map((o) => (o as HTMLOptionElement).value);
    expect(options).toEqual(["GOOD", "NEEDS_REPAIR", "IN_REPAIR"]);
  });

  it("opens on needs repair rather than good", () => {
    // A machine that was written off is rarely in good order the day it comes back, and a default
    // of Good would return a broken machine as working on one press and no thought.
    render(<EquipmentItemPage />);
    const form = openReinstate();

    expect(within(form).getByLabelText(/comes back as/i)).toHaveValue("NEEDS_REPAIR");
  });

  it("is the Temple Admin's alone — a kitchen manager who scrapped it cannot undo it", () => {
    // Both roles hold MANAGE_INVENTORY and both can scrap a machine. Neither may bring one back,
    // and that asymmetry is the entire reason REINSTATE_SCRAPPED_EQUIPMENT exists.
    for (const role of ["KITCHEN_MANAGER", "KITCHEN_STAFF"]) {
      authRef.current = { status: "signed-in", appUser: { role, userId: "me" } };
      const view = render(<EquipmentItemPage />);

      expect(screen.queryByRole("button", { name: /bring it back/i })).not.toBeInTheDocument();
      expect(screen.queryByRole("button", { name: /change condition/i })).not.toBeInTheDocument();
      view.unmount();
    }
  });
});

describe("what the scrapping confirmation is now allowed to claim", () => {
  /** Walk a scrapping as far as the question, from a machine that is still in use. */
  function askTheQuestion() {
    queryRef.current = { data: record(), error: null, loading: false };
    render(<EquipmentItemPage />);
    fireEvent.click(screen.getByRole("button", { name: /change condition/i }));
    fireEvent.change(screen.getByLabelText(/new condition/i), { target: { value: "SCRAPPED" } });
    fireEvent.change(screen.getByLabelText(/^why$/i), { target: { value: "Motor burnt out" } });
    fireEvent.click(screen.getByRole("button", { name: /record the change/i }));
    return screen.getByRole("dialog");
  }

  it("no longer says scrapping cannot be undone, anywhere on the page", () => {
    const dialog = askTheQuestion();

    expect(dialog).not.toHaveTextContent(/cannot be undone/i);
    expect(dialog).not.toHaveTextContent(/never be changed again/i);
    // And not merely moved out of the dialog into the prose around it.
    expect(document.body).not.toHaveTextContent(/cannot be undone/i);
    expect(document.body).not.toHaveTextContent(/never be changed again/i);
  });

  it("still discourages a casual scrapping, by naming the friction rather than an impossibility", () => {
    const dialog = askTheQuestion();

    expect(dialog).toHaveTextContent(/takes it off the equipment list/i);
    expect(dialog).toHaveTextContent(/condition can no longer be changed here/i);
    // The way back exists, it is not this reader's, and it costs a written reason. All three facts
    // matter: an undo the reader can reach for themselves would not discourage anything.
    expect(dialog).toHaveTextContent(/only a temple admin can bring it back/i);
    expect(dialog).toHaveTextContent(/must record why/i);
  });

  it("keeps the two sentences that were true all along", () => {
    const dialog = askTheQuestion();

    expect(dialog).toHaveTextContent(
      /the record stays on the register with everything written against it/i
    );
    expect(dialog).toHaveTextContent(
      /if the machine is only broken, choose\s+Needs repair\s+instead — that one can be taken back/i
    );
  });

  it("still asks before anything is sent, and still offers no undo of its own", () => {
    const dialog = askTheQuestion();

    expect(conditionMock).not.toHaveBeenCalled();
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(within(dialog).getAllByRole("button").map((b) => b.textContent)).toEqual([
      "Cancel",
      "Scrap it",
    ]);
  });
});
