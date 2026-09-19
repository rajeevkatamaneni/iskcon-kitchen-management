-- LOCAL DATABASE ONLY (docker kms-postgres, db kms). Never run against staging or production.
-- The local API applied an early draft of V144 at 2026-09-19 01:50 (DevTools hot reload picked up
-- T-248's migration mid-edit). The finished file's checksum differs, so Flyway refuses to boot.
-- This removes what the draft created and forgets that it ran, so the next boot applies the
-- final V144. Run: docker exec -i kms-postgres psql -U kms -d kms -v ON_ERROR_STOP=1 < docs/work/local-v144-reset.sql

-- Before: what is being dropped (expected: 0 rows in every table but ingredient_aliases, which
-- holds 5 rows backfilled from ingredients.aliases and re-created by the final V144).
SELECT version, checksum, installed_on FROM flyway_schema_history WHERE version = '144';
SELECT tenant_id, ingredient_id, alias, normalised_alias FROM ingredient_aliases;
SELECT 'ingredient_pack_sizes' AS t, count(*) FROM ingredient_pack_sizes
UNION ALL SELECT 'ingredient_market_rate_history', count(*) FROM ingredient_market_rate_history
UNION ALL SELECT 'vendor_invoice_lines', count(*) FROM vendor_invoice_lines
UNION ALL SELECT 'vendor_invoice_deliveries', count(*) FROM vendor_invoice_deliveries
UNION ALL SELECT 'vendor_price_history', count(*) FROM vendor_price_history
UNION ALL SELECT 'attachments', count(*) FROM attachments
UNION ALL SELECT 'ingredient_aliases', count(*) FROM ingredient_aliases;

BEGIN;
DROP TABLE IF EXISTS attachments, vendor_price_history, vendor_invoice_deliveries,
    vendor_invoice_lines, ingredient_market_rate_history, ingredient_aliases CASCADE;
ALTER TABLE vendor_supplies DROP COLUMN IF EXISTS pack_size_id, DROP COLUMN IF EXISTS price_per_pack;
DROP TABLE IF EXISTS ingredient_pack_sizes CASCADE;
DROP FUNCTION IF EXISTS ingredient_pack_size_same_family() CASCADE;
ALTER TABLE ingredients DROP COLUMN IF EXISTS market_rate, DROP COLUMN IF EXISTS market_rate_on,
    DROP COLUMN IF EXISTS market_rate_source;
ALTER TABLE vendor_invoices DROP COLUMN IF EXISTS sub_total, DROP COLUMN IF EXISTS gst_amount,
    DROP COLUMN IF EXISTS other_charges, DROP COLUMN IF EXISTS other_charges_note,
    DROP COLUMN IF EXISTS discount, DROP COLUMN IF EXISTS grand_total;
ALTER TABLE invoice_payments DROP COLUMN IF EXISTS received_by_name;
ALTER TABLE recipe_ingredients DROP COLUMN IF EXISTS preparation_note;
DELETE FROM flyway_schema_history WHERE version = '144';
COMMIT;

-- After: then touch backend/src/main/java/org/iskcon/kms/KmsApplication.java (or restart
-- tools/local-backend.sh) so the API boots and applies the final V144.
