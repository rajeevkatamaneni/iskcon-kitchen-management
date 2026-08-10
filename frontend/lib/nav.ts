import type { NavItem } from "@/components/Sidebar";

/**
 * Navigation per role.
 *
 * <p>Kept here rather than inside components so that what each role can reach is readable in
 * one place — the same reasoning as RolePermissions on the backend. Navigation is not a
 * security boundary (the API enforces that), but it should never show someone a destination
 * they will be refused at.
 */

export const PLATFORM_NAV: NavItem[] = [
  { href: "/tenants", label: "Temples", icon: "building-community" },
  { href: "/operations", label: "Operations", icon: "activity" },
];

export const TEMPLE_NAV: NavItem[] = [
  { href: "/dashboard", label: "Dashboard", icon: "home" },
  { href: "/recipes", label: "Recipes", icon: "tools-kitchen-2" },
  { href: "/inventory", label: "Inventory", icon: "box" },
  { href: "/planner", label: "Meal plan", icon: "calendar" },
  { href: "/orders", label: "Orders", icon: "truck-delivery" },
  { href: "/volunteers", label: "Volunteers", icon: "users" },
  { href: "/money", label: "Payments", icon: "receipt" },
  // Admin-only (VIEW_AUDIT_LOG). Shown in the shared temple nav for now; when temple nav is
  // split by permission (needed for E1-S12 user management too), this moves to the admin list.
  { href: "/audit", label: "Audit log", icon: "history" },
];

export const VOLUNTEER_NAV: NavItem[] = [
  { href: "/my-shifts", label: "My shifts", icon: "calendar-check" },
  { href: "/shifts", label: "Available shifts", icon: "hand-click" },
];
