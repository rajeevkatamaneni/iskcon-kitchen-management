package org.iskcon.kms.staff;

/**
 * Why a payment was made (B8).
 *
 * <p>Two values rather than one, for a single reason worth the extra field: the termination screen
 * has to name the <em>last salary payment</em> — "last recorded payment 31 July; terminating 12
 * September" — and it is being shown at the moment a settlement is about to be recorded. Without the
 * distinction, the settlement the admin is in the middle of entering would become the answer to the
 * question it was meant to inform.
 */
public enum PaymentPurpose {

	/** Pay for work done. */
	SALARY("Salary"),

	/** The figure agreed when somebody leaves, typed by the admin rather than worked out here. */
	SETTLEMENT("Final settlement");

	private final String label;

	PaymentPurpose(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}
}
