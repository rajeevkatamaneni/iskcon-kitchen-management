-- =====================================================================
-- V85 — how much notice a temple wants (E5-S1 D2, E3-S1)
--
-- Two "warns you in advance" horizons existed as two constants:
--
--   InventoryItemService.DEFAULT_EXPIRY_WINDOW_DAYS = 7   a batch nearing its
--                                                         use-by date
--   VendorService.CONTRACT_WARNING_WINDOW_DAYS      = 7   an agreement with a
--                                                         supplier running out
--
-- The second borrowed the first's number, and V83 said in as many words why:
-- there was no evidence a contract wanted a different one, and two constants
-- that quietly disagree are worse than one that is merely arguable. That open
-- question is now answered, and the answer is that they do disagree. Seven days
-- is enough warning to cook a sack of flour before it turns. It is not enough to
-- renegotiate a commercial agreement, get a quote from anybody else, or take the
-- decision to a committee that meets monthly.
--
-- So both move here together, which is the resolution E5-S1 D2 wrote down: one
-- of them becoming configurable while the other stayed a constant would leave
-- exactly the mismatch the shared number was avoiding.
--
--   stock expiry     7 days   unchanged, for every temple that already has one
--   contract end    30 days   a month's notice to renegotiate
--
-- Bounds: 1 to 365, on both, enforced here and in bean validation.
--
--   Below 1 the setting cannot do its job. Zero warns on the morning the thing
--   has already expired or already ended, which is not advance notice, and a
--   negative horizon warns about dates in the past only, which is not a horizon.
--
--   Above 365 it cannot do its job either, for the opposite reason. A temple
--   stocks almost nothing with more than a year of shelf life, and a supplier
--   agreement is usually annual — so a horizon of a year flags every batch and
--   every contract from the day it is entered. A badge that is always on is not
--   read. One rule for both, because there is no case for two.
--
-- Neither number is read by any job, and nothing filters or decides on either.
-- They change which rows carry a warning badge, and nothing else.
-- =====================================================================

ALTER TABLE tenant_settings
    ADD COLUMN stock_expiry_warning_days INTEGER NOT NULL DEFAULT 7,
    ADD COLUMN contract_end_warning_days INTEGER NOT NULL DEFAULT 30,

    ADD CONSTRAINT tenant_settings_stock_expiry_warning_days_sane
        CHECK (stock_expiry_warning_days BETWEEN 1 AND 365),
    ADD CONSTRAINT tenant_settings_contract_end_warning_days_sane
        CHECK (contract_end_warning_days BETWEEN 1 AND 365);

COMMENT ON COLUMN tenant_settings.stock_expiry_warning_days IS
    'How many days ahead a batch nearing its use-by date is badged on the stock screens. Warns only (E3-S1).';
COMMENT ON COLUMN tenant_settings.contract_end_warning_days IS
    'How many days ahead a vendor whose agreement is running out is badged. Warns only — nothing filters on it (E5-S1).';

-- ---------------------------------------------------------------------
-- Every existing temple, written explicitly.
--
-- The column defaults above are not the backfill, for two separate reasons.
-- A temple that has never opened the settings screen has no tenant_settings row
-- at all — this table has been sparse since V36 — so a column default reaches
-- nothing for it. And stating the values row by row is what makes this
-- migration readable a year from now as the moment the contract horizon went
-- from 7 to 30 for everybody, rather than something inferred from a DDL default
-- that a later migration might change.
--
-- Per tenant, because this table carries the standard RLS policy and the
-- migration runs unprivileged: a single cross-tenant UPDATE would match no rows
-- and report success. Same shape as V58.
-- ---------------------------------------------------------------------
DO $$
DECLARE tenant_row RECORD;
BEGIN
    FOR tenant_row IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', tenant_row.id::text, true);

        INSERT INTO tenant_settings (tenant_id, stock_expiry_warning_days, contract_end_warning_days)
        VALUES (tenant_row.id, 7, 30)
        ON CONFLICT (tenant_id) DO UPDATE
            SET stock_expiry_warning_days = 7,
                contract_end_warning_days = 30,
                updated_at                = now();
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;
