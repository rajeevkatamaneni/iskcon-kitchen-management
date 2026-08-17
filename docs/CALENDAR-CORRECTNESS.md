# Vaishnava Calendar — correctness gate (E4-S1)

**Status:** reference validated 2026-08-10; Maha-Dvadashi defect found and fixed 2026-08-17
(see "The Maha-Dvadashi defect" below — one fasting day in this window moved, and the
"GCAL-vs-drik divergence" recorded here turned out to be the defect, not a divergence).
This document is the "documented comparison" the story's correctness gate requires.

## What we compute, and against what we check it

The calendar engine is a faithful Java port of the ISKCON **GCAL** algorithm — specifically the
MIT-licensed Python reference [`gopa810/gaurabda-calendar`](https://github.com/gopa810/gaurabda-calendar),
which is itself a port of the official *Gaurabda Calendar Program* maintained by the ISKCON GBC
Vaishnava Calendar Committee. "Compute, don't import" and the post-2006 Hari-bhakti-vilasa schema are
locked decisions (REQUIREMENTS/SYSTEM_DESIGN); this is the reference those decisions point to.

Because the reference is runnable, the gate is **exhaustive and objective**, not a spot check:

1. We ran the Python reference for **Bengaluru** (lat 12.9716, lon 77.5946, tz +05:30) over **400 days**
   (2024-12-20 → 2026-01-24) and captured every field for every day — tithi, paksa, masa, Gaurabda
   year, naksatra, yoga, sunrise/sunset, fast type, Maha-Dvadashi type, Ekadashi name, and festivals.
   That output is committed as the test fixture `backend/src/test/resources/calendar/blr-reference-2025.json`,
   and `backend/tools/calendar-reference/` regenerates it — the reference checkout is pinned, and it
   carries one patch, explained below.
2. `CalendarReferenceTest` runs the **Java** engine for the same location and range and asserts it
   reproduces that fixture **day-for-day** (exact integer equality on tithi/masa/paksa/naksatra/yoga/
   Gaurabda year/fast/Maha-Dvadashi/Ekadashi name; sunrise/sunset within 1e-6°; festival sets equal).
   A divergence on any day fails the build — the gate blocks the story, it does not ship with it.

Matching GCAL day-for-day is the right gate for a GCAL port. Below we additionally confirm that GCAL
itself matches the **published** ISKCON Bangalore calendar, so the reference we chase is the truth.

## Reference vs published — Bengaluru 2025

Published dates from the ISKCON event calendar for Bengaluru (drikpanchang, geoname 1277333), compared
to the GCAL reference we gate against.

### Festivals — all match

| Festival | GCAL reference | Published | Match |
|---|---|---|---|
| Gaura Purnima (Sri Caitanya appearance) | 14 Mar 2025 | 14 Mar 2025 | ✓ |
| Sri Krsna Janmastami | 16 Aug 2025 | 16 Aug 2025 | ✓ |
| Radhastami | 31 Aug 2025 | 31 Aug 2025 | ✓ |

### Ekadashi fasting days — all 25 identical

The 25 Ekadashi fasts GCAL computes for 2025 match the published list on all 25.

GCAL Ekadashi dates 2025: Jan 10, Jan 25, Feb 8, Feb 24, Mar 10, Mar 26, Apr 8, Apr 24, May 8,
May 23, **Jun 7**, Jun 22, Jul 6, Jul 21, Aug 5, Aug 19, Sep 3, Sep 17, Oct 3, Oct 17, Nov 2, Nov 15,
Dec 1, Dec 16, Dec 31. (Maha-Dvadashi postponements included — e.g. Utthana Ekadashi 2 Nov and
Saphala Ekadashi 16 Dec carry non-null Maha-Dvadashi variants in the reference.)

An earlier version of this document recorded **Pandava Nirjala as 6 Jun** against a published 7 Jun,
and dismissed it as a siddhanta divergence between GCAL and the drik-ganita method drikpanchang uses.
That was wrong, and the next section is why.

## The Maha-Dvadashi defect (found 2026-08-17)

There are eight Maha-Dvadashis — Unmilani, Vyanjuli, Trisprsa, Paksavardhini, Jaya, Vijaya, Jayanti,
Papanasini — and on each, the Ekadashi is not fasted at all: the fast moves to the Dvadashi that
follows. The engine had the rules, and lost half of them to one operator.

In the original C++ GCAL, `IsMhd58` is a predicate returning `TRUE`/`FALSE` with the type handed back
through an out-parameter. The Python reference we ported collapsed it into a function returning the
type, using `EV_NULL` (0x100) for "none" — but kept the C++ call site's `nMahaType != 0` test.
`0x100 != 0`, so on **every Gaura-paksa Dvadashi** the engine entered the Maha-Dvadashi 5–8 branch
carrying a type of *none*, which pre-empted the branch below it that detects Vyanjuli, Paksavardhini
and Suddha. Gaura-paksa Vyanjuli and Paksavardhini were therefore never found, and the temple fasted
a day early. Krsna-paksa ones were unaffected, which is why the calendar looked right most of the year.

Reported by Rajeev against **Pavitropana Ekadashi 2026**: the engine said fast on 23 Aug, the ISKCON
app said 24 Aug. The Ekadashi tithi runs 23 Aug 02:00 → 24 Aug 04:18, and the Dvadashi that follows
runs to 06:23 on the 25th — past that morning's 06:09 sunrise. A Dvadashi spanning two sunrises after
an Ekadashi unbroken from arunodaya is Vyanjuli, so the fast belongs on the 24th, with a 14-minute
parana window on the 25th (06:09–06:23). The engine now computes exactly that: fast 24 Aug, parana
06:08–06:22.

The fix is one line in `CalendarBuilder.mahadvadasiCalc` (`nMahaType != EV_NULL`), named and pinned by
`MahadvadasiRuleTest`. The reference fixture was regenerated from the Python reference with the same
one-line patch applied — `backend/tools/calendar-reference/` holds the script, the patch and the
reasoning, and reproduces the committed fixture byte-for-byte.

Across the fixture's 400 days it changes seven days, all in the same direction:

| Day | Before | After |
|---|---|---|
| 6 Jun 2025 | fast (Pandava Nirjala) | no fast |
| 7 Jun 2025 | parana day | **fast** — Vyanjuli Maha-Dvadashi, parana 8 Jun 05:52–07:20 |
| 31 Dec 2025 | fast, Maha-Dvadashi type *none* — nothing said why it moved | fast, classified Suddha |
| 9 Feb, 9 Apr, 7 Jul, 4 Sep 2025 | named for an Ekadashi they did not fast | name cleared |

So the 6 Jun figure this document defended was the defect's output, and GCAL, drikpanchang and the
engine now agree on 7 Jun. Over three years of Bengaluru days the engine now finds ten
Maha-Dvadashis where it previously found six.

## Acceptance criteria coverage

- [x] Full-year output for an Indian metro (Bengaluru) matches the published ISKCON calendar for all
      25 Ekadashis and for Janmashtami, Gaura Purnima, and Radhastami. This is that documented
      comparison.
- [x] Exhaustive day-by-day gate against the reference (`CalendarReferenceTest`, 400 days).
- Two-city divergence, nightly-job idempotency, and the ops "last precompute" readout are covered by
  the engine's service/job tests (E4-S1 backend), added with the precompute pipeline.
