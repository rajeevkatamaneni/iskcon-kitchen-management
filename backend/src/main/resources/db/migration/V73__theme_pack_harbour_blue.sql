-- =====================================================================
-- V73 — Harbour blue
--
-- The first pack that is not the palette the application was designed in,
-- and the one Rajeev asked for to try the switch end to end before the
-- other fifteen are built: blue and white.
--
-- Built by tools/theme/build_theme_pack.py at hue 248, family BALANCED.
-- Every lightness in it was solved against the contrast it has to clear
-- rather than chosen and then checked, and all thirty-four required
-- pairings pass — the tightest by 0.01, which is the solver doing exactly
-- what it is for. To see the report:
--
--     python3 tools/theme/build_theme_pack.py \
--         --slug harbour-blue --name "Harbour blue" --family BALANCED \
--         --hue 248 --sort-order 1 --description "..."
--
-- ---------------------------------------------------------------------
-- Why this migration exists separately from V72
--
-- Two reasons, and the second is the real one.
--
-- The first is honest bookkeeping: V72 builds a table, and a table and its
-- contents are different changes.
--
-- The second is that this exercises the `app.theme_load` escape V72 put
-- there for exactly this, and does it once, with one row, where a mistake
-- is obvious — rather than first discovering whether it works while
-- inserting fifteen packs. The escape opens INSERT only when the setting
-- is present *and* there is no authenticated identity on the connection,
-- which is true of a migration and can never be true of a request.
--
-- SET LOCAL, so it lasts as long as Flyway's transaction for this file and
-- not a moment longer. Nothing has to unset it, and nothing can forget to.
-- =====================================================================

SET LOCAL app.theme_load = 'true';

INSERT INTO theme_packs (slug, name, family, description, palette, sort_order)
VALUES (
    'harbour-blue',
    'Harbour blue',
    'BALANCED',
    'A deep harbour blue on white, over cool grey surfaces. Crisp and businesslike.',
    '{
        "canvas": "#FFFFFF",
        "raised": "#F7FBFF",
        "sunken": "#EDF1F5",
        "hairline": "#E0E4E8",
        "hairline-strong": "#D1D5D9",
        "ink": "#292E34",
        "ink-secondary": "#595E63",
        "ink-muted": "#696E72",
        "ink-inverse": "#F7FBFF",
        "accent-bg": "#E5F2FE",
        "accent-border": "#C9E3FD",
        "accent": "#2573B3",
        "accent-hover": "#1265A5",
        "accent-text": "#2A71AE",
        "focus-ring": "#558EC3",
        "danger-bg": "#FFE6E3",
        "danger": "#B6433C",
        "info-bg": "#DBF1FE",
        "info": "#0E739F",
        "warning-bg": "#F9EBD1",
        "warning": "#886604",
        "success-bg": "#DCF5E0",
        "success": "#0B7C39"
    }'::jsonb,
    1
);
