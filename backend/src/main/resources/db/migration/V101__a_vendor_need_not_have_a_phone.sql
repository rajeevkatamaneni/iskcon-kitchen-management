-- =====================================================================
-- V101 — A vendor need not have a phone number (T-025, D-2)
--
-- The temple walks into a hardware shop, buys four brooms, pays at the
-- counter and files the bill. That shop is a vendor: the purchase order,
-- the invoice, the payment and the vendor's share of the month's spend
-- all want a real `vendors` row to hang off, because
-- `purchase_orders.vendor_id` is NOT NULL (V25) and everything downstream
-- of it is keyed on that id. A PO carrying only a typed-in store name
-- would silently lose the ability to be invoiced or paid, and "Reliance
-- Fresh" typed three ways would become three suppliers that never merge.
--
-- So the vendor record is the right home. The only thing standing in the
-- way of creating one at the moment of need was this column.
--
-- ---------------------------------------------------------------------
-- Why the column was NOT NULL, and why that reason has run out
--
-- V24:18 made it NOT NULL, and V24's own header says why in one line:
-- "The phone is the WhatsApp destination for purchase orders (E5-S7)."
-- That is the whole of the justification. The number was not required
-- because a supplier must be contactable in principle; it was required
-- because one specific feature — sending the order on WhatsApp — has
-- nowhere to send to without it.
--
-- That reason simply does not apply to a shop somebody walks into. There
-- is no order to send; the order is a sheet carried in a hand. Requiring
-- a number for it means inventing one, and an invented number is worse
-- than an absent one: it is indistinguishable from a real one, so the
-- send does not refuse, it delivers a purchase order to a stranger.
--
-- The refusal therefore moves to where the reason lives. The send path
-- (PurchaseOrderDeliveryService) now checks for a number BEFORE it does
-- anything, and refuses with KMS-400130 — which says the vendor has no
-- number and that the order can be downloaded and handed over instead.
--
-- ---------------------------------------------------------------------
-- The CHECK is REPLACED, not dropped
--
-- Relaxing "a number is required" must not become "any text is a number".
-- Those are different rules, and only the first one has run out.
--
-- Note that SQL would have given us the null case for nothing: a CHECK
-- whose expression evaluates to NULL is satisfied, so `phone ~ '...'`
-- already passes for a null phone and dropping NOT NULL alone would have
-- worked. The constraint is rewritten anyway, spelling the null branch
-- out, because the person who reads `\d vendors` in two years should see
-- the rule the table actually keeps — "null, or E.164" — rather than have
-- to recall a three-valued-logic subtlety to work out that a null is
-- allowed here. This is a readability change on top of a real one; the
-- behaviour of the two forms is identical.
--
-- ---------------------------------------------------------------------
-- What this is NOT
--
-- `vendors.whatsapp_reachable` (V24:27) is untouched and unrelated. It is
-- a stored flag, cleared when a send FAILS, prompting somebody to recheck
-- a number that exists and did not work. It says nothing about whether a
-- number is there at all, and a phoneless vendor reads `true` on it like
-- every other new row. The two must not be conflated: one is "we tried
-- and it bounced", the other is "there is nothing to try".
--
-- ---------------------------------------------------------------------
-- RLS, and why there is no per-tenant loop here
--
-- `vendors` carries enable_tenant_rls() (V24:42) and the migration role is
-- unprivileged, so any SELECT or UPDATE in this file would run with
-- app.tenant_id unset and see no rows at all (the policy fails closed via
-- NULLIF). Migrations that touch rows in this project therefore adopt each
-- tenant in turn — V97 and V98 both do.
--
-- This one touches no rows. Every existing vendor has a phone (it was NOT
-- NULL until this statement) and so already satisfies the relaxed check;
-- there is nothing to seed and nothing to backfill. DROP NOT NULL, DROP
-- CONSTRAINT and ADD CONSTRAINT are all DDL and run as the table owner,
-- which is not subject to the policy. The absence of a tenant loop is a
-- decision, not an oversight.
--
-- ADD CONSTRAINT does validate the existing rows, and does so as the owner
-- — it is not a policy-filtered read, so it genuinely checks every
-- tenant's vendors rather than silently checking none of them.
-- =====================================================================

ALTER TABLE vendors
    ALTER COLUMN phone DROP NOT NULL;

ALTER TABLE vendors
    DROP CONSTRAINT vendors_phone_e164;

ALTER TABLE vendors
    ADD CONSTRAINT vendors_phone_e164
    CHECK (phone IS NULL OR phone ~ '^\+[1-9][0-9]{7,14}$');

COMMENT ON COLUMN vendors.phone IS
    'The WhatsApp destination a purchase order is sent to, or NULL for a vendor nobody messages — a shop somebody walks into and pays at the counter (T-025). Anything present is still E.164. A NULL here refuses the WhatsApp send with KMS-400130; it is not whatsapp_reachable, which is about a number that exists and bounced.';
