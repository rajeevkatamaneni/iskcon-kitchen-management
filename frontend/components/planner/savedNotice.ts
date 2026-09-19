/**
 * The sentence a planner page says when a meal's screen sends it back with `?saved=…` (T-311).
 *
 * <p>Two pages read that flag — the planner itself and the separate page for one day — and they used
 * to phrase it each for themselves. When the composer started sending `saved=planned` instead of the
 * meal's name, the planner learnt the new word and the day page did not, so a new meal landed on the
 * day page saying "planned was saved." One function for both is so the two cannot drift apart again.
 *
 * <p>There are exactly two writers of the flag, and this covers both:
 * <ul>
 *   <li>the composer (`/planner/compose`) sends `planned`, because a new meal has no name worth
 *       repeating until it is saved — "The meal was planned.";</li>
 *   <li>the edit screen (`/planner/meal/[id]`) sends the meal's own name, the event's name or the
 *       meal kind — "Lunch was saved.", "Janmashtami feast was saved.".</li>
 * </ul>
 *
 * <p>One known collision, left as it is: an event literally named "planned" that is edited would read
 * as a new meal. The edit screen would have to send a different flag to tell them apart, and that
 * screen is outside this change.
 *
 * <p>Null for an absent or blank flag, so a page shows nothing rather than " was saved.".
 */
export function savedNotice(flag: string | null | undefined): string | null {
  const value = flag?.trim();
  if (!value) return null;
  if (value === "planned") return "The meal was planned.";
  return `${value} was saved.`;
}
