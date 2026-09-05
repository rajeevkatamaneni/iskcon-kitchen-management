-- =====================================================================
-- V93 — A delivery address you picked, and a travel time you own
--
-- Rajeev, 2026-09-05, on the address box:
--
--     "That is a valid good address. The Clubhouse part is questionable
--      because that is just a sub location / the final destination inside
--      Mantri Serenity which MAY or MAYNOT be there BUT that doesnt make
--      this addrerss invalid... Being int the correct main location is
--      more important than being able to put a laser pointer on the final
--      destination. The delivery driver can call the person taking the
--      delivery and ask 'I am at the main entrance, where do I come to
--      deliver?'"
--
-- Two ideas in that, and this migration is the shape of both.
--
-- ---------------------------------------------------------------------
-- 1. The address is picked, not typed
--
-- Until now the address was free text, handed to OpenStreetMap's geocoder
-- (V88) and hoped for. When that missed — and its coverage of Bengaluru
-- apartment complexes is thin — the failure was silent, late, and on a
-- screen nobody was watching. The address becomes a choice from Google
-- Places instead, so the lookup either succeeds in front of the person
-- typing or does not happen at all.
--
-- `delivery_place_id` is what makes that durable. Coordinates go stale
-- when a road moves; a place id does not, and it is the one piece of Maps
-- content the licence is unambiguous about keeping. delivery_latitude,
-- delivery_longitude and geocoded_at stay exactly as V88 built them: a
-- temple that has picked a place still needs coordinates to route from,
-- and a plan saved before this migration still has the ones it had.
--
-- `delivery_sub_location` is the "Clubhouse". It is kept and it is NEVER
-- geocoded — routing goes to the gate, and the last fifty metres is a
-- phone call. Dropping it, which was the first proposal, would have thrown
-- away the one line the driver most needs when they arrive.
--
-- ---------------------------------------------------------------------
-- 2. The travel time belongs to the temple
--
-- `travel_minutes` is how long the temple has decided to allow for this
-- drive. It is prefilled from Google's estimate the moment there is an
-- address and a time the guests eat, and it is editable, because somebody
-- who drives Kanakapura Road at noon knows things a traffic model does
-- not.
--
-- `travel_minutes_source` is what makes the two rules Rajeev settled work
-- together. He asked for the number to be recalculated when the card is
-- printed, and separately for a planner to be able to override it. Those
-- collide: a recalculation would silently discard the correction of the
-- one person who knew better, at the worst possible moment. So the source
-- decides — ESTIMATED is refreshed at print, MANUAL is left alone and
-- prints as the person set it.
--
-- The stored figure is also what the job card prints, which means no
-- number of Google's is ever written onto a filed sheet. That was a
-- licensing worry (Maps ToS §3.2.3(b) permits caching coordinates and not
-- durations) and Rajeev overruled it as hair-splitting on 2026-09-05,
-- which is his call to make; the design happens to answer it anyway.
-- =====================================================================

ALTER TABLE meal_plans
    ADD COLUMN delivery_place_id     TEXT
        CHECK (delivery_place_id IS NULL OR length(delivery_place_id) <= 300),
    ADD COLUMN delivery_sub_location TEXT
        CHECK (delivery_sub_location IS NULL OR length(delivery_sub_location) <= 200),
    ADD COLUMN travel_minutes        INTEGER
        CHECK (travel_minutes IS NULL OR travel_minutes BETWEEN 1 AND 600),
    ADD COLUMN travel_minutes_source TEXT
        CHECK (travel_minutes_source IS NULL OR travel_minutes_source IN ('ESTIMATED', 'MANUAL')),

    -- A source with no figure, or a figure with no source, would leave the
    -- print-time rule with nothing to decide on. Neither is a state the
    -- application can produce; this makes it a state the database refuses.
    ADD CONSTRAINT meal_plans_travel_minutes_has_a_source
        CHECK ((travel_minutes IS NULL) = (travel_minutes_source IS NULL));

COMMENT ON COLUMN meal_plans.delivery_place_id IS
    'Google''s stable identifier for the picked delivery address (V93). Kept rather than re-derived: coordinates go stale when a road moves, and of everything a Maps response carries this is the piece the licence is clearest about storing.';

COMMENT ON COLUMN meal_plans.delivery_sub_location IS
    'Where exactly, once the driver is there — "Clubhouse", "Block C, second gate" (V93). Never geocoded and never part of the route: being at the right gate matters, and the last fifty metres is a phone call. Printed large on the job card''s delivery sheet.';

COMMENT ON COLUMN meal_plans.travel_minutes IS
    'How long the temple allows for this drive (V93). Prefilled from Google once there is an address and a time the guests eat, and editable by anyone who knows the road better than a traffic model does. This is the figure the job card prints — the temple''s own, never a live one.';

COMMENT ON COLUMN meal_plans.travel_minutes_source IS
    'ESTIMATED (Google''s figure, untouched — refreshed when the job card is printed) or MANUAL (a person set it — left alone, and printed as they set it). Without this, the refresh Rajeev asked for would silently overrule the override he asked for in the same breath.';
