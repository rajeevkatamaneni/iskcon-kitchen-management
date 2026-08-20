package org.iskcon.kms.ban;

/**
 * Which detail of a person matched a ban record (B9).
 *
 * <p>A finding has to be able to say <em>why</em> it is a finding, because the hiring admin is going
 * to act on it. "This might be the same person" is not something anybody can pick up a phone about;
 * "the PAN is identical" and "the name is close and the address is the same" are.
 *
 * <p>{@link #exact()} separates the two kinds. An exact signal is a comparison of one value against
 * the same value — the same PAN produces the same fingerprint everywhere, so a match on it is a
 * match on the person. A fuzzy signal is a score against a threshold and can be wrong. Nothing
 * blocks a hire either way; the distinction changes only how a finding is described, and a phone
 * number is a deliberate reminder of why: it is compared exactly and is still not proof of who
 * anybody is, because numbers get reassigned.
 */
public enum MatchSignal {

	PAN("PAN", true),
	AADHAAR("Aadhaar name, date of birth and last four digits", true),
	PHONE("Phone number", true),
	NAME("Name", false),
	ADDRESS("Address", false);

	private final String label;
	private final boolean exact;

	MatchSignal(String label, boolean exact) {
		this.label = label;
		this.exact = exact;
	}

	public String label() {
		return label;
	}

	public boolean exact() {
		return exact;
	}
}
