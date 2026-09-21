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

	/**
	 * Whether <em>reading</em> one is an event worth an audit row.
	 *
	 * <p>The policy lives on the kind rather than in the service so that a fourth kind cannot be
	 * added without somebody deciding this, which is the same reason the class note above says a
	 * fourth kind is a decision taken here.
	 *
	 * <p><b>A photograph is not.</b> Ruled by Rajeev, 2026-09-21. The portrait at the top right of a
	 * staff record is fetched the moment the page renders — it cannot be a plain {@code <img src>},
	 * because the bytes only come back from an endpoint that checks the permission with the token in
	 * a header. So every open of a record with a photo was writing "X's photo was opened", when
	 * nobody opened it and the page drew it. Those rows would be almost everything this action ever
	 * held, and the one row it exists for — somebody deliberately opening an Aadhaar card — would be
	 * buried in them.
	 *
	 * <p><b>What this does not change.</b> Attaching, replacing and removing a photograph stay
	 * audited, for every kind, and Rajeev asked for that explicitly on the same day: "Uploading a
	 * Photo or changing a photo should be audited." A replacement is recorded as a removal and an
	 * addition, because that is what happened. Only the <em>read</em> of a photograph is exempt.
	 */
	public boolean readIsAnEvent() {
		return this != PHOTO;
	}

	/** What the screens call it. Kept beside the vocabulary so there is one set of words. */
	public String label() {
		return switch (this) {
			case PHOTO -> "Photo";
			case PAN_SCAN -> "PAN card";
			case AADHAAR_SCAN -> "Aadhaar card";
		};
	}
}
