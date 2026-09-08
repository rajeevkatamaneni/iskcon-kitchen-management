"use client";

import { useState } from "react";
import { api, type GeocodedAddress } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";

/**
 * Look a temple's address up, and let the operator confirm what came back before it is kept
 * (T-042, D-17).
 *
 * <p><strong>What this replaces.</strong> Provisioning asked a person to type two free-text
 * six-decimal numbers, and that is where a wrong temple location comes from. Since T-041 the
 * coordinates are read-only after provisioning — a temple does not move — so the moment they are
 * first entered is the only moment they can be got right. Rajeev, D-17: <em>"If you want to use the
 * back end we have to translate an address to Lat Long, go for it."</em>
 *
 * <p><strong>The typed boxes stay, and stay usable.</strong> This is an offer, never a gate. Some
 * temple addresses will not geocode cleanly, some deployments have no map service at all, and
 * bringing a temple onto the platform must not be blocked by either. Nothing here disables the
 * latitude and longitude fields, before a lookup or after one.
 *
 * <p><strong>Confirming is the point, not looking up.</strong> A lookup that quietly filled the
 * boxes would trade a typo for a wrong match, which is worse — it is wrong and nobody is watching.
 * So the answer is shown and has to be accepted. What it is shown <em>with</em> depends on the
 * deployment, and each is an upgrade on the last without being a different question:
 *
 * <ul>
 *   <li>a static-map key configured — a pin on a map, obvious at a glance;
 *   <li>no key, but the geocoder returned its own normalised address — that, read back;
 *   <li>neither — the coordinates alone. Weak, and still an improvement, because the machine
 *       produced the numbers rather than a person typing them.
 * </ul>
 *
 * <p><strong>No effect, deliberately.</strong> Everything here runs from a click. This component
 * was written without a {@code useEffect} on purpose: an effect that depends on {@code getToken} —
 * a new function every render — and then sets state is the shape that took a vitest worker down
 * with a heap exhaustion twice in this codebase, in {@code AddressPicker} and in the flash banner.
 * There is nothing to synchronise here, so there is nothing to get wrong.
 */
export function AddressLookup({
  address,
  onConfirm,
}: {
  /** Whatever is in the address box right now. Read on click; never written to. */
  address: string;
  /** The operator said yes. The coordinates go into the two fields, which stay editable. */
  onConfirm: (at: { latitude: number; longitude: number }) => void;
}) {
  const { getToken } = useAuth();
  const [looking, setLooking] = useState(false);
  const [result, setResult] = useState<GeocodedAddress | null>(null);

  const typed = address.trim();

  async function look() {
    setLooking(true);
    setResult(null);
    try {
      setResult(await api.geocodeAddress(typed, await getToken()));
    } catch {
      // A lookup that fails is the same to the operator as one that finds nothing: the boxes below
      // still work. Showing an error notice here would make a convenience look like a problem with
      // the temple they are trying to add.
      setResult(NOTHING);
    } finally {
      setLooking(false);
    }
  }

  return (
    <div className="space-y-3">
      <button
        type="button"
        onClick={() => void look()}
        disabled={!typed || looking}
        className="btn btn-secondary min-h-touch px-4 text-sm transition-colors duration-state disabled:opacity-60"
      >
        {looking ? "Looking up the address…" : "Find the coordinates from the address"}
      </button>

      {/* Polite rather than assertive: the operator asked for this and is looking at it. */}
      <div aria-live="polite">
        {result?.found && (
          <div className="card space-y-3 p-4">
            <p className="font-medium">Is this the right place?</p>

            {result.mapDataUri && (
              // eslint-disable-next-line @next/next/no-img-element -- a data: URI, not a served
              // asset: next/image would try to optimise a base64 string it cannot fetch. The bytes
              // travel inside the JSON because the endpoint is behind a permission and an <img src>
              // cannot carry a bearer token.
              <img
                src={result.mapDataUri}
                alt={`Map of where ${result.resolvedAddress ?? typed} was found`}
                className="w-full rounded-control border border-hairline"
              />
            )}

            {result.resolvedAddress && (
              <p className="text-sm text-ink-secondary">
                We found: <span className="text-ink">{result.resolvedAddress}</span>
              </p>
            )}

            <p className="text-sm text-ink-secondary">
              <span className="font-mono text-ink">
                {result.latitude}, {result.longitude}
              </span>
            </p>

            {!result.mapDataUri && !result.resolvedAddress && (
              // The weakest of the three, so it says plainly what the operator is being asked to
              // vouch for rather than letting two numbers pass as a confirmation.
              <p className="text-sm text-ink-muted">
                Check these against the temple’s own records before using them.
              </p>
            )}

            <div className="flex flex-wrap items-center gap-3">
              <button
                type="button"
                onClick={() => {
                  onConfirm({
                    latitude: result.latitude as number,
                    longitude: result.longitude as number,
                  });
                  setResult(null);
                }}
                className="btn btn-primary min-h-touch px-4 text-sm transition-colors duration-state"
              >
                Use these coordinates
              </button>
              <button
                type="button"
                onClick={() => setResult(null)}
                className="btn btn-quiet min-h-touch px-3 text-sm transition-colors duration-state"
              >
                No, I’ll type them
              </button>
            </div>
          </div>
        )}

        {result && !result.found && (
          // Worded to be true of both cases, because the endpoint cannot tell them apart and
          // neither can this: the geocoder looked and missed, or this deployment has no geocoder
          // at all. Telling an operator an address "could not be found" when nobody ever looked
          // would be a lie, and it is the lie GeocodingProvider.configured() exists to prevent.
          <p className="text-sm text-ink-secondary">
            No coordinates came back for that address. Enter them below, or try again with a
            landmark and a pin code.
          </p>
        )}
      </div>
    </div>
  );
}

/** A failed request and an empty answer read the same to whoever is provisioning the temple. */
const NOTHING: GeocodedAddress = {
  found: false,
  latitude: null,
  longitude: null,
  resolvedAddress: null,
  mapDataUri: null,
};
