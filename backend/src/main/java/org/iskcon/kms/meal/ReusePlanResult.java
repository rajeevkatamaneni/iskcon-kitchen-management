package org.iskcon.kms.meal;

/**
 * What reusing a plan actually did.
 *
 * <p>The same shape the preview promised, returned again after the writing, so a screen can say
 * "that is what happened" rather than "that is what we said would happen".
 *
 * @param daysLeftAlone days that already had meals and were not touched. Never a failure and always
 *                      worth saying: a planner who had already filled in a Thursday needs to know
 *                      their Thursday survived.
 * @param notCopied     meals refused because the day they would land on forbids what is in them.
 */
public record ReusePlanResult(
		int copied, int daysWritten, int daysLeftAlone, int notCopied, boolean sourceWasEmpty) {
}
