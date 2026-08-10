package org.iskcon.kms.meal;

/**
 * The context a meal is cooked in (E4-S4), which scales it and sets expectations. Auto-suggested from
 * the calendar (a festival occasion, or the weekday) and overridable — except catering, which is
 * always an explicit choice because it carries a client commitment.
 */
public enum DayType {
	REGULAR,
	WEEKEND,
	FESTIVAL,
	CATERING
}
