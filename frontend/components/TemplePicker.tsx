"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Loading } from "@/components/Loading";
import { api, type TempleSummary } from "@/lib/api";

/**
 * Choosing a temple, when there are hundreds of them.
 *
 * <p>Nothing is offered until the person has said something. If they allow the browser to share
 * where they are, the temples near them appear and most people never type; if they don't, the box
 * waits. A list nobody asked for is a guess, and a guess at the top of a registration form reads as
 * a decision already made — which is exactly what it looked like when this shipped with every
 * temple listed by default.
 */
export function TemplePicker({
  token,
  value,
  onChange,
  label = "Which temple do you serve at?",
}: {
  token: string | undefined;
  value: TempleSummary | null;
  onChange: (temple: TempleSummary | null) => void;
  /** The question the surrounding page has not already asked. */
  label?: string;
}) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<TempleSummary[]>([]);
  const [searching, setSearching] = useState(false);
  const [locating, setLocating] = useState(false);
  const [located, setLocated] = useState(false);
  const near = useRef<string | null>(null);

  const search = useCallback(
    async (q: string, from: string | null) => {
      if (!q && !from) {
        setResults([]);
        return;
      }
      setSearching(true);
      try {
        setResults(await api.temples({ q: q || undefined, near: from ?? undefined }, token));
      } catch {
        setResults([]);
      } finally {
        setSearching(false);
      }
    },
    [token]
  );

  // Typing searches, once they stop — a keystroke is not a question.
  useEffect(() => {
    const q = query.trim();
    if (!q) {
      if (!near.current) setResults([]);
      return;
    }
    const timer = setTimeout(() => {
      // A typed place beats a location: they are telling us where they mean.
      near.current = null;
      search(q, null);
    }, 350);
    return () => clearTimeout(timer);
  }, [query, search]);

  function useMyLocation() {
    if (typeof navigator === "undefined" || !navigator.geolocation) return;
    setLocating(true);
    navigator.geolocation.getCurrentPosition(
      (position) => {
        near.current = `${position.coords.latitude},${position.coords.longitude}`;
        setLocating(false);
        setLocated(true);
        setQuery("");
        search("", near.current);
      },
      () => {
        // Refused, or unavailable. Nothing is lost but a shortcut.
        setLocating(false);
        setLocated(true);
      },
      { timeout: 8000, maximumAge: 600_000 }
    );
  }

  if (value) {
    return (
      <div className="grid gap-1">
        <span className="text-sm text-ink-secondary">Your temple</span>
        <div className="flex items-center gap-3 rounded-lg border border-accent bg-accent-bg px-4 py-3">
          <span className="grid flex-1">
            <span className="text-sm font-medium text-ink">{value.name}</span>
            {value.address && <span className="text-xs text-ink-muted">{value.address}</span>}
          </span>
          <button
            type="button"
            onClick={() => {
              onChange(null);
              setQuery("");
              setResults([]);
            }}
            className="text-sm text-accent-text hover:underline"
          >
            Change
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="grid gap-3">
      <label className="grid gap-1 text-sm text-ink-secondary">
        {label}
        <input
          type="search"
          // Chrome reads an unnamed search box on a form with a password as a username field and
          // fills it with a saved email. Watched it happen: the temple question arrived pre-filled
          // with somebody's address.
          name="temple-search"
          autoComplete="off"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search by temple, neighbourhood or city"
          className="min-h-touch rounded border border-hairline bg-canvas px-3 text-ink"
        />
      </label>

      {!located && !query.trim() && (
        <button
          type="button"
          onClick={useMyLocation}
          disabled={locating}
          className="justify-self-start text-sm text-accent-text hover:underline disabled:text-ink-muted"
        >
          {locating ? "Finding temples near you…" : "Use my location to find temples near me"}
        </button>
      )}

      {searching && <Loading label="Looking…" />}

      {!searching && (query.trim() || near.current) && results.length === 0 && (
        <p className="text-sm text-ink-secondary">
          {query.trim()
            ? `No temple found near “${query.trim()}”. Try a nearby town, or the temple's name.`
            : "No temples near you. Search by name or city instead."}
        </p>
      )}

      <div className="grid gap-2">
        {results.map((temple) => (
          <button
            key={temple.id}
            type="button"
            onClick={() => onChange(temple)}
            className="flex items-center gap-3 rounded-lg border border-hairline bg-canvas px-4 py-3 text-left transition-colors duration-state hover:bg-raised"
          >
            <span className="grid flex-1">
              <span className="text-sm font-medium text-ink">{temple.name}</span>
              {temple.address && <span className="text-xs text-ink-muted">{temple.address}</span>}
            </span>
            {temple.distanceKm != null && (
              <span className="text-xs tabular-nums text-ink-muted">{temple.distanceKm} km away</span>
            )}
          </button>
        ))}
      </div>
    </div>
  );
}
