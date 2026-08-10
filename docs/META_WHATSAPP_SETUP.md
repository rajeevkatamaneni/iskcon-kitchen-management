# WhatsApp (Meta Cloud API) — setup checklist

External-lead-time work for E1-S10. Tracked here, separate from the code, because it depends on Meta's review queues, not on us — and Meta approval is the long pole for anything that sends on WhatsApp.

**Status (2026-08-10):** not started — WhatsApp Business API access is not yet available, timeline unknown. The notification service is built and works today on the dev channel adapters; enabling WhatsApp for real is a matter of writing one adapter and completing the steps below. See "If WhatsApp stays blocked" for the fallback.

## Steps

- [ ] **Meta Business account + Business verification.** Create/verify the ISKCON temple business on Meta Business Manager (legal name, address, documents). This is the slow step — days to weeks.
- [ ] **WhatsApp Business Platform app** in Meta for Developers; add the WhatsApp product.
- [ ] **Register a phone number** for the WhatsApp Business number (a number not already on a personal/normal WhatsApp account) and complete its verification.
- [ ] **System user + permanent access token** with `whatsapp_business_messaging` scope (a non-expiring token for the backend to send with).
- [ ] **Utility message templates** submitted for approval, one per message we send:
  - [ ] `shift_reminder` — volunteer shift reminder
  - [ ] `po_delivery` — purchase-order delivery to a vendor
  - Match the parameter positions to `NotificationTemplate` in the code.
- [ ] **App secret** for webhook signature verification → set as `WHATSAPP_APP_SECRET` in the deployed environment (the code verifies `X-Hub-Signature-256` against it).
- [ ] **Webhook subscription** pointed at `POST /api/v1/public/webhooks/whatsapp`, subscribed to message-status events, verified with the app's verify token.

## Then, in code

- [ ] Write the real `ChannelAdapter` for `WHATSAPP` (calls the Meta Cloud API `messages` endpoint with the approved template + params; returns Meta's message id), replacing `DevChannelAdapters.WhatsApp`.
- [ ] Adapt Meta's status-webhook payload to the `{messageId, status}` the delivery service consumes (Meta's is nested under `entry[].changes[].value.statuses[]`).

## If WhatsApp stays blocked

The fallback cascade already covers this: with no WhatsApp adapter enabled, the cascade drops to SMS then email. Email needs no Meta approval and no India DLT registration, so an **email-first** pilot can ship without any of the above. SMS to Indian numbers needs DLT registration (a separate long pole, provider-independent). Decision on making email the primary channel is still open (see docs/CHANGELOG.md discussion) — the adapter design commits us to nothing until then.
