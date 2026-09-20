package org.iskcon.kms.staff;

/**
 * What a file attached to a staff record is (T-428). The same three values as V155's
 * {@code staff_documents_kind_valid}, and one of each per person.
 *
 * <p>A closed vocabulary of three and deliberately not a free-text label. "Papers" grows without a
 * limit if anybody may name a new kind from the form — and the two identity documents here need a
 * name the server can reason about, because the audit entry for opening one says which kind it was.
 * A fourth kind is a decision, taken here, not something a screen invents.
 */
public enum StaffDocumentKind {

	/** The person's photograph, shown at the top of their record. */
	PHOTO,

	/** A scan or photograph of their PAN card. */
	PAN_SCAN,

	/**
	 * A scan or photograph of their Aadhaar card.
	 *
	 * <p>Rajeev asked for this knowing exactly what it is — 2026-09-20: "I know… we are not supposed
	 * to legally store aadhaar card…. This is india… Rules are there to be broken…". So it is built,
	 * and it is built as the most sensitive thing this application holds: behind MANAGE_STAFF, read
	 * only through an endpoint that streams it, audited on every open, and never reachable by a URL
	 * anybody can pass on. What it does <em>not</em> have is encryption at rest beyond the bucket's
	 * own — see V155's header, which says so in as many words so that the risk is Rajeev's to judge
	 * and not something a comment quietly implies has been handled.
	 */
	AADHAAR_SCAN;

	/** What the screens call it. Kept beside the vocabulary so there is one set of words. */
	public String label() {
		return switch (this) {
			case PHOTO -> "Photo";
			case PAN_SCAN -> "PAN card";
			case AADHAAR_SCAN -> "Aadhaar card";
		};
	}
}
