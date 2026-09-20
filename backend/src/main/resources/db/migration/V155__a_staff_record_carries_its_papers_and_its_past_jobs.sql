-- =====================================================================
-- V155 — a staff record carries its papers and its past jobs (T-428)
--
-- Rajeev, 2026-09-20, reviewing the staff screens: a person's record should
-- open as a record rather than as a form; it should carry their photograph;
-- it should hold "Scanned Copy or photograph of Pan Card, Aadhar card"; and
-- it should say where they worked before — "employer, their title there,
-- manager's name, manager's phone number, from date, to date, reason for
-- leaving".
--
-- Two tables, both hung off staff_profiles, both under tenant RLS.
--
-- ---------------------------------------------------------------------
-- 1. Why staff_documents is its own table and not `attachments` (V144)
-- ---------------------------------------------------------------------
-- V144's own header settled the principle for exactly this case: "The storage
-- service is shared; the table is not." attachments is the record of a
-- procurement upload — its parent is an invoice or a payment, its kind
-- vocabulary is a bill and three flavours of payment proof, and its
-- attachments_parent_matches_kind constraint exists to keep those two straight.
-- A staff photograph belongs to neither parent. Folding it in would mean a
-- third nullable parent column, a kind vocabulary spanning two unrelated
-- subjects, and a CHECK constraint that has to be re-read every time either
-- half changes.
--
-- What IS shared is everything that matters for safety: the bytes go through
-- the same DocumentStorage (GCS in a deployment, a directory in development),
-- under the same tenants/<tenantId>/... prefix; the content type is sniffed
-- from the bytes by the same AttachmentFileType and never taken from the
-- browser; the same ten-megabyte ceiling applies; and the same two refusals,
-- KMS-400165 and KMS-400166, answer a file that is the wrong type or too big.
-- No second upload mechanism was built.
--
-- ---------------------------------------------------------------------
-- What protection these files actually have, stated plainly
-- ---------------------------------------------------------------------
-- These rows point at a photograph of somebody's PAN card and — Rajeev's
-- explicit instruction, knowing what it is — of their Aadhaar card. So:
--
--   * There is no public URL and no signed URL, here or anywhere in this
--     application. The only way to the bytes is GET .../documents/{id}, which
--     checks MANAGE_STAFF and runs under RLS, exactly as a vendor's bill does.
--   * Every read is written to the audit log as STAFF_DOCUMENT_VIEWED, naming
--     who read whose document of which kind, and nothing from inside the file.
--     That is the shape STAFF_PAN_VIEWED already uses.
--   * RLS keeps one temple's documents from another's, at the database.
--   * The bytes are NOT encrypted by this application at rest. The PAN *number*
--     is (PanCipher, V57) because it is a short string we can cipher and index
--     around; a scan is an opaque blob served straight back out, and encrypting
--     it here would be new cryptography invented in a build task. At rest it
--     has whatever the bucket gives it — Google-managed encryption on GCS — and
--     nothing more. If that is not enough for an Aadhaar scan, the answer is a
--     deliberate decision about key management, not a quiet addition here.
--
-- One document of each kind per person. A photograph is *the* photograph, and
-- a second PAN card scan is a replacement rather than an addition, so the
-- unique index says so and the service replaces in one transaction. Replacing
-- or removing one leaves its object in storage with no row pointing at it:
-- DocumentStorage has no delete, and V144 already accepts the same waste for
-- an abandoned upload. Waste, not a leak — nothing can reach an object whose
-- key is in no row.
--
-- ---------------------------------------------------------------------
-- 2. Why staff_previous_employment is a table and not a JSON column
-- ---------------------------------------------------------------------
-- Seven named fields, several rows per person, and the manager's phone number
-- is a phone number the same way every other phone number in this schema is.
-- A JSONB blob would put all seven past a CHECK constraint and past every
-- query that will one day ask "who else worked at that caterer".
--
-- Nothing here is verified. It is what the person said at their interview, and
-- the columns are named and shaped so that nobody later mistakes it for a
-- reference this temple took up. There is deliberately no "verified" flag: a
-- box nobody is accountable for ticking is worse than no box.
--
-- Not append-only. A previous employment is a typed-in fact about somebody's
-- past, corrected the way a misspelt address is corrected — the edit screen
-- sends the whole list and the service replaces it. Conduct notes are
-- append-only because they are evidence about a person's behaviour; "he worked
-- at Adyar Ananda Bhavan from 2019" is not.
--
-- ---------------------------------------------------------------------
-- 3. staff_profiles.notes is retired
-- ---------------------------------------------------------------------
-- Rajeev, same review: "If you cant justify why that is needed, remove it."
-- V84's own header already argued it — an unlabelled single-line TEXT box with
-- no author and no date, which the next edit destroys without trace, and which
-- staff_conduct_notes replaced for the one use anybody had for it. Nothing
-- reads it into a report, a search or a WHERE clause.
--
-- The column is NOT dropped, and that is deliberate. Three of the eleven staff
-- records in the working database hold text in it ("Twenty years at the stove.
-- Owns the festival menus."), so dropping it would destroy something, and a
-- drop cannot be undone by the next migration. It stops being read and stops
-- being written here; whether it goes is a data decision for Rajeev, and the
-- comment below is what tells the next reader that.
-- =====================================================================

CREATE TABLE staff_documents (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    staff_profile_id UUID        NOT NULL REFERENCES staff_profiles(id) ON DELETE RESTRICT,

    -- PHOTO, PAN_SCAN or AADHAAR_SCAN. A CHECK and not an enum type, matching
    -- attachments_kind_valid: the vocabulary is small, closed, and readable in
    -- the one place it is written.
    kind             TEXT        NOT NULL,

    -- The DocumentStorage key. Unique for V144's reason: two rows claiming one
    -- object would mean removing either takes the other's file with it.
    storage_key      TEXT        NOT NULL,
    -- What the bytes were found to be, never what the browser declared.
    content_type     TEXT        NOT NULL,
    size_bytes       BIGINT      NOT NULL,
    -- As the uploader's device named it ("IMG_2041.jpg"), for display only.
    original_name    TEXT,

    uploaded_by      UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    uploaded_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT staff_documents_kind_valid CHECK (
        kind IN ('PHOTO', 'PAN_SCAN', 'AADHAAR_SCAN')),
    CONSTRAINT staff_documents_storage_key_unique UNIQUE (storage_key),
    CONSTRAINT staff_documents_storage_key_present CHECK (btrim(storage_key) <> ''),
    CONSTRAINT staff_documents_content_type_present CHECK (btrim(content_type) <> ''),
    CONSTRAINT staff_documents_size_positive CHECK (size_bytes > 0)
);

COMMENT ON TABLE staff_documents IS
    'A staff member''s photograph and the scans of their PAN and Aadhaar cards (T-428). Bytes live in DocumentStorage under storage_key; read only through GET /api/v1/staff/members/{id}/documents/{documentId}, behind MANAGE_STAFF, and every read is audited. No signed or public URL exists.';
COMMENT ON COLUMN staff_documents.kind IS
    'PHOTO, PAN_SCAN or AADHAAR_SCAN. One of each per person — a second is a replacement.';
COMMENT ON COLUMN staff_documents.content_type IS
    'What AttachmentFileType found the bytes to be. Never the browser''s Content-Type.';

-- One of each kind per person, and the lookup the record screen makes.
CREATE UNIQUE INDEX staff_documents_one_per_kind
    ON staff_documents (tenant_id, staff_profile_id, kind);


CREATE TABLE staff_previous_employment (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    staff_profile_id   UUID        NOT NULL REFERENCES staff_profiles(id) ON DELETE RESTRICT,

    -- The only field a row cannot do without. Somebody who cannot remember
    -- their old manager's phone number still worked somewhere.
    employer           TEXT        NOT NULL,
    -- What they were called there, in that employer's words, not ours. This is
    -- free text and not job_title on purpose: "Tandoor Assistant" at a
    -- restaurant is not one of this temple's job titles and never will be.
    their_title        TEXT,

    manager_name       TEXT,
    -- Stored in the same shape as every other phone number here: +, country
    -- code, digits. Checked in the request object so the refusal reads as a
    -- field error on the form rather than as a database failure.
    manager_phone      TEXT,

    from_date          DATE,
    to_date            DATE,
    reason_for_leaving TEXT,

    -- The order the person gave them in, kept so the list does not reshuffle
    -- itself on every save. Not a date sort: half these rows will have no dates.
    sort_order         INTEGER     NOT NULL,

    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT staff_previous_employer_present CHECK (length(btrim(employer)) > 0),
    CONSTRAINT staff_previous_employer_bounded CHECK (length(employer) <= 200),
    CONSTRAINT staff_previous_title_bounded CHECK (their_title IS NULL OR length(their_title) <= 200),
    CONSTRAINT staff_previous_manager_bounded CHECK (manager_name IS NULL OR length(manager_name) <= 200),
    CONSTRAINT staff_previous_reason_bounded CHECK (
        reason_for_leaving IS NULL OR length(reason_for_leaving) <= 500),
    -- Either date may be missing — people forget, and a job with no end date is
    -- one they were still in when they came here. When both are given they have
    -- to be the right way round.
    CONSTRAINT staff_previous_dates_ordered CHECK (
        from_date IS NULL OR to_date IS NULL OR to_date >= from_date),
    CONSTRAINT staff_previous_sort_order_natural CHECK (sort_order >= 0)
);

COMMENT ON TABLE staff_previous_employment IS
    'Where somebody worked before this temple (T-428): employer, their title there, the manager and their number, the dates, and why they left. What the person said at their interview — nothing here is verified, and there is deliberately no flag claiming it was.';
COMMENT ON COLUMN staff_previous_employment.sort_order IS
    'The order the admin entered them in. Not a date sort — most rows will have no dates.';

CREATE INDEX staff_previous_employment_by_person
    ON staff_previous_employment (tenant_id, staff_profile_id, sort_order);


SELECT enable_tenant_rls('staff_documents');
SELECT enable_tenant_rls('staff_previous_employment');


COMMENT ON COLUMN staff_profiles.notes IS
    'RETIRED 2026-09-20 (T-428). Nothing reads or writes this column any more: it had no author and no date, and staff_conduct_notes (V84) replaced the one use anybody had for it. Not dropped, because rows still hold text — dropping it is a data decision, not a schema tidy-up.';
