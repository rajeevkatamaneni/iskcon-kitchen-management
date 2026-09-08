"use client";

import { useCallback, useEffect, useRef, useState, type InputHTMLAttributes } from "react";
import { api, type PlaceSuggestion } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";

/**
 * The delivery address, picked rather than typed (2026-09-05).
 *
 * <p><strong>What this replaces and why.</strong> It was a plain text box whose contents were sent
 * to a geocoder afterwards and hoped for. When the geocoder missed — and OpenStreetMap misses often
 * on Bengaluru apartment complexes — nothing happened: no coordinates, so no travel estimate, so no
 * leave-by, and nobody was told. Picking from suggestions moves that failure to the one moment
 * somebody is present to fix it, which is while they are typing.
 *
 * <p><strong>It stays a text box when there is no map service.</strong> Rendered the same, typed
 * into the same, saved the same — the suggestions simply never appear. A temple without a Maps key
 * is the ordinary case and is not told off about it.
 *
 * <p><strong>The sub-premise does not belong in here.</strong> "Clubhouse" is exactly the part
 * Google is least likely to know and most likely to fail the whole lookup over. It has its own
 * field beside this one, is never geocoded, and prints large on the delivery sheet — Rajeev,
 * 2026-09-05: *"Being int the correct main location is more important than being able to put a
 * laser pointer on the final destination."*
 *
 * <p>One session token covers a whole search — every keystroke plus the lookup that ends it — which
 * is what makes Google bill it as one, and a new one is minted the moment something is picked.
 *
 * <p><strong>Two callers now (T-054).</strong> Provisioning a temple asks the same question this
 * does — which real building is this person naming — so {@code /tenants/new} uses this rather than
 * geocoding a typed string, which is what removed a measured 600 m error there. Nothing about the
 * picking changed for that; only the box's own styling had to, which is what `inputProps` is for.
 */
export function AddressPicker({
  id,
  value,
  onPick,
  onType,
  inputProps,
}: {
  /**
   * The id the surrounding field's `<label>` points at. Supplied rather than generated here: the
   * label belongs to the row and the box belongs to this component, and a box that minted its own id
   * would be a labelled control whose label points at nothing.
   */
  id: string;
  value: string;
  /** A suggestion was chosen: the canonical address, its id, and where it is. */
  onPick: (place: { address: string; placeId: string; latitude: number; longitude: number }) => void;
  /** Somebody is typing their own. Nothing is resolved, and any previous pick is discarded. */
  onType: (address: string) => void;
  /**
   * Attributes for the box itself, where the surrounding form has its own — the class every other
   * control on that screen wears, and the `aria-invalid`/`aria-describedby` a {@link Field} builds
   * to wire an error message to the input it belongs to.
   *
   * <p>Optional, and omitted by the planner's row, which supplies its own label and has always
   * used the plain styling below. It is attributes only: the value, the change handler and the
   * combobox wiring are this component's own and are set after the spread, so nothing passed here
   * can quietly take the picker apart.
   */
  inputProps?: InputHTMLAttributes<HTMLInputElement>;
}) {
  const { getToken } = useAuth();
  /**
   * Held in a ref rather than depended on.
   *
   * <p>`getToken` is a new function on every render, so an effect naming it in its dependency array
   * re-runs on every render — and an effect that also sets state then re-renders, which re-runs it,
   * which is not a slow component but an out-of-memory crash. This file was written with it in the
   * deps and took the vitest worker down with a heap exhaustion; the same shape is on record from
   * the flash-banner effect. A ref is the fix: always current, never a dependency.
   */
  const tokenRef = useRef(getToken);
  tokenRef.current = getToken;
  const [offered, setOffered] = useState(false);
  const [suggestions, setSuggestions] = useState<PlaceSuggestion[]>([]);
  const [open, setOpen] = useState(false);
  const session = useRef(newSession());
  // What the box held when the last request went out. A reply for anything else is stale — the
  // user has typed on — and is dropped rather than repainting the list underneath them.
  const inFlightFor = useRef("");
  /**
   * The address this component itself just wrote into the box by picking a suggestion.
   *
   * <p>Without it the list reopens the instant you choose something: picking sets the value, the
   * value is what the search watches, so the search runs again and offers you the address you are
   * already looking at. Seen on the live app on 2026-09-05, sitting over the leave-by line
   * underneath it.
   */
  const justPicked = useRef("");

  useEffect(() => {
    let live = true;
    (async () => {
      try {
        const { available } = await api.placesAvailable(await tokenRef.current());
        if (live) setOffered(available);
      } catch {
        // No suggestions, then. The box below still works, which is the whole point of asking.
      }
    })();
    return () => {
      live = false;
    };
  }, []);

  const look = useCallback(
    async (typed: string) => {
      inFlightFor.current = typed;
      try {
        const found = await api.placeSuggestions(typed, session.current, await tokenRef.current());
        if (inFlightFor.current !== typed) return;
        setSuggestions(found);
        setOpen(found.length > 0);
      } catch {
        setSuggestions([]);
        setOpen(false);
      }
    },
    []
  );

  // Debounced, because this is a paid lookup and a temple types an address at human speed. 250ms is
  // long enough that "Mantri Ser" is one call rather than ten and short enough that nobody waits.
  useEffect(() => {
    if (!offered || value.trim().length < 3 || value === justPicked.current) {
      setSuggestions([]);
      setOpen(false);
      return;
    }
    const timer = setTimeout(() => void look(value), 250);
    return () => clearTimeout(timer);
  }, [value, offered, look]);

  async function choose(suggestion: PlaceSuggestion) {
    setOpen(false);
    setSuggestions([]);
    const token = await tokenRef.current();
    let resolved = null;
    try {
      resolved = await api.resolvePlace(suggestion.placeId, session.current, token);
    } catch {
      // Fall through: the description is still a better address than what was typed, even without
      // coordinates. What is lost is the travel estimate, not the delivery.
    }
    session.current = newSession();
    if (resolved) {
      justPicked.current = resolved.formattedAddress;
      onPick({
        address: resolved.formattedAddress,
        placeId: resolved.placeId,
        latitude: resolved.at.latitude,
        longitude: resolved.at.longitude,
      });
    } else {
      justPicked.current = suggestion.description;
      onType(suggestion.description);
    }
  }

  return (
    <span className="relative block">
      <input
        {...inputProps}
        id={id}
        value={value}
        autoComplete="off"
        role="combobox"
        aria-expanded={open}
        aria-autocomplete="list"
        aria-controls={`${id}-list`}
        onChange={(e) => onType(e.target.value)}
        // Closed on the way out rather than on blur alone, so a click on a suggestion still lands.
        onKeyDown={(e) => {
          if (e.key === "Escape") setOpen(false);
        }}
        className={
          inputProps?.className ?? "min-h-touch w-full rounded-control border border-hairline px-3"
        }
      />
      {open && (
        <ul
          id={`${id}-list`}
          role="listbox"
          className="dropdown absolute z-10 mt-1 max-h-64 w-full overflow-auto"
        >
          {suggestions.map((s) => (
            <li key={s.placeId} role="option" aria-selected={false}>
              <button
                type="button"
                // Fires before blur closes the list, which a click alone would not.
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => void choose(s)}
                className="block w-full px-3 py-2 text-left text-sm hover:bg-raised"
              >
                <span className="block text-ink">{s.primary}</span>
                {s.secondary && (
                  <span className="block text-xs text-ink-secondary">{s.secondary}</span>
                )}
              </button>
            </li>
          ))}
        </ul>
      )}
    </span>
  );
}

/**
 * A token grouping one search's keystrokes with the lookup that ends it.
 *
 * <p>`crypto.randomUUID` where it exists, and a plain random string where it does not — this is a
 * billing hint to Google, not a secret, and an older browser must not lose the address box over it.
 */
function newSession(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return crypto.randomUUID();
  }
  return Math.random().toString(36).slice(2) + Date.now().toString(36);
}
