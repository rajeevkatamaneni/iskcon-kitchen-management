-- =====================================================================
-- V39 — Donation webhook lookup escape (E7-S2)
--
-- A payment webhook arrives unauthenticated, before any tenant is known, and must
-- find the one PENDING donation its order belongs to. This is the same narrow
-- SELECT-only escape the notification webhook uses (V7): keyed on the generic
-- app.webhook_message_id session var, which the handler sets only to an order id
-- from a signature-verified payload. It exposes at most the one matching row and
-- grants no writes — the handler reads that row's tenant, establishes it as
-- context, and does the status UPDATE through ordinary tenant isolation.
-- =====================================================================

CREATE POLICY donation_webhook_lookup ON donations
    FOR SELECT
    USING (
        provider_order_id IS NOT NULL
        AND provider_order_id = NULLIF(current_setting('app.webhook_message_id', true), ''));
