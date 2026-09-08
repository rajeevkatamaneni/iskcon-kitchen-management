import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { VendorDetailView, VendorView } from "@/lib/api";

/**
 * A vendor you walk into has no WhatsApp number (T-025, D-2), on the three screens that touch one.
 *
 * <p>None of these three failures would have shown up in `tsc --noEmit` once `phone` became
 * nullable, which is the reason they each get a test rather than a read-through:
 *
 * <ul>
 *   <li>the list rendered `{v.phone}` bare, and React renders null as nothing — an empty cell, for
 *       exactly the vendor this feature exists to create;
 *   <li>the add screen sent the phone box straight through `.trim()` while every other optional
 *       field went through `emptyToNull`, so a blank box posted `""`, which fails the E.164 pattern
 *       and refused the vendor;
 *   <li>both forms marked the box `required`, so the browser refused before the server ever saw it.
 * </ul>
 *
 * <p>The payload assertions inspect `Object.keys(...)` and then the value. `objectContaining` cannot
 * be used for this: a missing property and an explicit null read identically to it, and "the key is
 * absent" and "the key is null" are different requests to a server whose update endpoint overwrites
 * every column it is given.
 */

const { authRef } = vi.hoisted(() => ({
  // A stable object, not a fresh one per render: useAuthedQuery depends on getToken's identity.
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "KITCHEN_STAFF", userId: "me" },
      getToken: async () => "test-token",
      refresh: () => {},
    } as {
      status: string;
      appUser: { role: string; userId: string } | null;
      getToken: () => Promise<string>;
      refresh: () => void;
    },
  },
}));

const { pushMock, replaceMock, paramsRef } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  replaceMock: vi.fn(),
  paramsRef: { current: new URLSearchParams() },
}));

const { listVendorsMock, getVendorMock, listIngredientsMock, createVendorMock, updateVendorMock } =
  vi.hoisted(() => ({
    listVendorsMock: vi.fn(),
    getVendorMock: vi.fn(),
    listIngredientsMock: vi.fn(),
    createVendorMock: vi.fn(),
    updateVendorMock: vi.fn(),
  }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
  useSearchParams: () => paramsRef.current,
  useParams: () => ({ id: "v1" }),
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      listVendors: listVendorsMock,
      getVendor: getVendorMock,
      listIngredients: listIngredientsMock,
      createVendor: createVendorMock,
      updateVendor: updateVendorMock,
    },
  };
});

import VendorsPage from "@/app/vendors/page";
import NewVendorPage from "@/app/vendors/new/page";
import VendorDetailPage from "@/app/vendors/[id]/page";

/** The hardware shop on the corner: a real vendor, with nowhere to send a purchase order. */
function walkIn(overrides: Partial<VendorView> = {}): VendorView {
  return {
    id: "v1",
    name: "Corner Hardware",
    contactPerson: null,
    phone: null,
    email: null,
    address: null,
    gstin: null,
    preferredLanguage: "en",
    notes: null,
    contractEndDate: null,
    contractEndingSoon: false,
    active: true,
    whatsappReachable: true,
    createdAt: "2026-08-01T00:00:00Z",
    ...overrides,
  };
}

function detail(vendor: VendorView): VendorDetailView {
  return { vendor, supplies: [], statusHistory: [] };
}

/**
 * The phone box, and not the "i" beside it.
 *
 * <p>{@link HintedField} gives its hint button the accessible name "More about Phone", so a bare
 * `getByLabelText(/phone/i)` matches two elements and throws. InfoHint's own comment says as much:
 * naming the field in the button is what stops a screen reader hearing a row of anonymous "more
 * information" buttons, and the cost is that a test on a hinted field states which element it means.
 */
const PHONE_BOX = { selector: "input" } as const;

/** The single argument the screen posted, whichever call it was. */
function payload(mock: ReturnType<typeof vi.fn>, index: number): Record<string, unknown> {
  return mock.mock.calls[0][index] as Record<string, unknown>;
}

beforeEach(() => {
  authRef.current = {
    status: "signed-in",
    appUser: { role: "KITCHEN_STAFF", userId: "me" },
    getToken: async () => "test-token",
    refresh: () => {},
  };
  paramsRef.current = new URLSearchParams();
  pushMock.mockReset();
  replaceMock.mockReset();
  listVendorsMock.mockReset().mockResolvedValue([walkIn()]);
  getVendorMock.mockReset().mockResolvedValue(detail(walkIn()));
  listIngredientsMock.mockReset().mockResolvedValue([]);
  createVendorMock.mockReset().mockResolvedValue({ id: "v1" });
  updateVendorMock.mockReset().mockResolvedValue(undefined);
});

describe("the vendor list", () => {
  it("says an em-dash for a vendor with no number, rather than leaving the cell blank", async () => {
    render(<VendorsPage />);

    const name = await screen.findByRole("link", { name: "Corner Hardware" });
    // The row, not the page: an em-dash elsewhere on the screen would satisfy a bare getByText.
    const row = name.closest("tr");
    expect(row).not.toBeNull();
    const cells = Array.from(row!.querySelectorAll("td")).map((c) => c.textContent);
    // Vendor, Phone, Language, Contract ends, Status, Actions — the phone is the second.
    expect(cells[1]).toBe("—");
  });

  it("still prints a real number for a vendor that has one", async () => {
    listVendorsMock.mockResolvedValue([walkIn({ name: "Govind Wholesale", phone: "+919845012303" })]);
    render(<VendorsPage />);

    const name = await screen.findByRole("link", { name: "Govind Wholesale" });
    const cells = Array.from(name.closest("tr")!.querySelectorAll("td")).map((c) => c.textContent);
    expect(cells[1]).toBe("+919845012303");
  });
});

describe("adding a vendor with no number", () => {
  it("does not demand a phone, and posts an explicit null when the box is left blank", async () => {
    render(<NewVendorPage />);

    const phone = screen.getByLabelText(/phone/i, PHONE_BOX);
    expect(phone).not.toBeRequired();

    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Corner Hardware" } });
    fireEvent.submit(screen.getByRole("form", { name: /add a vendor/i }));

    await waitFor(() => expect(createVendorMock).toHaveBeenCalled());
    const body = payload(createVendorMock, 0);
    // The key is there, and it is null. An absent key and a null one are indistinguishable to
    // objectContaining, and only one of them is what this screen means to say.
    expect(Object.keys(body)).toContain("phone");
    expect(body.phone).toBeNull();
    expect(body.name).toBe("Corner Hardware");
  });

  it("sends a number that was typed, trimmed, and still refuses to invent one", async () => {
    render(<NewVendorPage />);

    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Govind Wholesale" } });
    fireEvent.change(screen.getByLabelText(/phone/i, PHONE_BOX), { target: { value: "  +919845012303  " } });
    fireEvent.submit(screen.getByRole("form", { name: /add a vendor/i }));

    await waitFor(() => expect(createVendorMock).toHaveBeenCalled());
    expect(payload(createVendorMock, 0).phone).toBe("+919845012303");
  });
});

describe("editing a vendor with no number", () => {
  it("renders the empty box without demanding one, and saves the null back", async () => {
    render(<VendorDetailPage />);

    const phone = await screen.findByLabelText(/phone/i, PHONE_BOX);
    expect(phone).toHaveValue("");
    expect(phone).not.toBeRequired();

    fireEvent.submit(screen.getByRole("form", { name: /edit vendor/i }));

    await waitFor(() => expect(updateVendorMock).toHaveBeenCalled());
    const body = payload(updateVendorMock, 1);
    expect(Object.keys(body)).toContain("phone");
    expect(body.phone).toBeNull();
    expect(body.name).toBe("Corner Hardware");
  });

  it("lets a number be added to a walk-in vendor later", async () => {
    render(<VendorDetailPage />);

    const phone = await screen.findByLabelText(/phone/i, PHONE_BOX);
    fireEvent.change(phone, { target: { value: "+919845012303" } });
    fireEvent.submit(screen.getByRole("form", { name: /edit vendor/i }));

    await waitFor(() => expect(updateVendorMock).toHaveBeenCalled());
    expect(payload(updateVendorMock, 1).phone).toBe("+919845012303");
  });

  it("leaves a vendor that has a number exactly as it was", async () => {
    getVendorMock.mockResolvedValue(
      detail(walkIn({ name: "Govind Wholesale", phone: "+919845012303" }))
    );
    render(<VendorDetailPage />);

    expect(await screen.findByLabelText(/phone/i, PHONE_BOX)).toHaveValue("+919845012303");

    fireEvent.submit(screen.getByRole("form", { name: /edit vendor/i }));
    await waitFor(() => expect(updateVendorMock).toHaveBeenCalled());
    expect(payload(updateVendorMock, 1).phone).toBe("+919845012303");
  });
});
