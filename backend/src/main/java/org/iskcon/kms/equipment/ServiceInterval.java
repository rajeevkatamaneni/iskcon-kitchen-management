package org.iskcon.kms.equipment;

/**
 * The unit a service interval was said in (E3-S10 D3).
 *
 * <p>The interval itself is stored as a count of days; this records the words the person used, so
 * the form shows "every 6 months" back rather than "every 180 days". Both halves are needed: the
 * days are what the arithmetic runs on, and the unit is what makes the round trip lossless.
 *
 * <p><strong>A month is thirty days and a year is three hundred and sixty-five.</strong> That is
 * the arithmetic a service contract means and not the arithmetic a calendar means — nobody servicing
 * a wet grinder every six months intends the date to shift by three days because February is short.
 * It is also what makes {@link #countIn(int)} exact: 180 days entered as MONTHS divides back to the
 * six that was typed, which calendar months could not promise.
 */
public enum ServiceInterval {

	DAYS(1),

	WEEKS(7),

	MONTHS(30),

	YEARS(365);

	private final int daysEach;

	ServiceInterval(int daysEach) {
		this.daysEach = daysEach;
	}

	/** How many days one of these is. */
	public int daysEach() {
		return daysEach;
	}

	/** The interval in days — what is stored, and what the next-service date is worked out from. */
	public int toDays(int count) {
		return count * daysEach;
	}

	/**
	 * The count that was typed, recovered from the stored day total.
	 *
	 * <p>Exact for anything this application wrote, because it wrote {@code count * daysEach}.
	 * Integer division for anything it did not: a row edited by hand to 100 days in MONTHS reads
	 * back as 3, which is the nearest true statement available and is better than refusing to show
	 * the machine at all.
	 */
	public int countIn(int intervalDays) {
		return intervalDays / daysEach;
	}
}
