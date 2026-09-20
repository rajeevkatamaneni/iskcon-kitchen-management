import type { MenuLayout, PrincipalRole } from "./api";

/**
 * Navigation per role, in one place — the same reasoning as RolePermissions on the backend: what
 * each role can reach should be readable at a glance, not scattered across pages.
 *
 * <p>Grouped, per the ISKCON Kitchen Design System (Claude Design, 2026-08-14). A flat list of
 * eighteen destinations is a wall; grouped, a person finds "the ordering one" without reading every
 * label. The first group is deliberately untitled — Today and the meal plan are where people live,
 * and a heading above two items you use daily is noise.
 *
 * <p><b>The order and the grouping below are Rajeev's own, settled 2026-09-19</b> after two rounds on
 * the local playground and written down in `docs/work/MENU-LAYOUT-2026-09-19.md`. Sentence case
 * throughout, seven groups, the first unheaded. It is a specification, not a suggestion: reordering a
 * group or renaming a heading is a change to ask him about, and `nav.test.ts` asserts it item by item
 * so that a well-meant tidy-up fails rather than ships.
 *
 * <p>Navigation is not the security boundary (the API enforces that on every request), but a menu
 * should never offer someone a destination they'll only be refused at. Every item carries the exact
 * set of roles the destination's own page guard allows, and {@link navForRole} filters by the
 * signed-in role. Add a page, add its item here with the roles its `RequireRole` uses — the two stay
 * in step.
 *
 * <p>The meal planner is the one destination a role alone does not settle (Epic 12): a cook whose
 * kitchen does not plan its meals here holds the role and is still refused. So its items carry
 * `mealPlanner`, {@link navForRole} drops them for that person, and `RequireRole` refuses them at
 * every `/planner` address — the same rule, applied in the menu and at the door.
 *
 * <p>A temple may arrange this menu for itself (Settings → Menu, Rajeev 2026-09-19): its own order
 * within a group, its own headings, an item moved from one group to another. That arrangement rides
 * on the session as `WhoAmI.menuLayout` and is merged with the list below by {@link applyMenuLayout},
 * at render, every time — the list below stays the one place a destination exists, and the
 * arrangement only ever orders ids. <b>It decides order and grouping and nothing else:</b> items are
 * never renamed and never hidden by it, and {@link navForRole} filters by role after it has run, so
 * no arrangement can add a destination to somebody's menu or take one away.
 */

export interface NavItem {
  /**
   * This destination's permanent name in a temple's own arrangement of the menu — see
   * {@link applyMenuLayout}. Opaque: nothing may be read out of it, and nothing may be derived from
   * it, because the whole point of it is that it never changes.
   *
   * <p><b>How these were first chosen, which is not a rule for deriving them again:</b> when ids
   * were introduced (T-421) each one was written as the item's `href` with the leading slash
   * dropped and the remaining slashes turned into hyphens — `/today` → `today`,
   * `/settings/occasions` → `settings-occasions`, `/issued-from-store` → `issued-from-store`. That
   * was a one-off convenience so the first set read sensibly. <b>From here an id is permanent and
   * independent of the address.</b> Renaming a route does not touch it, and must not: the
   * arrangement is stored as a list of these ids, so an id that moved with its route would silently
   * drop that destination out of every temple's arrangement and down to the bottom of the menu.
   * `/issued-from-store` keeps its id for the same reason it kept its address when Rajeev renamed
   * it to "Issued to kitchens".
   *
   * <p>A new destination picks a new id that has never been used; ids are never reused, for the
   * same reason an error code is never reused.
   */
  id: string;
  href: string;
  label: string;
  /** Tabler outline icon name, without the `ti-` prefix. */
  icon: string;
  roles: PrincipalRole[];
  /**
   * A meal-planner destination (Epic 12). Offered only to somebody the planner is open to, as well as
   * holding one of `roles` — see {@link navForRole}. Set on every `/planner` item and on nothing else.
   */
  mealPlanner?: true;
}

export interface NavGroup {
  /**
   * The group's permanent name in a temple's arrangement, chosen the same way and permanent for the
   * same reasons as {@link NavItem.id}. A temple may rename the heading above a group as often as it
   * likes; the id underneath does not move, which is what lets a destination added in a later
   * release land in the right group even at a temple that calls it something else entirely.
   */
  id: string;
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
    id: "main",
    // The platform operator runs the platform, not a temple — their two destinations sit here
    // alongside the daily ones, since they never see any of the groups below.
    items: [
      { id: "tenants", href: "/tenants", label: "Temples", icon: "building-community", roles: [OPERATOR] },
      { id: "operations", href: "/operations", label: "Operations", icon: "activity", roles: [OPERATOR] },
      // Beside Operations (T-177): what the app sends on WhatsApp, read-only, for the operator who
      // otherwise could not see inside a template (Rajeev, 2026-09-13).
      { id: "whatsapp-templates", href: "/whatsapp-templates", label: "WhatsApp templates", icon: "messages", roles: [OPERATOR] },
      // Its own destination rather than a tab inside Operations: that screen answers "what is
      // failing", and a catalogue of five thousand recipes is not an answer to it.
      { id: "library", href: "/library", label: "Recipe library", icon: "book", roles: [OPERATOR] },
      // Beside Operations, because a downtime notice is an operations act.
      //
      // `notices-operator`, not `notices`: this row and the admin's under "Temple" are the only two
      // items in this file at one address, and the ids have to differ or a temple's arrangement
      // would index one of them over the other and lose it. The admin's keeps the plain `notices`,
      // being the one every temple can actually arrange; the operator carries no arrangement at all.
      { id: "notices-operator", href: "/notices", label: "Notices", icon: "speakerphone", roles: [OPERATOR] },
      { id: "today", href: "/today", label: "Today", icon: "sun", roles: [ADMIN, MANAGER, KITCHEN] },
      { id: "calendar", href: "/calendar", label: "Vaishnava calendar", icon: "calendar-event", roles: [ADMIN, MANAGER, KITCHEN] },
      { id: "planner", href: "/planner", label: "Meal planner", icon: "calendar-month", roles: [ADMIN, MANAGER, KITCHEN], mealPlanner: true },
      // Directly under the planner, because it is the planner's most-travelled path: temples copy a
      // previous cycle and adjust it rather than building one from nothing, whatever their buying
      // interval is (Rajeev, 2026-09-05). It sits in the first group for the same reason Today and
      // the planner do — it is where people live, not a tool they go looking for.
      { id: "planner-reuse", href: "/planner/reuse", label: "Reuse a plan", icon: "copy", roles: [ADMIN, MANAGER, KITCHEN], mealPlanner: true },
      // Last of the daily five, and deliberately not under Kitchens (Rajeev, 2026-09-19): it measures
      // what the meal planner planned, not what a kitchen took from the store, and the admin checks
      // it often to watch the budget. When billing the sister kitchens arrives there will be two
      // money screens in two places — this one for planned meals, the kitchen's bill under Kitchens
      // for what a kitchen owes — so each page's subtitle has to say which question it answers.
      //
      // Not a meal-planner destination in the `mealPlanner` sense: the report reads recorded meals
      // and issues, and a kitchen that does not plan here still has both. Its own page admits the
      // same three roles with no planner condition, and the menu matches the page.
      { id: "cost-per-serving", href: "/cost-per-serving", label: "Cost per serving", icon: "report-money", roles: [ADMIN, MANAGER, KITCHEN] },
      // Seva, not work. D-16: staff get neither this nor Donate — "they are already doing their
      // part". Their own rostered days are /my-schedule, on VIEW_OWN_SHIFTS, under People.
      { id: "my-shifts", href: "/my-shifts", label: "My shifts", icon: "calendar-check", roles: [VOLUNTEER] },
      { id: "shifts", href: "/shifts", label: "Available shifts", icon: "hand-click", roles: [VOLUNTEER] },
      // A devotee who serves is the same person who gives — the kitchen's donors are its
      // volunteers, not strangers. One destination, because money and the things the kitchen wants
      // are two tabs of the same question.
      // Volunteers alone, and that is not a menu decision (Rajeev, 2026-09-07): people employed by
      // the temple — admin, manager or cook — do not give, because a temple kitchen wage is not a
      // king's ransom and their service is the donation. So the page guard refuses them too; this
      // row and `app/donate/page.tsx` carry the same list, which is nav.ts's own rule.
      { id: "donate", href: "/donate", label: "Donate", icon: "heart-handshake", roles: [VOLUNTEER] },
      // Beside Donate (T-179): the gifts that are theirs and the receipt for each one. The temple's "send
      // receipt" stays as the fallback, not the default (Rajeev, 2026-09-13).
      { id: "my-donations", href: "/my-donations", label: "My donations", icon: "receipt", roles: [VOLUNTEER] },
    ],
  },
  {
    id: "ordering",
    title: "Ordering",
    items: [
      { id: "shopping-list", href: "/shopping-list", label: "Shopping list", icon: "clipboard-list", roles: [ADMIN, MANAGER, KITCHEN] },
      { id: "orders", href: "/orders", label: "Purchase orders", icon: "truck-delivery", roles: [ADMIN, MANAGER, KITCHEN] },
      // Right after Purchase orders (R-DEL-1). Needs RECEIVE_DELIVERIES: Temple Admin, Kitchen
      // Manager and Kitchen Staff (Rajeev answered Q-1 on 2026-09-19: Kitchen Staff by default).
      { id: "deliveries", href: "/deliveries", label: "Deliveries", icon: "package-import", roles: [ADMIN, MANAGER, KITCHEN] },
      // The group reads as one errand in the order it actually happens: write the list, place the
      // order, take the goods in, pay for them (Rajeev's menu, 2026-09-19). Vendors and the
      // judgement of them come after, because they are the standing facts behind that errand rather
      // than a step in it.
      { id: "invoices", href: "/invoices", label: "Invoices", icon: "file-invoice", roles: [ADMIN, MANAGER, KITCHEN] },
      // No Payments item (R-PAY-4, 2026-09-19): invoices are paid from the invoice itself, and what
      // the Payments page listed (unpaid invoices by age, and the total owed) is the Invoices list's
      // Unpaid and overdue filters now. /money redirects to /invoices?filter=unpaid.
      { id: "vendors", href: "/vendors", label: "Vendors", icon: "building-store", roles: [ADMIN, MANAGER, KITCHEN] },
      // Directly under the vendors it judges. Its own destination rather than a tab on a vendor,
      // because the question it answers — who should we keep buying from — is asked across all of
      // them at once, and cannot be seen one vendor at a time.
      { id: "vendor-performance", href: "/vendor-performance", label: "Vendor performance", icon: "gauge", roles: [ADMIN, MANAGER, KITCHEN] },
    ],
  },
  {
    // What the temple holds and what it knows how to cook. The group used to be called "Kitchen" and
    // held the issuing screens too, which made one word cover the store room, the recipe book and the
    // rooms food is issued to; those rooms have their own group below now (Rajeev's menu, 2026-09-19).
    id: "inventory-recipes",
    title: "Inventory & Recipes",
    items: [
      // Inventory leads: what is actually on the shelf is the question asked most, and the four
      // catalogues under it are what its rows are made of.
      { id: "inventory", href: "/inventory", label: "Inventory", icon: "package", roles: [ADMIN, MANAGER, KITCHEN] },
      { id: "recipes", href: "/recipes", label: "Recipes", icon: "tools-kitchen-2", roles: [ADMIN, MANAGER, KITCHEN] },
      { id: "ingredients", href: "/ingredients", label: "Ingredients", icon: "salt", roles: [ADMIN, MANAGER, KITCHEN] },
      // Directly after Ingredients, because it is the same catalogue read the other way round
      // (T-089). LPG, kerosene, cleaning liquid, bulbs and brooms are bought, received, stored and
      // used up exactly as food is — the rule that puts a thing here rather than under Equipment is
      // whether USE consumes it, and a ladder or a plastic stool is not consumed however cheap it
      // is. Same roles as Ingredients: anybody who may catalogue rice may catalogue soap.
      { id: "supplies", href: "/supplies", label: "Supplies", icon: "spray", roles: [ADMIN, MANAGER, KITCHEN] },
      // Last, after the consumables: they are the two halves of one word — what flows through the
      // store room, and what the temple owns and maintains. Everybody who may read the register is
      // here — a cook standing in front of a stopped grinder is the person who knows first — while
      // recording the service against it is the admin's alone, on the page itself.
      { id: "equipment", href: "/equipment", label: "Equipment", icon: "tools", roles: [ADMIN, MANAGER, KITCHEN] },
    ],
  },
  {
    // The rooms the food goes to, and the traffic between them and the store: asked for, issued,
    // and the list of the kitchens themselves. Its own group since Epic 12 gave a temple more than
    // one kitchen worth naming (Rajeev's menu, 2026-09-19).
    id: "kitchens",
    title: "Kitchens",
    items: [
      // Daily work, and the head of the group because it is where the traffic starts. Anybody who
      // cooks may ask the store for something; only an admin or a manager answers, and the page
      // shows each of them what they can actually do (E10-S8).
      { id: "ingredient-requests", href: "/ingredient-requests", label: "Ingredient requests", icon: "clipboard-text", roles: [ADMIN, MANAGER, KITCHEN] },
      // Renamed from "Issued from store" (Rajeev, 2026-09-19, chosen over "Out of the store"). Same
      // screen and same address: it is the one place to see what each kitchen took, when, how much
      // and what it cost, and naming it for the kitchens says that better than naming it for the
      // store — which matters more now that billing the sister kitchens will sit beside it. The
      // route stays `/issued-from-store` so that every existing link and bookmark still works.
      { id: "issued-from-store", href: "/issued-from-store", label: "Issued to kitchens", icon: "package-export", roles: [ADMIN, MANAGER, KITCHEN] },
      // Last, and admin-only: which kitchens a temple runs is a structural fact about the temple,
      // closer to Settings than to a morning's cooking. "All kitchens" rather than "Kitchens" so the
      // row is not the group's own heading said twice.
      { id: "kitchens", href: "/kitchens", label: "All kitchens", icon: "building-warehouse", roles: [ADMIN] },
    ],
  },
  {
    // Everyone the temple deals with, in one group: the person reading it, the people it employs,
    // the community that registered itself, and the seva offered to it.
    id: "people",
    title: "People",
    items: [
      // First, because it is the reader's own: *My shifts* is seva a volunteer offered, *My
      // schedule* is the working days a person is rostered for. Behind VIEW_OWN_SHIFTS, which an
      // admin holds as surely as a cook does — so all three are here. A volunteer is not: they hold
      // the permission but have no staff profile for it to read.
      { id: "my-schedule", href: "/my-schedule", label: "My schedule", icon: "calendar-user", roles: [ADMIN, MANAGER, KITCHEN] },
      { id: "staff", href: "/staff", label: "Staff", icon: "id-badge-2", roles: [ADMIN] },
      { id: "staff-schedule", href: "/staff-schedule", label: "Staff schedule", icon: "calendar-time", roles: [ADMIN, MANAGER] },
      // Beside the roster it bends, and behind the same two roles: answering leave is what a
      // kitchen manager is appointed to do, and it is the reason the role exists at all.
      { id: "leave", href: "/leave", label: "Leave", icon: "calendar-off", roles: [ADMIN, MANAGER] },
      // The community register, after the payroll: the temple's own people, then everybody else.
      { id: "users", href: "/users", label: "Devotees", icon: "users-group", roles: [ADMIN] },
      { id: "volunteers", href: "/volunteers", label: "Volunteer shifts", icon: "users", roles: [ADMIN, MANAGER, KITCHEN] },
    ],
  },
  {
    // What the temple is given, what it is asking for, and what it says to the people who give it.
    // Communications moved here from People (Rajeev's menu, 2026-09-19): writing to the devotees is
    // an act of outreach, and it sat under People only because the register did.
    id: "giving-outreach",
    title: "Giving & Outreach",
    items: [
      { id: "donations", href: "/donations", label: "Donations", icon: "gift", roles: [ADMIN, MANAGER, KITCHEN] },
      { id: "wishlist", href: "/wishlist", label: "Wish list", icon: "heart-handshake", roles: [ADMIN] },
      { id: "communications", href: "/communications", label: "Communications", icon: "mail", roles: [ADMIN] },
    ],
  },
  {
    id: "temple",
    title: "Temple",
    items: [
      // Raising a notice reaches every temple on the platform. Rare and serious enough to sit
      // somewhere deliberate rather than one click from daily work — which is why it is here and
      // not beside the kitchen screens an admin uses all morning.
      { id: "notices", href: "/notices", label: "Notices", icon: "speakerphone", roles: [ADMIN] },
      // Curating the catalogue of special days is what this is: which days the planner should treat
      // as special is a standing fact about this temple, not a meal. Admin alone, matching
      // MANAGE_TEMPLE_SETTINGS — the planner reads the catalogue, the admin writes it.
      { id: "settings-occasions", href: "/settings/occasions", label: "Festival occasions", icon: "confetti", roles: [ADMIN] },
      // And beside it, what the temple calls its meals. Same act as curating occasions — a standing
      // fact about this temple that the planner reads and only an admin writes — and the same role
      // set for the same reason: reading the kinds is MANAGE_MEAL_PLANS, changing them is
      // MANAGE_TEMPLE_SETTINGS. Until this row existed the rename endpoints had no caller at all.
      { id: "settings-meal-kinds", href: "/settings/meal-kinds", label: "Meal kinds", icon: "soup", roles: [ADMIN] },
      { id: "audit", href: "/audit", label: "Audit log", icon: "history", roles: [ADMIN] },
      // Last, and one word: the temple's name is already at the top of this menu, so "whose
      // settings" needs no saying, and "Settings" stays right as it grows past payments.
      { id: "settings", href: "/settings", label: "Settings", icon: "settings", roles: [ADMIN] },
    ],
  },
];

/**
 * The standard menu: every destination there is, in Rajeev's order, before any temple's arrangement
 * and before any role filter. The Settings → Menu screen arranges this; nothing else should need it,
 * because {@link navForRole} is what a menu is built from.
 *
 * <p>Copies, deliberately. The array above is module state shared by every caller in the page, and a
 * screen that reorders a menu is exactly the kind of caller that would splice it.
 */
export function standardMenu(): NavGroup[] {
  return GROUPS.map((g) => ({ ...g, items: g.items.map((i) => ({ ...i })) }));
}

/**
 * The group a destination lands in when its own standard group is not in the temple's arrangement —
 * created by the merge, at the bottom, and never stored. Rajeev, 2026-09-19: "New screens in future
 * releases land in their standard group if the temple still has it, else at the bottom under 'New'."
 *
 * <p>The id is reserved: the Settings screen must not mint it for a group somebody makes, or the
 * merge would have two groups with one id and React two children with one key.
 */
export const NEW_GROUP_ID = "new";
export const NEW_GROUP_TITLE = "New";

/**
 * The standard menu arranged the way a temple has arranged it (Rajeev, 2026-09-19). Pure: it reads
 * `groups` and `layout` and returns a new menu, and it decides order and grouping only — every item
 * it returns is one of `groups`' own, with its label, its icon and its roles untouched.
 *
 * <p><b>A saved arrangement is never migrated. The two lists are merged here, at render, every
 * time.</b> `nav.ts` stays the one list of what destinations exist; the stored arrangement only
 * orders ids. The alternative — rewriting every temple's stored row on each upgrade so it matches
 * the menu that shipped — needs a migration per release, runs once and cannot be undone, and gets
 * one release wrong in silence. Merging at render costs a few microseconds per page and is always
 * right about the menu that is actually on the screen.
 *
 * <p>The rules, each with the reason it is that way:
 *
 * <ul>
 *   <li><b>Groups come out in the arrangement's order</b>, each with the arrangement's own heading,
 *       so a temple that renamed "Ordering" to "Buying" sees "Buying"; and each holds its items in
 *       the arrangement's order.
 *   <li><b>An id the arrangement names that this menu does not have is passed over.</b> It stays in
 *       the stored row rather than being pruned — a destination withdrawn for a release and brought
 *       back comes back where the temple put it, instead of at the bottom under "New".
 *   <li><b>A destination the arrangement does not place goes to the end of its standard group</b>,
 *       if that group's id is still in the arrangement — <i>even where the temple renamed the
 *       heading</i>, which is the whole reason groups are keyed on an id rather than on their words.
 *   <li><b>If its standard group is not in the arrangement at all, it goes to the bottom, under
 *       "New".</b> That group is made here and never stored, so it disappears of its own accord once
 *       the admin files its contents somewhere.
 *   <li><b>A group the arrangement holds that ends up with no items still comes out of here.</b>
 *       Dropping empty groups is {@link navForRole}'s job and stays there: the Settings screen has to
 *       see an emptied group to drag something back into it, and a kept group is where the next
 *       release's new destination lands. If emptying "Ordering" quietly deleted it, the next ordering
 *       screen would appear under "New" — a surprise produced by merely rearranging.
 *   <li><b>A destination the arrangement lists twice appears once</b>, in the first place it is
 *       listed. The server refuses a save like that (KMS-400190), so this is only the belt to that
 *       brace — but a menu with one destination on it twice is worse than a menu in the wrong order.
 *   <li><b>No arrangement, an arrangement with no groups, or an arrangement naming not one group and
 *       not one destination this menu knows about, returns the standard menu unchanged.</b> The last
 *       of those is the guard that matters: whatever is in the stored row, a menu must never come out
 *       of here empty, or arranged into a single heap under "New", because the row was odd.
 * </ul>
 */
export function applyMenuLayout(groups: NavGroup[], layout: MenuLayout | null | undefined): NavGroup[] {
  const arranged = Array.isArray(layout?.groups) ? layout!.groups.filter((g) => g && typeof g === "object") : [];
  if (arranged.length === 0) return groups;

  const byItemId = new Map<string, NavItem>();
  for (const group of groups) {
    for (const item of group.items) byItemId.set(item.id, item);
  }

  // Does this arrangement have anything at all to do with this menu? A row that names no group we
  // have and no destination we have cannot arrange anything; honouring it would put the whole menu
  // under "New". Treated as no arrangement, which is the one answer that cannot leave a person
  // without a menu.
  const standardGroupIds = new Set(groups.map((g) => g.id));
  const recognises =
    arranged.some((g) => standardGroupIds.has(String(g.id))) ||
    arranged.some((g) => (Array.isArray(g.items) ? g.items : []).some((id) => byItemId.has(String(id))));
  if (!recognises) return groups;

  const placed = new Set<string>();
  const merged: NavGroup[] = arranged.map((group) => ({
    id: String(group.id),
    // `null` is how the wire says "no heading"; `NavGroup` says it with the field absent, and the
    // first group has always been the unheaded one. A heading of nothing but spaces is treated as
    // no heading rather than as a blank line above the group.
    title: typeof group.title === "string" && group.title.trim() ? group.title : undefined,
    items: (Array.isArray(group.items) ? group.items : []).flatMap((id) => {
      const item = byItemId.get(String(id));
      if (!item || placed.has(item.id)) return [];
      placed.add(item.id);
      return [item];
    }),
  }));

  const mergedById = new Map(merged.map((g) => [g.id, g]));
  const newcomers: NavItem[] = [];
  for (const group of groups) {
    for (const item of group.items) {
      if (placed.has(item.id)) continue;
      placed.add(item.id);
      const home = mergedById.get(group.id);
      if (home) home.items.push(item);
      else newcomers.push(item);
    }
  }
  if (newcomers.length > 0) {
    merged.push({ id: NEW_GROUP_ID, title: NEW_GROUP_TITLE, items: newcomers });
  }
  return merged;
}

/**
 * The menu for a role: every destination that role's page guard admits, arranged the way the temple
 * arranged it, grouped, with any group that ends up empty dropped rather than left as a heading over
 * nothing. Returns empty for an unknown or absent role — a menu is never shown before the person is
 * resolved.
 *
 * <p><b>The order of the three steps is the security-relevant part.</b> The arrangement is applied
 * first, and the role filter runs over its result — so an arrangement can move a destination and
 * rename the heading above it, and can neither offer somebody a destination their role does not
 * already allow nor take one away. The filter below is the only thing here that decides who sees
 * what, exactly as it was before arrangements existed, and the meal-planner rule rides with it.
 * Nothing about a temple's arrangement is allowed to reach that line.
 */
export function navForRole(
  role: PrincipalRole | null | undefined,
  person?: { canPlanMeals?: boolean } | null,
  layout?: MenuLayout | null,
): NavGroup[] {
  if (!role) return [];
  const plannerShut = plannerRefused(person);
  return applyMenuLayout(GROUPS, layout)
    .map((g) => ({
      ...g,
      items: g.items.filter((i) => i.roles.includes(role) && !(i.mealPlanner && plannerShut)),
    }))
    .filter((g) => g.items.length > 0);
}

/**
 * Whether the meal planner is shut to this person (Epic 12). Rajeev, 2026-09-19: "Only people whose
 * kitchen plans its meals here can open the meal planner (server-enforced, and hidden from the
 * menu)". The server decides it — `WhoAmI.canPlanMeals`, the same rule it refuses with `KMS-400183` —
 * and this only reads the answer, so the rule lives in one place.
 *
 * <p>Only an explicit `false` shuts it. A session shaped before Epic 12, or a test fixture that never
 * heard of the field, carries `undefined`, and that must keep today's menu rather than quietly take
 * the planner away from everybody: the role check above still applies, and the server still refuses
 * anybody it should.
 */
export function plannerRefused(person?: { canPlanMeals?: boolean } | null): boolean {
  return person?.canPlanMeals === false;
}

/**
 * Whether a path is one of the meal planner's screens: `/planner` and everything under it. The page
 * guard uses it to refuse the person the menu above no longer offers the planner to, so that typing
 * the address, or an old bookmark, meets the same refusal as a wrong role.
 */
export function isMealPlannerPath(path: string | null | undefined): boolean {
  return !!path && (path === "/planner" || path.startsWith("/planner/"));
}

/**
 * Where the menu was left, and who left it there.
 *
 * <p>Session-scoped, so a new tab starts at the top. That was not enough: signing out and back in
 * happens in the same tab, and the menu came back scrolled to wherever the last person had it —
 * which, on a menu long enough to scroll, means the first thing a temple admin sees on logging in
 * is the middle of it, with Today selected somewhere above the fold. So signing in forgets the
 * position and signing out does too; everything between them is remembered, because that part was
 * right.
 */
export const SIDEBAR_SCROLL_KEY = "kms.sidebar.scroll";

/** Sends the menu back to the top, where Today is. Safe to call before the window exists. */
export function forgetSidebarScroll() {
  if (typeof window !== "undefined") {
    window.sessionStorage.removeItem(SIDEBAR_SCROLL_KEY);
  }
}
