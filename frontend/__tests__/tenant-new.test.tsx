import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ApiError } from "@/lib/api";

// The form provisions a tenant behind SUPER_ADMIN. Mock auth (role + token) and the api calls so
// we can assert exactly what the form sends — the point of these tests is that it normalizes
// input the strict server-side validation would otherwise reject, derives the slug itself, and
// takes its coordinates from the server rather than from anything a person typed.
const { provisionSpy, placesAvailable, placeSuggestions, resolvePlace, pushMock, authRef } =
  vi.hoisted(() => ({
    provisionSpy: vi.fn(async (input: { slug: string }) => ({ slug: input.slug })),
    placesAvailable: vi.fn(async (_token?: string) => ({ available: true })),
    placeSuggestions: vi.fn(async (_q: string, _session: string, _token?: string) => [] as unknown[]),
    resolvePlace: vi.fn(async (_id: string, _session: string, _token?: string) => null as unknown),
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
    api: {
      ...actual.api,
      provisionTenant: provisionSpy,
      placesAvailable,
      placeSuggestions,
      resolvePlace,
    },
  };
});

import NewTenantPage from "@/app/tenants/new/page";

/**
 * What somebody provisioning a temple actually types — short, lowercase, and not an address.
 * Nothing that comes out of the lookup may be traceable to this string.
 */
const TYPED = "hare krishna";

/** The row Google offers for it. Its text is a label, not an answer: nothing is resolved yet. */
const SUGGESTION = {
  placeId: "ChIJhare-krishna-hill",
  description: "ISKCON Sri Radha Krishna Temple, Hare Krishna Hill, Bengaluru",
  primary: "ISKCON Sri Radha Krishna Temple",
  secondary: "Hare Krishna Hill, Bengaluru, Karnataka, India",
};

/**
 * What the server answers when that suggestion is picked — deliberately different text from both
 * the typed string and the suggestion's own description, so a screen that showed either of those
 * back would be caught rather than passing on a resemblance.
 */
const RESOLVED = {
  placeId: "ChIJhare-krishna-hill",
  formattedAddress:
    "ISKCON Sri Radha Krishna Temple, Hare Krishna Hill, Chord Rd, Bengaluru, Karnataka 560010, India",
  at: { latitude: 13.0098, longitude: 77.5511 },
};

describe("add a temple", () => {
  beforeEach(() => {
    provisionSpy.mockClear();
    pushMock.mockClear();
    placesAvailable.mockReset();
    placeSuggestions.mockReset();
    resolvePlace.mockReset();
    placesAvailable.mockResolvedValue({ available: true });
    placeSuggestions.mockResolvedValue([SUGGESTION]);
    resolvePlace.mockResolvedValue(RESOLVED);
    authRef.current = {
      status: "signed-in",
      appUser: { role: "SUPER_ADMIN", fullName: "Test Person" },
      getToken: async () => "token",
    };
  });

  function typeAddress(value: string) {
    fireEvent.change(screen.getByLabelText(/^address/i), { target: { value } });
  }

  /** Type, wait out the picker's debounce, and choose the one thing it offers. */
  async function pickTheTemple() {
    typeAddress(TYPED);
    fireEvent.click(await screen.findByText(SUGGESTION.primary));
  }

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

  // ---- Picking the temple rather than geocoding a string (T-054, D-17) ----
  //
  // Coordinates are read-only after provisioning, so this screen is the only place they can be got
  // right. It used to hand a typed address to a geocoder, which is a machine guessing which of
  // several places was meant — measured 600 m out on the temple this platform was built for. Now
  // the operator names one building and the server answers with where that building is.

  it("fills the coordinates from the server's answer once the operator confirms it", async () => {
    render(<NewTenantPage />);
    fireEvent.change(screen.getByLabelText(/^name/i), { target: { value: "ISKCON Bangalore" } });

    await pickTheTemple();

    // Shown for confirmation, not written straight into the boxes: autocomplete's characteristic
    // mistake is the right name in the wrong city, and nobody is watching the number fields.
    expect(await screen.findByText(/is this the right place\?/i)).toBeInTheDocument();
    expect((screen.getByLabelText(/^latitude/i) as HTMLInputElement).value).toBe("");

    // Every word of the confirmation is the server's. Asserted from both ends: what is shown is
    // the resolved address, and neither the operator's typing nor the suggestion's own label —
    // which is list text, not an answer — appears anywhere as the found place.
    expect(screen.getByText(RESOLVED.formattedAddress)).toBeInTheDocument();
    expect(screen.getByText("13.0098, 77.5511")).toBeInTheDocument();
    expect(screen.queryByText(TYPED)).not.toBeInTheDocument();
    expect(screen.queryByText(SUGGESTION.description)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /use these coordinates/i }));

    expect((screen.getByLabelText(/^latitude/i) as HTMLInputElement).value).toBe("13.0098");
    expect((screen.getByLabelText(/^longitude/i) as HTMLInputElement).value).toBe("77.5511");
    // The panel has done its job and goes, rather than sitting over a form that has moved on.
    expect(screen.queryByText(/is this the right place\?/i)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /add temple/i }));

    await waitFor(() => expect(provisionSpy).toHaveBeenCalledTimes(1));
    const sent = provisionSpy.mock.calls[0][0] as unknown as {
      address: string;
      latitude: number;
      longitude: number;
    };
    expect(sent.latitude).toBe(13.0098);
    expect(sent.longitude).toBe(77.5511);
    // The address stored is the server's rendering too — a temple's own row should not carry
    // "hare krishna" as its address because that is what somebody typed to find it.
    expect(sent.address).toBe(RESOLVED.formattedAddress);
    expect(resolvePlace).toHaveBeenCalledWith(SUGGESTION.placeId, expect.any(String), "token");
  });

  it("declining what came back leaves the coordinates empty and the boxes typeable", async () => {
    render(<NewTenantPage />);

    await pickTheTemple();
    fireEvent.click(await screen.findByRole("button", { name: /no, i’ll type them/i }));

    expect(screen.queryByText(/is this the right place\?/i)).not.toBeInTheDocument();
    expect((screen.getByLabelText(/^latitude/i) as HTMLInputElement).value).toBe("");

    fireEvent.change(screen.getByLabelText(/^latitude/i), { target: { value: "13.0100" } });
    fireEvent.change(screen.getByLabelText(/^longitude/i), { target: { value: "77.5500" } });
    fireEvent.click(screen.getByRole("button", { name: /add temple/i }));

    await waitFor(() => expect(provisionSpy).toHaveBeenCalledTimes(1));
    const sent = provisionSpy.mock.calls[0][0] as unknown as { latitude: number };
    expect(sent.latitude).toBe(13.01);
  });

  it("a coordinate the operator would rather type wins over the one that was picked", async () => {
    // The fallback is not merely present, it is on top. Nothing here disables the boxes, before a
    // pick or after one.
    render(<NewTenantPage />);

    await pickTheTemple();
    fireEvent.click(await screen.findByRole("button", { name: /use these coordinates/i }));

    fireEvent.change(screen.getByLabelText(/^latitude/i), { target: { value: "12.2958" } });
    fireEvent.click(screen.getByRole("button", { name: /add temple/i }));

    await waitFor(() => expect(provisionSpy).toHaveBeenCalledTimes(1));
    expect((provisionSpy.mock.calls[0][0] as unknown as { latitude: number }).latitude).toBe(
      12.2958
    );
  });

  it("a place that cannot be resolved never becomes a confirmation, and blocks nothing", async () => {
    // The map service had a bad minute, or the id went stale. There is no answer to confirm, so
    // there is no panel — a screen that offered "Is this the right place?" with nothing behind it
    // would be asking the operator to vouch for their own typing.
    resolvePlace.mockResolvedValue(null);
    render(<NewTenantPage />);

    await pickTheTemple();

    await waitFor(() => expect(resolvePlace).toHaveBeenCalled());
    expect(screen.queryByText(/is this the right place\?/i)).not.toBeInTheDocument();
    expect((screen.getByLabelText(/^latitude/i) as HTMLInputElement).value).toBe("");
    // No error notice and no KMS code: not resolving a place is an answer, not a failure.
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/^latitude/i), { target: { value: "13.0100" } });
    fireEvent.change(screen.getByLabelText(/^longitude/i), { target: { value: "77.5500" } });
    fireEvent.click(screen.getByRole("button", { name: /add temple/i }));

    await waitFor(() => expect(provisionSpy).toHaveBeenCalledTimes(1));
    expect((provisionSpy.mock.calls[0][0] as unknown as { latitude: number }).latitude).toBe(13.01);
  });

  it("a deployment with no Places key gets the plain box it always had, and is not told off", async () => {
    // The ordinary case for a temple with no Maps key, and the one this must never block. The box
    // is typed into and the two numbers are typed in, exactly as before any of this existed.
    placesAvailable.mockResolvedValue({ available: false });
    render(<NewTenantPage />);

    await waitFor(() => expect(placesAvailable).toHaveBeenCalled());
    typeAddress("Hare Krishna Hill, Bengaluru");

    // Nothing is looked up, because we were told there is nothing to look up with.
    await new Promise((resolve) => setTimeout(resolve, 400));
    expect(placeSuggestions).not.toHaveBeenCalled();
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();

    // And nothing on the screen mentions a missing service, a key, or suggestions that are not
    // coming. An operator without a Maps key is having an ordinary day, not a degraded one.
    expect(screen.queryByText(/suggestion|not available|unavailable|no map/i)).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/^latitude/i), { target: { value: "13.0100" } });
    fireEvent.change(screen.getByLabelText(/^longitude/i), { target: { value: "77.5500" } });
    fireEvent.click(screen.getByRole("button", { name: /add temple/i }));

    await waitFor(() => expect(provisionSpy).toHaveBeenCalledTimes(1));
    const sent = provisionSpy.mock.calls[0][0] as unknown as {
      address: string;
      latitude: number;
      longitude: number;
    };
    expect(sent.address).toBe("Hare Krishna Hill, Bengaluru");
    expect(sent.latitude).toBe(13.01);
    expect(sent.longitude).toBe(77.55);
  });

  /**
   * T-059. The fifteen-digit latitude Rajeev saw on staging was Google's own number, cut at the
   * provider now, and this screen was left alone deliberately: it renders the number it was given
   * and never a rendering of its own. These two tests are what keeps that true, because the obvious
   * "fix" if the defect ever came back would be a `toFixed(6)` here — which would pad every honest
   * coordinate out to six places and put a different string in the box from the one on the card.
   */
  it("shows the coordinates exactly as the server sent them, in both places and in what it stores", async () => {
    resolvePlace.mockResolvedValue({
      placeId: "ChIJiskcon-mysuru",
      formattedAddress: "ISKCON - Mysuru, Jayanagara, Mysuru, Karnataka 570014, India",
      at: { latitude: 12.285518, longitude: 76.634087 },
    });
    render(<NewTenantPage />);
    fireEvent.change(screen.getByLabelText(/^name/i), { target: { value: "ISKCON Mysuru" } });

    await pickTheTemple();

    // The card and the box are the same value rendered twice, and an operator confirming one and
    // saving the other is the whole point of the confirm step — so they are asserted to be the same
    // characters, not merely the same number.
    expect(await screen.findByText("12.285518, 76.634087")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /use these coordinates/i }));
    expect((screen.getByLabelText(/^latitude/i) as HTMLInputElement).value).toBe("12.285518");
    expect((screen.getByLabelText(/^longitude/i) as HTMLInputElement).value).toBe("76.634087");

    fireEvent.click(screen.getByRole("button", { name: /add temple/i }));

    await waitFor(() => expect(provisionSpy).toHaveBeenCalledTimes(1));
    const sent = provisionSpy.mock.calls[0][0] as unknown as {
      latitude: number;
      longitude: number;
    };
    // What is stored is what was shown. A screen that formatted the display while sending the
    // original number would satisfy the screenshot and fail the point of it.
    expect(sent.latitude).toBe(12.285518);
    expect(sent.longitude).toBe(76.634087);
  });

  it("leaves a coordinate that is already short exactly as short as it arrived", async () => {
    resolvePlace.mockResolvedValue({
      placeId: "ChIJhare-krishna-hill",
      formattedAddress: RESOLVED.formattedAddress,
      at: { latitude: 12.9716, longitude: 77.5946 },
    });
    render(<NewTenantPage />);

    await pickTheTemple();

    expect(await screen.findByText("12.9716, 77.5946")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /use these coordinates/i }));
    expect((screen.getByLabelText(/^latitude/i) as HTMLInputElement).value).toBe("12.9716");
    expect((screen.getByLabelText(/^longitude/i) as HTMLInputElement).value).toBe("77.5946");
  });

  it("never spends a lookup on two characters", async () => {
    // Every one of these is a paid call, and below three characters there is nothing to go on.
    render(<NewTenantPage />);
    await waitFor(() => expect(placesAvailable).toHaveBeenCalled());

    typeAddress("ha");
    await new Promise((resolve) => setTimeout(resolve, 400));

    expect(placeSuggestions).not.toHaveBeenCalled();
  });
});
