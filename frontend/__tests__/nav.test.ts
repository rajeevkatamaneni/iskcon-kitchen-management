import { describe, expect, it } from "vitest";
import { navForRole } from "@/lib/nav";

/**
 * The navigation policy as an executable spec — mirrors the intent of the backend's
 * RolePermissionsTest. Each menu item must match its page's own access guard, so nobody is ever
 * offered a destination they'd be refused at.
 */
describe("navForRole", () => {
  it("gives the platform operator only platform destinations — and no temple profile", () => {
    expect(navForRole("SUPER_ADMIN").map((i) => i.href)).toEqual(["/tenants", "/operations"]);
  });

  it("gives a volunteer only their shifts and profile", () => {
    expect(navForRole("VOLUNTEER").map((i) => i.href)).toEqual([
      "/my-shifts",
      "/shifts",
      "/profile",
    ]);
  });

  it("gives kitchen staff the kitchen menu but not the leadership-only pages", () => {
    const hrefs = navForRole("KITCHEN_STAFF").map((i) => i.href);
    expect(hrefs).toContain("/recipes");
    expect(hrefs).toContain("/inventory");
    expect(hrefs).toContain("/my-shifts"); // kitchen staff can offer seva too
    for (const adminOnly of ["/users", "/audit", "/money", "/ledger", "/wishlist", "/staff-schedule"]) {
      expect(hrefs).not.toContain(adminOnly);
    }
    expect(hrefs).not.toContain("/shifts"); // signing up for seva is a volunteer action
  });

  it("gives the temple admin the leadership pages but not the volunteer sign-up", () => {
    const hrefs = navForRole("TEMPLE_ADMIN").map((i) => i.href);
    for (const adminOnly of ["/users", "/audit", "/money", "/ledger", "/wishlist", "/staff-schedule"]) {
      expect(hrefs).toContain(adminOnly);
    }
    expect(hrefs).not.toContain("/shifts");
    expect(hrefs).not.toContain("/my-shifts"); // /my-shifts admits only volunteers and kitchen staff
  });

  it("never offers the dead Dashboard link to anyone", () => {
    for (const role of ["SUPER_ADMIN", "TEMPLE_ADMIN", "KITCHEN_STAFF", "VOLUNTEER"] as const) {
      expect(navForRole(role).map((i) => i.href)).not.toContain("/dashboard");
    }
  });

  it("shows nothing until a role is known", () => {
    expect(navForRole(null)).toEqual([]);
    expect(navForRole(undefined)).toEqual([]);
  });
});
