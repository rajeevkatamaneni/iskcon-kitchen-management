package org.iskcon.kms.tenant;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.math.BigDecimal;

/**
 * Refuses a temple being added at latitude 0 and longitude 0 together. See {@link NotAtZeroZero}
 * for why the pair and never one axis.
 *
 * <p>Typed to {@link ProvisionTenantRequest} rather than to some "has coordinates" interface. It
 * is the only request that creates a temple's coordinates — the edit path cannot change them
 * (D-17) — and an abstraction for a second case nobody can name would be a guess.
 */
public class NotAtZeroZeroValidator implements ConstraintValidator<NotAtZeroZero, ProvisionTenantRequest> {

	/** The property the refusal is filed against, so the screen shows it under the Latitude box. */
	static final String REPORTED_ON = "latitude";

	@Override
	public boolean isValid(ProvisionTenantRequest request, ConstraintValidatorContext context) {
		if (request == null || request.latitude() == null || request.longitude() == null) {
			// A missing coordinate is @NotNull's to answer, with "Enter the temple's latitude."
			// Saying "that puts the temple at 0, 0" to somebody who typed nothing would be untrue.
			return true;
		}

		// compareTo, never equals: BigDecimal.equals counts scale, so 0.000000 would not equal 0
		// and the exact shape the database stores a coordinate in would slip through.
		boolean atZeroZero = request.latitude().compareTo(BigDecimal.ZERO) == 0
				&& request.longitude().compareTo(BigDecimal.ZERO) == 0;
		if (!atZeroZero) {
			return true;
		}

		// Re-filed against latitude, so it arrives as a field error beside the box rather than as
		// an object error with no box to sit under. Only latitude: the refusal is of the pair, and
		// the same sentence under both boxes would say one thing twice. Latitude is the first of
		// the two on the form, which is where a person reads from.
		context.disableDefaultConstraintViolation();
		context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
				.addPropertyNode(REPORTED_ON)
				.addConstraintViolation();
		return false;
	}
}
