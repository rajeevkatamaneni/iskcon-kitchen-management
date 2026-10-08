import type { PrincipalRole } from "@/lib/api";

/**
 * Who may open the Kitchen Assistant, written as the permission its endpoints declare.
 *
 * <p>Every assistant endpoint is `@PreAuthorize("hasAuthority('USE_KITCHEN_ASSISTANT')")`, and
 * `RolePermissions.java` grants that to all temple staff — Temple Admin, Kitchen Manager and Kitchen Staff (Rajeev,
 * 2026-10-07: "All temple staff should see the assistant."). The session this browser holds knows the role, not the permissions, so the
 * translation is done once, here, named by the permission — the way `components/ingredient/access.tsx`
 * does it — and when the grant moves this is the one line to move with it.
 *
 * <p>Hiding the button is a courtesy, never the guard: the api refuses anyone without the permission.
 */
const USE_KITCHEN_ASSISTANT: readonly PrincipalRole[] = ["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"];

/** Whether a signed-in role may use the assistant. No role, no assistant. */
export function canUseAssistant(role: PrincipalRole | null | undefined): boolean {
  return role != null && USE_KITCHEN_ASSISTANT.includes(role);
}
