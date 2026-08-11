-- =====================================================================
-- V31 — WhatsApp PO delivery (E5-S7)
--
-- Sending a purchase order to its vendor on WhatsApp goes through the E1-S10
-- notification service. The PO's activity trail records the send as a
-- WHATSAPP_SENT event linked to the notification, so when the provider's
-- delivery webhook updates that notification's status, the outcome can be
-- reflected back onto the PO trail (a WHATSAPP_DELIVERED / WHATSAPP_FAILED
-- event) and, on failure, the vendor flagged for a phone recheck
-- (vendors.whatsapp_reachable, from V24).
-- =====================================================================

ALTER TABLE po_events
    ADD COLUMN notification_id UUID REFERENCES notifications(id) ON DELETE SET NULL;

-- The listener that turns a delivery-status change into a trail event finds the
-- originating send by its notification id.
CREATE INDEX po_events_notification ON po_events (tenant_id, notification_id)
    WHERE notification_id IS NOT NULL;
