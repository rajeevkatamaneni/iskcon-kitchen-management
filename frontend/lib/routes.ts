import type { PrincipalRole } from "./api";

/**
 * Where each role lands after signing in.
 *
 * <p>A platform operator goes to the temple list — running the platform. A volunteer goes straight
 * to their shifts, which is the whole reason they signed in. Admins and kitchen staff land on their
 * profile for now; role-specific dashboards (a temple admin's overview) arrive with the epics that
 * build them, and this is the one place to change where they land when they do.
 */
export function homeForRole(role: PrincipalRole): string {
  switch (role) {
    case "SUPER_ADMIN":
      return "/tenants";
    case "VOLUNTEER":
      return "/my-shifts";
    case "TEMPLE_ADMIN":
    case "KITCHEN_STAFF":
      return "/profile";
  }
}
