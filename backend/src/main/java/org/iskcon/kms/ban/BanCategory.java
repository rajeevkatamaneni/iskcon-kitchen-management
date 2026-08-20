package org.iskcon.kms.ban;

import java.util.Arrays;
import java.util.List;

/**
 * Why a temple raised a ban (B9).
 *
 * <p>The reason a temple records is two things and needs both. This is the half that is
 * <em>comparable</em> — one temple's "theft" and another's "theft" mean the same thing on a list —
 * and the free text beside it is the half that carries the account of what actually happened. A
 * category alone is an allegation with nothing behind it; free text alone cannot be reasoned about
 * across two hundred temples. The service refuses either without the other (KMS-4010).
 *
 * <p><b>Deliberately no OTHER.</b> Every other controlled vocabulary in this product has one, and
 * this one must not: an OTHER bucket on a list whose entire purpose is comparability becomes the
 * list. The free text already carries anything these eight do not, and adding a ninth category a
 * temple genuinely needs is one line here — kept in Java rather than a CHECK constraint for exactly
 * that reason, as with {@code JobTitle} and {@code AuditAction}. The cost is real and is accepted
 * knowingly: an admin whose case sits between two of these has to choose the nearer one and explain
 * the rest in their own words.
 *
 * <p>Names are stored as text in {@code employment_bans.category}, so treat an existing name as
 * permanent — somebody may be reading a record raised years ago.
 */
public enum BanCategory {

	/** Taking money, stock or property. The commonest reason a kitchen dismisses anybody. */
	THEFT("Theft or misappropriation"),

	/** Books that do not add up: inflated invoices, kickbacks from a vendor, a fabricated payment. */
	FINANCIAL_IRREGULARITY("Financial irregularity"),

	/** Violence, or a credible threat of it, against anybody at the temple. */
	VIOLENCE_OR_THREATS("Violence or threats"),

	/** Harassment or abuse of a colleague, a devotee or a visitor. */
	HARASSMENT("Harassment or abuse"),

	/**
	 * Anything concerning the safety of a child. Its own category rather than folded into
	 * harassment, because a temple reading a finding needs to see this one without opening it.
	 */
	CHILD_SAFETY("Child safety concern"),

	/** Intoxication on duty — a live danger in a room full of fire, knives and hot oil. */
	INTOXICATION_ON_DUTY("Intoxication on duty"),

	/** A false name, forged references, a qualification that does not exist. */
	FALSIFIED_IDENTITY("Falsified identity or credentials"),

	/** Negligence that put people at risk — food safety abandoned, a gas line left open. */
	SERIOUS_NEGLIGENCE("Serious negligence putting people at risk");

	private final String label;

	BanCategory(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	public static List<BanCategory> all() {
		return Arrays.asList(values());
	}
}
