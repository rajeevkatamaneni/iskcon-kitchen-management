# The standard menu, settled by Rajeev on 2026-09-19

Agreed in the terminal after two rounds on the local playground (`/dev-menu`). This replaces the order in
`frontend/lib/nav.ts`. Build it with the kitchen work (Epic 12), since that adds "All kitchens" and renames
"Issued from store".

- *(no heading)* Today · Vaishnava calendar · Meal planner · Reuse a plan · Cost per serving
- **Ordering** Shopping list · Purchase orders · Deliveries · Invoices · Vendors · Vendor performance
- **Inventory & Recipes** Inventory · Recipes · Ingredients · Supplies · Equipment
- **Kitchens** Ingredient requests · Issued to kitchens · All kitchens
- **People** My schedule · Staff · Staff schedule · Leave · Devotees · Volunteer shifts
- **Giving & Outreach** Donations · Wish list · Communications
- **Temple** Notices · Festival occasions · Meal kinds · Audit log · Settings

Rules that go with it:
- The first group keeps no heading (Today and the planner are where people live).
- Sentence case everywhere: "All kitchens", not "All Kitchens".
- **"Issued from store" is renamed "Issued to kitchens"** (Rajeev chose it over "Out of the store", 2026-09-19) — the menu item, the page title and anything that links
  to it. Rajeev, 2026-09-19: the page is the one place to see what each kitchen took, when, how much and what it
  cost, and it will grow to billing the sister kitchens (bills and payments beside it later).
- Roles still decide what each person sees; an empty group is dropped, not left as a heading over nothing.
- **Cost per serving stays in the first group** (Rajeev, 2026-09-19): it measures what the meal planner planned,
  not what a kitchen took from the store, and the admin checks it often to watch the budget. When the sister-kitchen
  billing arrives there will be two money pages in different places — Cost per serving (planned meals) and the
  kitchen bills under Kitchens (what a kitchen owes) — so each page's subtitle must say which question it answers.
- Operator (Super Admin) items are unaffected.
