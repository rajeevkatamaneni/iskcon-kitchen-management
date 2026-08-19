package org.iskcon.kms.staff;

/**
 * One entry of the hire form's job-title picklist (E6-S8), served by the API so the vocabulary and
 * its suggested access live in one place rather than being retyped in TypeScript and drifting.
 */
public record JobTitleOption(
		JobTitle value,
		String label,
		JobTitle.Group group,
		/** What the form should pre-select for access, or null for a job that needs no login. */
		SystemAccess suggestedAccess) {
}
