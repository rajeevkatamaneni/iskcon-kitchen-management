import type { PrincipalRole } from "@/lib/api";

/**
 * Who may do what on an ingredient's page, written as the permissions its endpoints declare (T-286).
 *
 * <p><strong>Why a map from permission to roles, and not `role === "TEMPLE_ADMIN"` at each control.</strong>
 * The server decides by permission — `@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")` on the market
 * rate, `MANAGE_RECIPES` on the ingredient and its pack sizes, `MANAGE_VENDORS` on every supply read and
 * write — and the session this screen holds knows only the role. So the translation is done once, here,
 * named by the permission, and each control asks `can("MANAGE_INVENTORY", role)`. When `RolePermissions`
 * moves a grant, this is the one line to move with it, and a reader can see which endpoint a hidden
 * control was hidden for.
 *
 * <p>The grants below are the ones in force on 2026-09-19, read from the screens already gated on the
 * same endpoints (`/ingredients`, `/inventory` and `/vendors` each admit exactly these three roles) and
 * from `RolePermissionsTest`, which asserts Kitchen Staff hold `MANAGE_RECIPES` and `MANAGE_INVENTORY`.
 * Hiding a control is a courtesy, never the guard: the API refuses anyone without the permission.
 *
 * <p><strong>`MANAGE_BUYING_POLICY` is the first entry here that is not held by all three</strong>
 * (T-402). `RolePermissions.java` grants it to the Temple Admin alone, because marking an ingredient
 * as one the temple never buys takes it off every shopping list from then on and the list says
 * nothing about what is missing from it. That is exactly the case this file was written as a map for
 * rather than as `role === "TEMPLE_ADMIN"` at each control: the grant is stated once, named by the
 * permission the endpoint declares, and `holdersOf`/`can` behave the same on a one-role entry as on a
 * three-role one — `RequireRole` takes the array it is given, and `can` is an `includes`.
 */
export type IngredientPagePermission =
  | "MANAGE_RECIPES"
  | "MANAGE_INVENTORY"
  | "MANAGE_VENDORS"
  | "MANAGE_BUYING_POLICY";

const HOLDERS: Record<IngredientPagePermission, readonly PrincipalRole[]> = {
  MANAGE_RECIPES: ["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"],
  MANAGE_INVENTORY: ["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"],
  MANAGE_VENDORS: ["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"],
  // The Temple Admin alone — `PATCH /ingredients/{id}/not-bought` (T-402). Not a copy of the
  // Ekadashi grant: `MANAGE_DIETARY_POLICY` is also the Temple Admin's today, and the two are held
  // apart on purpose so either can move without dragging the other.
  MANAGE_BUYING_POLICY: ["TEMPLE_ADMIN"],
};

/** The roles holding a permission, for `RequireRole`. */
export function holdersOf(permission: IngredientPagePermission): PrincipalRole[] {
  return [...HOLDERS[permission]];
}

/** Whether a signed-in role holds a permission. No role, no permission. */
export function can(permission: IngredientPagePermission, role: PrincipalRole | null | undefined): boolean {
  return role != null && HOLDERS[permission].includes(role);
}
