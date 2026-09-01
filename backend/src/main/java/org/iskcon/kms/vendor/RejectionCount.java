package org.iskcon.kms.vendor;

/**
 * How many delivery lines this vendor had refused for one reason, over the reported period (E5-S9).
 *
 * <p>Lines, not quantities. A rejection of two sacks of rice and a rejection of forty litres of oil
 * are both one line each; adding 2 to 40 would produce a number in no unit at all. The count answers
 * the question the reasons were kept for — <em>how often does this supplier send us something we
 * cannot use, and what kind of thing is it</em> — and the receipt itself holds the amounts.
 *
 * @param reason one of {@code RejectReason}: DAMAGED, SPOILED, WRONG_ITEM, OTHER.
 */
public record RejectionCount(String reason, int lines) {
}
