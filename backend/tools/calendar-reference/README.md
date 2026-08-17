# The calendar reference fixture

`src/test/resources/calendar/blr-reference-2025.json` is 400 days of GCAL output for Bengaluru,
and `CalendarReferenceTest` asserts the Java engine reproduces it day-for-day. `generate.py`
regenerates it from the Python reference
([`gopa810/gaurabda-calendar`](https://github.com/gopa810/gaurabda-calendar), pinned at `92c36b5`).

## Why the reference is patched

`mahadvadasi-ismhd58.patch` changes one line of the reference, and the fixture is generated with it
applied. In the original C++ GCAL (`gopa810/gcal-cpp`, `TResultCalendar::MahadvadasiCalc`),
`IsMhd58` is a **predicate** — it returns `TRUE`/`FALSE` and hands the type back through an
out-parameter:

```cpp
if (TITHI_GAURA_DVADASI == t.astrodata.nTithi && TITHI_GAURA_DVADASI == t.astrodata.nTithiSunset
        && IsMhd58(nIndex, nMahaType))
```

The Python port collapsed that to a function returning the type, using `EV_NULL` (0x100) for
"none" — but kept the C++ call site's `nMahaType != 0` test. `0x100 != 0`, so the branch fires on
**every** Gaura-paksa Dvadashi, with a type of *none*, and pre-empts the `elif` that finds Vyanjuli,
Paksavardhini and Suddha. The consequence is a temple fasting a day early: the Gaura-paksa
Maha-Dvadashis disappear, so the fast stays on an Ekadashi the śastra says to skip.

The patch restores the C++ meaning (`nMahaType != EV_NULL`). Over the fixture's 400 days it moves
one fast — Pandava Nirjala 2025, 6 June → 7 June, matching the published calendars — labels
31 December 2025's fast as the Suddha Maha-Dvadashi it is instead of leaving it unexplained, and
clears four stray Ekadashi names left on days that were not fasting.

The same defect, and the same one-line fix, applied to our Java port:
`CalendarBuilder.mahadvadasiCalc`, covered by `MahadvadasiRuleTest`.
