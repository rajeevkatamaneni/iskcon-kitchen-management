-- =====================================================================
-- V59 — Communications a temple writes for itself (E8-S2, E8-S3)
--
-- A newsletter, a festival announcement, an appeal. Composed on screen or
-- pasted in from whatever the temple already writes in, previewed, sent to the
-- author first if they want, and then to everyone who has not declined that
-- kind of message.
--
-- Two bodies, and both are kept:
--
--   * `body_html` is the newsletter as it will be read in an email client,
--     after sanitising. It is stored sanitised, not raw — the sanitiser runs on
--     the way in, so nothing unsafe is ever at rest and no later reader has to
--     remember to clean it.
--   * `body_text` is the same message as a sentence. It is the plain-text half
--     of the email (a multipart message without one lands in spam filters), and
--     it is the whole of what WhatsApp and SMS can carry.
--
-- Because WhatsApp cannot carry the newsletter. Meta only delivers
-- business-initiated messages that match a template it has already approved,
-- so a pasted 600-word letter has no road onto that channel. What a WhatsApp
-- communication is instead: a short approved announcement carrying the subject,
-- one line the admin writes, and a link back to `public_token` — which is why
-- that column exists.
--
-- `communication_recipients` is the same shape as shift_broadcast_recipients
-- and for the same reason: after sending to four hundred people, "did it go?"
-- is a question about each of them, not about the batch.
-- =====================================================================

CREATE TABLE communications (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    -- A CommunicationCategory, and never OPERATIONAL: nothing composed by hand is
    -- the consequence of something a devotee already did, so all of it is declinable.
    category        TEXT        NOT NULL,

    channel         TEXT        NOT NULL,
    subject         TEXT        NOT NULL,
    body_html       TEXT,
    body_text       TEXT        NOT NULL,

    -- What WhatsApp actually carries, since it cannot carry the letter itself.
    whatsapp_summary TEXT,

    status          TEXT        NOT NULL DEFAULT 'DRAFT',

    -- Filled at send time, so a sent communication reports the audience it really
    -- reached rather than recomputing one that has since changed.
    audience_count  INTEGER,

    -- The unguessable name of this communication's public web copy. Random rather
    -- than the id, so holding one link never implies the next: a temple's
    -- newsletters must not be enumerable by anybody who was sent one.
    -- gen_random_uuid() rather than pgcrypto's gen_random_bytes: pgcrypto is not
    -- installed here, and a v4 UUID is 122 bits of the same randomness. Hyphens
    -- stripped so the address reads as one word in a WhatsApp message.
    public_token    TEXT        NOT NULL DEFAULT replace(gen_random_uuid()::text, '-', ''),

    created_by      UUID        REFERENCES users(id) ON DELETE RESTRICT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,

    CONSTRAINT communications_channel_valid CHECK (channel IN ('EMAIL', 'WHATSAPP')),
    CONSTRAINT communications_status_valid CHECK (status IN ('DRAFT', 'SENT')),

    -- A sent communication has a time and an audience; a draft has neither.
    CONSTRAINT communications_sent_shape CHECK (
        (status = 'DRAFT'  AND sent_at IS NULL AND audience_count IS NULL)
        OR (status = 'SENT' AND sent_at IS NOT NULL AND audience_count IS NOT NULL)),

    -- An email carries the letter; a WhatsApp message carries the line that stands in for it.
    CONSTRAINT communications_body_for_channel CHECK (
        channel <> 'EMAIL' OR body_html IS NOT NULL)
);

CREATE UNIQUE INDEX communications_public_token ON communications (public_token);
CREATE INDEX communications_by_recency ON communications (tenant_id, created_at DESC);
SELECT enable_tenant_rls('communications');

COMMENT ON COLUMN communications.body_html IS
    'Stored already sanitised (E8-S2). Nothing unsafe is ever at rest, so no reader has to remember to clean it.';
COMMENT ON COLUMN communications.public_token IS
    'Names the public web copy. Random, not derived from the id — one link must never imply another.';

-- ---------------------------------------------------------------------
-- Who it went to, and what became of it. One row per person per send.
-- ---------------------------------------------------------------------
CREATE TABLE communication_recipients (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    communication_id UUID        NOT NULL REFERENCES communications(id) ON DELETE CASCADE,
    recipient_user_id UUID       NOT NULL REFERENCES users(id) ON DELETE RESTRICT,

    -- The message this became, so delivery status is read from the one place that
    -- knows it rather than copied and left to drift.
    notification_id  UUID        REFERENCES notifications(id) ON DELETE SET NULL,

    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX communication_recipients_once
    ON communication_recipients (tenant_id, communication_id, recipient_user_id);
CREATE INDEX communication_recipients_by_communication
    ON communication_recipients (tenant_id, communication_id);
SELECT enable_tenant_rls('communication_recipients');
