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
-- So this file moves the things that record *when paperwork was raised* and have no other way to
-- be set: purchase orders, their deliveries, invoices and payments.
--
-- ---------------------------------------------------------------------
-- A guard only checks the pair you were thinking about
--
-- Worth reading before adding another date rule here, because the next person will make the same
-- assumption I did. The first version of this script had three guards — a needed-by before its
-- order, a payment before its invoice, a due date before its invoice — and all three passed. The
-- pair that actually broke was the one I had not thought to check, because I believed it was
-- already correct: **the delivery against its order.** Phase 07 sets `received_date` through the
-- API, so I reasoned it was right, and it was — right relative to *today*. The moment this script
-- moved the orders across a month, the two became unrelated and five orders showed goods arriving
-- before the order existed, one of them nine days before.
--
-- The lesson is not "add more guards". It is that **the dates this script does not move are as
-- much its responsibility as the ones it does**, because moving one end of a relationship breaks
-- it just as thoroughly as mis-setting both. Anything dated that hangs off a purchase order has
-- to move with it, or be asserted against it.
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
    -- Paperwork stops at today. The window runs to 26 September so that meals can be *planned*
    -- ahead, but an order that has been delivered, billed and paid has already happened, and
    -- spreading the orders evenly to the end of the window dated three of them into the future —
    -- an invoice raised on 28 September and a payment made on 4 October, with today the 20th.
    -- Orders therefore spread only as far as a few days before today, leaving room for their
    -- delivery and their bill to land before today as well.
    v_today   date := CURRENT_DATE;
    v_spread  date := LEAST(v_to, v_today - 3);
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
               (v_from + (((v_spread - v_from) * o.n / GREATEST(o.total - 1, 1))::int)) AS new_order_date,
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
    -- Goods receipts, which have to MOVE WITH THEIR ORDER.
    --
    -- The first version of this script left the delivery dates alone, on the reasoning that
    -- phase 07 sets `received_date` through the API and they were therefore already right. They
    -- were right relative to *today*, and the orders had just been spread across a month — so the
    -- two became unrelated, and the result was **five orders whose goods arrived before the order
    -- was raised**: PO-2026-0014 ordered on the 24th and delivered on the 15th. At the other end
    -- PO-2026-0001 waited twenty days. Nothing caught it, because the guards below checked that a
    -- needed-by was not before its order date and never checked the delivery.
    --
    -- So each receipt is re-dated from its own order: the first lands a day before it was needed,
    -- or four days after if this is one of the orders meant to arrive late, and a second receipt
    -- follows three days behind the first. Which orders are late is taken from the PO number the
    -- same way phase 07 chooses the delivery — every sixth one from the third — so the script and
    -- the seeding agree instead of each having an opinion.
    -- ---------------------------------------------------------------
    WITH numbered AS (
        SELECT r.id,
               r.po_id,
               po.order_date,
               po.needed_by,
               (regexp_replace(po.po_number, '^.*-', ''))::int AS po_seq,
               row_number() OVER (PARTITION BY r.po_id ORDER BY r.received_at) - 1 AS part
        FROM goods_receipts r
        JOIN purchase_orders po ON po.id = r.po_id
        WHERE r.tenant_id = v_tenant
    ), dated AS (
        SELECT id,
               GREATEST(
                   order_date + 1,
                   CASE WHEN (po_seq - 1) % 6 = 2      -- the "late" case in phase 07's rotation
                        THEN needed_by + 4
                        ELSE needed_by - 1
                   END
               ) + (part * 3)::int AS arrived   -- row_number() is bigint; date + bigint has no operator
        FROM numbered
    )
    UPDATE goods_receipt_lines l
    SET received_date = LEAST(dated.arrived, v_today)
    FROM dated
    WHERE l.receipt_id = dated.id AND l.tenant_id = v_tenant;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    v_total := v_total + v_rows;
    RAISE NOTICE 'Delivery dates moved to follow their orders: %', v_rows;

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
    -- days after the last delivery it covers. What it then falls due depends on THE VENDOR.
    --
    -- Every bill used to fall due 23 days after delivery, and a temple that pays everybody in
    -- twenty-three days is a temple nobody recognises. The mandi sells vegetables off a cart and
    -- wants cash; the packaging supplier delivers on a purchase order and gives thirty days. The
    -- terms below are the trade each vendor is in, and they line up with the lead times phase 03
    -- already gives them: a vendor who delivers same-day is a vendor you pay on the spot.
    --
    -- They are written out here, by name, rather than computed from the lead time by a formula,
    -- so that a reader can see what the temple's terms actually are and change one of them
    -- without working out a rule first. A vendor not named here gets 21 days.
    -- ---------------------------------------------------------------
    UPDATE vendor_invoices i
    SET invoice_date = LEAST(billed.last_delivery + 2, v_today),
        due_date     = LEAST(billed.last_delivery + 2, v_today) + COALESCE(terms.days, 21),
        created_at   = LEAST(billed.last_delivery + 2, v_today)::timestamptz + TIME '16:20'
    FROM (
        SELECT d.invoice_id, max(l.received_date) AS last_delivery
        FROM vendor_invoice_deliveries d
        JOIN goods_receipt_lines l ON l.receipt_id = d.receipt_id
        WHERE d.tenant_id = v_tenant AND l.received_date IS NOT NULL
        GROUP BY d.invoice_id
    ) billed
    LEFT JOIN vendor_invoices vi ON vi.id = billed.invoice_id
    LEFT JOIN vendors v ON v.id = vi.vendor_id
    LEFT JOIN (VALUES
        ('Kalasipalya Vegetable Mandi',    0),   -- a market cart: cash on the day
        ('Heritage Fresh Dairy',           7),   -- delivers at 5am daily, billed weekly
        ('Jayanagar Hardware Store',       7),   -- a walk-in counter with an account
        ('Sri Venkateshwara Rice Traders',15),   -- sacks on an account, settled fortnightly
        ('Sri Balaji Traders',            15),
        ('Mahalakshmi Stores',            15),
        ('Anand Masala Depot',            21),
        ('Ganesh Oil & Provisions',       21),
        ('Karnataka Provision Mart',      21),
        ('Vishwa Packaging & Supplies',   30)    -- a proper supplier, thirty days
    ) AS terms(vendor_name, days) ON terms.vendor_name = v.name
    WHERE i.id = billed.invoice_id AND i.tenant_id = v_tenant;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    v_total := v_total + v_rows;
    RAISE NOTICE 'Invoices dated from their deliveries, due on each vendor own terms: %', v_rows;

    -- ---------------------------------------------------------------
    -- Payments. A payment cannot predate its bill. Each one lands a few days after, and the
    -- append-only trigger is why the purge flag is set at the top of this file.
    -- ---------------------------------------------------------------
    UPDATE invoice_payments p
    SET paid_on    = LEAST(i.invoice_date + 6, v_today),
        created_at = LEAST(i.invoice_date + 6, v_today)::timestamptz + TIME '11:05'
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

    -- The one that was missing, and the one that actually fired in reality: goods cannot arrive
    -- before the order that asked for them.
    PERFORM 1 FROM goods_receipt_lines l
    JOIN goods_receipts r ON r.id = l.receipt_id
    JOIN purchase_orders po ON po.id = r.po_id
    WHERE l.tenant_id = v_tenant AND l.received_date IS NOT NULL
      AND l.received_date < po.order_date
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'A delivery is dated before the order that asked for it. Rolled back.';
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

    -- Nothing that has already happened may be dated after today. Meals are planned ahead on
    -- purpose and are not touched by this script; paperwork is not.
    PERFORM 1 FROM purchase_orders
    WHERE tenant_id = v_tenant AND order_date > v_today LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'An order is dated in the future. Rolled back.';
    END IF;

    PERFORM 1 FROM goods_receipt_lines
    WHERE tenant_id = v_tenant AND received_date > v_today LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'A delivery is dated in the future. Rolled back.';
    END IF;

    PERFORM 1 FROM vendor_invoices
    WHERE tenant_id = v_tenant AND invoice_date > v_today LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'An invoice is dated in the future. Rolled back.';
    END IF;

    PERFORM 1 FROM invoice_payments
    WHERE tenant_id = v_tenant AND paid_on > v_today LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'A payment is dated in the future. Rolled back.';
    END IF;

    RAISE NOTICE 'Back-dated % row(s). Window % to %, paperwork spread % to % (today %).',
        v_total, v_from, v_to, v_from, v_spread, v_today;
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

SELECT invoice_number, v.name AS vendor, invoice_date, due_date,
       (due_date - invoice_date) AS terms_days,
       CASE WHEN due_date < CURRENT_DATE THEN 'overdue' ELSE '' END AS overdue,
       status,
       (SELECT min(paid_on) FROM invoice_payments p WHERE p.invoice_id = i.id) AS paid_on
FROM vendor_invoices i
JOIN vendors v ON v.id = i.vendor_id
WHERE i.tenant_id = :'tenant'
ORDER BY invoice_date;
