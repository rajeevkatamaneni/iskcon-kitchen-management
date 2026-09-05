package org.iskcon.kms.meal;

/**
 * The context a meal is cooked in (E4-S4), which scales it and sets expectations. Derived from the
 * calendar and the date — a festival outranks a weekend, because it is what explains the quantity —
 * and never chosen by a person.
 *
 * <p>CATERING was here and is gone (E4-S15). It was never a kind of day: it was a fact about the
 * meal, which is why V48 moved catering into the meal kinds and left this behind. A temple that
 * caters now plans an event that is going outside, and what sort of day it was cooked on is still
 * the weekday or the festival.
 */
public enum DayType {
	REGULAR,
	WEEKEND,
	FESTIVAL
}
