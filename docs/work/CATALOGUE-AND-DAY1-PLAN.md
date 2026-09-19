# After the procurement release: vetted catalogue, Day-1 reset, then seeding

Agreed with Rajeev on the Decisions Desk, 2026-09-19 (Q-12, Q-20, Q-21). Not scheduled work for the procurement release.

Order:
1. **Vet the ingredient list first** — a clean list (~300–400) with stock unit, pack sizes and a "Not bought" mark (water and the like; the shopping list skips them). Recipes-as-ingredients ("Cooked rice") map to the raw ingredient with the right amount, never hidden.
2. **Stand-alone local curation page** — each recipe shown with a machine-cleaned draft (base ingredient + preparation note + amount; combined lines split; water marked not bought); Rajeev checks, fixes by hand, presses "Add to catalogue". Only doubtful lines highlighted.
3. **Start with what the temple cooks** — its 39 recipes and those in `reference/recipes/RM 2019_v2.xlsx`; more later as needed (the library has 5,376).
4. **The bucket is files in the repo** — one JSON per recipe; diffable, reloadable, undoable. The teammate's vendored library files stay untouched.
5. **Day-1 reset** (staging backup first; exact list of what goes shown to Rajeev for a yes). KEEP: people, temples, WhatsApp and email settings, wish list, kitchens, meal kinds, calendar/festivals, **vendors** (Rajeev: "leave the vendors we already have but every price and supply link on them goes"). CLEAR: ingredients, recipes, meal plans, shopping lists, POs, deliveries, invoices, payments, inventory/stock, and all vendor prices and supply links.
6. Nuke the old master library; load the vetted catalogue.
7. **Seed** a month or a few months of simulated operations (redo vendor supply links and prices as part of it).

Rajeev's rule for preparations (Q-12, option 1, liked but not to be applied until this plan): if the store buys it as a different item it stays in the name; if the cook does it, it's a preparation note; same for a leading word; purpose tails ("for frying") become the note.
Facts: `docs/work/reference/INGREDIENT-MESS-2026-09-19.md`.
