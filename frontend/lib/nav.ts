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
// A kitchen manager holds everything kitchen staff hold, so they appear beside them on every
// kitchen destination — and alone with the admin on the two the role exists for, the roster and
// the leave queue. What they deliberately never see is the staff register: it is the only screen
// salary and PAN appear on (build brief 2026-08-20, §7).
const MANAGER = "KITCHEN_MANAGER" as const;
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
      // Beside Operations, because a downtime notice is an operations act.
      { href: "/notices", label: "Notices", icon: "speakerphone", roles: [OPERATOR] },
      { href: "/today", label: "Today", icon: "sun", roles: [ADMIN, MANAGER, KITCHEN] },
      { href: "/calendar", label: "Vaishnava calendar", icon: "calendar-event", roles: [ADMIN, MANAGER, KITCHEN] },
      { href: "/planner", label: "Meal planner", icon: "calendar-month", roles: [ADMIN, MANAGER, KITCHEN] },
      { href: "/my-shifts", label: "My shifts", icon: "calendar-check", roles: [VOLUNTEER, MANAGER, KITCHEN] },
      { href: "/shifts", label: "Available shifts", icon: "hand-click", roles: [VOLUNTEER] },
      // A devotee who serves is the same person who gives — the kitchen's donors are its
      // volunteers, not strangers. One destination, because money and the things the kitchen wants
      // are two tabs of the same question.
      { href: "/donate", label: "Donate", icon: "heart-handshake", roles: [VOLUNTEER, MANAGER, KITCHEN] },
    ],
  },
  {
    title: "Kitchen",
    items: [
      { href: "/recipes", label: "Recipes", icon: "tools-kitchen-2", roles: [ADMIN, MANAGER, KITCHEN] },
      { href: "/ingredients", label: "Ingredients", icon: "salt", roles: [ADMIN, MANAGER, KITCHEN] },
      { href: "/inventory", label: "Inventory", icon: "package", roles: [ADMIN, MANAGER, KITCHEN] },
    ],
  },
  {
    title: "Ordering",
    items: [
      { href: "/order-list", label: "Order list", icon: "clipboard-list", roles: [ADMIN, MANAGER, KITCHEN] },
      { href: "/orders", label: "Purchase orders", icon: "truck-delivery", roles: [ADMIN, MANAGER, KITCHEN] },
      { href: "/vendors", label: "Vendors", icon: "building-store", roles: [ADMIN, MANAGER, KITCHEN] },
      { href: "/invoices", label: "Invoices", icon: "file-invoice", roles: [ADMIN, MANAGER, KITCHEN] },
      // Paying those invoices belongs with the ordering it settles, not with the devotees who give.
      { href: "/money", label: "Payments", icon: "receipt", roles: [ADMIN] },
    ],
  },
  {
    // Everyone the temple deals with, in one group: the community that registered itself, and the
    // people the temple employs. The group that used to be titled "Devotees" is now "Giving" —
    // holding a *Devotees* item inside *People* next to a *Devotees* group about money made the
    // word mean two things one screen apart.
    title: "People",
    items: [
      { href: "/users", label: "Devotees", icon: "users-group", roles: [ADMIN] },
      { href: "/staff", label: "Staff", icon: "id-badge-2", roles: [ADMIN] },
      { href: "/staff-schedule", label: "Staff schedule", icon: "calendar-time", roles: [ADMIN, MANAGER] },
      // Beside the roster it bends, and behind the same two roles: answering leave is what a
      // kitchen manager is appointed to do, and it is the reason the role exists at all.
      { href: "/leave", label: "Leave", icon: "calendar-off", roles: [ADMIN, MANAGER] },
      { href: "/volunteers", label: "Volunteer shifts", icon: "users", roles: [ADMIN, MANAGER, KITCHEN] },
      // Last in the group: the register comes before writing to it.
      { href: "/communications", label: "Communications", icon: "mail", roles: [ADMIN] },
    ],
  },
  {
    title: "Giving",
    items: [
      { href: "/donations", label: "Donations", icon: "gift", roles: [ADMIN, MANAGER, KITCHEN] },
      { href: "/wishlist", label: "Wish list", icon: "heart-handshake", roles: [ADMIN] },
    ],
  },
  {
    title: "Temple",
    items: [
      // Raising a notice reaches every temple on the platform. Rare and serious enough to sit
      // somewhere deliberate rather than one click from daily work — which is why it is here and
      // not beside the kitchen screens an admin uses all morning.
      { href: "/notices", label: "Notices", icon: "speakerphone", roles: [ADMIN] },
      { href: "/audit", label: "Audit log", icon: "history", roles: [ADMIN] },
      // Last, and one word: the temple's name is already at the top of this menu, so "whose
      // settings" needs no saying, and "Settings" stays right as it grows past payments.
      { href: "/settings", label: "Settings", icon: "settings", roles: [ADMIN] },
    ],
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
