import { describe, expect, it } from "vitest";
import { navForRole } from "@/lib/nav";
import type { PrincipalRole } from "@/lib/api";

/**
 * The navigation policy as an executable spec — mirrors the intent of the backend's
 * RolePermissionsTest. Each menu item must match its page's own access guard, so nobody is ever
 * offered a destination they'd be refused at.
 *
 * <p>The menu is grouped now (design system, 2026-08-14), so these read through the groups: what a
 * role can reach, and how it is arranged, are separate questions and both are asserted.
 *
 * <p><b>The order and the grouping are Rajeev's, settled 2026-09-19</b> and written down in
 * `docs/work/MENU-LAYOUT-2026-09-19.md`. The first test below spells out the whole temple-admin menu
 * heading by heading and label by label, so a reorder is a failing test rather than a thing somebody
 * notices on staging. The tests after it say *why* each neighbour sits where it does, which is what
 * a reader needs when a future change makes one of them fail.
 */

/** Every destination a role is offered, flattened out of its groups. */
const hrefsFor = (role: PrincipalRole) => navForRole(role).flatMap((g) => g.items.map((i) => i.href));

/** A role's menu as a person reads it: `[heading | null, ...labels]` per group, in order. */
const menuFor = (role: PrincipalRole, person?: { canPlanMeals?: boolean } | null) =>
  navForRole(role, person).map((g) => [g.title ?? null, ...g.items.map((i) => i.label)]);

describe("navForRole", () => {
  it("is the menu Rajeev settled on 2026-09-19, in his order and his words", () => {
    // The standard menu, read top to bottom as a temple admin — who is the only role that sees every
    // group, so this is the whole specification in one assertion. Sentence case throughout; the
    // first group carries no heading because Today and the planner are where people live.
    expect(menuFor("TEMPLE_ADMIN")).toEqual([
      [null, "Today", "Vaishnava calendar", "Meal planner", "Reuse a plan", "Cost per serving"],
      ["Ordering", "Shopping list", "Purchase orders", "Deliveries", "Invoices", "Vendors", "Vendor performance"],
      ["Inventory & Recipes", "Inventory", "Recipes", "Ingredients", "Supplies", "Equipment"],
      ["Kitchens", "Ingredient requests", "Issued to kitchens", "All kitchens"],
      ["People", "My schedule", "Staff", "Staff schedule", "Leave", "Devotees", "Volunteer shifts"],
      ["Giving & Outreach", "Donations", "Wish list", "Communications"],
      ["Temple", "Notices", "Festival occasions", "Meal kinds", "Audit log", "Settings"],
    ]);
  });

  it("gives a kitchen manager the same menu less the admin's own screens, and no empty heading", () => {
    // Every group survives for them, because each one holds something of theirs — which is what
    // makes the next test (kitchen staff, whose Temple group goes entirely) the real one.
    expect(menuFor("KITCHEN_MANAGER")).toEqual([
      [null, "Today", "Vaishnava calendar", "Meal planner", "Reuse a plan", "Cost per serving"],
      ["Ordering", "Shopping list", "Purchase orders", "Deliveries", "Invoices", "Vendors", "Vendor performance"],
      ["Inventory & Recipes", "Inventory", "Recipes", "Ingredients", "Supplies", "Equipment"],
      ["Kitchens", "Ingredient requests", "Issued to kitchens"],
      ["People", "My schedule", "Staff schedule", "Leave", "Volunteer shifts"],
      ["Giving & Outreach", "Donations"],
    ]);
  });

  it("drops the Temple group for a kitchen cook rather than heading an empty list", () => {
    // Every destination under Temple is the admin's, so for a cook the heading would stand over
    // nothing. It is dropped, and the groups above it keep their order and their words.
    expect(menuFor("KITCHEN_STAFF")).toEqual([
      [null, "Today", "Vaishnava calendar", "Meal planner", "Reuse a plan", "Cost per serving"],
      ["Ordering", "Shopping list", "Purchase orders", "Deliveries", "Invoices", "Vendors", "Vendor performance"],
      ["Inventory & Recipes", "Inventory", "Recipes", "Ingredients", "Supplies", "Equipment"],
      ["Kitchens", "Ingredient requests", "Issued to kitchens"],
      ["People", "My schedule", "Volunteer shifts"],
      ["Giving & Outreach", "Donations"],
    ]);
    expect(menuFor("KITCHEN_STAFF").map((g) => g[0])).not.toContain("Temple");
    // And "All kitchens" goes with it: which kitchens the temple runs is the admin's to change.
    expect(hrefsFor("KITCHEN_STAFF")).not.toContain("/kitchens");
  });

  it("renames Issued from store to Issued to kitchens, keeping the address (2026-09-19)", () => {
    // Rajeev chose the words over "Out of the store". The route is untouched on purpose — renaming
    // it would break every link and bookmark already pointing at the screen — so the item is the
    // one place the two can disagree, and this pins them together.
    for (const role of ["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"] as const) {
      const items = navForRole(role).flatMap((g) => g.items);
      expect(items.find((i) => i.href === "/issued-from-store")?.label).toBe("Issued to kitchens");
      expect(items.map((i) => i.label)).not.toContain("Issued from store");
    }
  });
  it("gives the platform operator only platform destinations", () => {
    // Notices joins them: posting a downtime or maintenance notice is an operations act, and the
    // operator's takedown of somebody else's notice is what stands in for pre-moderation (E9-S1).
    expect(hrefsFor("SUPER_ADMIN")).toEqual(["/tenants", "/operations", "/whatsapp-templates", "/library", "/notices"]);
  });

  it("gives a volunteer their seva and their giving, and nothing of the kitchen's", () => {
    // The kitchen's donors are its volunteers, not strangers: the same person serves and gives, so
    // giving belongs in their menu rather than behind a link somebody has to send them.
    expect(hrefsFor("VOLUNTEER")).toEqual(["/my-shifts", "/shifts", "/donate", "/my-donations"]);
  });

  it("gives kitchen staff the kitchen menu but not the leadership-only pages", () => {
    const hrefs = hrefsFor("KITCHEN_STAFF");
    expect(hrefs).toContain("/recipes");
    expect(hrefs).toContain("/inventory");
    // A cook standing in front of a stopped grinder is the person who knows first, so the register
    // is theirs to read. Recording the service against it is not, and the page enforces that.
    expect(hrefs).toContain("/equipment");
    // D-16, closing D-10: seva is for people who are not employed here. A cook is already doing
    // their part, so *My shifts* is not theirs — and their own rostered days are /my-schedule,
    // which is beside it in the menu and behind VIEW_OWN_SHIFTS.
    expect(hrefs).not.toContain("/my-shifts");
    expect(hrefs).toContain("/my-schedule");
    for (const adminOnly of [
      "/users", "/staff", "/communications", "/audit", "/money", "/wishlist", "/staff-schedule",
      "/leave", "/notices",
    ]) {
      expect(hrefs).not.toContain(adminOnly);
    }
    expect(hrefs).not.toContain("/shifts"); // signing up for seva is a volunteer action
    // D-8: a cook is already serving, which is donation enough — giving stays a volunteer's alone.
    expect(hrefs).not.toContain("/donate");
  });

  it("gives the kitchen manager the roster and the leave it bends around, and no money", () => {
    // The whole of the role, read as a menu: everything a cook reaches, plus the two screens the
    // role exists for — and never /staff, which is the only place salary and a PAN appear.
    const hrefs = hrefsFor("KITCHEN_MANAGER");
    for (const theirs of ["/recipes", "/inventory", "/equipment", "/orders", "/staff-schedule", "/leave"]) {
      expect(hrefs).toContain(theirs);
    }
    for (const notTheirs of ["/staff", "/users", "/money", "/audit", "/notices", "/wishlist"]) {
      expect(hrefs).not.toContain(notTheirs);
    }
    // D-8: a manager draws a salary for this, which is not what giving is for.
    expect(hrefs).not.toContain("/donate");
    // D-16, and the same sentence: neither is seva. The roster they run is /staff-schedule and
    // their own working days are /my-schedule.
    expect(hrefs).not.toContain("/my-shifts");
  });

  it("gives the temple admin the leadership pages but not the volunteer sign-up", () => {
    const hrefs = hrefsFor("TEMPLE_ADMIN");
    for (const adminOnly of [
      "/users", "/staff", "/communications", "/audit", "/wishlist", "/staff-schedule",
      "/leave", "/notices",
    ]) {
      expect(hrefs).toContain(adminOnly);
    }
    expect(hrefs).not.toContain("/shifts");
    expect(hrefs).not.toContain("/my-shifts"); // D-16: /my-shifts admits volunteers and nobody else
    // D-8: admins shouldn't be asked for money by their own admin app.
    expect(hrefs).not.toContain("/donate");
  });

  it("offers My shifts to a volunteer and to nobody else", () => {
    // D-16, ruled 2026-09-07, completing D-10's table: "NO My shifts OR Donate options for Staff.
    // They are already doing their part." Read across every role at once, because the rule is
    // about the whole set and not about the cook it was noticed on — and it is the pair of the
    // page's own guard, which is now `roles={["VOLUNTEER"]}`.
    for (const role of ["SUPER_ADMIN", "TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"] as const) {
      expect(hrefsFor(role)).not.toContain("/my-shifts");
    }
    expect(hrefsFor("VOLUNTEER")).toContain("/my-shifts");
  });

  it("never uses one word as a heading and as a destination under it", () => {
    // "Devotees" was once a group of money screens *and* an item inside People, one screen apart.
    // The same trap caught the kitchens group on 2026-09-19, which is why the list of them is
    // "All kitchens" under the heading "Kitchens" rather than "Kitchens" under "Kitchens".
    const groups = navForRole("TEMPLE_ADMIN");
    const titles = groups.map((g) => g.title).filter(Boolean);
    const labels = groups.flatMap((g) => g.items.map((i) => i.label));
    expect(titles.filter((t) => labels.includes(t!))).toEqual([]);
  });

  it("keeps Equipment in the catalogue group, and offers it to nobody else", () => {
    // What the temple owns and maintains is catalogued like what it consumes, so it sits at the end
    // of the same group — after the three things that are used up. And the rule at the head of
    // nav.ts: the roles here are the page's own RequireRole set, so a menu entry can never lead
    // somebody to a refusal.
    const catalogue = navForRole("TEMPLE_ADMIN").find((g) => g.title === "Inventory & Recipes");
    const labels = catalogue?.items.map((i) => i.label) ?? [];
    expect(labels[labels.length - 1]).toBe("Equipment");
    expect(labels[labels.indexOf("Ingredients") + 1]).toBe("Supplies");

    expect(hrefsFor("VOLUNTEER")).not.toContain("/equipment");
    expect(hrefsFor("SUPER_ADMIN")).not.toContain("/equipment");
  });

  it("keeps Cost per serving with the daily screens, not under Kitchens (2026-09-19)", () => {
    // Rajeev: it measures what the meal planner planned, not what a kitchen took from the store, and
    // the admin checks it often to watch the budget. When billing the sister kitchens arrives, the
    // kitchen's bill goes under Kitchens and these two money screens stay apart on purpose.
    const groups = navForRole("TEMPLE_ADMIN");
    expect(groups[0].title).toBeUndefined();
    expect(groups[0].items[groups[0].items.length - 1].href).toBe("/cost-per-serving");
    const kitchens = groups.find((g) => g.title === "Kitchens");
    expect(kitchens?.items.map((i) => i.href)).not.toContain("/cost-per-serving");
  });

  it("keeps Cost per serving for a kitchen the planner is shut to", () => {
    // It is not a planner destination: the report reads recorded meals and issues, and a kitchen
    // that does not plan here still has both. So it must survive the filter that takes /planner away.
    const hrefs = navForRole("KITCHEN_MANAGER", { canPlanMeals: false }).flatMap((g) =>
      g.items.map((i) => i.href),
    );
    expect(hrefs).toContain("/cost-per-serving");
    expect(hrefs).not.toContain("/planner");
  });

  it("puts Meal kinds beside Festival occasions, and offers both to the admin alone", () => {
    // T-005. The two are the same act on two standing facts about the temple — which days it
    // observes, and what it calls its meals — so they sit together under Settings and carry the
    // same role set. And the same rule as everywhere else in this file, from nav.ts:12: the roles
    // here are the page's own RequireRole set, which for both of these is TEMPLE_ADMIN alone,
    // matching MANAGE_TEMPLE_SETTINGS on the server. Reading the kinds is MANAGE_MEAL_PLANS and the
    // planner does that for itself; changing them is not a choice made mid-shift.
    const temple = navForRole("TEMPLE_ADMIN").find((g) => g.title === "Temple");
    const labels = temple?.items.map((i) => i.label) ?? [];
    expect(labels[labels.indexOf("Festival occasions") + 1]).toBe("Meal kinds");
    expect(hrefsFor("TEMPLE_ADMIN")).toContain("/settings/meal-kinds");

    for (const role of ["SUPER_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF", "VOLUNTEER"] as const) {
      expect(hrefsFor(role)).not.toContain("/settings/meal-kinds");
    }
  });

  it("puts Deliveries straight after Purchase orders, for the admin, the manager and kitchen staff", () => {
    // R-DEL-1 (T-266). The page needs RECEIVE_DELIVERIES. Kitchen Staff was open question Q-1
    // ("does Kitchen Staff get RECEIVE_DELIVERIES by default, or only named staff?") until Rajeev
    // answered it on 2026-09-19: by default. So a cook at the gate is offered the door too (T-282).
    // Volunteers and the operator still hold no RECEIVE_DELIVERIES and are offered nothing.
    for (const role of ["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"] as const) {
      const ordering = navForRole(role).find((g) => g.items.some((i) => i.href === "/orders"));
      const hrefs = ordering?.items.map((i) => i.href) ?? [];
      expect(hrefs[hrefs.indexOf("/orders") + 1]).toBe("/deliveries");
      expect(ordering?.items.find((i) => i.href === "/deliveries")?.label).toBe("Deliveries");
    }
    for (const role of ["VOLUNTEER", "SUPER_ADMIN"] as const) {
      expect(hrefsFor(role)).not.toContain("/deliveries");
    }
  });

  it("offers nobody a Payments item, or anything leading to /money (R-PAY-4)", () => {
    // T-275. The Payments page is gone: what it listed (unpaid invoices by age, and the total owed)
    // is the Invoices list's filters now, and /money only redirects there. Read across every role,
    // because the rule is that the item exists for nobody, not that the admin lost it.
    for (const role of ["SUPER_ADMIN", "TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF", "VOLUNTEER"] as const) {
      const items = navForRole(role).flatMap((g) => g.items);
      expect(items.map((i) => i.href)).not.toContain("/money");
      expect(items.map((i) => i.label)).not.toContain("Payments");
    }
    // Invoices stays, for every role that had it.
    for (const role of ["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"] as const) {
      expect(hrefsFor(role)).toContain("/invoices");
    }
  });

  it("never offers the dead Dashboard link to anyone", () => {
    for (const role of ["SUPER_ADMIN", "TEMPLE_ADMIN", "KITCHEN_STAFF", "VOLUNTEER"] as const) {
      expect(hrefsFor(role)).not.toContain("/dashboard");
    }
  });

  it("hides every meal-planner destination from somebody the planner is shut to (Epic 12)", () => {
    // Rajeev, 2026-09-19: only people whose kitchen plans its meals here can open the planner,
    // "server-enforced, and hidden from the menu". The server's answer is WhoAmI.canPlanMeals.
    for (const role of ["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"] as const) {
      const hrefs = navForRole(role, { canPlanMeals: false }).flatMap((g) => g.items.map((i) => i.href));
      expect(hrefs.filter((h) => h === "/planner" || h.startsWith("/planner/"))).toEqual([]);
      // Nothing else goes with it.
      expect(hrefs).toContain("/today");
      expect(hrefs).toContain("/recipes");
    }
  });

  it("keeps the planner when it is open to them, and when the session never said", () => {
    // Only an explicit false shuts it: an older session shape must not quietly lose the planner.
    for (const person of [{ canPlanMeals: true }, {}, null, undefined]) {
      const hrefs = navForRole("KITCHEN_STAFF", person).flatMap((g) => g.items.map((i) => i.href));
      expect(hrefs).toContain("/planner");
      expect(hrefs).toContain("/planner/reuse");
    }
    expect(hrefsFor("KITCHEN_STAFF")).toContain("/planner");
  });

  it("shows nothing until a role is known", () => {
    expect(navForRole(null)).toEqual([]);
    expect(navForRole(undefined)).toEqual([]);
  });

  it("never leaves a group heading over an empty group", () => {
    for (const role of ["SUPER_ADMIN", "TEMPLE_ADMIN", "KITCHEN_STAFF", "VOLUNTEER"] as const) {
      for (const group of navForRole(role)) {
        expect(group.items.length).toBeGreaterThan(0);
      }
    }
  });

  it("leaves the first group untitled — the daily destinations need no heading", () => {
    expect(navForRole("TEMPLE_ADMIN")[0].title).toBeUndefined();
    expect(navForRole("VOLUNTEER")[0].title).toBeUndefined();
  });

  it("puts a volunteer's whole menu in that first group, so they never see a heading at all", () => {
    const groups = navForRole("VOLUNTEER");
    expect(groups).toHaveLength(1);
  });
});
