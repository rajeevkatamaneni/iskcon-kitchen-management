# Local check — everything built 17–18 September

Open http://localhost:3000 and sign in as temple-admin.1. Each line says where to go, what to do and
what you should see. Items marked **(cloud)** can't be fully checked locally and will be checked after
the staging deploy.

## Meal planner
1. Open 19 Sep → Dinner → Edit. Type 210000 into the Basmati Ghee Rice amount and press Update. You
   should see "…can be at most 50,000" under the box, and nothing saves. Change it to 210 and press
   Update. It saves, and **Today loads again**.
2. Plan a new meal with 600 adults and add Basmati Ghee Rice. The amount fills in as 210 L, not
   210,000.
3. Week view → open a meal → Cancel. You land back in Week view on the same week. Do it again with
   Update: same place, plus a "saved" message.
4. On the meal edit screen, in "Who will run it": People needed, Rostered and the volunteers button
   sit in one row across the full width. "Ready by" sits close under the meal badge.
5. On the grain warning, "Leave it out" really removes the dish.
6. Week view shows an event's name, not "Event".
7. The day page no longer has "Correct this date", "The week around it" or "Open the calendar".

## Recipes
8. Basmati Ghee Rice → Edit. The labels read "This recipe makes" and "Measured in", with an ⓘ. The
   unit list offers only Kg, L and pieces. The portion unit only offers the same family. A line reads
   "This recipe feeds about … people at … each". Set it to 1 to see the amber warning.
9. The cost field reads "Rough cost of one batch (₹)", with the batch size beside it.
10. The recipe page shows the category and tags beside the name, the cost in the figures row
    ("₹8,000 per batch (…)"), Scale as a plain button with the unit beside the box, and it starts at
    the same height as other screens.

## Vaishnava calendar
11. Month view → click a day. "Correct this date" is here now (open it and cancel).
12. Festivals are saffron, not green. Ekadashi is blue. A fast until noon reads "Fast until noon. Plan
    the feast for after."

## Look and words, everywhere
13. Pills, counts, chips and the Day/Week/Month switch have the same subtle corner as the buttons.
14. Colours: amber only for warnings, red only for serious problems, green only right after your own
    action worked. "Ingredients ready" is no longer green.
15. Words: Ekadashi (never Ekadasi), "Ekadashi-friendly", "Served", "Vendor", "Reorder level",
    "Bulk sabjis". No "Catering note" on recipes.
16. Vendor performance: the explainer is 3 short lines, not a paragraph.
17. Tables: text columns on the left, short columns grouped on the right, no "Actions" heading. Users:
    emails on one line.

## Phone and tablet (narrow the browser window below 1024px)
18. A "Menu" bar appears at the top. Menu opens the side menu over the page; it closes on a tap
    outside, Escape, or choosing a page.
19. Form screens: the title and Cancel/Save stay visible under the Menu bar. Nothing scrolls sideways.
20. Tables turn into one card per row.

## Can't be checked locally
- **(cloud)** Anything that sends WhatsApp or email. Those are switched off locally.
- **(cloud)** The "Bulk sabjis" rename of existing data runs as a database migration on staging.
