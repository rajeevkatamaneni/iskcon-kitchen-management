package org.iskcon.kms.staff;

/**
 * Whether someone still works here, and if not, how that ended (E6-S8).
 *
 * <p>This replaced a boolean. A boolean cannot tell a resignation from a dismissal, and that
 * difference is precisely what an admin looks back for — it is also the fact BL-6 would carry to
 * other temples if that work is ever picked up.
 */
public enum EmploymentStatus {

	ACTIVE("Active"),
	RESIGNED("Resigned"),
	TERMINATED("Dismissed"),
	CONTRACT_ENDED("Contract ended");

	private final String label;

	EmploymentStatus(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	public boolean isFormer() {
		return this != ACTIVE;
	}
}
