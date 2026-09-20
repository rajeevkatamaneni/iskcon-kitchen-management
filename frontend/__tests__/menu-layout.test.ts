import { describe, expect, it } from "vitest";
import { NEW_GROUP_TITLE, applyMenuLayout, navForRole, standardMenu } from "@/lib/nav";
import type { MenuLayout, PrincipalRole } from "@/lib/api";

/**
 * A temple's own arrangement of the left-hand menu (Settings → Menu, approved by Rajeev 2026-09-19)
 * and the merge that puts it together with the standard menu at render.
 *
 * <p>Two things these tests exist to hold down, above everything else:
 *
 * <ol>
 *   <li><b>An arrangement decides order and grouping, never access.</b> It cannot put a destination
 *       in front of somebody whose role does not already allow it, and cannot take one away from
 *       somebody whose role does. Several of the tests below therefore assert an <i>absence</i>, and
 *       an absence is a weak thing to assert on its own — so each of those also asserts that the
 *       menu it read is otherwise the menu it should be, and the negative control for this task
 *       records how many of them fail with the feature patched out and why the rest pass vacuously.
 *   <li><b>A menu can never come out empty.</b> Whatever is in the stored row — nothing, an empty
 *       list, or ids from a release nobody remembers — the person still gets a menu.
 * </ol>
 */

/** The arrangement as the wire carries it. */
const layout = (groups: MenuLayout["groups"]): MenuLayout => ({ version: 1, groups });

/** A group of the standard menu, as ids, so a test can rearrange it without retyping it. */
const standardItemIds = (groupId: string) =>
  standardMenu().find((g) => g.id === groupId)!.items.map((i) => i.id);

/** What the merge produced, read the way a person reads a menu: `[heading | null, ...item ids]`. */
const shapeOf = (groups: { title?: string; items: { id: string }[] }[]) =>
  groups.map((g) => [g.title ?? null, ...g.items.map((i) => i.id)]);

/** Every destination a role is actually offered, under an arrangement. */
const idsFor = (role: PrincipalRole, l?: MenuLayout | null, person?: { canPlanMeals?: boolean } | null) =>
  navForRole(role, person, l).flatMap((g) => g.items.map((i) => i.id));

/** The whole standard menu as ids, for the fallback tests. */
const standardShape = shapeOf(standardMenu());

describe("applyMenuLayout", () => {
  it("puts the items of a group in the order the temple put them", () => {
    const ordering = standardItemIds("ordering");
    expect(ordering[0]).toBe("shopping-list"); // guards the reversal below against a silent no-op
    const merged = applyMenuLayout(
      standardMenu(),
      layout([
        ...standardMenu().map((g) => ({
          id: g.id,
          title: g.title ?? null,
          items: g.id === "ordering" ? [...ordering].reverse() : g.items.map((i) => i.id),
        })),
      ]),
    );
    expect(merged.find((g) => g.id === "ordering")!.items.map((i) => i.id)).toEqual([...ordering].reverse());
    // And nothing else moved: rearranging one group is not a rearrangement of the menu.
    expect(merged.map((g) => g.id)).toEqual(standardMenu().map((g) => g.id));
    expect(merged.find((g) => g.id === "people")!.items.map((i) => i.id)).toEqual(standardItemIds("people"));
  });

  it("moves a destination from one group to another, and it is gone from the one it left", () => {
    // A temple that thinks of Equipment as a kitchen's business rather than a catalogue's.
    const merged = applyMenuLayout(
      standardMenu(),
      layout(
        standardMenu().map((g) => ({
          id: g.id,
          title: g.title ?? null,
          items:
            g.id === "inventory-recipes"
              ? standardItemIds("inventory-recipes").filter((id) => id !== "equipment")
              : g.id === "kitchens"
                ? [...standardItemIds("kitchens"), "equipment"]
                : g.items.map((i) => i.id),
        })),
      ),
    );
    expect(merged.find((g) => g.id === "inventory-recipes")!.items.map((i) => i.id)).not.toContain("equipment");
    expect(merged.find((g) => g.id === "kitchens")!.items.map((i) => i.id)).toEqual([
      "ingredient-requests", "issued-from-store", "kitchens", "equipment",
    ]);
    // Once only, and still the item nav.ts defines — the merge moves destinations, it does not
    // invent or rename them.
    const all = merged.flatMap((g) => g.items.map((i) => i.id));
    expect(all.filter((id) => id === "equipment")).toHaveLength(1);
    expect(merged.flatMap((g) => g.items).find((i) => i.id === "equipment")!.label).toBe("Equipment");
  });

  it("puts whole groups in the temple's order", () => {
    const reversed = [...standardMenu()].reverse();
    const merged = applyMenuLayout(
      standardMenu(),
      layout(reversed.map((g) => ({ id: g.id, title: g.title ?? null, items: g.items.map((i) => i.id) }))),
    );
    expect(merged.map((g) => g.id)).toEqual([
      "temple", "giving-outreach", "people", "kitchens", "inventory-recipes", "ordering", "main",
    ]);
    // Each group still holds its own items, in their own order.
    expect(merged[merged.length - 1].items.map((i) => i.id)).toEqual(standardItemIds("main"));
  });

  it("honours a renamed heading — and still lands an unplaced destination in that renamed group", () => {
    // The one that matters. The temple calls Ordering "Buying", and a release adds a destination to
    // the ordering group. Keyed on the words, it would have nowhere to go and would fall to the
    // bottom under "New"; keyed on the id, it lands where it belongs, under the temple's own word.
    const merged = applyMenuLayout(
      standardMenu(),
      layout(
        standardMenu().map((g) => ({
          id: g.id,
          title: g.id === "ordering" ? "Buying" : (g.title ?? null),
          // "Vendor performance" stands in for the destination the temple has never seen: it is in
          // the standard menu and not in this arrangement.
          items: g.items.map((i) => i.id).filter((id) => id !== "vendor-performance"),
        })),
      ),
    );
    const buying = merged.find((g) => g.id === "ordering")!;
    expect(buying.title).toBe("Buying");
    expect(buying.items.map((i) => i.id)).toEqual([
      "shopping-list", "orders", "deliveries", "invoices", "vendors", "vendor-performance",
    ]);
    expect(merged.map((g) => g.title)).not.toContain(NEW_GROUP_TITLE);
  });

  it("sends an unplaced destination to the bottom under New when its group is gone", () => {
    // Rajeev, 2026-09-19: "New screens in future releases land in their standard group if the temple
    // still has it, else at the bottom under 'New'." Here the temple has deleted the Kitchens group
    // (its items filed elsewhere) and "All kitchens" has nowhere of its own to go.
    const kept = standardMenu()
      .filter((g) => g.id !== "kitchens")
      .map((g) => ({
        id: g.id,
        title: g.title ?? null,
        items:
          g.id === "inventory-recipes"
            ? [...standardItemIds("inventory-recipes"), "ingredient-requests", "issued-from-store"]
            : g.items.map((i) => i.id),
      }));
    const merged = applyMenuLayout(standardMenu(), layout(kept));

    const last = merged[merged.length - 1];
    expect(last.title).toBe("New");
    expect(last.items.map((i) => i.id)).toEqual(["kitchens"]);
    // The group is made by the merge and is not in what the temple saved — nothing stores it, so it
    // disappears of its own accord the moment the admin files what is in it.
    expect(kept.map((g) => g.title)).not.toContain("New");
    expect(kept.map((g) => g.id)).not.toContain(last.id);
    // The two the temple did file are where it filed them, not in New.
    expect(merged.find((g) => g.id === "inventory-recipes")!.items.map((i) => i.id)).toContain("issued-from-store");
  });

  it("passes over an id it has never heard of, without disturbing what is around it", () => {
    // A destination withdrawn in a later release. It stays in the stored row on purpose — nothing
    // here prunes it — so if it ever comes back it comes back where the temple put it.
    //
    // The temple's People group is in its own order here, not the standard one, so this test is
    // about the ghost rather than about the merge doing nothing: the ids around the ghost must come
    // out in the temple's order, closed up as if the ghost had never been listed.
    const withGhost = standardMenu().map((g) => ({
      id: g.id,
      title: g.title ?? null,
      items:
        g.id === "people"
          ? ["staff", "staff-schedule", "a-screen-that-was-withdrawn", "leave", "my-schedule", "users", "volunteers"]
          : g.items.map((i) => i.id),
    }));
    const merged = applyMenuLayout(standardMenu(), layout(withGhost));
    expect(merged.find((g) => g.id === "people")!.items.map((i) => i.id)).toEqual([
      "staff", "staff-schedule", "leave", "my-schedule", "users", "volunteers",
    ]);
    expect(merged.map((g) => g.title)).not.toContain(NEW_GROUP_TITLE);
    // The arrangement handed in is untouched: the merge reads, it does not prune.
    expect(withGhost.find((g) => g.id === "people")!.items).toContain("a-screen-that-was-withdrawn");
  });

  it("offers a destination once even if the arrangement lists it twice", () => {
    // The server refuses a save like this (KMS-400190). This is the belt to that brace: a menu in
    // the wrong order is a nuisance, a menu with one destination on it twice is a fault.
    const merged = applyMenuLayout(
      standardMenu(),
      layout(
        standardMenu().map((g) => ({
          id: g.id,
          title: g.title ?? null,
          // The Kitchens group is in the temple's own order as well, so this test cannot pass by
          // the merge simply handing back the standard menu.
          items:
            g.id === "kitchens"
              ? ["issued-from-store", "ingredient-requests", "kitchens", "recipes"]
              : g.items.map((i) => i.id),
        })),
      ),
    );
    const all = merged.flatMap((g) => g.items.map((i) => i.id));
    expect(all.filter((id) => id === "recipes")).toHaveLength(1);
    // The first place it is listed wins, which is the catalogue group it was already in.
    expect(merged.find((g) => g.id === "inventory-recipes")!.items.map((i) => i.id)).toContain("recipes");
    expect(merged.find((g) => g.id === "kitchens")!.items.map((i) => i.id)).toEqual([
      "issued-from-store", "ingredient-requests", "kitchens",
    ]);
  });

  it("keeps a group the temple emptied — and navForRole is what drops it", () => {
    // Deliberately two different answers to one question. The Settings screen has to see the empty
    // group to drag something back into it, and a kept group is where a later release's ordering
    // screen lands; a person's menu must never carry a heading over nothing.
    const emptied = standardMenu().map((g) => ({
      id: g.id,
      title: g.title ?? null,
      // Ordering is emptied into the first group, so nothing is left unplaced to fall back into it.
      items:
        g.id === "ordering"
          ? []
          : g.id === "main"
            ? [...standardItemIds("main"), ...standardItemIds("ordering")]
            : g.items.map((i) => i.id),
    }));
    const merged = applyMenuLayout(standardMenu(), layout(emptied));
    const ordering = merged.find((g) => g.id === "ordering");
    expect(ordering).toBeDefined();
    expect(ordering!.items).toEqual([]);

    expect(navForRole("TEMPLE_ADMIN", null, layout(emptied)).map((g) => g.id)).not.toContain("ordering");
    for (const group of navForRole("TEMPLE_ADMIN", null, layout(emptied))) {
      expect(group.items.length).toBeGreaterThan(0);
    }
  });
});

describe("an arrangement and who may see what", () => {
  /** Everything in one group, in one order, so the arrangement is as aggressive as it can be. */
  const oneHeap = layout([
    {
      id: "main",
      title: null,
      items: standardMenu().flatMap((g) => g.items.map((i) => i.id)),
    },
  ]);

  it("cannot grant a destination to a role that may not reach it", () => {
    // The arrangement names every destination there is, including the ones each of these roles is
    // refused at. The role filter runs after the merge and is the only thing that decides.
    const cook = idsFor("KITCHEN_STAFF", oneHeap);
    for (const adminOnly of ["staff", "users", "audit", "communications", "wishlist", "notices", "kitchens"]) {
      expect(cook).not.toContain(adminOnly);
    }
    // ...while the cook's own destinations are all still there, so the absences above are the filter
    // working rather than the menu having collapsed.
    for (const theirs of ["today", "recipes", "inventory", "equipment", "my-schedule"]) {
      expect(cook).toContain(theirs);
    }

    const admin = idsFor("TEMPLE_ADMIN", oneHeap);
    for (const volunteerOnly of ["donate", "my-donations", "my-shifts", "shifts"]) {
      expect(admin).not.toContain(volunteerOnly);
    }
    for (const operatorOnly of ["tenants", "operations", "library", "whatsapp-templates", "notices-operator"]) {
      expect(admin).not.toContain(operatorOnly);
    }
    expect(admin).toContain("staff");
  });

  it("cannot take a destination away from a role that may reach it", () => {
    // Hiding is not something a temple may do (Rajeev, 2026-09-19: a temple that does not use a
    // feature switches the feature off instead). An arrangement that mentions nothing at all is the
    // strongest form of that attempt, and every destination still appears.
    const mentionsNothing = layout([{ id: "main", title: null, items: [] }]);
    expect(idsFor("TEMPLE_ADMIN", mentionsNothing).sort()).toEqual(idsFor("TEMPLE_ADMIN").sort());

    // And the ordinary case: one group kept, everything else unmentioned.
    const partial = layout([{ id: "temple", title: "Temple", items: ["settings", "audit"] }]);
    expect(idsFor("TEMPLE_ADMIN", partial).sort()).toEqual(idsFor("TEMPLE_ADMIN").sort());
  });

  it("does not hand the planner to somebody whose kitchen does not plan here (Epic 12)", () => {
    // The rule applies over the top of any arrangement: it is the server's answer, read by the menu,
    // and moving an item about cannot argue with it.
    for (const role of ["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"] as const) {
      const shut = navForRole(role, { canPlanMeals: false }, oneHeap);
      const hrefs = shut.flatMap((g) => g.items.map((i) => i.href));
      expect(hrefs.filter((h) => h === "/planner" || h.startsWith("/planner/"))).toEqual([]);
      // Nothing else went with it, and the arrangement is still in force.
      expect(shut.flatMap((g) => g.items.map((i) => i.id))).toContain("cost-per-serving");
      expect(shut).toHaveLength(1);
    }
  });

  it("leaves the platform operator exactly the five destinations they have today", () => {
    // An operator belongs to no temple, so `menuLayout` is null for them and there is nothing to
    // merge. Asserted through both doors: no arrangement, and a temple's arrangement handed in by
    // mistake, which must not move the platform's own menu about.
    expect(navForRole("SUPER_ADMIN").flatMap((g) => g.items.map((i) => i.href))).toEqual([
      "/tenants", "/operations", "/whatsapp-templates", "/library", "/notices",
    ]);
    expect(idsFor("SUPER_ADMIN", null)).toEqual([
      "tenants", "operations", "whatsapp-templates", "library", "notices-operator",
    ]);
    const templeArrangement = layout([{ id: "temple", title: "Temple", items: ["settings", "audit"] }]);
    expect(idsFor("SUPER_ADMIN", templeArrangement).sort()).toEqual(idsFor("SUPER_ADMIN").sort());
  });
});

describe("an odd arrangement falls back to the standard menu, never to an empty one", () => {
  it("takes no arrangement, and an arrangement with no groups, as the standard menu", () => {
    expect(shapeOf(applyMenuLayout(standardMenu(), null))).toEqual(standardShape);
    expect(shapeOf(applyMenuLayout(standardMenu(), undefined))).toEqual(standardShape);
    expect(shapeOf(applyMenuLayout(standardMenu(), layout([])))).toEqual(standardShape);
    expect(shapeOf(navForRole("TEMPLE_ADMIN", null, layout([])))).toEqual(
      shapeOf(navForRole("TEMPLE_ADMIN")),
    );
  });

  it("takes an arrangement that recognises nothing as the standard menu, not as one heap under New", () => {
    // Well-typed and meaningless: a row saved by some future release, or corrupted. Honouring it
    // literally would tip all 42 destinations into a single group headed "New", which is a menu
    // nobody can use — so an arrangement that names no group we have and no destination we have is
    // treated as no arrangement at all.
    const nonsense = layout([
      { id: "a-group-from-another-life", title: "Whatever", items: ["nothing", "we", "know"] },
      { id: "another-one", title: null, items: [] },
    ]);
    expect(shapeOf(applyMenuLayout(standardMenu(), nonsense))).toEqual(standardShape);
    expect(shapeOf(navForRole("TEMPLE_ADMIN", null, nonsense))).toEqual(shapeOf(navForRole("TEMPLE_ADMIN")));
    expect(navForRole("KITCHEN_STAFF", null, nonsense).length).toBeGreaterThan(0);
  });

  it("still gives every role a menu when the arrangement is odd in each of the ways it can be", () => {
    // The point of the whole group of tests, said once across every role: whatever the stored row
    // is, nobody is left looking at a column with nothing in it.
    const odd: (MenuLayout | null)[] = [
      null,
      layout([]),
      layout([{ id: "unknown", title: "Unknown", items: [] }]),
      // Recognises one group and nothing else; everything unplaced falls back into its own group.
      layout([{ id: "main", title: null, items: [] }]),
      // Recognises one destination and no group: it is an arrangement of this menu, so it is
      // honoured — and the rest of the menu still comes with it, under New.
      layout([{ id: "made-up", title: "Ours", items: ["today"] }]),
    ];
    for (const role of ["SUPER_ADMIN", "TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF", "VOLUNTEER"] as const) {
      for (const l of odd) {
        const groups = navForRole(role, null, l);
        expect(groups.length).toBeGreaterThan(0);
        expect(groups.flatMap((g) => g.items).length).toBeGreaterThan(0);
        // And never a heading over nothing, whatever the arrangement did.
        for (const group of groups) expect(group.items.length).toBeGreaterThan(0);
      }
    }
  });
});
