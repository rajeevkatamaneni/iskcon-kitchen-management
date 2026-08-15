"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Loading } from "@/components/Loading";
import { api, type TempleSummary } from "@/lib/api";

/**
 * Choosing a temple, when there are hundreds of them.
 *
 * <p>A list is not an answer at that size, so the screen tries to answer for them: with the
 * browser's own location it offers what is within 25 km, nearest first, and most devotees never
 * type anything. Someone registering somewhere they are not standing types a place instead — a
 * neighbourhood, a city — which is looked up on a map, not matched against our addresses.
 *
 * <p>Location is asked for, never taken: the browser prompts, and a refusal costs nothing but a
 * search box.
 */
export function TemplePicker({
  token,
  value,
  onChange,
}: {
  token: string | undefined;
  value: TempleSummary | null;
  onChange: (temple: TempleSummary) => void;
}) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<TempleSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [locating, setLocating] = useState(false);
  const [askedLocation, setAskedLocation] = useState(false);
  const near = useRef<string | null>(null);

  const search = useCallback(
    async (q: string) => {
      setLoading(true);
      try {
        setResults(await api.temples({ q: q || undefined, near: near.current ?? undefined }, token));
      } catch {
        setResults([]);
      } finally {
        setLoading(false);
      }
    },
    [token]
  );

  // Ask the browser where we are, once. Everything works without it; this only saves typing.
  useEffect(() => {
    if (askedLocation || typeof navigator === "undefined" || !navigator.geolocation) {
      if (!askedLocation) setAskedLocation(true);
      return;
    }
    setAskedLocation(true);
    setLocating(true);
    navigator.geolocation.getCurrentPosition(
      (position) => {
        near.current = `${position.coords.latitude},${position.coords.longitude}`;
        setLocating(false);
        search("");
      },
      () => {
        setLocating(false);
        search("");
      },
      { timeout: 8000, maximumAge: 600_000 }
    );
  }, [askedLocation, search]);

  // Typing searches, once they stop typing — a keystroke is not a question.
  useEffect(() => {
    if (!askedLocation || locating) return;
    const timer = setTimeout(() => {
      // A typed place beats a location: they are telling us where they mean.
      if (query.trim()) near.current = null;
      search(query.trim());
    }, 350);
    return () => clearTimeout(timer);
  }, [query, askedLocation, locating, search]);

  return (
    <div className="grid gap-3">
      <label className="grid gap-1 text-sm text-ink-secondary">
        Which temple do you serve at?
        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search by temple, neighbourhood or city"
          className="min-h-touch rounded border border-hairline bg-canvas px-3 text-ink"
        />
      </label>

      {locating && <Loading label="Finding temples near you…" />}

      {!locating && loading && <Loading label="Looking…" />}

      {!locating && !loading && results.length === 0 && (
        <p className="text-sm text-ink-secondary">
          {query.trim()
            ? `No temple within 25 km of “${query.trim()}”. Try a nearby town, or the temple's name.`
            : "No temples to show yet."}
        </p>
      )}

      <div className="grid gap-2">
        {results.map((temple) => {
          const chosen = value?.id === temple.id;
          return (
            <button
              key={temple.id}
              type="button"
              onClick={() => onChange(temple)}
              aria-pressed={chosen}
              className={[
                "flex items-center gap-3 rounded-lg border px-4 py-3 text-left transition-colors duration-state",
                chosen
                  ? "border-accent bg-accent-bg"
                  : "border-hairline bg-canvas hover:bg-raised",
              ].join(" ")}
            >
              <span
                aria-hidden
                className={[
                  "h-4 w-4 flex-none rounded-full border",
                  chosen ? "border-accent bg-accent" : "border-hairline-strong",
                ].join(" ")}
              />
              <span className="grid flex-1">
                <span className="text-sm font-medium text-ink">{temple.name}</span>
                {temple.address && (
                  <span className="text-xs text-ink-muted">{temple.address}</span>
                )}
              </span>
              {temple.distanceKm != null && (
                <span className="text-xs tabular-nums text-ink-muted">{temple.distanceKm} km</span>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}
