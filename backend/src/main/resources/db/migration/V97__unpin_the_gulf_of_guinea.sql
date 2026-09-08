-- =====================================================================
-- V97 — Unpin the delivery events that were pinned to the Gulf of Guinea
--
-- T-044 (2026-09-06) fixed the cause. This clears the damage it had already
-- done, which does not clear itself.
--
-- ---------------------------------------------------------------------
-- What was written, and why it was believed
--
-- Editing a delivery event re-pinned it to 0,0. The composer rebuilt the
-- picked place as { placeId: <the real one>, latitude: 0, longitude: 0 } —
-- placeholder zeroes, because the client type never carried the true
-- coordinates — and the server's Event.isPlaced() was
--
--     latitude != null && longitude != null
--
-- which never consulted placeId and for which 0 is emphatically not null. So
-- the placeholder was stored, and then trusted for thirty days as a pin
-- somebody had chosen. 0°N 0°E is a point in the Atlantic about 600 km south
-- of Accra. No temple in India delivers there.
--
-- Any delivery event edited between 2026-09-05 and T-044 may be holding it.
--
-- ---------------------------------------------------------------------
-- Why the rows do not heal on their own
--
-- T-044 made fresh() use the corrected isPlaced(), so a poisoned row now
-- reads as STALE rather than being reused, and a stale row is re-resolved.
-- That is a real improvement and it is not enough: re-resolution falls back
-- to geocoding the address TEXT, and addresses a geocoder cannot find are
-- precisely why the place picker exists — a sub-premise, a landmark, half a
-- pincode. The self-healing therefore covers the easy rows and leaves the
-- hard ones, which are the ones the picker was built for. A driver follows
-- this pin; "mostly recovered" is not a state to leave it in.
--
-- ---------------------------------------------------------------------
-- Why the WHERE does not mention delivery_place_id, in either direction
--
-- FILTERING TO ROWS WITH ZEROES *AND NO PLACE ID* WAS PROPOSED AND REJECTED.
-- It would have matched nothing that matters: every poisoned row carries a
-- REAL place id, because the old composer sent a true placeId beside the
-- placeholder coordinates. That filter skips the entire population this
-- migration exists for. Nor would it have protected a genuine pick, since a
-- genuine pick has a place id too — the column does not separate the two
-- cases in either direction, so it is no basis for a decision.
--
-- What protects a genuine pick is LEAVING delivery_place_id ALONE. Cleared
-- coordinates beside a surviving place id is exactly the state T-044 built
-- the recovery for: isPlaced() reads false, the row is stale, and the server
-- re-resolves the pin FROM PLACES BY ID — one API call, and the pick comes
-- back losslessly, no geocoding of address text involved. So the requirement
-- "do not destroy a pick somebody made" is met by not touching a column,
-- rather than by filtering on one.
--
-- geocoded_at goes with the coordinates. It is the timestamp of the lookup
-- that produced them; without them it dates nothing, and left behind it would
-- keep asserting that this address was resolved recently.
--
-- NULL is the correct value here, not a corrected guess. It means "we do not
-- know where this is", which is true, and it makes the row ASK again instead
-- of asserting something false.
--
-- ---------------------------------------------------------------------
-- The single-zero case: DELIBERATELY LEFT ALONE
--
-- A row with one zero axis and one real coordinate — (0, 77.548110), or
-- (12.856230, 0) — is NOT touched. Three reasons, and the third is the one
-- that decides it:
--
--   1. It is not this defect. The defect's signature is both axes zero
--      together, written as a pair by one line of client code. A migration
--      repairing a known defect should match the rows it can account for;
--      widening the net is a guess about rows whose history it does not know.
--
--   2. 0 is a legal coordinate on each axis by itself — the equator, the
--      Greenwich meridian. It is only 0 AND 0 TOGETHER that is provably not a
--      delivery: a point in the ocean. We can prove the pair is wrong. We
--      cannot prove a single zero is.
--
--   3. Nulling one would change nothing about what the application does.
--      T-044's isCoordinate() already refuses zero on EITHER axis, so a
--      single-zero row is already unplaced and already stale: it is already
--      re-resolved on the next read, by place id where there is one and from
--      the address otherwise — the identical behaviour it would have if this
--      migration nulled it. The two options are indistinguishable to the
--      running system and differ only in whether a stored datum is destroyed
--      irreversibly. Given that, keep it: a half-zero pin is evidence a human
--      can look at later, and destroying evidence to no behavioural end is
--      the wrong trade.
--
-- If a single-zero row ever turns up in the wild it wants a person, not a
-- backfill — its provenance is unknown and it is not what T-044 diagnosed.
--
-- ---------------------------------------------------------------------
-- Per tenant, because migrations here are subject to RLS
--
-- meal_plans carries FORCE ROW LEVEL SECURITY (V22) and the migration role is
-- unprivileged, so a blanket UPDATE runs with app.tenant_id unset, the policy
-- fails closed via NULLIF, and it would clear NOTHING while reporting
-- success — the worst possible outcome, since the deploy would look done. So
-- this adopts each tenant in turn, as V48, V67, V88 and V94 do.
--
-- The count is raised as a NOTICE because it is the one number nobody can
-- recover afterwards: once the rows are repaired there is no "after" query
-- that says how many there were. Where the notice ends up depends on the
-- environment's client_min_messages and on what the caller does with client
-- notices, so this is a best effort and not a guarantee — but a best effort
-- at recording the size of a data repair costs one line and beats losing the
-- number entirely.
-- =====================================================================

DO $$
DECLARE
    t       RECORD;
    cleared INTEGER;
    total   INTEGER := 0;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t.id::text, true);

        UPDATE meal_plans
        SET delivery_latitude  = NULL,
            delivery_longitude = NULL,
            geocoded_at        = NULL
        WHERE tenant_id = t.id
          AND delivery_latitude  = 0
          AND delivery_longitude = 0;

        GET DIAGNOSTICS cleared = ROW_COUNT;
        total := total + cleared;
    END LOOP;

    PERFORM set_config('app.tenant_id', '', true);

    RAISE NOTICE 'V97: unpinned % delivery event(s) that were sitting at 0,0; '
        'each keeps its delivery_place_id and is re-resolved from Places on next read.', total;
END $$;
