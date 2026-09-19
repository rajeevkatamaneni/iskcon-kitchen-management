# Phase B item 1 — does Meta accept the temple's stored token for the PDF uploads?

Checked 2026-09-14 on staging, API revision `kms-staging-api-00168-zjc` (Phase B release 69da777).
Done through the app's own API as Temple Admin (`ikms.temple-admin.1`, ISKCON South Bengaluru),
signed in with a Firebase custom token. No Meta token or secret was read, printed or stored. No
WhatsApp message was sent. Nothing was saved or changed on staging.

## Result

- Token accepted by `POST /<APP_ID>/uploads` (template sample): **unknown, not tried.**
- Token accepted by `POST /<PHONE_NUMBER_ID>/media` (send-time PDF): **unknown, not tried.**
- **Stopped at step 1: the temple has no App ID stored.** Neither staging config nor anything the app
  exposes holds one.

## Step 1 — is an App ID configured?

`GET /api/v1/settings/whatsapp` as Temple Admin:

```
"connected": true,
"phoneNumberId": "1211371598734181",
"wabaId": "2154813661742453",
"appId": null,
"displayNumber": "Test Number (+1 555-646-5474)",
"verifiedAt": "2026-09-14T02:20:21.486354Z",
"templatesSubmittedAt": "2026-09-14T02:20:42.582271Z",
"templatesPending": {"changed": 1, "refused": 0, "accountChanged": false, "unchecked": 0}
```

Looked for it elsewhere, without touching a secret:

- Cloud Run env var names on `kms-staging-api` (names only): no Meta or WhatsApp variable. The App ID
  is per temple, in `tenant_settings.whatsapp_app_id`, and the API above reads it back as null.
- `infra/environment/*.tf` and `terraform.tfvars`: nothing Meta-related.
- Repo docs and memory: no App ID recorded.
- Cloud Run logs for the last day: no "sample" or "upload session" line, which fits. With no App ID,
  the code never attempts the upload.

**A candidate, not confirmed.** A past session's transcript (`d32ed1b7…`, the one where WhatsApp was
set up) has Meta developer console URLs of the form `developers.facebook.com/apps/1920408082251143/…`,
including `whatsapp-business/wa-dev-console/` and `settings/basic/`. The same transcript also mentions
this temple's WABA and phone number ids. That strongly suggests `1920408082251143` is the right App ID.
I did not enter it:

- the brief said not to guess;
- the transcript is not staging config or the database;
- reading the context around it was blocked.

Rajeev should confirm it.

## What Meta holds for po_delivery now (read only)

`GET /api/v1/settings/whatsapp/templates/meta-comparison`:

```
{"name": "po_delivery", "ourCategory": "UTILITY", "metaCategory": "UTILITY", "metaStatus": "APPROVED",
 "held": true, "bodyMatchesExactly": true, "ourHeaderFormat": "DOCUMENT", "metaHeaderFormat": null,
 "headerMatches": false}
```

So Meta holds po_delivery APPROVED, without the PDF header. That is the one "changed" template on the
button.

## Steps 2 and 3 — not done

Neither was run, because step 1 stopped the check. What they need, found while looking:

- **Reload** (`POST /api/v1/settings/whatsapp/templates/reload`) will:
  - upload the sample through `/<APP_ID>/uploads`;
  - on success, edit the APPROVED po_delivery to add the DOCUMENT header, which sends it back to
    Meta's review;
  - if Meta refuses the sample, store `SAMPLE_NOT_TAKEN` on po_delivery and log
    `Meta would not start an upload session for a template sample: <Meta's sentence>` or
    `Meta would not take the bytes of a template sample: …`. That log line is the answer for the
    resumable upload.
- **Rajeev's test vendor:** `Mahalakshmi Stores` (id `e5b51708-…`), phone `+12693523612`, language
  `kn`, `whatsappReachable: true`.
  - None of the 23 purchase orders on staging is for this vendor, so step 3 needs a new order first.
  - Its sheet will be the Kannada one.
- **Send path:** `POST /api/v1/purchase-orders/{id}/whatsapp`.
  - The adapter uploads the PDF to `/<PHONE_NUMBER_ID>/media` first. A refusal is logged as
    `WhatsApp send failed for template po_delivery: …WhatsAppSendFailed: <Meta's sentence>`.
  - `NotificationService.notify` takes WhatsApp as a channel to *prefer*, so a refused WhatsApp send
    may fall back to SMS to the same number or to email. Expect that on the retry.
- **Order matters.** Until po_delivery carries the header at Meta, a send with the header component
  will be refused at the send step even if the media upload succeeds. After Reload it will be refused
  while the edit is in review. Either way, the media upload result is logged before the send.

## What Rajeev needs to do

1. Open Meta's developer dashboard, pick the app that owns this WhatsApp number, go to
   **App settings → Basic**, and copy the **App ID** at the top.
   - It is probably `1920408082251143`, the app in the console URLs from the WhatsApp setup session.
2. On staging: **Settings → WhatsApp → Edit**, put it in the **App ID** box (after the Business
   Account ID), and **Save**.
   - Leave the access key and app secret blank; the stored ones are kept.
   - Saving sends nothing to Meta.
   - Or say the word and the session enters the confirmed id through the API.
3. Then steps 2 and 3 above can be run: Reload, read the log line, create an order for Mahalakshmi
   Stores, and send it once.

Worth knowing before Reload: once the header is accepted, the approved po_delivery goes back into
Meta's review. Purchase orders on WhatsApp are refused until Meta approves it again. Changing the
header type later means another review.

---

## Resumed 2026-09-14 20:25–20:45 UTC, after Rajeev entered the App ID and set up the webhook

Same rules as before: Temple Admin through the app's API, no Meta token or secret seen, no Chrome.
API revision `kms-staging-api-00168-zjc`. **No WhatsApp message was sent.**

### Result

- **Resumable upload (`/<APP_ID>/uploads`) with the stored System User token: yes, accepted.**
- **Media upload (`/<PHONE_NUMBER_ID>/media`): not reached.** po_delivery is back in Meta's review,
  so no order was sent.
- **po_delivery at Meta: PENDING**, now registered with the DOCUMENT header. Still PENDING 12 minutes
  after Reload.
- **Meta's sample Test webhook: not received.** No POST reached the webhook by 20:45 UTC.
- **Real delivery-status webhook: not reached**, because nothing was sent.

### 1. App ID and webhook verification

`GET /api/v1/settings/whatsapp` at ~20:25 UTC:

```
'connected': True, 'appId': '1920408082251143', 'verifiedAt': '2026-09-14T20:22:29.334319Z',
'webhookSeenAt': None, 'templatesPending': {'changed': 1, ...}
```

The App ID is stored, and it is the id suggested above. Meta's verification handshake reached
staging. From the Cloud Run request log (path token redacted here):

```
2026-09-14T20:28:32.652177Z  GET  200  facebookplatform/1.0 (+http://developers.facebook.com)
```

That is the GET handshake, answered 200. `webhookSeenAt` stays null, which is correct: only a signed
POST (`WhatsAppWebhookController`, `settings.markWebhookSeen`) stamps it.

**Sample Test webhook: not seen.** Requests to `/api/v1/public/webhooks/whatsapp/*` since 20:29 UTC,
checked every minute until 20:45: none. No "callback" warning lines either, such as an unknown token
or a bad signature.

- Either Test was not pressed in that window, or Meta's Test did not reach staging.
- If Rajeev presses it again, a handled one shows as a POST 200 in the request log and sets
  `webhookSeenAt`.
- A POST whose signature does not match the stored app secret logs
  `Rejected a WhatsApp callback for temple … with a missing or invalid signature`.

### 2. Template registration with the PDF header (Reload)

`POST /api/v1/settings/whatsapp/templates/reload` at 20:32:57 UTC: **HTTP 200 in 51.3 s.**
The body was not captured (a script error on my side), so the facts come from the logs and the reads
that followed.

Logs for that request (`req=83ee32cc-…`):

```
20:33:40.618 INFO TenantWhatsAppSettingsService - Submitted 21 of 21 WhatsApp templates for temple f935450b-…: 0 new, 20 already held, 1 held under another category, 0 not registered
20:33:40.618 INFO TenantWhatsAppSettingsService - Reload for temple f935450b-…: 1 reworded at Meta, 0 still waiting for new wording
20:33:48.682 INFO WhatsAppTemplateComparison - Compared 21 WhatsApp templates with Meta for temple f935450b-…: 21 identical, 0 identical only after trimming, 0 worded differently, 0 not held, 0 not answered, 0 with a different header
```

No `Meta would not start an upload session…` or `Meta would not take the bytes…` line was logged.
The edit carries the handle the upload returned, so the upload succeeded with the stored token.

`GET …/templates/meta-comparison` afterwards:

```
{"name": "po_delivery", "ourCategory": "UTILITY", "metaCategory": "UTILITY", "metaStatus": "PENDING",
 "held": true, "bodyMatchesExactly": true, "ourHeaderFormat": "DOCUMENT", "metaHeaderFormat": "DOCUMENT",
 "headerMatches": true}
```

`GET /api/v1/settings/whatsapp` afterwards:

```
'templatesSubmittedAt': '2026-09-14T20:32:58.092Z',
'templatesPending': {'changed': 0, 'refused': 0, 'accountChanged': False, 'unchecked': 0}
refusedTemplates: [wishlist_gift_split — held as marketing]
```

`donation_thank_you` has dropped off the "held as marketing" list, so Meta now holds it as UTILITY.
`wishlist_gift_split` is still held as marketing.

Polled po_delivery's status every ~70 s:

```
20:36:02 PENDING … 20:44:15 PENDING (8 polls, no change)
```

### 3 and 4. Send and delivery webhook — not done

The brief said to send only once the template is approved. po_delivery is PENDING, so:

- no purchase order was created for Mahalakshmi Stores;
- nothing was sent;
- no delivery webhook was waited for.

The two WhatsApp messages allowed are both unused.

To finish once Meta approves po_delivery:

- The comparison shows `metaStatus: APPROVED`, or Meta's template_status webhook, if that field is
  subscribed.
- Then create an order for Mahalakshmi Stores (`+12693523612`, `kn`) and send it with
  `POST /api/v1/purchase-orders/{id}/whatsapp`.
- Media upload result: a log line `WhatsApp send failed for template po_delivery: …` means it failed;
  a delivered message means it worked.
- Then watch for a POST to the webhook with a `statuses` entry. With the app unpublished, Meta's banner
  says only dashboard test webhooks are delivered, so this may never come. That is the open question.
