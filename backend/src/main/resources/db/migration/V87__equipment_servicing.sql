-- =====================================================================
-- V87 — Equipment servicing, and the record of it (E3-S4, E3-S10)
--
-- V16 built the register: a thing, in a state, with an append-only trail
-- of how that state changed. It deliberately stopped there, because
-- E3-S4 assumed preventive maintenance was Phase 2. Rajeev overruled that
-- assumption on 2026-09-04 — "there might not be a phase two any time
-- soon and we dont want them to wait for it forever" — and the locked
-- requirement it sat under already asks for equipment to be tracked by
-- "condition, location, and service status" (REQUIREMENTS.md §2). So what
-- changes here is the story's assumption, not the requirement.
--
-- Nothing in V16 is rewritten. The condition trail keeps its own job: a
-- service is a different event from a change of condition, and a wet
-- grinder can be serviced every six months for five years without its
-- condition ever moving off GOOD.
--
-- ---------------------------------------------------------------------
-- 1. A service is an event, not a date field
--
-- equipment_services is a row per visit: when, by whom, what was done,
-- what it cost, and who wrote it down. "Last serviced" is then READ from
-- the newest row and is never typed.
--
-- An editable last_serviced_on column on equipment_items was the obvious
-- alternative and is rejected for the reason the stock ledger and the
-- condition trail were: the moment somebody types over it the previous
-- service has never happened, and a register whose history can be
-- overwritten is a worse record than a notebook. Hence make_append_only,
-- exactly as equipment_state_changes has had since V16.
--
-- ---------------------------------------------------------------------
-- 2. The interval is a number and a unit, held in days
--
-- "Every six months" and "every ninety days" are both things a real
-- service contract says, and a months-only column forces the second into
-- a lie. So the interval is stored as a count of DAYS, and the unit the
-- person actually chose is stored beside it so the form can show their
-- own words back:
--
--     every 6 months  ->  service_interval_days 180, unit MONTHS
--     every 90 days   ->  service_interval_days  90, unit DAYS
--
-- A month is 30 days and a year is 365. That is the arithmetic a service
-- contract means and not the arithmetic a calendar means, and it is what
-- makes the count recoverable by division — 180/30 is the six the person
-- typed. Calendar months would make 180 days ambiguous and the round trip
-- lossy, which is the whole reason the pair is stored rather than a
-- single interval type.
--
-- Servicing by running hours was considered and is NOT built: no temple
-- artifact in this repository records running hours, and a field nobody
-- fills is worse than an absent one. If a temple asks, it is a second
-- interval kind beside this one, not a rewrite of it.
--
-- ---------------------------------------------------------------------
-- 3. There is no next_service_date column, and there will not be
--
-- The next date is newest service + interval, or — where nothing has ever
-- been serviced — acquisition_date + interval, and where there is neither
-- it is not scheduled at all. All three are worked out in code at read
-- time (EquipmentService).
--
-- A stored column was rejected: it goes stale the moment an interval
-- changes or a service is recorded out of order, and keeping it true
-- would need a backfill nobody would remember to run. The derivation is
-- three lines of arithmetic over columns that are already here; caching
-- it would buy nothing and could be wrong.
--
-- ---------------------------------------------------------------------
-- 4. The service company is its own small list
--
-- A temple with one annual maintenance contract covering six machines
-- types the phone number once. Reusing `vendors` was considered and
-- rejected: a vendor carries purchase orders, payment terms, delivery
-- performance and a contract horizon, none of which mean anything for an
-- engineer who comes to fix a boiler, and an is_service_provider flag on
-- that table would leave half its columns permanently blank for these
-- rows.
--
-- This is a STATED ASSUMPTION, not a fact from the temple: nobody has
-- confirmed whether the firms that service the equipment overlap with the
-- firms that sell the groceries. If they turn out to be the same people
-- the two lists reconcile later, which is a smaller mistake than bolting
-- servicing onto the purchasing machinery now (E3-S10 D7).
-- =====================================================================


-- ---------------------------------------------------------------------
-- The people who come and fix things.
-- ---------------------------------------------------------------------
CREATE TABLE service_providers (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    name        TEXT        NOT NULL,

    -- All three optional. A temple may know only "the man from the mixer
    -- shop" and a phone number, and refusing the row until it has an
    -- email address would mean the phone number is not written down
    -- anywhere at all.
    phone       TEXT,
    email       TEXT,
    note        TEXT,

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT service_providers_name_present CHECK (length(name) > 0)
);

COMMENT ON TABLE service_providers IS
    'Firms and people who service temple equipment (E3-S10). Deliberately not vendors: a purchase order, payment terms and delivery scoring mean nothing for an engineer who fixes a boiler.';

-- Deliberately NOT unique on the name. Two rows called "Sharma Engineering"
-- are a nuisance the temple can tidy up; refusing the second one would need a
-- permanent error code for a rule E3-S10 does not ask for, and a list this
-- small is not where a temple needs protecting from itself. The index is here
-- for the ordering the list does on every read.
CREATE INDEX service_providers_name_per_tenant
    ON service_providers (tenant_id, lower(name));

SELECT enable_tenant_rls('service_providers');


-- ---------------------------------------------------------------------
-- What the register now carries about each machine.
-- ---------------------------------------------------------------------
ALTER TABLE equipment_items
    -- The interval, in days, whatever unit it was said in. NULL where the
    -- temple has not decided how often this thing needs looking at — which
    -- is the honest state for a trestle table, and the state every existing
    -- row starts in.
    ADD COLUMN service_interval_days INTEGER,

    -- The unit the person chose, kept so the form shows their own words
    -- back rather than "every 180 days" for something they entered as six
    -- months. Meaningless on its own: it is only ever read alongside the
    -- day count above.
    ADD COLUMN service_interval_unit TEXT,

    -- Optional, because furniture has none, and unique where present,
    -- because two rows claiming the same serial are the same machine
    -- entered twice (D9). Refused to the user as KMS-4015.
    ADD COLUMN serial_number         TEXT,

    -- Cost and warranty ride with the PURCHASE, beside acquisition_date,
    -- not with any one service (D8). Both optional: the temple will not
    -- know what a donated table cost, and furniture has no warranty. A
    -- service's own cost is a column on the service row, because that is
    -- a different fact each time.
    ADD COLUMN purchase_cost_inr     NUMERIC(12, 2),
    ADD COLUMN warranty_expiry       DATE,

    -- Who normally services this one. RESTRICT rather than SET NULL, for
    -- the reason kitchens.in_charge_user_id gives (V76): removing a
    -- provider should have to reckon with the machines that name it
    -- rather than silently forgetting which firm holds the contract.
    ADD COLUMN service_provider_id   UUID REFERENCES service_providers(id) ON DELETE RESTRICT,

    ADD CONSTRAINT equipment_service_interval_unit_valid CHECK (
        service_interval_unit IS NULL
            OR service_interval_unit IN ('DAYS', 'WEEKS', 'MONTHS', 'YEARS')),

    -- The two halves of the interval travel together or not at all. A day
    -- count with no unit cannot be shown back in the words it was entered
    -- in, and a unit with no count is not an interval.
    ADD CONSTRAINT equipment_service_interval_paired CHECK (
        (service_interval_days IS NULL) = (service_interval_unit IS NULL)),

    ADD CONSTRAINT equipment_service_interval_positive CHECK (
        service_interval_days IS NULL OR service_interval_days > 0),

    ADD CONSTRAINT equipment_purchase_cost_not_negative CHECK (
        purchase_cost_inr IS NULL OR purchase_cost_inr >= 0);

COMMENT ON COLUMN equipment_items.service_interval_days IS
    'How often this needs servicing, in days, whatever unit it was entered in. NULL where nobody has said — such a machine reads NOT_SCHEDULED and appears in no warning count (E3-S10 D3).';
COMMENT ON COLUMN equipment_items.service_interval_unit IS
    'The unit the person chose: DAYS, WEEKS, MONTHS or YEARS. Months are 30 days and years 365, so the count they typed is service_interval_days divided by that. Display only.';
COMMENT ON COLUMN equipment_items.serial_number IS
    'Manufacturer serial, where the thing has one. Unique per temple when present — two rows with one serial are one machine entered twice (E3-S10 D9).';
COMMENT ON COLUMN equipment_items.purchase_cost_inr IS
    'What the temple paid, in rupees. NULL for a donation, or where nobody recorded it. A service''s own cost lives on the service row.';

-- Partial, so any number of rows may leave the serial blank — furniture,
-- cauldrons, ladles — while two grinders may not claim one serial.
CREATE UNIQUE INDEX equipment_serial_per_tenant
    ON equipment_items (tenant_id, serial_number)
    WHERE serial_number IS NOT NULL;

CREATE INDEX equipment_tenant_provider
    ON equipment_items (tenant_id, service_provider_id)
    WHERE service_provider_id IS NOT NULL;


-- ---------------------------------------------------------------------
-- Every visit that ever happened.
-- ---------------------------------------------------------------------
CREATE TABLE equipment_services (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    equipment_id        UUID        NOT NULL REFERENCES equipment_items(id) ON DELETE RESTRICT,

    -- The day the work was done, which is not the day it was written down.
    -- created_at below carries the second. A date in the future is refused
    -- in the application (KMS-4016) rather than here, because "future"
    -- means the temple's today and this table has no opinion about which
    -- timezone the temple is in.
    serviced_on         DATE        NOT NULL,

    -- Who did the work. Nullable: the temple's own fitter is not a firm,
    -- and a visit somebody has already forgotten the name of is still a
    -- visit worth recording.
    service_provider_id UUID        REFERENCES service_providers(id) ON DELETE RESTRICT,

    work_done           TEXT,
    cost_inr            NUMERIC(12, 2),

    -- Who wrote it down. NOT NULL, and the reason the row is worth
    -- anything six months later: the same shape equipment_state_changes
    -- has had since V16.
    actor_user_id       UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT equipment_services_cost_not_negative CHECK (
        cost_inr IS NULL OR cost_inr >= 0)
);

COMMENT ON TABLE equipment_services IS
    'Append-only record of every service a piece of equipment has had (E3-S10). "Last serviced" is the newest row here and is never typed; the next service date is derived from it in code and never stored.';

-- The one access pattern: this machine's services, newest first — which is
-- both the history panel and the single row the next-service derivation
-- needs.
CREATE INDEX equipment_services_equipment
    ON equipment_services (tenant_id, equipment_id, serviced_on DESC, created_at DESC);

SELECT enable_tenant_rls('equipment_services');
SELECT make_append_only('equipment_services');


-- ---------------------------------------------------------------------
-- How much notice the temple wants before a machine falls due.
-- ---------------------------------------------------------------------
--
-- The third warning horizon, joining the two V85 moved out of constants
-- and into this table. Same bounds as both, 1 to 365, and for the same
-- reasons V85 set out at length: below 1 it cannot warn in advance, and
-- above a year it badges everything from the day it is entered.
--
-- Thirty days rather than the seven the stock screens use. Booking an
-- engineer is closer to renegotiating a supplier agreement than to
-- cooking a sack of flour before it turns — the temple has to find the
-- firm, agree a date, and have somebody there when they come.
--
-- Red on the morning a service falls due is a fire alarm. The amber state
-- this horizon defines is the one that does the actual work of the
-- feature: booking the engineer while there is still time (D5).
ALTER TABLE tenant_settings
    ADD COLUMN equipment_service_warning_days INTEGER NOT NULL DEFAULT 30,

    ADD CONSTRAINT tenant_settings_equipment_service_warning_days_sane
        CHECK (equipment_service_warning_days BETWEEN 1 AND 365);

COMMENT ON COLUMN tenant_settings.equipment_service_warning_days IS
    'How many days ahead a machine approaching its next service is badged amber. Warns only — nothing filters or decides on it (E3-S10 D5).';

-- ---------------------------------------------------------------------
-- Every existing temple, written explicitly.
--
-- The column default above is not the backfill, for the two reasons V85
-- gave. A temple that has never opened the settings screen has no
-- tenant_settings row at all — this table has been sparse since V36 — so
-- a column default reaches nothing for it. And stating the value row by
-- row is what makes this migration readable a year from now as the moment
-- the servicing horizon became thirty days for everybody.
--
-- Per tenant, because this table carries the standard RLS policy and
-- migrations run unprivileged: a single cross-tenant UPDATE would match
-- no rows and report success. Same shape as V85 and V58.
--
-- The INSERT is what does the work here and the UPDATE arm is what makes
-- it safe to re-read: a temple that already has a settings row gets the
-- new column set, and one that has none gets a row carrying the two V85
-- horizons at their own defaults alongside it.
-- ---------------------------------------------------------------------
DO $$
DECLARE tenant_row RECORD;
BEGIN
    FOR tenant_row IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', tenant_row.id::text, true);

        INSERT INTO tenant_settings (tenant_id, equipment_service_warning_days)
        VALUES (tenant_row.id, 30)
        ON CONFLICT (tenant_id) DO UPDATE
            SET equipment_service_warning_days = 30,
                updated_at                     = now();
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;
