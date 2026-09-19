package org.iskcon.kms.recipe;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.iskcon.kms.ingredient.Unit;

/**
 * Refuses a portion unit from a different family to the recipe's own. See {@link PortionFitsYield}.
 *
 * <p>Keyed on {@link Unit#family()}, the one statement of which units convert into which. The
 * frontend's {@code portionUnitsFor} is built on the same families and offers exactly the units this
 * accepts.
 */
public class PortionFitsYieldValidator implements ConstraintValidator<PortionFitsYield, YieldAndPortion> {

	/** The property the refusal is filed against, so it arrives as a field error on the portion. */
	static final String REPORTED_ON = "perHeadUnit";

	@Override
	public boolean isValid(YieldAndPortion request, ConstraintValidatorContext context) {
		if (request == null) {
			return true;
		}
		// A blank portion is allowed and stays allowed: the planner asks where there is none. A
		// missing or unknown recipe unit is somebody else's refusal to make — @NotBlank's, or
		// RecipeService.parseYieldUnit's — and a second sentence about the portion on top of it
		// would send the reader to the wrong box.
		Unit yield = parse(request.baseYieldUnit());
		Unit portion = parse(request.perHeadUnit());
		if (yield == null || portion == null || yield.family() == portion.family()) {
			return true;
		}

		// The sentence names the units that would fit, because "that does not fit" on its own
		// leaves the reader to work out what does. No braces or dollar signs can reach it — every
		// word comes from Unit's own labels — so the message interpolator has nothing to expand.
		String fits = Arrays.stream(Unit.values())
				.filter(u -> u.family() == yield.family())
				.map(Unit::label)
				.collect(Collectors.joining(" or "));
		context.disableDefaultConstraintViolation();
		context.buildConstraintViolationWithTemplate(
						"A recipe measured in " + yield.label() + " takes its portion in " + fits + ".")
				.addPropertyNode(REPORTED_ON)
				.addConstraintViolation();
		return false;
	}

	private static Unit parse(String unit) {
		if (unit == null || unit.isBlank()) {
			return null;
		}
		try {
			return Unit.valueOf(unit);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
