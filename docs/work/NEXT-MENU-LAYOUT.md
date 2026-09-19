# Next in line after the procurement release: the temple's own menu layout

Approved by Rajeev, 2026-09-19 (terminal): "go, put it next in line after this release". Grew out of the local
playground at http://localhost:3000/dev-menu (frontend/app/dev-menu, never committed).

- **Settings → Menu**, Temple Admin only (a permission, never a role). Built from the playground: drag items and
  groups, Move up/down buttons, **New group**, rename groups, **Reset to the standard menu**. Saving applies to
  everyone at that temple (temple-wide, not per person).
- **The layout decides order and grouping, never access.** Each person still sees only what their role allows;
  empty groups disappear for them. The phone drawer uses the same layout.
- **Not allowed:** renaming menu items (they must match page titles, help and training); hiding items (a temple
  that doesn't use a feature switches the feature off instead).
- **New screens in future releases** land in their standard group if the temple still has it, else at the bottom
  under "New".
- Storage: a per-tenant layout row (RLS via enable_tenant_rls; tenant from the token); the sidebar merges it with
  the standard menu and the permission filter. Easy to undo: reset restores the standard menu.
- Help/UAT wording says "open Deliveries", never "Ordering → Deliveries".
- Estimate: about a day.

---
# Also approved 2026-09-19: Epic 12, which kitchen is cooking (before or after the menu layout — ask Rajeev)

Rajeev (terminal): "GO ahead, add the Which Kitchen is making this meal feature." Additions to
docs/stories/EPIC-12-which-kitchen-is-cooking-DESIGN.md:
- "People needed" is answered **per kitchen**; the rostered staff shown are that kitchen's staff.
- **Staff listing gets a kitchen classifier** — every staff member belongs to a kitchen — so rostering and
  "people needed" work per kitchen, including a sister kitchen that adds its own staff.
- A lunch cooked by two kitchens: **each kitchen gets its own job card** to print. Whether it's one Lunch card
  with a clear section per kitchen or two Lunch cards was decided by Rajeev: OPTION 1 (one Lunch card, a section per kitchen), from the local mock
  http://localhost:3000/dev-kitchen-meal (T-342).
- **Composer approved by Rajeev ("looks AMAZING", 2026-09-19)** as in /dev-kitchen-meal?option=build: one section to start;
  "+ Add another kitchen" searchable dropdown (only kitchens that plan meals here, not already on the meal); × per
  section with inline "Remove <kitchen> and its N dishes?"; no Move. **Default section = the signed-in person's own
  kitchen** (from the staff kitchen classifier); falls back to the main kitchen when the person has no kitchen, or
  their kitchen doesn't plan meals here.
- **Section order (Rajeev, 2026-09-19, revised the same day):** the **signed-in person's own kitchen is always
  the first section** — when building, after save and on every later edit, and in the meal's view card for them.
  (He first asked for the main kitchen to always float to the top, then took it back: "we have to go with the
  user's kitchen assignment".) The other kitchens follow in the order the temple's kitchens are listed in Settings.
  A person with no kitchen, or whose kitchen isn't on the meal, sees the main kitchen first (if present), then
  Settings order.
- **Staff and kitchens (Rajeev, 2026-09-19):** every staff member belongs to exactly one kitchen — required on add
  and edit. Only people whose kitchen plans its meals here can open the meal planner (server-enforced, and hidden
  from the menu). Rajeev said yes to both defaults: **the Temple Admin sees and plans for every kitchen**; **existing
  staff are put in the main kitchen when the change ships**, and the Temple Admin gets a "check these kitchen
  assignments" list to move anyone. Festival helpers in another kitchen: one kitchen each for now; a "home kitchen,
  can also cook in" idea only if it proves common.
