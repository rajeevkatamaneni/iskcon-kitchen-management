"use client";

import { useEffect, useMemo, useRef, useState } from "react";

import { ErrorNotice } from "@/components/ErrorNotice";
import { FIELD_LABEL } from "@/components/Field";
import { Form } from "@/components/ds/Form";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { api, toApiError, type ApiError, type MenuLayout } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { NEW_GROUP_ID, applyMenuLayout, standardMenu, type NavItem } from "@/lib/nav";

/**
 * The screen a temple arranges its own left-hand menu on (Rajeev, 2026-09-19; T-422).
 *
 * <p>It grew out of a local playground Rajeev tried and liked, and it keeps that page's
 * interaction — move an item, move a group, make a group, rename a heading, delete a group — and
 * none of its mechanics. The playground was one person trying orders out in their own browser. This
 * is the whole temple's menu, stored on the server, so three things about it are deliberately
 * different and each is recorded in `docs/work/DISPATCH.md` under the 2026-09-20 block:
 *
 * <ul>
 *   <li><b>It saves on a button, never as you drag (D-M8).</b> The playground wrote every change
 *       straight to `localStorage`, which was right for a page nobody else could see. Here a stray
 *       drag would repaint the menu of every cook at the temple, so nothing leaves this screen until
 *       Save, and the screen says plainly when there is work not yet saved.</li>
 *   <li><b>There is no preview copy of the sidebar (D-M9).</b> The playground drew its own left-hand
 *       column out of `Sidebar.tsx`'s class strings and said in its own comment that it would go
 *       stale. This screen sits inside the application with the real menu already beside it, so the
 *       list below <i>is</i> the preview, and the real column repaints when Save calls `refresh()`.
 *       One list that is always right beats two that agree until somebody touches the sidebar.</li>
 *   <li><b>Drag is an enhancement and nothing depends on it (D-M10).</b> HTML5 drag-and-drop does
 *       not work on a touch screen and cannot be driven from a keyboard, and this screen has to work
 *       at 390px. So Move up, Move down and Move to group are the primary controls, they are
 *       ordinary buttons and a select, and dragging is offered on top of them.</li>
 * </ul>
 *
 * <p><b>What this screen cannot do, on purpose.</b> It decides order and grouping and nothing else.
 * There is no control anywhere that renames a menu item — labels have to match the page titles, the
 * help and the training — and none that hides one. A temple that does not use a feature switches the
 * feature off rather than taking its row off the menu, so that nobody is left hunting for a screen
 * the application still has. `navForRole` applies the arrangement and then filters by role, so
 * nothing arranged here can offer a person a destination their role does not already allow.
 *
 * <p><b>Only the Temple Admin's own destinations are on the board (D-M7).</b> Nine of the
 * application's destinations no administrator can reach — four a volunteer sees and five the
 * platform operator sees — and they are not arrangeable here. They are not lost either: the merge in
 * `nav.ts` puts every destination this arrangement does not place at the end of its standard group,
 * which for all nine is the first one, so a volunteer's menu is unchanged by anything done here.
 * <b>That standard group cannot be deleted</b> (D-M11), because deleting it is the one act on this
 * screen that would change a volunteer's menu — see {@link PROTECTED_GROUP_IDS} for the whole of it.
 */

/** A group as this screen holds it while it is being arranged: a heading, and a list of item ids. */
interface DraftGroup {
  /** `NavGroup.id`, or one minted here for a group the temple made. Never the reserved `"new"`. */
  id: string;
  /** As typed. Empty means the group has no heading, which the first group has never had. */
  title: string;
  /** `NavItem.id`s, in the order they will appear. */
  items: string[];
}

/**
 * Every destination there is, by id, for the label and icon each row shows.
 *
 * <p>Read once at module load from {@link standardMenu}, which hands back copies, so nothing here
 * can reach the array `nav.ts` shares with the rest of the page.
 */
const EVERY_ITEM: Map<string, NavItem> = new Map(
  standardMenu()
    .flatMap((group) => group.items)
    .map((item) => [item.id, item])
);

/**
 * The groups that cannot be deleted, however empty they are (D-M11).
 *
 * <p><b>Why any group is protected at all.</b> Nine destinations are deliberately not on this board:
 * a volunteer's four and the platform operator's five, because no administrator can reach them
 * (D-M7). They are not lost — `applyMenuLayout` puts every destination this arrangement does not
 * place at the end of its standard group. But if that standard group is not in the arrangement at
 * all, the merge has nowhere to put them and drops them at the bottom under a group headed "New". So
 * deleting such a group leaves a volunteer at this temple with their whole menu under a heading
 * reading "New", and the administrator who did it cannot see that happen, because none of it is on
 * this screen. Nothing is lost, nothing is hidden, the role filter is untouched and Reset undoes it,
 * so it is not a defect — but a change whose only visible effect is on a role you are not looking at
 * is one a person cannot learn from, and that is why it is refused.
 *
 * <p><b>Keyed on the group's id, and that is the whole point of the rule</b> — not on its position,
 * which is a coincidence of the standard arrangement. `applyMenuLayout` decides where an unplaced
 * destination lands with `mergedById.get(group.id)`: it looks for the *standard group's id* in the
 * temple's arrangement, wherever in the order that group now sits and whatever heading it now
 * carries. So an administrator who moves this group to the bottom has changed nothing about where
 * those nine destinations land, and the group that is now first is an ordinary group they may delete.
 * A position rule would protect the wrong group in exactly that case.
 *
 * <p><b>Worked out from `nav.ts` rather than written down as `"main"`</b>, so that it stays true. The
 * rule is "a group that is the standard home of a destination this board never shows", and that is
 * this expression. Today it yields exactly one group, the unheaded first one; if a later release puts
 * a volunteer-only or operator-only destination into another group, that group is protected too, on
 * the day it happens rather than on the day somebody notices.
 */
const PROTECTED_GROUP_IDS: ReadonlySet<string> = new Set(
  standardMenu()
    .filter((group) => group.items.some((item) => !item.roles.includes("TEMPLE_ADMIN")))
    .map((group) => group.id)
);

/** The bound the server puts on an arrangement, said here so the button can say it before it is hit. */
const MAX_GROUPS = 20;
/** The bound the server puts on a heading. The box stops at it rather than letting a save be refused. */
const MAX_HEADING = 40;

/**
 * The board, built from the standard menu and the temple's own arrangement and then narrowed to what
 * a Temple Admin can reach.
 *
 * <p>The merge runs first and the narrowing second, in that order, because the merge is what decides
 * where a destination the arrangement has never heard of belongs — and it can only do that while it
 * can still see every group.
 */
function boardFrom(layout: MenuLayout | null | undefined): DraftGroup[] {
  return applyMenuLayout(standardMenu(), layout).map((group) => ({
    id: group.id,
    title: group.title ?? "",
    items: group.items.filter((item) => item.roles.includes("TEMPLE_ADMIN")).map((item) => item.id),
  }));
}

/**
 * The board as one string, for comparing two of them.
 *
 * <p>A primitive rather than an object, and that is not a style choice: an effect whose dependency is
 * a freshly-built object re-runs on every render for ever. The same trap the Settings screen's two
 * warning numbers were split apart to avoid.
 */
function fingerprint(board: DraftGroup[]): string {
  return JSON.stringify(board.map((g) => ({ id: g.id, title: g.title.trim(), items: g.items })));
}

/** What gets sent. A heading of nothing is `null` — the wire's way of saying the group has none. */
function asLayout(board: DraftGroup[]): MenuLayout {
  return {
    version: 1,
    groups: board.map((group) => ({
      id: group.id,
      title: group.title.trim() || null,
      items: group.items,
    })),
  };
}

/** Where an item is on the board, or `[-1, -1]` if it is not on it at all. */
function locate(board: DraftGroup[], itemId: string): [number, number] {
  for (let gi = 0; gi < board.length; gi++) {
    const ii = board[gi].items.indexOf(itemId);
    if (ii >= 0) return [gi, ii];
  }
  return [-1, -1];
}

/**
 * An item moved to `index` in group `toGroup`, where `index` is counted before the item is taken out
 * of where it was. Counting it the other way round makes every "move down by one" an off-by-one.
 */
function withItemMoved(board: DraftGroup[], itemId: string, toGroup: number, index: number): DraftGroup[] {
  const [gi, ii] = locate(board, itemId);
  if (gi < 0 || toGroup < 0 || toGroup >= board.length) return board;
  const next = board.map((group) => ({ ...group, items: [...group.items] }));
  next[gi].items.splice(ii, 1);
  const at = gi === toGroup && ii < index ? index - 1 : index;
  next[toGroup].items.splice(Math.max(0, Math.min(at, next[toGroup].items.length)), 0, itemId);
  return next;
}

/** A group moved to `index`, counted the same way and for the same reason. */
function withGroupMoved(board: DraftGroup[], groupId: string, index: number): DraftGroup[] {
  const from = board.findIndex((group) => group.id === groupId);
  if (from < 0) return board;
  const next = [...board];
  const [moved] = next.splice(from, 1);
  const at = from < index ? index - 1 : index;
  next.splice(Math.max(0, Math.min(at, next.length)), 0, moved);
  return next;
}

/**
 * What to call a group in a sentence.
 *
 * <p>Its heading when it has one. When it has not — and the first group never has — its position,
 * because that is the only thing about it a person can point at. Never the words of the menu path it
 * used to be part of: the moment a temple moves an item, a sentence naming the group it came from is
 * a sentence that has gone wrong for that temple first.
 */
function groupName(group: DraftGroup, index: number): string {
  return group.title.trim() || `group ${index + 1}`;
}

/**
 * Why this group can't be deleted, in the words the screen says it in — or `null` when it can be.
 *
 * <p>One function for both refusals, and it returns one sentence, because the two overlap: the last
 * group left may also be a protected one, and two greyed-out reasons for one greyed-out button teach
 * a person less than either of them does alone. <b>The last group left is the sentence
 * that wins</b>, and not arbitrarily: with one group on the board that refusal is the one that binds
 * whatever group it is, and the other becomes true again — and is then shown — the moment they make a
 * second group and could otherwise act on it.
 *
 * <p>Deleting an emptied group is what this refuses, and nothing else. Emptying one is still allowed
 * (D-M2): a group with nothing in it stays on the board with its empty-state line, and it disappears
 * from a person's own menu without taking anybody else's destinations with it.
 */
function whyUndeletable(board: DraftGroup[], group: DraftGroup): string | null {
  if (board.length < 2) return "A menu needs one group, so this one can’t be deleted.";
  if (PROTECTED_GROUP_IDS.has(group.id)) {
    return "Other people see destinations in this group that you can’t, so it can’t be deleted. You can still move your own items out of it.";
  }
  return null;
}

/** The label a row shows, which is `nav.ts`'s and is never a temple's to change. */
function itemLabel(itemId: string): string {
  return EVERY_ITEM.get(itemId)?.label ?? itemId;
}

/**
 * An id for a group the temple has just made.
 *
 * <p>Lower-case letters, digits and hyphens, because that is the shape the server accepts, and
 * prefixed with a letter so it always starts with one. Never `"new"`: `nav.ts` reserves that id for
 * the group the merge itself creates at the bottom for a destination whose standard group the temple
 * no longer has, and two groups with one id would leave the menu indexing one of them over the other.
 */
function mintGroupId(taken: Set<string>): string {
  for (let attempt = 0; attempt < 50; attempt++) {
    const id = `g-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`;
    if (id !== NEW_GROUP_ID && !taken.has(id)) return id;
  }
  // Fifty collisions in a row is not a thing that happens, but a group without an id would be, so
  // the last resort is a counter that cannot collide with anything already on the board.
  let n = taken.size;
  while (taken.has(`g-${n}`)) n++;
  return `g-${n}`;
}

/** What is being dragged, if anything. */
type Dragged = { kind: "item"; id: string } | { kind: "group"; id: string } | null;

export function MenuArranger() {
  const { appUser, getToken, refresh } = useAuth();

  // What the session says is saved, and its fingerprint — a string, so the effect below has a
  // primitive to watch rather than an object rebuilt on every render.
  const savedInSession = useMemo(() => boardFrom(appUser?.menuLayout), [appUser?.menuLayout]);
  const savedBoard = useMemo(() => fingerprint(savedInSession), [savedInSession]);
  const [board, setBoard] = useState<DraftGroup[]>(savedInSession);
  const applied = useRef(savedBoard);
  /**
   * What this screen knows it has saved, when the session has not caught up.
   *
   * <p>Only ever set when a save succeeded and the refresh after it did not. Without it the screen
   * contradicts itself in that one case: "Saved." at the top and "Not saved yet" beside the button,
   * both about the same arrangement. It is dropped the moment the session does catch up.
   */
  const [savedHere, setSavedHere] = useState<DraftGroup[] | null>(null);

  const [busy, setBusy] = useState<"save" | "reset" | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [flash, setFlash] = useState<string | null>(null);
  const [announcement, setAnnouncement] = useState("");
  // Three inline panels, each open for one thing at a time. None of them is a browser dialog:
  // prompt() and confirm() block the page, cannot be styled, and cannot be driven by a test.
  const [creating, setCreating] = useState<{ title: string; at: number } | null>(null);
  const [deleting, setDeleting] = useState<{ id: string; target: string } | null>(null);
  const [resetting, setResetting] = useState(false);

  const drag = useRef<Dragged>(null);
  const [dropHint, setDropHint] = useState<string | null>(null);
  /** The id {@link focusSoon} was last asked for, cleared by the effect that acts on it. */
  const wanted = useRef<string | null>(null);

  useEffect(() => {
    const id = wanted.current;
    if (!id) return;
    wanted.current = null;
    document.getElementById(id)?.focus();
  });

  // Rebases the board when what is saved changes under it — after this screen's own Save or Reset,
  // and after a temple switch. Keyed on the fingerprint, which is a string, so it cannot loop; the
  // ref is what keeps it from running on the first render, when the two already agree.
  useEffect(() => {
    if (applied.current === savedBoard) return;
    applied.current = savedBoard;
    setBoard(savedInSession);
    setSavedHere(null);
  }, [savedBoard, savedInSession]);

  /** What Cancel goes back to, and what "Not saved yet" is measured against. */
  const saved = savedHere ?? savedInSession;
  const dirty = fingerprint(board) !== fingerprint(saved);
  const working = busy !== null;

  /**
   * Puts the keyboard on `id` once React has drawn it.
   *
   * <p><b>An effect, not a `requestAnimationFrame`.</b> The playground used a frame, and measured in
   * a real browser it does not work: after Add group the focus landed on `document.body`, because
   * React had not committed the new group by the time the frame ran, so there was no element with
   * that id to focus. An effect with no dependency list runs after every commit, which is exactly
   * when the element exists. The ref is what keeps it from stealing focus on renders nobody asked to
   * move the keyboard.
   *
   * <p>It matters most where a control is replaced rather than moved: an item moving within its
   * group keeps its own DOM node and the browser keeps the focus on it, but an item crossing into
   * another group, a group being deleted and the New group panel closing all take the focused
   * element out of the page, and without this the keyboard goes back to the top of the document.
   */
  function focusSoon(id: string) {
    wanted.current = id;
  }

  function apply(next: DraftGroup[], said: string) {
    setBoard(next);
    setFlash(null);
    setAnnouncement(said);
  }

  function sayWhereItemIs(next: DraftGroup[], itemId: string) {
    const [gi, ii] = locate(next, itemId);
    if (gi < 0) return "";
    return `${itemLabel(itemId)} is now ${ii + 1} of ${next[gi].items.length} in ${groupName(next[gi], gi)}.`;
  }

  function moveItem(itemId: string, direction: -1 | 1) {
    const [gi, ii] = locate(board, itemId);
    if (gi < 0) return;
    let next = board;
    if (direction < 0) {
      if (ii > 0) next = withItemMoved(board, itemId, gi, ii - 1);
      else if (gi > 0) next = withItemMoved(board, itemId, gi - 1, board[gi - 1].items.length);
      else return;
    } else if (ii < board[gi].items.length - 1) {
      next = withItemMoved(board, itemId, gi, ii + 2);
    } else if (gi < board.length - 1) {
      next = withItemMoved(board, itemId, gi + 1, 0);
    } else {
      return;
    }
    apply(next, sayWhereItemIs(next, itemId));
    focusSoon(`${direction < 0 ? "up" : "down"}-${itemId}`);
  }

  function moveItemToGroup(itemId: string, groupId: string) {
    const to = board.findIndex((group) => group.id === groupId);
    if (to < 0) return;
    const next = withItemMoved(board, itemId, to, board[to].items.length);
    apply(next, sayWhereItemIs(next, itemId));
    focusSoon(`to-${itemId}`);
  }

  function moveGroup(groupId: string, direction: -1 | 1) {
    const from = board.findIndex((group) => group.id === groupId);
    const to = direction < 0 ? from - 1 : from + 2;
    if (from < 0 || to < 0 || to > board.length) return;
    const next = withGroupMoved(board, groupId, to);
    const now = next.findIndex((group) => group.id === groupId);
    apply(next, `${groupName(next[now], now)} is now group ${now + 1} of ${next.length}.`);
    focusSoon(`${direction < 0 ? "group-up" : "group-down"}-${groupId}`);
  }

  function renameGroup(groupId: string, title: string) {
    setBoard(board.map((group) => (group.id === groupId ? { ...group, title } : group)));
    setFlash(null);
  }

  function addGroup() {
    if (!creating) return;
    const title = creating.title.trim();
    if (!title) return;
    const id = mintGroupId(new Set(board.map((group) => group.id)));
    const next = [...board];
    next.splice(Math.max(0, Math.min(creating.at, next.length)), 0, { id, title, items: [] });
    apply(next, `${title} added as group ${Math.min(creating.at, board.length) + 1} of ${next.length}.`);
    setCreating(null);
    focusSoon(`heading-${id}`);
  }

  function askToDelete(group: DraftGroup) {
    if (whyUndeletable(board, group) !== null) return;
    if (group.items.length === 0) {
      deleteGroup(group.id, null);
      return;
    }
    const other = board.find((g) => g.id !== group.id);
    setDeleting({ id: group.id, target: other ? other.id : "" });
    focusSoon(`delete-target-${group.id}`);
  }

  /** Deletes a group. Anything in it goes to the end of `target`, so nothing can be lost by deleting. */
  function deleteGroup(groupId: string, target: string | null) {
    const doomed = board.find((group) => group.id === groupId);
    // The same guard as the button's, asked again here rather than trusted: this is the only line
    // that actually takes a group off the board, and it is reachable from the delete panel, which
    // stays open across other edits.
    if (!doomed || whyUndeletable(board, doomed) !== null) return;
    const index = board.findIndex((group) => group.id === groupId);
    const next = board
      .filter((group) => group.id !== groupId)
      .map((group) =>
        group.id === target ? { ...group, items: [...group.items, ...doomed.items] } : group
      );
    const moved = target ? board.find((group) => group.id === target) : null;
    const movedTo = moved ? next.findIndex((group) => group.id === moved.id) : -1;
    apply(
      next,
      doomed.items.length === 0 || movedTo < 0
        ? `${groupName(doomed, index)} deleted. ${next.length} groups left.`
        : `${groupName(doomed, index)} deleted and its ${doomed.items.length} items moved to ${groupName(next[movedTo], movedTo)}.`
    );
    setDeleting(null);
    focusSoon("new-group-button");
  }

  function cancelEdits() {
    setBoard(saved);
    setCreating(null);
    setDeleting(null);
    setError(null);
    setFlash(null);
    setAnnouncement("Your changes have been put back to the saved arrangement.");
  }

  async function save() {
    setBusy("save");
    setError(null);
    setFlash(null);
    try {
      await api.saveMenuLayout(asLayout(board), await getToken());
    } catch (e) {
      setError(toApiError(e, "We couldn’t save your menu arrangement."));
      setBusy(null);
      return;
    }
    // Two acts, and only the first of them can fail in a way worth reporting. The arrangement is
    // stored by the time we get here; refreshing the session is what repaints the menu beside this
    // screen. If that second step fails, saying "we couldn’t save" would be false, and would have
    // the administrator save a thing that is already saved — so it says what is actually true.
    setSavedHere(board);
    try {
      await refresh();
      setFlash("Saved. Everyone at this temple sees this arrangement now.");
    } catch {
      setFlash("Saved. The menu on the left catches up the next time you open a page.");
    } finally {
      setBusy(null);
    }
  }

  async function resetToStandard() {
    setBusy("reset");
    setError(null);
    setFlash(null);
    try {
      await api.resetMenuLayout(await getToken());
    } catch (e) {
      setError(toApiError(e, "We couldn’t put the standard menu back."));
      setBusy(null);
      return;
    }
    const standard = boardFrom(null);
    setBoard(standard);
    setSavedHere(standard);
    setResetting(false);
    // Split for the same reason as Save above: the arrangement is already forgotten by here.
    try {
      await refresh();
      setFlash("The standard menu is back for everyone at this temple.");
    } catch {
      setFlash("The standard menu is back. The menu on the left catches up the next time you open a page.");
    } finally {
      setBusy(null);
    }
  }

  // ---- dragging ------------------------------------------------------------
  // The dragged thing is held in a ref because `dataTransfer` cannot be read during `dragover`, and
  // `setData` is still called because Firefox will not start a drag without it.

  function isAfter(event: React.DragEvent<HTMLElement>) {
    const box = event.currentTarget.getBoundingClientRect();
    return event.clientY > box.top + box.height / 2;
  }

  function endDrag() {
    drag.current = null;
    setDropHint(null);
  }

  function onItemDragOver(event: React.DragEvent<HTMLElement>, itemId: string) {
    if (drag.current?.kind !== "item") return;
    event.preventDefault();
    event.stopPropagation();
    setDropHint(`${isAfter(event) ? "after" : "before"}:item:${itemId}`);
  }

  function onItemDrop(event: React.DragEvent<HTMLElement>, gi: number, ii: number) {
    const dragged = drag.current;
    if (dragged?.kind !== "item") return;
    event.preventDefault();
    event.stopPropagation();
    const next = withItemMoved(board, dragged.id, gi, isAfter(event) ? ii + 1 : ii);
    apply(next, sayWhereItemIs(next, dragged.id));
    endDrag();
  }

  function onGroupDragOver(event: React.DragEvent<HTMLElement>, groupId: string) {
    const dragged = drag.current;
    if (!dragged) return;
    event.preventDefault();
    setDropHint(
      dragged.kind === "group"
        ? `${isAfter(event) ? "after" : "before"}:group:${groupId}`
        : `into:group:${groupId}`
    );
  }

  function onGroupDrop(event: React.DragEvent<HTMLElement>, gi: number) {
    const dragged = drag.current;
    if (!dragged) return;
    event.preventDefault();
    if (dragged.kind === "group") {
      const next = withGroupMoved(board, dragged.id, isAfter(event) ? gi + 1 : gi);
      const now = next.findIndex((group) => group.id === dragged.id);
      apply(next, `${groupName(next[now], now)} is now group ${now + 1} of ${next.length}.`);
    } else {
      // Dropped on the group but not on one of its items: the end of it, which is also the only way
      // to drag something into a group that has been emptied.
      const next = withItemMoved(board, dragged.id, gi, board[gi].items.length);
      apply(next, sayWhereItemIs(next, dragged.id));
    }
    endDrag();
  }

  // ---- the screen ----------------------------------------------------------

  const full = board.length >= MAX_GROUPS;
  // Why each group on the board can't be deleted, in the board's own order, and null where it can
  // be. Worked out once per render rather than at each of the two places below that need it — the
  // button's disabled state and the sentence under it — so the two can never disagree about a group.
  const refusals = board.map((group) => whyUndeletable(board, group));

  return (
    <main className="mx-auto max-w-4xl px-4 py-8 sm:px-10 sm:py-12">
      <h1 className="text-3xl font-semibold text-ink">Menu</h1>
      <p className="mt-2 max-w-[60ch] text-ink-secondary">
        Arrange the left-hand menu for everyone at this temple. You choose the order and the
        grouping. What each person can open stays exactly what their role allows.
      </p>
      <p className="mt-2 max-w-[60ch] text-sm text-ink-secondary">
        Only the destinations you can open are listed here. Items can’t be renamed or hidden. To take
        a feature off the menu, switch the feature off.
      </p>

      {/*
        The bar follows the page down, because the board below it is thirty-three rows long and Save
        has to be reachable from the bottom of it. Below `lg` it stops under the phone bar, which is
        itself sticky at the top of the page and 56px tall; above `lg` that bar is not rendered and
        this one sits at the very top. Its own z is below the phone bar's, so an overlap can only ever
        be this bar passing under that one.
      */}
      <div className="sticky top-14 z-20 -mx-4 mt-6 flex flex-wrap items-center gap-3 border-b border-hairline bg-canvas px-4 py-3 sm:-mx-10 sm:px-10 lg:top-0">
        <button
          id="new-group-button"
          type="button"
          aria-expanded={creating !== null}
          // Only while the panel is in the page: an `aria-controls` pointing at an id that is not
          // there names nothing, and an audit reads it as a broken reference rather than a closed
          // panel.
          aria-controls={creating ? "new-group-form" : undefined}
          disabled={working || full}
          onClick={() => setCreating(creating ? null : { title: "", at: board.length })}
          className="btn btn-secondary inline-flex min-h-touch items-center gap-2 px-4 text-sm disabled:opacity-60"
        >
          <i className="ti ti-folder-plus text-lg" aria-hidden="true" />
          New group
        </button>
        <span className="flex-1" />
        {dirty && <span className="text-sm text-ink-secondary">Not saved yet</span>}
        <button
          type="button"
          onClick={cancelEdits}
          disabled={working || !dirty}
          className="btn btn-quiet inline-flex min-h-touch items-center px-4 text-sm disabled:opacity-60"
        >
          Cancel
        </button>
        <button
          type="button"
          onClick={save}
          disabled={working || !dirty}
          className="btn btn-primary inline-flex min-h-touch items-center px-4 text-sm disabled:opacity-60"
        >
          {busy === "save" ? "Saving…" : "Save"}
        </button>
        {/*
          One region, always in the page and empty until there is something to say, because a live
          region added at the moment of the announcement is the one screen readers miss. It carries
          what just moved and where it landed, which is the whole of what a person who cannot see the
          list needs after pressing Move up.
        */}
        <p role="status" aria-live="polite" className="min-h-5 basis-full text-sm text-ink-secondary">
          {announcement}
        </p>
      </div>

      {error && (
        <div className="mt-4">
          <ErrorNotice error={error} />
        </div>
      )}
      {flash && !error && (
        <div className="mt-4">
          <InlineNotice tone="success" autoDismiss>
            {flash}
          </InlineNotice>
        </div>
      )}
      {full && (
        <p className="mt-4 text-sm text-warning">
          This menu has {MAX_GROUPS} groups, which is as many as it can have. Delete one to make
          another.
        </p>
      )}

      {creating && (
        <Form
          id="new-group-form"
          aria-label="New group"
          className="card mt-4 flex flex-wrap items-end gap-3 px-4 py-4 sm:px-5"
          onSubmit={(event) => {
            event.preventDefault();
            addGroup();
          }}
          onKeyDown={(event) => {
            if (event.key === "Escape") {
              setCreating(null);
              focusSoon("new-group-button");
            }
          }}
        >
          <label className="grid min-w-0 flex-1 basis-full gap-1 sm:basis-48">
            <span className={FIELD_LABEL}>Heading</span>
            <input
              id="new-group-title"
              autoFocus
              required
              maxLength={MAX_HEADING}
              autoComplete="off"
              value={creating.title}
              onChange={(event) => setCreating({ ...creating, title: event.target.value })}
              className="min-h-touch w-full rounded-control border border-hairline px-3 text-ink"
            />
          </label>
          <label className="grid min-w-0 flex-1 basis-full gap-1 sm:basis-56">
            <span className={FIELD_LABEL}>Where it goes</span>
            <select
              id="new-group-at"
              value={creating.at}
              onChange={(event) => setCreating({ ...creating, at: Number(event.target.value) })}
              className="min-h-touch w-full rounded-control border border-hairline px-3 text-ink"
            >
              <option value={0}>At the top</option>
              {board.map((group, gi) => (
                <option key={group.id} value={gi + 1}>
                  {gi === board.length - 1 ? "At the bottom" : `After ${groupName(group, gi)}`}
                </option>
              ))}
            </select>
          </label>
          <button
            type="submit"
            className="btn btn-primary inline-flex min-h-touch items-center px-4 text-sm"
          >
            Add group
          </button>
          <button
            type="button"
            onClick={() => {
              setCreating(null);
              focusSoon("new-group-button");
            }}
            className="btn btn-quiet inline-flex min-h-touch items-center px-4 text-sm"
          >
            Cancel
          </button>
        </Form>
      )}

      <ol className="mt-4 grid gap-4">
        {board.map((group, gi) => (
          <li key={group.id}>
            <section
              aria-label={`Group: ${groupName(group, gi)}`}
              onDragOver={(event) => onGroupDragOver(event, group.id)}
              onDrop={(event) => onGroupDrop(event, gi)}
              className={[
                "card grid gap-3 px-4 py-4 sm:px-5",
                dropHint === `before:group:${group.id}` ? "border-t-4 border-t-accent" : "",
                dropHint === `after:group:${group.id}` ? "border-b-4 border-b-accent" : "",
                dropHint === `into:group:${group.id}` ? "ring-2 ring-accent" : "",
              ].join(" ")}
            >
              {/*
                The 9px inset — 8px of padding and a transparent 1px border — is not decoration. It
                is exactly the inset an item row below gets from its own border and padding, so the
                heading row's grip, its controls and their two edges land on the same vertical lines
                as every item's. Measured: without it the header sat 9px wider than the rows under it
                at both ends.
              */}
              <div className="flex flex-wrap items-end gap-2 rounded-control border border-transparent px-2">
                <span
                  draggable
                  onDragStart={(event) => {
                    drag.current = { kind: "group", id: group.id };
                    event.dataTransfer.setData("text/plain", `group:${group.id}`);
                    event.dataTransfer.effectAllowed = "move";
                  }}
                  onDragEnd={endDrag}
                  aria-hidden="true"
                  className="hidden h-11 cursor-grab items-center px-1 text-ink-muted sm:flex"
                >
                  <i className="ti ti-grip-vertical text-lg" />
                </span>
                {/*
                  Capped rather than stretched, so that every line in this card has the same shape:
                  what it is on the left, what you can do to it on the right. A heading box running
                  the width of the card would be the one row of the group that looked different, and
                  it would offer 900px of room for something the server stops at forty characters.
                */}
                <label className="grid min-w-0 flex-1 basis-full gap-1 sm:max-w-sm sm:basis-48">
                  <span className={FIELD_LABEL}>Heading</span>
                  <input
                    id={`heading-${group.id}`}
                    value={group.title}
                    maxLength={MAX_HEADING}
                    autoComplete="off"
                    placeholder="No heading"
                    aria-label={`Heading for ${groupName(group, gi)}`}
                    onChange={(event) => renameGroup(group.id, event.target.value)}
                    className="min-h-touch w-full rounded-control border border-hairline px-3 text-ink"
                  />
                </label>
                {/*
                  Three controls of the same three widths as an item row's, at the same trailing
                  edge, so a group's Move up sits in the same column as every item's. Delete is the
                  one that carries words rather than a picture: it is the only control here that
                  takes something away, and it is the third column, where an item row has its Move to
                  group list. Its `aria-label` names the group, because seven buttons all saying
                  "Delete" are seven identical rows to anybody reading them one at a time.
                */}
                <div className="flex flex-1 basis-full items-center gap-1 sm:ms-auto sm:flex-none sm:basis-auto">
                  <IconButton
                    id={`group-up-${group.id}`}
                    icon="arrow-up"
                    label={`Move ${groupName(group, gi)} up`}
                    disabled={gi === 0}
                    onClick={() => moveGroup(group.id, -1)}
                  />
                  <IconButton
                    id={`group-down-${group.id}`}
                    icon="arrow-down"
                    label={`Move ${groupName(group, gi)} down`}
                    disabled={gi === board.length - 1}
                    onClick={() => moveGroup(group.id, 1)}
                  />
                  <button
                    id={`delete-${group.id}`}
                    type="button"
                    aria-label={`Delete ${groupName(group, gi)}`}
                    disabled={refusals[gi] !== null || deleting?.id === group.id}
                    onClick={() => askToDelete(group)}
                    className="btn btn-quiet inline-flex min-h-touch flex-1 items-center justify-center gap-2 px-3 text-sm disabled:opacity-40 sm:w-48 sm:flex-none"
                  >
                    <i className="ti ti-trash text-lg" aria-hidden="true" />
                    Delete
                  </button>
                </div>
                {/*
                  One sentence or none, never two: {@link whyUndeletable} picks which refusal a
                  person reads when both are true. It sits under the three controls, on its own line,
                  where the button it explains is — a disabled button carries no tooltip a keyboard
                  or a touch screen can reach, so the reason has to be on the page beside it.
                */}
                {refusals[gi] && (
                  <p className="basis-full text-sm text-ink-muted">{refusals[gi]}</p>
                )}
              </div>

              {deleting?.id === group.id && (
                <div
                  role="group"
                  aria-label={`Delete ${groupName(group, gi)}`}
                  className="flex flex-wrap items-end gap-3 rounded-card bg-sunken px-4 py-3"
                  onKeyDown={(event) => {
                    if (event.key === "Escape") {
                      setDeleting(null);
                      focusSoon(`delete-${group.id}`);
                    }
                  }}
                >
                  <label className="grid min-w-0 flex-1 basis-full gap-1 sm:basis-56">
                    <span className={FIELD_LABEL}>
                      {group.items.length === 1
                        ? "This group holds 1 item. Move it to"
                        : `This group holds ${group.items.length} items. Move them to`}
                    </span>
                    <select
                      id={`delete-target-${group.id}`}
                      value={deleting.target}
                      onChange={(event) => setDeleting({ ...deleting, target: event.target.value })}
                      className="min-h-touch w-full rounded-control border border-hairline px-3 text-ink"
                    >
                      {board.map((other, oi) =>
                        other.id === group.id ? null : (
                          <option key={other.id} value={other.id}>
                            {groupName(other, oi)}
                          </option>
                        )
                      )}
                    </select>
                  </label>
                  <button
                    type="button"
                    onClick={() => deleteGroup(group.id, deleting.target)}
                    className="btn btn-danger inline-flex min-h-touch items-center px-4 text-sm"
                  >
                    Move and delete group
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      setDeleting(null);
                      focusSoon(`delete-${group.id}`);
                    }}
                    className="btn btn-quiet inline-flex min-h-touch items-center px-4 text-sm"
                  >
                    Cancel
                  </button>
                </div>
              )}

              <ul className="grid gap-2">
                {group.items.map((itemId, ii) => {
                  const item = EVERY_ITEM.get(itemId);
                  if (!item) return null;
                  return (
                    <li
                      key={itemId}
                      draggable
                      onDragStart={(event) => {
                        event.stopPropagation();
                        drag.current = { kind: "item", id: itemId };
                        event.dataTransfer.setData("text/plain", `item:${itemId}`);
                        event.dataTransfer.effectAllowed = "move";
                      }}
                      onDragEnd={endDrag}
                      onDragOver={(event) => onItemDragOver(event, itemId)}
                      onDrop={(event) => onItemDrop(event, gi, ii)}
                      className={[
                        "flex flex-wrap items-center gap-2 rounded-control border border-hairline bg-raised px-2 py-1.5",
                        dropHint === `before:item:${itemId}`
                          ? "shadow-[inset_0_3px_0_0_rgb(var(--kms-accent))]"
                          : "",
                        dropHint === `after:item:${itemId}`
                          ? "shadow-[inset_0_-3px_0_0_rgb(var(--kms-accent))]"
                          : "",
                      ].join(" ")}
                    >
                      <span className="flex min-w-0 flex-1 basis-full items-center gap-3 sm:basis-auto">
                        <i
                          className="ti ti-grip-vertical hidden text-base text-ink-muted sm:inline"
                          aria-hidden="true"
                        />
                        <i className={`ti ti-${item.icon} text-lg text-ink-secondary`} aria-hidden="true" />
                        <span className="min-w-0 text-ink">{item.label}</span>
                      </span>
                      {/*
                        The three controls travel together and sit at the trailing edge of the row.
                        Read as a two-column table — what the destination is, and what you can do to
                        it — that is what §5 rule 3 asks for: the width the two columns do not need
                        goes into the one gap between them, so the labels start at one edge and the
                        buttons end at the other. It is also what makes pressing Move up down a list
                        possible without moving the pointer, because every row's button is in the
                        same place.

                        Below `sm` they take a line of their own under the label, with the select
                        stretching into whatever the two buttons leave.
                      */}
                      <span className="flex min-w-0 flex-1 basis-full items-center justify-end gap-1 sm:flex-none sm:basis-auto">
                        <IconButton
                          id={`up-${itemId}`}
                          icon="arrow-up"
                          label={`Move ${item.label} up`}
                          disabled={gi === 0 && ii === 0}
                          onClick={() => moveItem(itemId, -1)}
                        />
                        <IconButton
                          id={`down-${itemId}`}
                          icon="arrow-down"
                          label={`Move ${item.label} down`}
                          disabled={gi === board.length - 1 && ii === group.items.length - 1}
                          onClick={() => moveItem(itemId, 1)}
                        />
                        <select
                          id={`to-${itemId}`}
                          value={group.id}
                          aria-label={`Move ${item.label} to another group`}
                          onChange={(event) => moveItemToGroup(itemId, event.target.value)}
                          className="min-h-touch min-w-0 flex-1 rounded-control border border-hairline px-2 text-sm text-ink sm:w-48 sm:flex-none"
                        >
                          {board.map((other, oi) => (
                            <option key={other.id} value={other.id}>
                              {other.id === group.id
                                ? `In ${groupName(other, oi)}`
                                : `Move to ${groupName(other, oi)}`}
                            </option>
                          ))}
                        </select>
                      </span>
                    </li>
                  );
                })}
                {group.items.length === 0 && (
                  <li
                    className={[
                      "rounded-control border border-dashed border-hairline px-3 py-4 text-sm text-ink-muted",
                      dropHint === `into:group:${group.id}` ? "border-accent" : "",
                    ].join(" ")}
                  >
                    Nothing in this group yet. Move an item here with its Move to group list, or drag
                    one in. A group with nothing in it doesn’t appear in the menu.
                  </li>
                )}
              </ul>
            </section>
          </li>
        ))}
      </ol>

      {/*
        Reset sits at the foot, away from Save, and asks first. It throws away work done for
        everybody at the temple, and it is a thing done once in a temple's life, so it has no claim
        on the bar at the top that Save needs.
      */}
      <section className="card mt-6 px-5 py-6 sm:px-7 sm:py-7" aria-label="The standard menu">
        <h2 className="text-lg font-semibold text-ink">The standard menu</h2>
        <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
          Put back the order and the grouping the application ships with. Your arrangement is
          forgotten, for everyone at this temple.
        </p>
        {resetting ? (
          <div
            role="group"
            aria-label="Reset to the standard menu"
            className="mt-5 flex flex-wrap items-center gap-3 rounded-card bg-sunken px-4 py-3"
          >
            <p className="basis-full text-sm text-ink">
              Reset the menu for everyone at this temple? This can’t be undone, though you can
              arrange it again.
            </p>
            <button
              type="button"
              onClick={resetToStandard}
              disabled={working}
              className="btn btn-danger inline-flex min-h-touch items-center px-4 text-sm disabled:opacity-60"
            >
              {busy === "reset" ? "Resetting…" : "Reset to the standard menu"}
            </button>
            <button
              type="button"
              onClick={() => setResetting(false)}
              disabled={working}
              className="btn btn-quiet inline-flex min-h-touch items-center px-4 text-sm disabled:opacity-60"
            >
              Cancel
            </button>
          </div>
        ) : (
          <div className="mt-5 flex">
            <button
              type="button"
              onClick={() => setResetting(true)}
              disabled={working}
              className="btn btn-quiet inline-flex min-h-touch items-center gap-2 px-4 text-sm disabled:opacity-60"
            >
              <i className="ti ti-restore text-lg" aria-hidden="true" />
              Reset to the standard menu
            </button>
          </div>
        )}
      </section>
    </main>
  );
}

/**
 * A square button with an icon and no visible words.
 *
 * <p>44px each way, which is the touch size the rest of the application uses, and its words live in
 * `aria-label` and `title` so that the name a screen reader reads and the name a pointer reveals are
 * the same string. Every one of them names the thing it moves — "Move Deliveries up", not "Move up"
 * — because a list of thirty-three identical buttons is unusable to anybody reading it one at a
 * time.
 */
function IconButton({
  id,
  icon,
  label,
  disabled,
  onClick,
}: {
  id: string;
  icon: string;
  label: string;
  disabled?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      id={id}
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      title={label}
      className="btn btn-quiet flex h-11 w-11 flex-none items-center justify-center text-sm disabled:opacity-40"
    >
      <i className={`ti ti-${icon} text-lg`} aria-hidden="true" />
    </button>
  );
}
