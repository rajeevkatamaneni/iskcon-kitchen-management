import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ApiError, type TenantDetail } from "@/lib/api";

/**
 * Correcting a temple's profile, and recording its 80G approval (T-008, narrowed by D-17).
 *
 * <p>What is asserted here is the pair of faults the screen exists to fix — a temple provisioned
 * with a misspelled name was unfixable, and 80G approval could never be recorded after the fact —
 * and, just as hard, three things about how it must behave that are easy to get subtly wrong and
 * impossible to notice afterwards:
 *
 * <ul>
 *   <li>It sends <em>all seven</em> fields, including the four nobody may change. The endpoint is a
 *       whole-record replacement, and since D-17 those four must arrive carrying exactly what is
 *       stored or the server refuses the save — so a coordinate that fails to reach the payload
 *       does not merely go missing, it makes the screen unusable.
 *   <li>The four frozen fields render as <em>text</em>. Not as disabled inputs: a disabled control
 *       submits nothing, so the values would leave the form empty and the save would fail for a
 *       reason nothing on the screen could explain.
 *   <li>It offers no way to change the web address, the coordinates, the timezone or the currency,
 *       and says why on the page rather than letting the operator find out by being refused.
 * </ul>
 */

const { authRef, queryRef, updateMock, pushMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "SUPER_ADMIN", fullName: "Operator" } } as {
      status: string;
      appUser: { role: string; fullName: string } | null;
    },
  },
  queryRef: {
    current: {
      data: null as unknown,
      error: null as ApiError | null,
      loading: false,
    },
  },
  updateMock: vi.fn(),
  pushMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: vi.fn() }),
  useParams: () => ({ id: "t1" }),
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
    api: { ...actual.api, updateTenant: updateMock, getTenant: vi.fn() },
  };
});

import EditTenantPage from "@/app/tenants/[id]/edit/page";
import TenantDetailPage from "@/app/tenants/[id]/page";

/**
 * What `GET /api/v1/tenants/{id}` returns: the detail row, including the coordinates the correction
 * screen has to open on. `TenantDetail` carries those as required fields, so a fixture that forgot
 * one would not compile — which is the point of their being required.
 */
function temple(over: Partial<TenantDetail> = {}): TenantDetail {
  return {
    id: "t1",
    slug: "iskcon-south-bangalore",
    name: "ISKCON South Bangalore",
    address: "Kumaraswamy Layout, Bengaluru",
    latitude: 12.9716,
    longitude: 77.5946,
    timezone: "Asia/Kolkata",
    currency: "INR",
    is_80g_approved: false,
    created_at: "2026-08-11T00:00:00Z",
    user_count: 3,
    last_export_at: null,
    ...over,
  };
}

beforeEach(() => {
  authRef.current = {
    status: "signed-in",
    appUser: { role: "SUPER_ADMIN", fullName: "Operator" },
  };
  queryRef.current = { data: temple(), error: null, loading: false };
  updateMock.mockReset().mockResolvedValue(undefined);
  pushMock.mockReset();
});

describe("correcting a temple's profile", () => {
  it("opens on what the record already says", () => {
    render(<EditTenantPage />);

    expect(screen.getByRole("heading", { name: /edit this temple/i })).toBeInTheDocument();
    expect(screen.getByLabelText(/^name/i)).toHaveValue("ISKCON South Bangalore");
    expect(screen.getByLabelText(/^address$/i)).toHaveValue("Kumaraswamy Layout, Bengaluru");
    expect(screen.getByRole("checkbox", { name: /80G receipts/i })).not.toBeChecked();
  });

  it("shows the location, timezone and currency, and offers no way to change them", () => {
    // D-17, ruled field by field: a temple does not move, so its coordinates and the timezone its
    // calendar is worked out from are not editable, and neither is a currency that money has
    // already been recorded in. The server refuses a change to all four, so a control here could
    // only ever produce a refusal.
    render(<EditTenantPage />);

    // Read on the page — an operator who came to fix a name is entitled to see what else the
    // record says, and a coordinate that is wrong is worth being able to read.
    expect(screen.getByText("12.9716")).toBeInTheDocument();
    expect(screen.getByText("77.5946")).toBeInTheDocument();
    expect(screen.getByText("Asia/Kolkata")).toBeInTheDocument();
    expect(screen.getByText("INR")).toBeInTheDocument();

    // And not as controls of any kind. Asserted twice over: by name, and by role across the whole
    // screen — because the failure that matters is a disabled <input> or <select> left behind,
    // which looks right, submits nothing, and would send four nulls to a server that requires all
    // seven fields.
    expect(screen.queryByLabelText(/^latitude/i)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/^longitude/i)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/^timezone/i)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/^currency/i)).not.toBeInTheDocument();
    expect(screen.queryAllByRole("combobox")).toHaveLength(0);
    expect(screen.queryAllByRole("spinbutton")).toHaveLength(0);
    expect(screen.queryAllByRole("textbox", { name: /latitude|longitude|timezone|currency/i }))
      .toHaveLength(0);
  });

  it("corrects a misspelled name and sends the whole record back", async () => {
    render(<EditTenantPage />);

    fireEvent.change(screen.getByLabelText(/^name/i), {
      target: { value: "ISKCON Bangalore South" },
    });
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(updateMock).toHaveBeenCalledTimes(1));
    expect(updateMock.mock.calls[0][0]).toBe("t1");
    // Every field, not just the changed one, and the four frozen ones carrying exactly what the
    // temple already had. They come off the loaded record rather than off the form, because they
    // are no longer form controls at all — and if one failed to reach the payload the server would
    // refuse the save outright: since D-17 a frozen field that differs from what is stored
    // is a refusal.
    expect(updateMock.mock.calls[0][1]).toEqual({
      name: "ISKCON Bangalore South",
      address: "Kumaraswamy Layout, Bengaluru",
      latitude: 12.9716,
      longitude: 77.5946,
      timezone: "Asia/Kolkata",
      currency: "INR",
      is80gApproved: false,
    });
    expect(updateMock.mock.calls[0][2]).toBe("test-token");
  });

  it("records 80G approval that came through after the temple was provisioned", async () => {
    // Docket A2, and the whole reason this field is on this screen rather than a second endpoint:
    // 80G approval arrives from the Income Tax department months after a temple starts using the
    // product, and until now it could only ever be set by the provisioning insert.
    render(<EditTenantPage />);

    fireEvent.click(screen.getByRole("checkbox", { name: /80G receipts/i }));
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(updateMock).toHaveBeenCalledTimes(1));
    expect(updateMock.mock.calls[0][1]).toMatchObject({ is80gApproved: true });
  });

  it("keeps a 80G approval the temple already has, rather than quietly withdrawing it", async () => {
    queryRef.current = { data: temple({ is_80g_approved: true }), error: null, loading: false };
    render(<EditTenantPage />);

    expect(screen.getByRole("checkbox", { name: /80G receipts/i })).toBeChecked();

    fireEvent.change(screen.getByLabelText(/^address$/i), { target: { value: "Bengaluru" } });
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(updateMock).toHaveBeenCalledTimes(1));
    expect(updateMock.mock.calls[0][1]).toMatchObject({ is80gApproved: true });
  });

  it("sends back whatever the record says, however unusual, rather than a default", async () => {
    // The frozen four are taken from the temple that was loaded, so a temple in Perth stays in
    // Perth. Under the pickers this screen used to have, the same case was a live hazard: a select
    // whose value is not among its options falls silently to the first one, which would have moved
    // this temple to Kolkata because somebody fixed a spelling. Now there are no options to fall
    // through — but the payload still has to carry the values, so the case is still worth holding.
    queryRef.current = {
      data: temple({ timezone: "Australia/Perth", currency: "AUD" }),
      error: null,
      loading: false,
    };
    render(<EditTenantPage />);

    expect(screen.getByText("Australia/Perth")).toBeInTheDocument();
    expect(screen.getByText("AUD")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(updateMock).toHaveBeenCalledTimes(1));
    expect(updateMock.mock.calls[0][1]).toMatchObject({
      timezone: "Australia/Perth",
      currency: "AUD",
      latitude: 12.9716,
      longitude: 77.5946,
    });
  });

  it("offers no way to change the web address, and says why", () => {
    render(<EditTenantPage />);

    expect(screen.queryByLabelText(/web address/i)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/^slug$/i)).not.toBeInTheDocument();
    expect(screen.getByText(/can’t be changed/i)).toBeInTheDocument();
    expect(screen.getByText("/t/iskcon-south-bangalore")).toBeInTheDocument();
  });

  it("offers nothing about the administrator, because a person is not a temple", () => {
    // /tenants/new asks for the first administrator's name, email and phone. They describe a
    // person and are corrected on that person's own record, so UpdateTenantInput omits all three.
    render(<EditTenantPage />);

    expect(screen.queryByLabelText(/full name/i)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/email/i)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/phone/i)).not.toBeInTheDocument();
  });

  it("returns to the temple's page, where the correction can be read back", async () => {
    render(<EditTenantPage />);

    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/tenants/t1"));
  });

  it("says so in the server's own words when the save is refused, and stays put", async () => {
    updateMock.mockRejectedValue(
      new ApiError(
        {
          code: "KMS-400001",
          message: "Some of the information entered isn't valid.",
          action: "Check the highlighted fields and try again.",
          fieldErrors: [
            { field: "slug", message: "A temple's web address is fixed when it is created." },
          ],
        },
        400
      )
    );
    render(<EditTenantPage />);

    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(screen.getByText("KMS-400001")).toBeInTheDocument());
    expect(pushMock).not.toHaveBeenCalled();
    // Still on the form, with what was typed still in it.
    expect(screen.getByLabelText(/^name/i)).toHaveValue("ISKCON South Bangalore");
  });

  it("offers Cancel back to the temple, and no other way out", () => {
    render(<EditTenantPage />);
    expect(screen.getByRole("link", { name: /^cancel$/i })).toHaveAttribute("href", "/tenants/t1");
  });

  it("admits the platform operator alone", () => {
    // D-13: a temple's profile is the operator's to correct, and a temple admin cannot fix even
    // its own address. The server holds that with MANAGE_TENANTS; this is the same line drawn on
    // the screen so nobody is shown a form they will only be refused.
    for (const role of ["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF", "VOLUNTEER"]) {
      authRef.current = { status: "signed-in", appUser: { role, fullName: "Someone" } };
      const view = render(<EditTenantPage />);
      expect(screen.getByText(/not your page/i)).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: /save changes/i })).not.toBeInTheDocument();
      view.unmount();
    }
  });
});

describe("the temple's page, after the edit screen exists", () => {
  it("offers the way in to it", () => {
    render(<TenantDetailPage />);

    expect(screen.getByRole("link", { name: /edit details/i })).toHaveAttribute(
      "href",
      "/tenants/t1/edit"
    );
  });

  it("still reads the 80G status back, which is what the correction is for", () => {
    queryRef.current = { data: temple({ is_80g_approved: true }), error: null, loading: false };
    render(<TenantDetailPage />);

    expect(screen.getByText("Approved")).toBeInTheDocument();
  });
});
