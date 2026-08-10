import type { PrincipalRole } from "./api";

/**
 * Where each role lands after signing in.
 *
 * <p>A platform operator goes to the temple list — running the platform. Everyone else goes to
 * their own profile for now; role-specific dashboards (a temple admin's overview, a volunteer's
 * shifts) arrive with the epics that build them, and this is the one place to change where they
 * land when they do.
 */
export function homeForRole(role: PrincipalRole): string {
  switch (role) {
    case "SUPER_ADMIN":
      return "/tenants";
    case "TEMPLE_ADMIN":
    case "KITCHEN_STAFF":
    case "VOLUNTEER":
      return "/profile";
  }
}
