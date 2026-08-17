"""Regenerates the calendar gate's reference fixture.

The engine in `org.iskcon.kms.calendar` is a Java port of GCAL, and
`CalendarReferenceTest` gates it against 400 days of output from the runnable
Python reference. This is the script that produced that output — committed so
the fixture is reproducible rather than a file someone once made.

    git clone https://github.com/gopa810/gaurabda-calendar.git   # pinned: 92c36b5
    cd gaurabda-calendar && git apply ../mahadvadasi-ismhd58.patch && cd ..
    python3 generate.py gaurabda-calendar \
        ../../src/test/resources/calendar/blr-reference-2025.json

The patch is required and its reason is in README.md: the Python reference
mistranslates one line of the original C++ and loses the Gaura-paksa
Maha-Dvadashis. Without it the fixture encodes a defect and the gate enforces it.
"""

import sys

if len(sys.argv) != 3:
    sys.exit(__doc__)

reference_checkout, out_path = sys.argv[1], sys.argv[2]
sys.path.insert(0, reference_checkout)

import gaurabda as gcal  # noqa: E402  (needs the path above)

# Bengaluru — the location the whole calendar gate is validated against.
loc = gcal.GCLocation(data={
    'latitude': 12.9716,
    'longitude': 77.5946,
    'tzname': '+5:30 Asia/Calcutta',
    'name': 'Bangalore, India',
})

tc = gcal.TCalendar()
tc.CalculateCalendar(loc, gcal.GCGregorianDate(year=2024, month=12, day=20), 400)

with open(out_path, 'wt') as wf:
    tc.write(wf, format='json')
