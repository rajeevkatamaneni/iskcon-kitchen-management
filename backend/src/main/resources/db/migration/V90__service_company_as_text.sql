-- =====================================================================
-- V90 — The service company is a text box (E3-S10 D7, reversed)
--
-- V87 built the service company as its own tenant-owned list:
-- service_providers, with a name, a phone, an email and a note, a small
-- CRUD behind MANAGE_EQUIPMENT_SERVICING, a foreign key from each machine
-- and another from each recorded visit, and a permanent error code for
-- refusing to delete a row something still pointed at.
--
-- Rajeev reversed that on 2026-09-04, having seen it working:
--
--     "can we make 'Service company' a free text box and Remove the Add a
--      Service Company button, the back end code and tables for it. I
--      think it is way too much for this little feature. A Text box
--      serves the purpose JUST FINE."
--
-- So it goes, and this migration is the whole of the database half.
--
-- ---------------------------------------------------------------------
-- What D7 argued, and why the argument lost
--
-- The case for the list was that a temple with one annual maintenance
-- contract covering six machines types the phone number once. That is
-- true, and it was never the whole cost. The list also bought a table, a
-- policy, four endpoints, a permission question about who may read the
-- temple's contacts, a delete rule with an error code behind it, a picker
-- with an add-without-leaving-the-screen flow on three screens, and a
-- second thing to keep tidy — the duplicate "Sharma Engineering" that
-- V87's own comment admitted the temple would have to clean up itself.
--
-- All of that to save re-typing a phone number for a handful of machines,
-- perhaps once a year each. Two text columns say the same fact and cost
-- nothing to maintain. The reversal is the right way round.
--
-- What is genuinely given up, stated plainly rather than glossed: change
-- the firm and you now change it on each machine that names it, and two
-- spellings of one company will not group. Neither is worth a table for a
-- register of dozens.
--
-- ---------------------------------------------------------------------
-- 1. The company rides on the machine, and on the visit
--
-- Two columns on equipment_items — who normally services this, and their
-- number — and one on equipment_services, because a recorded visit keeps
-- who came. That column is the reason a service row can still be read six
-- months later: the firm's name has to be ON the row, not reachable
-- through it, or dropping the list would rewrite the history to "the
-- grinder was serviced in March by nobody".
--
-- Both optional everywhere. A temple may know only "the man from the
-- mixer shop", and the temple's own fitter is not a firm at all.
--
-- No CHECK on either. The application caps the lengths (200 and 40, the
-- sizes the provider record used) and there is no rule here worth
-- refusing a row for — a blank company is an ordinary, honest state.
--
-- ---------------------------------------------------------------------
-- 2. The backfill runs before anything is dropped
--
-- A temple that has already named a company must not lose it. The two
-- UPDATEs below copy the name — and, for the machine, the phone — out of
-- service_providers and onto the rows that pointed at it, and only then
-- do the foreign keys and the table go.
--
-- Per tenant, with app.tenant_id set, because both tables carry the
-- standard RLS policy and migrations run unprivileged: a plain
-- cross-tenant UPDATE would match nothing and report success. Same shape
-- as V48, V67 and V87.
--
-- equipment_services is append-only, and this UPDATE is allowed on
-- purpose: since V49 that is a trigger which refuses kms_app and only
-- kms_app, and Flyway runs as the schema owner. The distinction is what
-- lets the schema maintain its own tables while the application still
-- cannot edit a service that happened. Nothing here changes what any
-- visit says — it copies a name that was already reachable from the row
-- into the row itself, so the history reads exactly as it did before.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. The new columns.
-- ---------------------------------------------------------------------
ALTER TABLE equipment_items
    ADD COLUMN service_company       TEXT,
    ADD COLUMN service_company_phone TEXT;

COMMENT ON COLUMN equipment_items.service_company IS
    'Who normally services this, as typed. Free text since V90 — the managed list V87 built was more machinery than the fact deserved (E3-S10 D7).';
COMMENT ON COLUMN equipment_items.service_company_phone IS
    'How to reach them, as typed. Beside the name rather than in a list, so changing the firm is one edit on the machine that named it.';

ALTER TABLE equipment_services
    ADD COLUMN service_company TEXT;

COMMENT ON COLUMN equipment_services.service_company IS
    'Who came, as typed at the time. On the row rather than reachable through it: a visit has to stay readable when the machine''s company later changes.';


-- ---------------------------------------------------------------------
-- 2. Carry across what temples have already named.
-- ---------------------------------------------------------------------
DO $$
DECLARE tenant_row RECORD;
BEGIN
    FOR tenant_row IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', tenant_row.id::text, true);

        UPDATE equipment_items e
        SET service_company       = p.name,
            service_company_phone = p.phone,
            updated_at            = now()
        FROM service_providers p
        WHERE p.id = e.service_provider_id;

        UPDATE equipment_services s
        SET service_company = p.name
        FROM service_providers p
        WHERE p.id = s.service_provider_id;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;


-- ---------------------------------------------------------------------
-- 3. And now the list itself.
--
-- Dropping the columns takes equipment_tenant_provider with them — an
-- index goes when any column it is built on does — so it is not named
-- here. The email and the note the provider record carried go with the
-- table: neither was ever shown on any screen, and inventing a place for
-- them on the machine would be keeping the parts of a decision that has
-- been reversed.
-- ---------------------------------------------------------------------
ALTER TABLE equipment_items    DROP COLUMN service_provider_id;
ALTER TABLE equipment_services DROP COLUMN service_provider_id;

DROP TABLE service_providers;
