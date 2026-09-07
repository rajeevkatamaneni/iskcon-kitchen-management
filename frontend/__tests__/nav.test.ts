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
 */

/** Every destination a role is offered, flattened out of its groups. */
const hrefsFor = (role: PrincipalRole) => navForRole(role).flatMap((g) => g.items.map((i) => i.href));

describe("navForRole", () => {
  it("gives the platform operator only platform destinations", () => {
    // Notices joins them: posting a downtime or maintenance notice is an operations act, and the
    // operator's takedown of somebody else's notice is what stands in for pre-moderation (E9-S1).
    expect(hrefsFor("SUPER_ADMIN")).toEqual(["/tenants", "/operations", "/library", "/notices"]);
  });

  it("gives a volunteer their seva and their giving, and nothing of the kitchen's", () => {
    // The kitchen's donors are its volunteers, not strangers: the same person serves and gives, so
    // giving belongs in their menu rather than behind a link somebody has to send them.
    expect(hrefsFor("VOLUNTEER")).toEqual(["/my-shifts", "/shifts", "/donate"]);
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
      "/users", "/staff", "/communications", "/audit", "/money", "/wishlist", "/staff-schedule",
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

  it("groups the community and the payroll apart, and never repeats a word between them", () => {
    // "Devotees" was once a group of money screens *and* an item inside People, one screen apart.
    const groups = navForRole("TEMPLE_ADMIN");
    const people = groups.find((g) => g.title === "People");
    const giving = groups.find((g) => g.title === "Giving");

    expect(people?.items.map((i) => i.label)).toEqual([
      "Devotees",
      "Staff",
      "Staff schedule",
      "Leave",
      "Volunteer shifts",
      "Communications",
    ]);
    expect(giving?.items.map((i) => i.label)).toEqual(["Donations", "Wish list"]);

    const titles = groups.map((g) => g.title).filter(Boolean);
    const labels = groups.flatMap((g) => g.items.map((i) => i.label));
    expect(titles.filter((t) => labels.includes(t!))).toEqual([]);
  });

  it("puts Equipment straight after Inventory, and offers it to nobody else", () => {
    // The two halves of one word: what flows through the store room, and what the temple owns and
    // maintains. And the rule at nav.ts:12 — the roles here are the page's own RequireRole set, so
    // a menu entry can never lead somebody to a refusal.
    const kitchen = navForRole("TEMPLE_ADMIN").find((g) => g.title === "Kitchen");
    const labels = kitchen?.items.map((i) => i.label) ?? [];
    expect(labels[labels.indexOf("Inventory") + 1]).toBe("Equipment");

    expect(hrefsFor("VOLUNTEER")).not.toContain("/equipment");
    expect(hrefsFor("SUPER_ADMIN")).not.toContain("/equipment");
  });

  it("never offers the dead Dashboard link to anyone", () => {
    for (const role of ["SUPER_ADMIN", "TEMPLE_ADMIN", "KITCHEN_STAFF", "VOLUNTEER"] as const) {
      expect(hrefsFor(role)).not.toContain("/dashboard");
    }
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
