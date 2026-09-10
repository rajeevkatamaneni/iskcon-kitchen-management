package org.iskcon.kms.vendor;

import java.time.LocalDate;

/**
 * Where today stands against the last day something could be ordered (T-090).
 *
 * <p>Rajeev's rule, in his own shape: <em>"Amber while there is still slack; red the day you hit the
 * order-by date; and past that it is not a warning any more but a fact, and should say something
 * different."</em>
 *
 * <h2>The third state is not a darker red</h2>
 *
 * <p>This is the part worth being explicit about, because building it as one more step up a colour
 * ramp is both the obvious thing to do and wrong. Once the order-by date has gone, telling somebody
 * to order in time is useless — there is no longer an action that produces the outcome the warning
 * was about. So {@link #TOO_LATE} is a different <em>sentence</em>, not a louder one: the screens
 * say what is now true (<em>"won't arrive in time"</em>) rather than what should have been done.
 *
 * <p>The colour deliberately does <strong>not</strong> escalate a third time. A meal tomorrow that
 * is short of rice is not less serious because the deadline has passed, so the two late states share
 * the same red and the words carry the difference. A person reading it needs to know which of two
 * problems they have — get the order out today, or find another way to feed people — and a shade is
 * not capable of telling them that.
 *
 * <h2>What it does not do</h2>
 *
 * <p>It never refuses anything. {@code leadTimeWarning} on the order screen states the principle
 * this follows: <em>"a temple that genuinely needs a sack of rice tomorrow should be able to ask for
 * it tomorrow, and a rule that made that impossible would only teach people to write a date they do
 * not mean."</em> Every state here is something a screen says; none of them is a gate.
 */
public enum OrderUrgency {

	/** There is still slack: the order-by date is ahead of today. Amber. */
	IN_TIME,

	/** Today <em>is</em> the order-by date — the last day it can be asked for. Red. */
	ORDER_TODAY,

	/**
	 * The order-by date has gone. Not a warning any more: an order placed now does not arrive before
	 * the food is cooked, and the sentence on screen says that rather than asking for something that
	 * is no longer possible.
	 */
	TOO_LATE;

	/**
	 * Which of the three today is, against an order-by date.
	 *
	 * <p>Whole days in the temple's own zone on both sides — the caller supplies today from {@code
	 * TempleClock}, for the reason everything else in this application dates itself that way: an
	 * order-by date that flips a day early for a reader in London is a red badge nobody in the
	 * kitchen can account for.
	 */
	public static OrderUrgency on(LocalDate today, LocalDate orderBy) {
		if (orderBy.isAfter(today)) {
			return IN_TIME;
		}
		return orderBy.isEqual(today) ? ORDER_TODAY : TOO_LATE;
	}
}
