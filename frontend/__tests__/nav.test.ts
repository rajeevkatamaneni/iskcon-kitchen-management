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
    expect(hrefsFor("SUPER_ADMIN")).toEqual(["/tenants", "/operations"]);
  });

  it("gives a volunteer only their shifts", () => {
    expect(hrefsFor("VOLUNTEER")).toEqual(["/my-shifts", "/shifts"]);
  });

  it("gives kitchen staff the kitchen menu but not the leadership-only pages", () => {
    const hrefs = hrefsFor("KITCHEN_STAFF");
    expect(hrefs).toContain("/recipes");
    expect(hrefs).toContain("/inventory");
    expect(hrefs).toContain("/my-shifts"); // kitchen staff can offer seva too
    for (const adminOnly of ["/users", "/audit", "/money", "/ledger", "/wishlist", "/staff-schedule"]) {
      expect(hrefs).not.toContain(adminOnly);
    }
    expect(hrefs).not.toContain("/shifts"); // signing up for seva is a volunteer action
  });

  it("gives the temple admin the leadership pages but not the volunteer sign-up", () => {
    const hrefs = hrefsFor("TEMPLE_ADMIN");
    for (const adminOnly of ["/users", "/audit", "/money", "/ledger", "/wishlist", "/staff-schedule"]) {
      expect(hrefs).toContain(adminOnly);
    }
    expect(hrefs).not.toContain("/shifts");
    expect(hrefs).not.toContain("/my-shifts"); // /my-shifts admits only volunteers and kitchen staff
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
