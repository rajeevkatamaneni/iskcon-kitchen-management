package org.iskcon.kms.staff;

/** How someone is engaged (E6-S8). Payroll is out of scope; this is what the record states. */
public enum EmploymentType {

	FULL_TIME("Full-time"),
	PART_TIME("Part-time"),
	CONTRACT("Contract");

	private final String label;

	EmploymentType(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}
}
