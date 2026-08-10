import { describe, expect, it } from "vitest";
import { homeForRole } from "@/lib/routes";

describe("homeForRole", () => {
  it("sends a platform operator to the temple list", () => {
    expect(homeForRole("SUPER_ADMIN")).toBe("/tenants");
  });

  it("sends temple roles to their profile for now", () => {
    expect(homeForRole("TEMPLE_ADMIN")).toBe("/profile");
    expect(homeForRole("KITCHEN_STAFF")).toBe("/profile");
    expect(homeForRole("VOLUNTEER")).toBe("/profile");
  });
});
