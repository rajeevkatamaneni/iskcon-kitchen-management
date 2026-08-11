import type { NavItem } from "@/components/Sidebar";
import type { PrincipalRole } from "./api";

/**
 * Navigation per role, in one place — the same reasoning as RolePermissions on the backend: what
 * each role can reach should be readable at a glance, not scattered across pages.
 *
 * <p>Navigation is not the security boundary (the API enforces that on every request), but a menu
 * should never offer someone a destination they'll only be refused at. So every item carries the
 * exact set of roles the destination's own page guard allows, and {@link navForRole} filters by the
 * signed-in role. Add a page, add its item here with the roles its `RequireRole` uses — the two
 * stay in step.
 */

interface RoleNavItem extends NavItem {
  /** Roles whose page guard admits this destination. Mirrors the page's own `RequireRole`. */
  roles: PrincipalRole[];
}

const ADMIN = "TEMPLE_ADMIN" as const;
const KITCHEN = "KITCHEN_STAFF" as const;
const VOLUNTEER = "VOLUNTEER" as const;
const OPERATOR = "SUPER_ADMIN" as const;

// One ordered list of every destination. navForRole() filters it; the order here is the order the
// menu appears in. Kept deliberately flat so the whole map is visible at once.
const ITEMS: RoleNavItem[] = [
  // Platform operator — runs the platform, not any temple.
  { href: "/tenants", label: "Temples", icon: "building-community", roles: [OPERATOR] },
  { href: "/operations", label: "Operations", icon: "activity", roles: [OPERATOR] },

  // Temple — kitchen and operations (admin + kitchen staff).
  { href: "/recipes", label: "Recipes", icon: "tools-kitchen-2", roles: [ADMIN, KITCHEN] },
  { href: "/ingredients", label: "Ingredients", icon: "salt", roles: [ADMIN, KITCHEN] },
  { href: "/inventory", label: "Inventory", icon: "box", roles: [ADMIN, KITCHEN] },
  { href: "/equipment", label: "Equipment", icon: "tools", roles: [ADMIN, KITCHEN] },
  { href: "/planner", label: "Meal plan", icon: "calendar", roles: [ADMIN, KITCHEN] },
  { href: "/vendors", label: "Vendors", icon: "building-store", roles: [ADMIN, KITCHEN] },
  { href: "/order-list", label: "Order list", icon: "clipboard-list", roles: [ADMIN, KITCHEN] },
  { href: "/orders", label: "Purchase orders", icon: "truck-delivery", roles: [ADMIN, KITCHEN] },
  { href: "/invoices", label: "Invoices", icon: "file-invoice", roles: [ADMIN, KITCHEN] },
  { href: "/donations", label: "Donations", icon: "gift", roles: [ADMIN, KITCHEN] },
  { href: "/volunteers", label: "Volunteers", icon: "users", roles: [ADMIN, KITCHEN] },

  // Temple — leadership only.
  { href: "/ledger", label: "Donations ledger", icon: "book", roles: [ADMIN] },
  { href: "/wishlist", label: "Wish list", icon: "heart", roles: [ADMIN] },
  { href: "/staff-schedule", label: "Staff schedule", icon: "calendar-time", roles: [ADMIN] },
  { href: "/money", label: "Payments", icon: "receipt", roles: [ADMIN] },
  { href: "/users", label: "People", icon: "users-group", roles: [ADMIN] },
  { href: "/audit", label: "Audit log", icon: "history", roles: [ADMIN] },

  // Seva. Kitchen staff can offer seva too, so they see their own shifts; signing up for more is a
  // volunteer action.
  { href: "/my-shifts", label: "My shifts", icon: "calendar-check", roles: [VOLUNTEER, KITCHEN] },
  { href: "/shifts", label: "Available shifts", icon: "hand-click", roles: [VOLUNTEER] },

  // Everyone with a temple account owns their contact channel and consent. A platform operator has
  // no temple, so no profile — which is why OPERATOR is absent here.
  { href: "/profile", label: "Profile", icon: "user-circle", roles: [ADMIN, KITCHEN, VOLUNTEER] },
];

/**
 * The menu for a role: every destination that role's page guard admits, in listed order. Returns
 * empty for an unknown/absent role (a menu is never shown before the person is resolved).
 */
export function navForRole(role: PrincipalRole | null | undefined): NavItem[] {
  if (!role) return [];
  return ITEMS.filter((item) => item.roles.includes(role));
}
