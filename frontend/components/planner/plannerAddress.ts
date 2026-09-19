/**
 * The planner's address, and the way back to it (T-219).
 *
 * <p>The planner keeps what you are looking at in the URL — `/planner?view=week&date=2026-09-20` —
 * so that reload and the back button keep you where you were. The screens it opens (editing a meal,
 * planning one) used to forget all of that: Cancel on a meal opened from the week went to
 * `/planner/<date>`, a different page for one day with no Day/Week/Month tabs and no date stepper.
 * Rajeev found it on 2026-09-17 and asked that leaving such a screen, by any door, land you exactly
 * where you started.
 *
 * <p>So the planner hands the screen it opens a `from`: its own address, in the query string. The
 * screen goes back to it on Cancel, after a save, and through "Leave without saving?". With no `from`
 * — a link from Today, a bookmark — it falls back to the planner's day view on the meal's own date.
 *
 * <p><b>Why `from` is checked and not trusted.</b> It is read out of a URL anybody can type or send,
 * and the screen then navigates to it. Taken as given, `?from=https://elsewhere.example` is an open
 * redirect: a link that looks like ours and delivers the person somewhere else after they press Save.
 * Only a path on this site, under `/planner`, is accepted; anything else is ignored as though it were
 * not there.
 */

export type PlannerView = "day" | "week" | "month";

/** The query-string name the origin travels under. */
export const FROM = "from";

/**
 * The planner, showing `view` around `date`. The one place the address is spelled, so the planner's
 * own tabs and every screen that returns to it write it the same way.
 */
export function plannerUrl(view: PlannerView, date: string): string {
  const q = new URLSearchParams();
  q.set("view", view);
  q.set("date", date);
  return `/planner?${q.toString()}`;
}

/**
 * `from`, if it is somewhere under the planner on this site; otherwise null.
 *
 * <p>Parsed against a stand-in origin rather than matched with a pattern, because the browser's own
 * parser is what will follow the link: `//evil.example`, `/\evil.example` and `/planner/../admin` all
 * look like paths to a regular expression and are not. Whatever comes back is rebuilt from the parsed
 * path and query, never passed through as typed. A `saved` flag left on it by an earlier save is
 * dropped, so the confirmation cannot be replayed by going back twice.
 */
export function safeReturn(raw: string | null | undefined): string | null {
  if (!raw || !raw.startsWith("/") || raw.startsWith("//") || raw.includes("\\")) return null;
  const base = "https://kms.invalid";
  let url: URL;
  try {
    url = new URL(raw, base);
  } catch {
    return null;
  }
  if (url.origin !== base) return null;
  if (url.pathname !== "/planner" && !url.pathname.startsWith("/planner/")) return null;
  url.searchParams.delete("saved");
  const search = url.searchParams.toString();
  return search ? `${url.pathname}?${search}` : url.pathname;
}

/** `href` with the origin attached, or `href` alone when there is none to attach. */
export function withReturn(href: string, returnTo: string | null | undefined): string {
  if (!returnTo) return href;
  const [path, query = ""] = href.split("?");
  const q = new URLSearchParams(query);
  q.set(FROM, returnTo);
  return `${path}?${q.toString()}`;
}

/** `href` with one more query parameter — the confirmation a save leaves for the screen it returns to. */
export function withParam(href: string, name: string, value: string): string {
  const [path, query = ""] = href.split("?");
  const q = new URLSearchParams(query);
  q.set(name, value);
  return `${path}?${q.toString()}`;
}
