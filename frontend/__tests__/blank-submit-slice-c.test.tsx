import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { EquipmentView, VendorDetailView, VendorView } from "@/lib/api";
import { dateWithYear, todayIso } from "@/lib/format";

/**
 * Blank required boxes on the vendor, equipment and kitchen forms are named in red (T-163).
 *
 * <p>Rajeev, 2026-09-11: *"Required fields should carry `required` on the element and if left
 * unfilled, we should at least show 'Required' in red on form submit. Ideally, we should say
 * 'Quantity is required' OR 'Note is required'."* T-160 built `Form` to do that; this slice swaps
 * the ten forms of these seven files onto it, and this file is the proof that each one now says
 * which box is wrong, beside that box, and sends nothing.
 *
 * <p>Every test presses the form's real submit button, never `fireEvent.submit`, for the reason
 * T-160 established: jsdom refuses a click-driven submit from a form without `novalidate`, so a
 * click is what tells a `Form` apart from a plain `<form>`. A dispatched submit event skips that
 * check altogether and would pass against either. The one exception is the vendor status dialog,
 * and its test says why.
 *
 * <p>Out-of-range values are typed with `fireEvent.change`, never seeded through `defaultValue`,
 * because jsdom does not step- or range-check a value that arrived as the `value` attribute.
 *
 * <p>The real `useAuthedQuery` runs against a mocked `api`, so each screen loads the way it does in
 * the browser rather than being handed its data.
 */

const { authRef, router, mocks } = vi.hoisted(() => ({
  router: { push: vi.fn(), replace: vi.fn() },
  // One stable object: useAuthedQuery lists getToken in its effect dependencies, and a fresh
  // closure per render would re-fetch for ever.
  authRef: {
    current: {
      status: "signed-in",
      appUser: {
        userId: "me",
        fullName: "Gopal Das",
        role: "TEMPLE_ADMIN",
        tenantName: "Bengaluru Temple",
        tenantSlug: "bengaluru",
        temples: [],
      },
      getToken: async () => "test-token",
      refresh: () => {},
      signOut: () => {},
      switchTemple: () => {},
    } as Record<string, unknown>,
  },
  mocks: {
    listVendors: vi.fn(),
    getVendor: vi.fn(),
    listIngredients: vi.fn(),
    createVendor: vi.fn(),
    updateVendor: vi.fn(),
    setVendorSupply: vi.fn(),
    deactivateVendor: vi.fn(),
    reactivateVendor: vi.fn(),
    getEquipment: vi.fn(),
    createEquipment: vi.fn(),
    setEquipmentServiceSchedule: vi.fn(),
    updateEquipment: vi.fn(),
    changeEquipmentCondition: vi.fn(),
    reinstateEquipment: vi.fn(),
    recordEquipmentService: vi.fn(),
    listKitchens: vi.fn(),
    listUsers: vi.fn(),
    createKitchen: vi.fn(),
  },
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: router.push, replace: router.replace }),
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({ id: "id-1" }),
  usePathname: () => "/",
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, ...mocks } };
});

import VendorsPage from "@/app/vendors/page";
import NewVendorPage from "@/app/vendors/new/page";
import VendorDetailPage from "@/app/vendors/[id]/page";
import NewEquipmentPage from "@/app/equipment/new/page";
import EditEquipmentPage from "@/app/equipment/[id]/edit/page";
import EquipmentItemPage from "@/app/equipment/[id]/page";
import NewKitchenPage from "@/app/kitchens/new/page";

function vendor(o: Partial<VendorView> = {}): VendorView {
  return {
    id: "v1",
    name: "Govind Wholesale",
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
    ...o,
  };
}

function vendorDetail(o: Partial<VendorView> = {}): VendorDetailView {
  return { vendor: vendor(o), supplies: [], statusHistory: [] };
}

function machine(o: Partial<EquipmentView> = {}): EquipmentView {
  return {
    id: "eq-1",
    name: "Wet Grinder 10L",
    storageLocation: "Main kitchen",
    condition: "GOOD",
    acquisitionDate: "2024-01-10",
    source: "PURCHASED",
    notes: null,
    createdAt: "2026-08-01T00:00:00Z",
    serialNumber: "WG-4471",
    purchaseCostInr: 48000,
    warrantyExpiry: "2027-01-10",
    neverNeedsServicing: false,
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

/**
 * The refusal as Rajeev asked for it: this exact sentence, in red, straight after the box (or after
 * the label wrapping it), and wired to the box so a screen reader hears the two together.
 */
function expectRefused(box: HTMLElement, sentence: string) {
  const said = screen.getByText(sentence);
  expect(said).toHaveClass("text-danger");
  expect((box.closest("label") ?? box).nextElementSibling).toContainElement(said);
  expect(box).toHaveAttribute("aria-invalid", "true");
  expect(box).toHaveAccessibleDescription(sentence);
}

beforeEach(() => {
  router.push.mockReset();
  router.replace.mockReset();
  mocks.listVendors.mockReset().mockResolvedValue([vendor()]);
  mocks.getVendor.mockReset().mockResolvedValue(vendorDetail());
  mocks.listIngredients.mockReset().mockResolvedValue([
    { id: "ing2", name: "Jaggery", unit: "KG", category: "Sweeteners" },
  ]);
  mocks.createVendor.mockReset().mockResolvedValue({ id: "v-new" });
  mocks.updateVendor.mockReset().mockResolvedValue(undefined);
  mocks.setVendorSupply.mockReset().mockResolvedValue(undefined);
  mocks.deactivateVendor.mockReset().mockResolvedValue(undefined);
  mocks.reactivateVendor.mockReset().mockResolvedValue(undefined);
  mocks.getEquipment.mockReset().mockResolvedValue({ equipment: machine(), services: [], history: [] });
  mocks.createEquipment.mockReset().mockResolvedValue({ id: "eq-new" });
  mocks.setEquipmentServiceSchedule.mockReset().mockResolvedValue(undefined);
  mocks.updateEquipment.mockReset().mockResolvedValue(undefined);
  mocks.changeEquipmentCondition.mockReset().mockResolvedValue(undefined);
  mocks.reinstateEquipment.mockReset().mockResolvedValue(undefined);
  mocks.recordEquipmentService.mockReset().mockResolvedValue(undefined);
  mocks.listKitchens.mockReset().mockResolvedValue([]);
  mocks.listUsers.mockReset().mockResolvedValue([]);
  mocks.createKitchen.mockReset().mockResolvedValue({ id: "k-new" });
});

describe("vendors (T-163)", () => {
  it("adding a vendor: names a blank Name, and adds nothing", async () => {
    render(<NewVendorPage />);

    // The commit button is in the header, outside the form, and names it with form="add-vendor".
    fireEvent.click(screen.getByRole("button", { name: /add vendor/i }));

    const form = screen.getByRole("form", { name: /add a vendor/i });
    expectRefused(within(form).getByLabelText("Name"), "Name is required");
    expect(mocks.createVendor).not.toHaveBeenCalled();
  });

  it("editing a vendor: names a cleared Name, and saves nothing", async () => {
    render(<VendorDetailPage />);
    const form = await screen.findByRole("form", { name: /edit vendor/i });

    fireEvent.change(within(form).getByLabelText("Name"), { target: { value: "" } });
    fireEvent.click(within(form).getByRole("button", { name: /save changes/i }));

    expectRefused(within(form).getByLabelText("Name"), "Name is required");
    expect(mocks.updateVendor).not.toHaveBeenCalled();
  });

  it("adding a supply: names a blank Ingredient, and sets nothing", async () => {
    render(<VendorDetailPage />);
    const form = await screen.findByRole("form", { name: /add a supply/i });
    const add = within(form).getByRole("button", { name: /add supply/i });
    // Disabled until the ingredient list has arrived, which is a separate rule and left alone.
    await waitFor(() => expect(add).toBeEnabled());

    fireEvent.click(add);

    expectRefused(within(form).getByLabelText("Ingredient"), "Ingredient is required");
    expect(mocks.setVendorSupply).not.toHaveBeenCalled();
  });

  it("adding a supply: says a lead time over 365 can be at most 365, and sets nothing", async () => {
    render(<VendorDetailPage />);
    const form = await screen.findByRole("form", { name: /add a supply/i });
    const add = within(form).getByRole("button", { name: /add supply/i });
    await waitFor(() => expect(add).toBeEnabled());

    fireEvent.change(within(form).getByLabelText("Ingredient"), { target: { value: "ing2" } });
    const lead = within(form).getByLabelText(/lead time/i, { selector: "input" });
    fireEvent.change(lead, { target: { value: "400" } });
    fireEvent.click(add);

    expectRefused(lead, "Lead time (days) can be at most 365");
    expect(mocks.setVendorSupply).not.toHaveBeenCalled();
  });
});

describe("the vendor status dialog, a form of its own opened over a page (T-163)", () => {
  /**
   * The dialog's commit button is disabled until the reason has words in it, which is the older,
   * hand-rolled half of the same rule and is left exactly as it was (the brief: a disabled button
   * is a separate, known issue). So no click can ever reach `Form`'s check on a blank reason, and a
   * person can never see this sentence. The submit event is dispatched directly, only here, to show
   * the dialog's own `Form` would still refuse it — against a plain `<form>` this test goes red,
   * because the dispatched event would reach the handler and post an empty reason.
   *
   * <p>The wrapping label holds the hint sentence as well as the question. Until T-171 the name was
   * both, run together; `Form` now leaves out words coloured as a hint, so the name is the question.
   */
  it("still refuses a blank reason through its own Form, though the button never allows it", async () => {
    render(<VendorDetailPage />);
    fireEvent.click(await screen.findByRole("button", { name: /make inactive/i }));

    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByRole("button", { name: /make inactive/i })).toBeDisabled();

    fireEvent.submit(within(dialog).getByRole("form"));

    expectRefused(
      within(dialog).getByRole("textbox"),
      "Why are they being dropped? is required"
    );
    expect(mocks.deactivateVendor).not.toHaveBeenCalled();
  });

  it("submits a written reason from the vendor's own page, and nothing else on that page", async () => {
    render(<VendorDetailPage />);
    fireEvent.click(await screen.findByRole("button", { name: /make inactive/i }));

    const dialog = screen.getByRole("dialog");
    fireEvent.change(within(dialog).getByRole("textbox"), {
      target: { value: "Short-weighed three deliveries" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: /make inactive/i }));

    await waitFor(() => expect(mocks.deactivateVendor).toHaveBeenCalledTimes(1));
    expect(mocks.deactivateVendor.mock.calls[0].slice(0, 2)).toEqual(["v1", "Short-weighed three deliveries"]);
    // The page's own two forms were not submitted by the dialog's submit.
    expect(mocks.updateVendor).not.toHaveBeenCalled();
    expect(mocks.setVendorSupply).not.toHaveBeenCalled();
  });

  it("submits a written reason from the vendor list", async () => {
    render(<VendorsPage />);
    fireEvent.click(await screen.findByRole("button", { name: /make inactive/i }));

    const dialog = screen.getByRole("dialog");
    fireEvent.change(within(dialog).getByRole("textbox"), { target: { value: "Closed down" } });
    fireEvent.click(within(dialog).getByRole("button", { name: /make inactive/i }));

    await waitFor(() => expect(mocks.deactivateVendor).toHaveBeenCalledTimes(1));
  });

  it("brings a vendor back with no reason at all, because the reason is not required that way", async () => {
    mocks.getVendor.mockResolvedValue(vendorDetail({ active: false }));
    render(<VendorDetailPage />);
    fireEvent.click(await screen.findByRole("button", { name: /bring back/i }));

    const dialog = screen.getByRole("dialog");
    fireEvent.click(within(dialog).getByRole("button", { name: /bring back/i }));

    await waitFor(() => expect(mocks.reactivateVendor).toHaveBeenCalledTimes(1));
    expect(mocks.reactivateVendor.mock.calls[0][1]).toBeNull();
    expect(within(dialog).queryByText(/is required/)).not.toBeInTheDocument();
  });
});

describe("equipment (T-163)", () => {
  it("registering: names a blank Name, and registers nothing", async () => {
    render(<NewEquipmentPage />);

    fireEvent.click(screen.getByRole("button", { name: /register it/i }));

    const form = screen.getByRole("form", { name: /register equipment/i });
    expectRefused(within(form).getByLabelText(/^name$/i), "Name is required");
    expect(mocks.createEquipment).not.toHaveBeenCalled();
    expect(mocks.setEquipmentServiceSchedule).not.toHaveBeenCalled();
  });

  it("registering: says a cost in fractions of a paisa can have at most 2 decimal places", async () => {
    render(<NewEquipmentPage />);
    const form = screen.getByRole("form", { name: /register equipment/i });

    fireEvent.change(within(form).getByLabelText(/^name$/i), { target: { value: "Wet Grinder 10L" } });
    const cost = within(form).getByLabelText(/what it cost/i);
    fireEvent.change(cost, { target: { value: "12.345" } });
    fireEvent.click(screen.getByRole("button", { name: /register it/i }));

    expectRefused(cost, "What it cost (₹) can have at most 2 decimal places");
    expect(mocks.createEquipment).not.toHaveBeenCalled();
  });

  it("editing: names a cleared Name, and saves nothing", async () => {
    render(<EditEquipmentPage />);
    const form = await screen.findByRole("form", { name: /edit equipment/i });

    fireEvent.change(within(form).getByLabelText(/^name$/i), { target: { value: "" } });
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    expectRefused(within(form).getByLabelText(/^name$/i), "Name is required");
    expect(mocks.updateEquipment).not.toHaveBeenCalled();
  });

  it("editing: says a cost below nothing must be at least 0", async () => {
    render(<EditEquipmentPage />);
    const form = await screen.findByRole("form", { name: /edit equipment/i });

    const cost = within(form).getByLabelText(/what it cost/i);
    fireEvent.change(cost, { target: { value: "-1" } });
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    expectRefused(cost, "What it cost (₹) must be at least 0");
    expect(mocks.updateEquipment).not.toHaveBeenCalled();
  });

  it("changing condition: names a blank Why, and records nothing", async () => {
    render(<EquipmentItemPage />);
    fireEvent.click(await screen.findByRole("button", { name: /change condition/i }));
    const form = screen.getByRole("form", { name: /change condition/i });

    fireEvent.click(within(form).getByRole("button", { name: /record the change/i }));

    expectRefused(within(form).getByLabelText(/^why$/i), "Why is required");
    expect(mocks.changeEquipmentCondition).not.toHaveBeenCalled();
  });

  it("changing condition to Scrapped with no reason: refuses before the scrap question is asked", async () => {
    render(<EquipmentItemPage />);
    fireEvent.click(await screen.findByRole("button", { name: /change condition/i }));
    const form = screen.getByRole("form", { name: /change condition/i });

    fireEvent.change(within(form).getByLabelText(/new condition/i), { target: { value: "SCRAPPED" } });
    fireEvent.click(within(form).getByRole("button", { name: /record the change/i }));

    expectRefused(within(form).getByLabelText(/^why$/i), "Why is required");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(mocks.changeEquipmentCondition).not.toHaveBeenCalled();
  });

  it("bringing a scrapped machine back: names a blank reason, and reinstates nothing", async () => {
    mocks.getEquipment.mockResolvedValue({
      equipment: machine({ condition: "SCRAPPED" }),
      services: [],
      history: [],
    });
    render(<EquipmentItemPage />);
    fireEvent.click(await screen.findByRole("button", { name: /bring it back/i }));
    const form = screen.getByRole("form", { name: /bring it back/i });

    fireEvent.click(within(form).getByRole("button", { name: /put it back on the register/i }));

    expectRefused(
      within(form).getByLabelText(/why it is coming back/i),
      "Why it is coming back is required"
    );
    expect(mocks.reinstateEquipment).not.toHaveBeenCalled();
  });

  /**
   * The Serviced on label wraps its box and the sentence under it ("A service dated next Tuesday has
   * not happened yet."). Until T-171 `Form` read both as the field's name, glued together; it now
   * leaves out words coloured as a hint, so the name is "Serviced on" alone.
   */
  const SERVICED_ON = "Serviced on";

  it("recording a service: names a cleared date, and records nothing", async () => {
    render(<EquipmentItemPage />);
    fireEvent.click(await screen.findByRole("button", { name: /record a service/i }));
    const form = screen.getByRole("form", { name: /record a service/i });

    const date = within(form).getByLabelText(/serviced on/i);
    fireEvent.change(date, { target: { value: "" } });
    fireEvent.click(within(form).getByRole("button", { name: /record the service/i }));

    expectRefused(date, `${SERVICED_ON} is required`);
    expect(mocks.recordEquipmentService).not.toHaveBeenCalled();
  });

  it("recording a service: refuses a date after today, and records nothing", async () => {
    render(<EquipmentItemPage />);
    fireEvent.click(await screen.findByRole("button", { name: /record a service/i }));
    const form = screen.getByRole("form", { name: /record a service/i });

    const date = within(form).getByLabelText(/serviced on/i);
    fireEvent.change(date, { target: { value: "2099-01-01" } });
    fireEvent.click(within(form).getByRole("button", { name: /record the service/i }));

    expectRefused(date, `${SERVICED_ON} must be on or before ${dateWithYear(todayIso())}`);
    expect(mocks.recordEquipmentService).not.toHaveBeenCalled();
  });
});

describe("kitchens (T-163)", () => {
  it("adding a kitchen: names a blank Name, and adds nothing", async () => {
    render(<NewKitchenPage />);
    const name = await screen.findByLabelText(/^name$/i);
    // Let the kitchens list arrive first, so the form is the settled one a person would press on.
    await waitFor(() => expect(mocks.listKitchens).toHaveBeenCalled());

    fireEvent.click(screen.getByRole("button", { name: /add kitchen/i }));

    expectRefused(name, "Name is required");
    expect(mocks.createKitchen).not.toHaveBeenCalled();
  });
});
