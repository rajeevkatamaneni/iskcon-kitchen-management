import { describe, expect, it } from "vitest";
import { homeForRole } from "@/lib/routes";

describe("homeForRole", () => {
  it("sends a platform operator to the temple list", () => {
    expect(homeForRole("SUPER_ADMIN")).toBe("/tenants");
  });

  it("sends a volunteer straight to their shifts", () => {
    expect(homeForRole("VOLUNTEER")).toBe("/my-shifts");
  });

  it("sends admins and kitchen staff to their profile for now", () => {
    expect(homeForRole("TEMPLE_ADMIN")).toBe("/profile");
    expect(homeForRole("KITCHEN_STAFF")).toBe("/profile");
  });
});
