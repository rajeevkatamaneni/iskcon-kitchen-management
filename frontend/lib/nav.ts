import type { NavItem } from "@/components/Sidebar";

/**
 * Navigation per role.
 *
 * <p>Kept here rather than inside components so that what each role can reach is readable in
 * one place — the same reasoning as RolePermissions on the backend. Navigation is not a
 * security boundary (the API enforces that), but it should never show someone a destination
 * they will be refused at.
 */

// Every authenticated user has an account of their own — contact channel and consent (E1-S8) —
// so a profile link belongs in each role's navigation.
const PROFILE_NAV: NavItem = { href: "/profile", label: "Profile", icon: "user-circle" };

export const PLATFORM_NAV: NavItem[] = [
  { href: "/tenants", label: "Temples", icon: "building-community" },
  { href: "/operations", label: "Operations", icon: "activity" },
  PROFILE_NAV,
];

export const TEMPLE_NAV: NavItem[] = [
  { href: "/dashboard", label: "Dashboard", icon: "home" },
  { href: "/recipes", label: "Recipes", icon: "tools-kitchen-2" },
  { href: "/inventory", label: "Inventory", icon: "box" },
  { href: "/planner", label: "Meal plan", icon: "calendar" },
  { href: "/orders", label: "Orders", icon: "truck-delivery" },
  { href: "/volunteers", label: "Volunteers", icon: "users" },
  { href: "/money", label: "Payments", icon: "receipt" },
  // Admin-only (MANAGE_USERS / VIEW_AUDIT_LOG). Shown in the shared temple nav for now; when the
  // temple nav is split by permission — which needs the frontend wired to the signed-in user's
  // role — these move to an admin-only list.
  { href: "/users", label: "People", icon: "users-group" },
  { href: "/audit", label: "Audit log", icon: "history" },
  PROFILE_NAV,
];

export const VOLUNTEER_NAV: NavItem[] = [
  { href: "/my-shifts", label: "My shifts", icon: "calendar-check" },
  { href: "/shifts", label: "Available shifts", icon: "hand-click" },
  PROFILE_NAV,
];
