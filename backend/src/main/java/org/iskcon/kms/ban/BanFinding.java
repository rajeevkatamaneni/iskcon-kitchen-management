package org.iskcon.kms.ban;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One ban record that might be about the person being hired (B9).
 *
 * <p>Everything here is chosen so that the hiring admin ends up on the telephone rather than in front
 * of a verdict. The raising temple is <b>named</b>, and what they wrote is quoted in full, because
 * "ISKCON South Bengaluru recorded this, here is their account of it, here is their name" is
 * something an administrator can act on — ring them, ask, form a view — and "this person is flagged"
 * is not. {@link #signals()} says which details actually matched, because that is the first thing
 * anybody sensible asks and the difference between an identical PAN and a similar name is the
 * difference between a serious conversation and a coincidence.
 *
 * @param banId             the record, so a decision can be traced back to what it was about
 * @param raisingTempleName the temple that raised it, in the open, always
 * @param bannedName        the name they employed the person under, which may not be this one
 * @param account           what they wrote, verbatim and unsummarised
 * @param raisedOn          when. An old record is not a fresh one and should not read like one
 * @param signals           which details matched, in the order they are read out
 * @param exact             true when at least one signal was a value compared against itself
 */
public record BanFinding(
		UUID banId,
		String raisingTempleName,
		BanCategory category,
		String categoryLabel,
		String bannedName,
		String account,
		LocalDate raisedOn,
		List<MatchSignal> signals,
		List<String> signalLabels,
		boolean exact) {
}
