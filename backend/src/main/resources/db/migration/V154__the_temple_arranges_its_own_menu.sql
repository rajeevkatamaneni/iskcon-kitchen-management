-- =====================================================================
-- V154 — The temple arranges its own menu
--
-- T-420, Rajeev on 2026-09-19 (terminal): a Temple Admin gets Settings →
-- Menu, where the temple decides the order of the left-hand menu, renames
-- and adds groups, moves an item from one group to another, and can put
-- the standard menu back. What is saved applies to everybody who serves at
-- that temple, not to the person who saved it.
--
-- ---------------------------------------------------------------------
-- 1. What the column holds, and what it deliberately does not
--
-- One JSON object, the whole arrangement:
--
--   {"version": 1,
--    "groups": [{"id": "kitchen", "title": "Kitchen",
--                "items": ["meals", "recipes"]}, ...]}
--
--   version  1 today. It is here so a later shape can be told from this
--            one by reading a row rather than by guessing from its keys.
--   id       a stable handle for a group — lower case, digits, hyphens.
--            Never shown. Renaming a group changes `title` and leaves the
--            id alone, so the rename does not read as a different group.
--   title    what the group is called, or null for the small unheaded
--            block at the top of the standard menu.
--   items    the destinations in that group, in the order the temple put
--            them, named by the ids the menu already uses.
--
-- What it does NOT hold is a copy of the menu. The list of destinations
-- that exist lives in frontend/lib/nav.ts, beside the pages it points at,
-- and this side never learns it: an id stored here that no longer matches
-- anything is passed over by the merge on the other side rather than
-- refused on this one. The alternative is a second copy of the catalogue,
-- in the one place that cannot see whether it is still true — the same
-- argument V72 made for selected_theme_id, and it has held since.
--
-- It also holds no statement about access. The arrangement decides order
-- and grouping; who may see a destination is still the role's permissions
-- and nothing else, applied after the merge. A temple cannot hide a screen
-- by taking it out of the arrangement — the merge puts an unplaced item
-- back — and cannot reveal one by putting it in.
--
-- ---------------------------------------------------------------------
-- 2. Nullable, and null is NOT "arranged it to look standard"
--
-- Null means this temple has never arranged its menu, and the standard
-- menu is what everyone there sees. That is a different fact from a temple
-- that opened the screen, moved nothing and saved: that one has said the
-- standard order is what it wants, and a later release that changes the
-- standard order should not quietly move its menu about.
--
-- selected_theme_id (V72) already draws this distinction — "never chose"
-- against "chose the default" — and the reason is the same one. Reset to
-- the standard menu therefore writes NULL rather than writing out today's
-- standard arrangement: a temple that resets is asking to follow the
-- standard from now on, including the parts of it that have not been
-- decided yet.
--
-- A column on tenant_settings rather than a table of groups and items,
-- because it is always read and written whole, for one temple, and has no
-- life of its own: nothing queries one group, joins to it, or keeps a
-- version of it once the next save has replaced it. Exactly the shape
-- whatsapp_refused_templates took in V128 and for the same reasons.
--
-- ---------------------------------------------------------------------
-- 3. The CHECK, and what it is and is not for
--
-- jsonb_typeof = 'object' refuses an array, a bare string and a naked
-- number at the last possible moment. It is not the validation — the
-- shape of the arrangement is checked in TenantSettingsService before any
-- write, in words a temple administrator can act on (KMS-400189 and
-- KMS-400190). This is the floor under that: a row that could not be read
-- back as an arrangement at all never lands, whatever writes it.
--
-- ---------------------------------------------------------------------
-- 4. RLS: nothing to add, and this is the reason rather than an omission
--
-- tenant_settings has carried enable_tenant_rls() since V36. A column
-- added to a table is covered by that table's existing policy — a policy
-- is a row predicate, not a column list — so a new column on an
-- RLS-protected table needs no policy of its own, no second GRANT and no
-- new table. The arrangement is per temple because the ROWS are per
-- temple: one temple's menu is invisible to every other, by the policy
-- already there, and a platform operator carries no app.tenant_id, so the
-- policy matches nothing and they read null without a special case.
--
-- The V55 webhook-lookup SELECT policy on this table lets an
-- unauthenticated Meta callback find its temple by the opaque token in
-- its URL, and so also exposes this column to a caller already holding
-- that token. It holds menu group headings and destination ids: nothing
-- that could send a message, move money or identify a person.
--
-- ---------------------------------------------------------------------
-- 5. No DML, and therefore no per-tenant loop
--
-- Migrations here run subject to RLS, so anything written row by row has
-- to adopt each tenant in turn (V97 and V98 both do). This migration
-- writes no rows at all: ADD COLUMN with no default is catalogue-only DDL
-- run as the table owner, and every existing row reads as null — which is
-- the true statement about every temple in existence today, none of which
-- has arranged anything. A loop over `tenants` here would iterate over
-- nothing to do.
-- =====================================================================

ALTER TABLE tenant_settings
    ADD COLUMN menu_layout JSONB,
    ADD CONSTRAINT tenant_settings_menu_layout_is_object
        CHECK (menu_layout IS NULL OR jsonb_typeof(menu_layout) = 'object');

COMMENT ON COLUMN tenant_settings.menu_layout IS
    'How this temple has arranged its own left-hand menu (T-420, Rajeev 2026-09-19): '
    '{"version": 1, "groups": [{"id", "title", "items"}]}, where items name destinations by the '
    'ids the menu already uses and title is null for the unheaded block at the top. Order and '
    'grouping only — never access, which stays entirely with the role''s permissions, applied '
    'after the frontend merges this with the standard menu. NULL means the temple has never '
    'arranged its menu and follows the standard one, which is NOT the same as having opened the '
    'screen and saved the standard order; resetting writes NULL back, so a temple that resets '
    'follows the standard menu as it changes. Deliberately holds no copy of the menu: an id here '
    'that matches nothing in frontend/lib/nav.ts is passed over by the merge rather than refused '
    'on this side. Written only through PUT/DELETE /api/v1/settings/menu-layout, behind '
    'MANAGE_TEMPLE_SETTINGS — the Temple Admin alone — and applies to everybody at the temple.';
