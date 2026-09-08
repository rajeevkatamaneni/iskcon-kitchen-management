import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ApiError } from "@/lib/api";

// The form provisions a tenant behind SUPER_ADMIN. Mock auth (role + token) and the api call so
// we can assert exactly what the form sends — the point of these tests is that it normalizes
// input the strict server-side validation would otherwise reject, and derives the slug itself.
const { provisionSpy, geocodeSpy, pushMock, authRef } = vi.hoisted(() => ({
  provisionSpy: vi.fn(async (input: { slug: string }) => ({ slug: input.slug })),
  geocodeSpy: vi.fn(async (_address: string) => ({
    found: false,
    latitude: null,
    longitude: null,
    resolvedAddress: null,
    mapDataUri: null,
  })),
  pushMock: vi.fn(),
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "SUPER_ADMIN", fullName: "Test Person" },
      getToken: async () => "token",
    } as {
      status: string;
      appUser: { role: string; fullName?: string } | null;
      getToken: () => Promise<string>;
    },
  },
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: pushMock, replace: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, provisionTenant: provisionSpy, geocodeAddress: geocodeSpy },
  };
});

import NewTenantPage from "@/app/tenants/new/page";

describe("add a temple", () => {
  beforeEach(() => {
    provisionSpy.mockClear();
    geocodeSpy.mockClear();
    pushMock.mockClear();
    authRef.current = {
      status: "signed-in",
      appUser: { role: "SUPER_ADMIN", fullName: "Test Person" },
      getToken: async () => "token",
    };
  });

  it("previews the derived web address, cleans the phone, and hands off on success", async () => {
    render(<NewTenantPage />);

    fireEvent.change(screen.getByLabelText(/^name/i), {
      target: { value: "Sri Sri Radha Govinda Temple" },
    });
    // The slug is shown as a read-only preview under the name, never an editable field.
    expect(screen.queryByLabelText(/link name/i)).not.toBeInTheDocument();
    expect(screen.getByText("/t/sri-sri-radha-govinda-temple")).toBeInTheDocument();

    // A number a person would actually type — spaces, and a zero-width character riding along.
    fireEvent.change(screen.getByLabelText(/phone number/i), {
      target: { value: "+91 70304 33344​" },
    });

    fireEvent.click(screen.getByRole("button", { name: /add temple/i }));

    await waitFor(() => expect(provisionSpy).toHaveBeenCalledTimes(1));
    const sent = provisionSpy.mock.calls[0][0] as { slug: string; adminPhone: string };
    expect(sent.slug).toBe("sri-sri-radha-govinda-temple");
    expect(sent.adminPhone).toBe("+917030433344");

    await waitFor(() =>
      expect(pushMock).toHaveBeenCalledWith("/tenants?created=sri-sri-radha-govinda-temple")
    );
  });

  it("steers a duplicate-name web-address clash to the Name field", async () => {
    provisionSpy.mockRejectedValueOnce(
      new ApiError({
        code: "KMS-400032",
        message: "Another temple is already using that web address.",
        action: "Choose a different one.",
        fieldErrors: [],
      })
    );
    render(<NewTenantPage />);

    fireEvent.change(screen.getByLabelText(/^name/i), { target: { value: "ISKCON Bangalore" } });
    fireEvent.click(screen.getByRole("button", { name: /add temple/i }));

    // The clash is really a name clash, since the slug is hidden and derived from the name.
    expect(await screen.findByText(/very similar name already exists/i)).toBeInTheDocument();
  });
  // ---- Geocoding the address (T-042, D-17) --------------------------------
  //
  // Coordinates are read-only after provisioning, so this screen is the only place they can be got
  // right. What is asserted here is the whole of the ruling: the lookup fills them in only when a
  // human has said yes to what came back, a miss never blocks anything, and the boxes stay typeable
  // throughout.

  /** What comes back with a map key configured — which no environment has today. */
  function withAMap() {
    return {
      found: true,
      latitude: 12.9716,
      longitude: 77.5946,
      resolvedAddress: "Bengaluru, Karnataka, India",
      mapDataUri: "data:image/png;base64,iVBORw0KGgo=",
    };
  }

  function typeAddress(value: string) {
    fireEvent.change(screen.getByLabelText(/^address/i), { target: { value } });
  }

  it("fills the coordinates from the address once the operator confirms what came back", async () => {
    geocodeSpy.mockResolvedValueOnce(withAMap() as never);
    render(<NewTenantPage />);

    fireEvent.change(screen.getByLabelText(/^name/i), { target: { value: "ISKCON Bangalore" } });
    typeAddress("Hare Krishna Hill, Bengaluru");

    fireEvent.click(screen.getByRole("button", { name: /find the coordinates/i }));

    // Shown for confirmation, not written straight into the boxes: a lookup that filled them
    // silently would trade a typo for a wrong match, which is worse because nobody is watching.
    expect(await screen.findByText(/is this the right place\?/i)).toBeInTheDocument();
    expect(screen.getByAltText(/map of where/i)).toBeInTheDocument();
    expect((screen.getByLabelText(/^latitude/i) as HTMLInputElement).value).toBe("");

    fireEvent.click(screen.getByRole("button", { name: /use these coordinates/i }));

    expect((screen.getByLabelText(/^latitude/i) as HTMLInputElement).value).toBe("12.9716");
    expect((screen.getByLabelText(/^longitude/i) as HTMLInputElement).value).toBe("77.5946");
    // The panel has done its job and goes, rather than sitting over a form that has moved on.
    expect(screen.queryByText(/is this the right place\?/i)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /add temple/i }));

    await waitFor(() => expect(provisionSpy).toHaveBeenCalledTimes(1));
    const sent = provisionSpy.mock.calls[0][0] as unknown as {
      latitude: number;
      longitude: number;
    };
    expect(sent.latitude).toBe(12.9716);
    expect(sent.longitude).toBe(77.5946);
    expect(geocodeSpy).toHaveBeenCalledWith("Hare Krishna Hill, Bengaluru", "token");
  });

  it("confirms by the resolved address when there is no map key — every environment today", async () => {
    // The state this ships into: kms.static-map.provider is 'none' everywhere, so there is no
    // picture. A wrong resolved address is as obvious to a person as a wrong pin, and confirming is
    // the same step either way — the key is an upgrade to what is displayed, not a different flow.
    geocodeSpy.mockResolvedValueOnce({ ...withAMap(), mapDataUri: null } as never);
    render(<NewTenantPage />);

    typeAddress("Hare Krishna Hill, Bengaluru");
    fireEvent.click(screen.getByRole("button", { name: /find the coordinates/i }));

    expect(await screen.findByText(/is this the right place\?/i)).toBeInTheDocument();
    expect(screen.getByText("Bengaluru, Karnataka, India")).toBeInTheDocument();
    expect(screen.queryByAltText(/map of where/i)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /use these coordinates/i }));
    expect((screen.getByLabelText(/^latitude/i) as HTMLInputElement).value).toBe("12.9716");
  });

  it("asks the operator to check the numbers when there is neither a map nor a resolved address", async () => {
    // The weakest of the three, and the one live today: the port carries coordinates and nothing
    // else, so there is no normalised address to read back. It still removes the typo — the machine
    // produced the numbers — but it cannot make a wrong match visible, so it says so.
    geocodeSpy.mockResolvedValueOnce({
      ...withAMap(),
      mapDataUri: null,
      resolvedAddress: null,
    } as never);
    render(<NewTenantPage />);

    typeAddress("Hare Krishna Hill, Bengaluru");
    fireEvent.click(screen.getByRole("button", { name: /find the coordinates/i }));

    expect(await screen.findByText("12.9716, 77.5946")).toBeInTheDocument();
    expect(screen.getByText(/check these against the temple’s own records/i)).toBeInTheDocument();
  });

  it("a no-match says so plainly and leaves the typed fields working", async () => {
    // Provisioning is never blocked by a map service. There is no error notice and no KMS code —
    // not finding an address is an answer, not a failure.
    render(<NewTenantPage />);

    fireEvent.change(screen.getByLabelText(/^name/i), { target: { value: "ISKCON Bangalore" } });
    typeAddress("Somewhere nothing matches");
    fireEvent.click(screen.getByRole("button", { name: /find the coordinates/i }));

    expect(await screen.findByText(/no coordinates came back for that address/i)).toBeInTheDocument();
    // Worded for both cases at once, because neither the screen nor the endpoint can tell a
    // geocoder that looked and missed from a deployment that has no geocoder at all — and telling
    // an operator an address "could not be found" when nobody looked would be a lie.
    expect(screen.queryByText(/couldn’t find/i)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /use these coordinates/i })).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/^latitude/i), { target: { value: "13.0100" } });
    fireEvent.change(screen.getByLabelText(/^longitude/i), { target: { value: "77.5500" } });
    fireEvent.click(screen.getByRole("button", { name: /add temple/i }));

    await waitFor(() => expect(provisionSpy).toHaveBeenCalledTimes(1));
    const sent = provisionSpy.mock.calls[0][0] as unknown as {
      latitude: number;
      longitude: number;
    };
    expect(sent.latitude).toBe(13.01);
    expect(sent.longitude).toBe(77.55);
  });

  it("a coordinate the operator would rather type wins over the one that was looked up", async () => {
    // The fallback is not just present, it is on top. Nothing here disables the boxes, before a
    // lookup or after one.
    geocodeSpy.mockResolvedValueOnce(withAMap() as never);
    render(<NewTenantPage />);

    typeAddress("Hare Krishna Hill, Bengaluru");
    fireEvent.click(screen.getByRole("button", { name: /find the coordinates/i }));
    fireEvent.click(await screen.findByRole("button", { name: /use these coordinates/i }));

    fireEvent.change(screen.getByLabelText(/^latitude/i), { target: { value: "12.2958" } });
    fireEvent.click(screen.getByRole("button", { name: /add temple/i }));

    await waitFor(() => expect(provisionSpy).toHaveBeenCalledTimes(1));
    expect((provisionSpy.mock.calls[0][0] as unknown as { latitude: number }).latitude).toBe(12.2958);
  });

  it("never spends a lookup on an empty address box", async () => {
    // Nominatim is somebody else's free service and its policy asks that it not be used carelessly.
    render(<NewTenantPage />);

    expect(screen.getByRole("button", { name: /find the coordinates/i })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: /find the coordinates/i }));
    expect(geocodeSpy).not.toHaveBeenCalled();
  });

  it("a lookup that fails outright reads exactly like one that found nothing", async () => {
    // A failed request must not become an error notice on a form the operator is halfway through.
    // What they need to know is the same either way: type the numbers.
    geocodeSpy.mockRejectedValueOnce(new Error("network"));
    render(<NewTenantPage />);

    typeAddress("Hare Krishna Hill, Bengaluru");
    fireEvent.click(screen.getByRole("button", { name: /find the coordinates/i }));

    expect(await screen.findByText(/no coordinates came back for that address/i)).toBeInTheDocument();
    expect((screen.getByLabelText(/^latitude/i) as HTMLInputElement).value).toBe("");
  });
});
