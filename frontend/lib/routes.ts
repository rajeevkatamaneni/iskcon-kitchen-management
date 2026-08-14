import type { PrincipalRole } from "./api";

/**
 * Where each role lands after signing in.
 *
 * <p>A platform operator goes to the temple list — running the platform. A volunteer goes straight
 * to their shifts, which is the whole reason they signed in. Admins and kitchen staff land on Today
 * (E4-S8): signing in first thing in the morning should show the state of the temple, not a form.
 */
export function homeForRole(role: PrincipalRole): string {
  switch (role) {
    case "SUPER_ADMIN":
      return "/tenants";
    case "VOLUNTEER":
      return "/my-shifts";
    case "TEMPLE_ADMIN":
    case "KITCHEN_STAFF":
      return "/today";
  }
}
