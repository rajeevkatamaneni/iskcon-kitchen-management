# Seeding brief — simulating a real temple's operations

Rajeev's brief, given in the terminal on 2026-09-19. Kept verbatim below, because the scripts will be re-run
many times and the intent must not drift. Runs AFTER the recipe curation and the Day-1 reset
(docs/work/CATALOGUE-AND-DAY1-PLAN.md).

## His words

> We are trying to simulate a real temples day-to-day operations. So imagine a temple was on boarded, and this is
> their very first day, what would they do apart from on boarding their staff setting their schedules and what not
> one of the first things for meal planning they have to first figure out what stuff they have on hand and how much
> they have so let's do that first. Make sure to include ingredients and supplies in the inventory think of yourself
> as a temple administrator, running a real temple and imagine what kind of supplies and ingredients you might have
> on hand and also pick realistic quantities for each one of those next step would be importing the recipes. As I
> will be cleaning up all the recipes we will have a very small set of recipes. I think you can import all the
> recipes that are available in the master. Once the recipes are imported, then the ingredients will get imported
> automatically. Then your next job would be to go to each ingredient and set the alias, units EkaDashi flag,
> category, pack size, price, a vendor and details that vendor needs. Check on the staff schedules and make sure
> it's all set properly. Then start meal planning. Create meal plans in 15 day blocks. Be sure to pick a variety of
> meal plans like every day we have breakfast lunch and dinner obviously so pick festival events pick outside events
> pick events in the temple. Pick delivery outside events pick outside events where they will come and pick up all
> combinations basically. Once you prepare your meal plan for the first 15 days, then run our ordering campaign you
> will start with the shopping list then create purchase orders then similar deliveries. When you're doing your
> shopping list be sure to order supplies as well. Supplies can be gas cylinders, cleaning products, lightbulbs,
> stools, cups plates anything the temple needs to run a kitchen operation. Deliveries will be partial, full delivery
> at once, late deliveries and a combination of all the delivery types. Also reject some items upon delivery because
> the qualities is not good and also do some returns after the fact.. Then create invoices when creating invoices
> attach sample receipts that you create then simulate paying invoices. In invoices do cancel invoice reduce the
> amount on the invoice because we returned something and we got credit from the vendor or a credit for late
> delivery. Anything else you can think of basically in invoices. After the first 15 day meal planning and ordering
> cycle has been completed then start adding satellite kitchens at least have five kitchens make two of them use the
> meal planner and three of them are just going to get ingredients then start raising request for ingredients for the
> 3 kitchens that are just requesting ingredients. Then simulate most of the request being approved some being denied
> then run the issuing cycle record deliveries to the individual kitchens the whole cycle of issuing ingredients to
> sister kitchens. Basically you have to simulate that. Then do the next round of 15 day meal planning and repeat the
> ordering cycle once more along with sister kitchens, requesting ingredients two of the sister kitchens, which are
> going to use the meal planner will also be planning their meals in conjunction with the main kitchen and so on and
> so forth. Not every meal has to be a collaboration between multiple kitchens it can be a few meals, especially when
> those meals are festival meals because that is when it makes sense for multiple kitchens to collaborate because the
> menu will be longer.. Do this for at least three months look at the calendar for festivals and plan festival meals
> exactly on the day when an actual festival is happening apart from this we also have to simulate day-to-day
> operations of the temple where meals are getting prepared. Most days whatever is on the work order gets created
> however, there might be days when more or less might be cooked similar that. Also, we have to simulate the
> recording of actual data. We have to simulate just enough food leftover food, not enough food all kinds of
> combinations also raise volunteer shifts while meal planning and simulate volunteer signing up or field shifts under
> field shift shifts that are filled just enough and the combination of everything simulate wish list of equipment.
> The temple wants simulate donations for equipment that is listed. Also simulate general donations and cash donations
> that are done in person, etc.. Also simulate equipment service request, payment for service etc.
> Bottom line make the simulation as realistic as possible to a real temple, running real operations. If you have to
> go to the Internet to look up prices and stuff and non-quantities in which they're sold and anything that you need
> to get realistic information don't be shy to browse the Internet.

## How it will be built (conductor's plan, 2026-09-19)

- **Scripts, kept.** Everything goes in `tools/seed/` (committed), one script per phase, re-runnable, each printing
  what it created. They will be run more than once, on more than one environment.
- **Through the app's own API as real users**, not SQL, so every rule, permission, audit row and stock movement is
  the real one. SQL only where the app has no path (e.g. back-dating a created_at).
- **Order of phases** exactly as above: opening stock (ingredients AND supplies) → import the curated recipes →
  complete every ingredient (alias, unit, Ekadashi, category, pack sizes, price, vendor) → check staff schedules →
  15-day meal plan (3 meals a day plus festivals, temple events, outside events both delivered and collected) →
  shopping list → POs (incl. supplies) → deliveries (full, partial, late, rejections, later returns) → invoices
  (with uploaded receipts, credit notes for returns and late delivery, a void) → payments → then five sister
  kitchens (2 planning, 3 requesting) → ingredient requests, approvals and denials, issuing → next 15-day cycle with
  joint festival meals → repeat to at least three months → cooking and recording actuals (right, over, short,
  leftovers) → volunteer shifts (filled, part-filled, empty) → wish list, donations for wish-list items, general and
  cash donations → equipment service requests and their payments.
- **Festivals on their real dates**, from the Vaishnava calendar in the app.
- Real-world prices and pack sizes looked up online where useful.
