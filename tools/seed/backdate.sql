-- =====================================================================
-- Back-dating — makes a month of seeded operations read as history.
--
-- The second and last piece of SQL in tools/seed/, and the one the brief explicitly allows:
-- "Direct SQL only where the app has no path (e.g. back-dating created_at)."
--
--   docker exec -i kms-postgres psql -U kms_migration -d kms_seed \
--     -v tenant=f935450b-1b7c-4b2c-a7e3-73e40c7e31e3 -f - < tools/seed/backdate.sql
--
-- ---------------------------------------------------------------------
-- Why it is needed at all
--
-- Everything the seeding scripts make is made *now*, because that is what happens when you call
-- an API. So a temple that is supposed to have been running since 29 August has an order book
-- where every order was raised this afternoon. Three things follow, and each of them is visible
-- on a screen Rajeev will open:
--
--   * Nothing is ever late. `POST /api/v1/purchase-orders` refuses an order needed before it was
--     raised (KMS-400014), so phase 06 has to clamp every needed-by date to tomorrow. Until the
--     orders move back, "arrived after the date it was needed" cannot happen at all.
--   * Every invoice is CURRENT. Nothing ages, so the payables screen has no 30-day or 60-day
--     bucket and the aging column is the same word all the way down.
--   * The audit trail says the whole month happened in one afternoon.
--
-- The application has no path for any of this, and it should not have one: back-dating a
-- purchase order is not something a temple does. That is exactly why it is here, in one file,
-- rather than spread through the phases.
--
-- ---------------------------------------------------------------------
-- What it does NOT touch, and why that matters
--
-- **Stock movements keep their real timestamps.** They are the ledger the on-hand figure is
-- computed from and they are append-only; moving them would be rewriting the history of the
-- stock rather than dating it. The delivery *dates* on the receipt lines are already correct,
-- because `receivedDate` is a real field the receiving endpoint accepts — phase 07 sets it.
--
-- **Nothing about a meal moves.** Meals already carry their own plan date, which phase 05 set
-- from the window. There is nothing to correct.
--
-- So this file only moves the three things that record *when paperwork was raised* and have no
-- other way to be set: purchase orders, invoices and payments.
-- =====================================================================

\set ON_ERROR_STOP on

BEGIN;

SET LOCAL app.tenant_id = :'tenant';
SET LOCAL app.purging_tenant = 'on';   -- invoice_payments and po_events are append-only

DO $backdate$
DECLARE
    v_tenant  uuid := current_setting('app.tenant_id')::uuid;
    v_rows    bigint;
    v_total   bigint := 0;
    -- The window the simulation covers. Orders are spread across it rather than all landing on
    -- one day, so the aging buckets on the payables screen have something in each of them.
    v_from    date := DATE '2026-08-29';
    v_to      date := DATE '2026-09-26';
BEGIN
    IF (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) THEN
        RAISE EXCEPTION
            'Refusing to run as the superuser %. A superuser bypasses row-level security, so '
            'nothing would confine this to one temple. Run it as kms_migration.', current_user;
    END IF;

    -- ---------------------------------------------------------------
    -- Purchase orders. Spread evenly across the window by PO number, so the earliest order is
    -- the oldest. `needed_by` moves with the order and keeps its original gap, which is what
    -- makes a delivery recorded on its real day genuinely late or genuinely on time.
    -- ---------------------------------------------------------------
    WITH ordered AS (
        SELECT id, po_number, vendor_id, order_date, needed_by,
               row_number() OVER (ORDER BY po_number) - 1 AS n,
               count(*) OVER () AS total
        FROM purchase_orders
        WHERE tenant_id = v_tenant
    ), placed AS (
        SELECT o.id,
               -- row_number() returns bigint and date + bigint has no operator, so the step
               -- is cast to int before it is added to a date.
               (v_from + (((v_to - v_from) * o.n / GREATEST(o.total - 1, 1))::int)) AS new_order_date,
               -- Needed-by comes from the VENDOR'S OWN LEAD TIME, not from the gap the order
               -- happened to carry. Two reasons. The gap is meaningless: phase 06 had to clamp
               -- every needed-by to tomorrow, so it is an artefact of the clamp rather than of
               -- anything the temple decided. And preserving a gap is not stable — run this
               -- twice and the second run reads back the dates the first one wrote, which
               -- collapsed needed-by onto order date the first time it was tried.
               --
               -- A lead time is the real thing: the mandi delivers the same day, the hardware
               -- shop next day, the packaging supplier in three. Taking it from the vendor makes
               -- the dates true to the vendor AND identical however many times this runs.
               COALESCE((
                   SELECT max(vs.lead_time_days)
                   FROM vendor_supplies vs
                   WHERE vs.vendor_id = o.vendor_id AND vs.tenant_id = v_tenant
               ), 2) AS lead_days
        FROM ordered o
    )
    UPDATE purchase_orders po
    SET order_date = placed.new_order_date,
        needed_by  = placed.new_order_date + placed.lead_days,
        created_at = placed.new_order_date + TIME '09:15',
        sent_at    = CASE WHEN po.sent_at IS NOT NULL
                          THEN placed.new_order_date + TIME '09:40' END
    FROM placed
    WHERE po.id = placed.id AND po.tenant_id = v_tenant;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    v_total := v_total + v_rows;
    RAISE NOTICE 'Purchase orders moved into the window: %', v_rows;

    -- The order's own event log, so "sent" does not postdate the order it belongs to.
    UPDATE po_events e
    SET created_at = po.created_at + INTERVAL '25 minutes'
    FROM purchase_orders po
    WHERE e.po_id = po.id AND e.tenant_id = v_tenant
      AND e.created_at > po.created_at;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    v_total := v_total + v_rows;
    RAISE NOTICE 'Order events aligned: %', v_rows;

    -- ---------------------------------------------------------------
    -- Goods receipts. The line-level received_date is already right (phase 07 sets it through
    -- the API), so only the header's timestamp is corrected, to the earliest date on its lines.
    -- ---------------------------------------------------------------
    UPDATE goods_receipts r
    SET received_at = COALESCE((
            SELECT min(l.received_date)::timestamptz + TIME '07:30'
            FROM goods_receipt_lines l
            WHERE l.receipt_id = r.id AND l.received_date IS NOT NULL
        ), r.received_at)
    WHERE r.tenant_id = v_tenant;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    v_total := v_total + v_rows;
    RAISE NOTICE 'Goods receipts aligned to their delivery dates: %', v_rows;

    -- ---------------------------------------------------------------
    -- Invoices. A bill follows its delivery rather than the calendar, so each one is dated two
    -- days after the last delivery it covers, and falls due 21 days after that. This is what
    -- puts real spread into the payables aging buckets.
    -- ---------------------------------------------------------------
    UPDATE vendor_invoices i
    SET invoice_date = billed.last_delivery + 2,
        due_date     = billed.last_delivery + 23,
        created_at   = (billed.last_delivery + 2)::timestamptz + TIME '16:20'
    FROM (
        SELECT d.invoice_id, max(l.received_date) AS last_delivery
        FROM vendor_invoice_deliveries d
        JOIN goods_receipt_lines l ON l.receipt_id = d.receipt_id
        WHERE d.tenant_id = v_tenant AND l.received_date IS NOT NULL
        GROUP BY d.invoice_id
    ) billed
    WHERE i.id = billed.invoice_id AND i.tenant_id = v_tenant;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    v_total := v_total + v_rows;
    RAISE NOTICE 'Invoices dated from their deliveries: %', v_rows;

    -- ---------------------------------------------------------------
    -- Payments. A payment cannot predate its bill. Each one lands a few days after, and the
    -- append-only trigger is why the purge flag is set at the top of this file.
    -- ---------------------------------------------------------------
    UPDATE invoice_payments p
    SET paid_on    = i.invoice_date + 6,
        created_at = (i.invoice_date + 6)::timestamptz + TIME '11:05'
    FROM vendor_invoices i
    WHERE p.invoice_id = i.id AND p.tenant_id = v_tenant;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    v_total := v_total + v_rows;
    RAISE NOTICE 'Payments dated after their invoices: %', v_rows;

    -- ---------------------------------------------------------------
    -- The checks. Each one is a thing that would be wrong on a screen, so each is asserted
    -- rather than hoped for — a back-dating script that produced a payment before its invoice
    -- would be worse than not running at all.
    -- ---------------------------------------------------------------
    PERFORM 1 FROM purchase_orders
    WHERE tenant_id = v_tenant AND needed_by < order_date LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'An order is needed before it was raised. Rolled back.';
    END IF;

    PERFORM 1 FROM invoice_payments p
    JOIN vendor_invoices i ON i.id = p.invoice_id
    WHERE p.tenant_id = v_tenant AND p.paid_on < i.invoice_date LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'A payment is dated before the invoice it pays. Rolled back.';
    END IF;

    PERFORM 1 FROM vendor_invoices i
    WHERE i.tenant_id = v_tenant AND i.due_date IS NOT NULL AND i.due_date < i.invoice_date
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'An invoice falls due before it was raised. Rolled back.';
    END IF;

    PERFORM 1 FROM purchase_orders
    WHERE tenant_id = v_tenant AND (order_date < v_from OR order_date > v_to) LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'An order landed outside the simulation window. Rolled back.';
    END IF;

    RAISE NOTICE 'Back-dated % row(s). Window % to %.', v_total, v_from, v_to;
END;
$backdate$;

COMMIT;

-- What it looks like afterwards.
--
-- A session-level SET, not SET LOCAL: the one inside the transaction went out with the COMMIT,
-- and without it row-level security hides every row from these queries — they came back empty
-- the first time, which looked like the script had done nothing.
SET app.tenant_id = :'tenant';

\pset border 2
SELECT po_number, order_date, needed_by, status,
       (SELECT min(l.received_date)
        FROM goods_receipts r JOIN goods_receipt_lines l ON l.receipt_id = r.id
        WHERE r.po_id = purchase_orders.id) AS first_delivery,
       CASE WHEN (SELECT max(l.received_date)
                  FROM goods_receipts r JOIN goods_receipt_lines l ON l.receipt_id = r.id
                  WHERE r.po_id = purchase_orders.id) > needed_by
            THEN 'late' ELSE '' END AS late
FROM purchase_orders
WHERE tenant_id = :'tenant'
ORDER BY po_number;

SELECT invoice_number, invoice_date, due_date, status,
       (SELECT min(paid_on) FROM invoice_payments p WHERE p.invoice_id = vendor_invoices.id) AS paid_on
FROM vendor_invoices
WHERE tenant_id = :'tenant'
ORDER BY invoice_number;
