import type { PrincipalRole } from "./api";

/**
 * Navigation per role, in one place — the same reasoning as RolePermissions on the backend: what
 * each role can reach should be readable at a glance, not scattered across pages.
 *
 * <p>Grouped, per the ISKCON Kitchen Design System (Claude Design, 2026-08-14). A flat list of
 * eighteen destinations is a wall; grouped, a person finds "the ordering one" without reading every
 * label. The first group is deliberately untitled — Today and the meal plan are where people live,
 * and a heading above two items you use daily is noise.
 *
 * <p>Navigation is not the security boundary (the API enforces that on every request), but a menu
 * should never offer someone a destination they'll only be refused at. Every item carries the exact
 * set of roles the destination's own page guard allows, and {@link navForRole} filters by the
 * signed-in role. Add a page, add its item here with the roles its `RequireRole` uses — the two stay
 * in step.
 */

export interface NavItem {
  href: string;
  label: string;
  /** Tabler outline icon name, without the `ti-` prefix. */
  icon: string;
  roles: PrincipalRole[];
}

export interface NavGroup {
  /** Uppercase section label. Absent for the first group, which needs no introduction. */
  title?: string;
  items: NavItem[];
}

const ADMIN = "TEMPLE_ADMIN" as const;
const KITCHEN = "KITCHEN_STAFF" as const;
const VOLUNTEER = "VOLUNTEER" as const;
const OPERATOR = "SUPER_ADMIN" as const;

const GROUPS: NavGroup[] = [
  {
    // The platform operator runs the platform, not a temple — their two destinations sit here
    // alongside the daily ones, since they never see any of the groups below.
    items: [
      { href: "/tenants", label: "Temples", icon: "building-community", roles: [OPERATOR] },
      { href: "/operations", label: "Operations", icon: "activity", roles: [OPERATOR] },
      { href: "/today", label: "Today", icon: "sun", roles: [ADMIN, KITCHEN] },
      { href: "/calendar", label: "Vaishnava calendar", icon: "calendar-event", roles: [ADMIN, KITCHEN] },
      { href: "/planner", label: "Meal planner", icon: "calendar-month", roles: [ADMIN, KITCHEN] },
      { href: "/my-shifts", label: "My shifts", icon: "calendar-check", roles: [VOLUNTEER, KITCHEN] },
      { href: "/shifts", label: "Available shifts", icon: "hand-click", roles: [VOLUNTEER] },
      // A devotee who serves is the same person who gives — the kitchen's donors are its
      // volunteers, not strangers. So giving belongs in their menu, not only on a public link
      // somebody has to be sent.
      { href: "/give", label: "Give", icon: "heart-handshake", roles: [VOLUNTEER, KITCHEN] },
      { href: "/give/wish-list", label: "Wish list", icon: "gift", roles: [VOLUNTEER, KITCHEN] },
    ],
  },
  {
    title: "Kitchen",
    items: [
      { href: "/recipes", label: "Recipes", icon: "tools-kitchen-2", roles: [ADMIN, KITCHEN] },
      { href: "/ingredients", label: "Ingredients", icon: "salt", roles: [ADMIN, KITCHEN] },
      { href: "/inventory", label: "Inventory", icon: "package", roles: [ADMIN, KITCHEN] },
    ],
  },
  {
    title: "Ordering",
    items: [
      { href: "/order-list", label: "Order list", icon: "clipboard-list", roles: [ADMIN, KITCHEN] },
      { href: "/orders", label: "Purchase orders", icon: "truck-delivery", roles: [ADMIN, KITCHEN] },
      { href: "/vendors", label: "Vendors", icon: "building-store", roles: [ADMIN, KITCHEN] },
      { href: "/invoices", label: "Invoices", icon: "file-invoice", roles: [ADMIN, KITCHEN] },
      // Paying those invoices belongs with the ordering it settles, not with the devotees who give.
      { href: "/money", label: "Payments", icon: "receipt", roles: [ADMIN] },
    ],
  },
  {
    title: "People",
    items: [
      { href: "/volunteers", label: "Volunteer shifts", icon: "users", roles: [ADMIN, KITCHEN] },
      { href: "/staff-schedule", label: "Staff schedule", icon: "calendar-time", roles: [ADMIN] },
      { href: "/users", label: "People", icon: "users-group", roles: [ADMIN] },
    ],
  },
  {
    title: "Devotees",
    items: [
      { href: "/donations", label: "Donations", icon: "gift", roles: [ADMIN, KITCHEN] },
      { href: "/wishlist", label: "Wish list", icon: "heart-handshake", roles: [ADMIN] },
      { href: "/ledger", label: "Donations ledger", icon: "book", roles: [ADMIN] },
    ],
  },
  {
    title: "Temple",
    items: [{ href: "/audit", label: "Audit log", icon: "history", roles: [ADMIN] }],
  },
];

/**
 * The menu for a role: every destination that role's page guard admits, grouped, with any group
 * that ends up empty dropped rather than left as a heading over nothing. Returns empty for an
 * unknown or absent role — a menu is never shown before the person is resolved.
 */
export function navForRole(role: PrincipalRole | null | undefined): NavGroup[] {
  if (!role) return [];
  return GROUPS.map((g) => ({ ...g, items: g.items.filter((i) => i.roles.includes(role)) })).filter(
    (g) => g.items.length > 0
  );
}
