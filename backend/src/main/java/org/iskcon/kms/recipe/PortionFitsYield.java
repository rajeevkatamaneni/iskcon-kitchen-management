package org.iskcon.kms.recipe;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A recipe's portion is in the same family of unit as the recipe itself (T-218): a recipe measured
 * in kilos takes a portion in Kg or gm, one in litres a portion in L or ml, one in pieces a portion
 * in pieces.
 *
 * <h2>What it catches</h2>
 *
 * <p>The recipe form let the two units be chosen independently, so nothing stopped a recipe in
 * kilos with a portion in millilitres. There is no honest way to turn that pair into a head count —
 * a volume into a mass needs a density nobody has recorded — and the planner (T-217) either
 * guesses or refuses. The form now offers only the units that fit; this is the same rule for a raw
 * POST, a screen written later, or a stale browser tab that still has the old dropdown.
 *
 * <h2>Why a class-level constraint that reports on {@code perHeadUnit}</h2>
 *
 * <p>No single field can see the pair, so it sits on the record, and {@link PortionFitsYieldValidator}
 * files the refusal against {@code perHeadUnit} so it arrives as an ordinary field error —
 * {@code KMS-400001} with a {@code fieldErrors} entry — the pattern {@code NotAtZeroZero} set for
 * a temple's coordinates. The portion is where it is filed because the portion is what changes to
 * fit: the form clears the portion unit when the recipe's own unit changes, not the other way round.
 *
 * <p>Carried by both {@link CreateRecipeRequest} and {@link UpdateRecipeRequest} through
 * {@link YieldAndPortion}, which is the two of them and no imagined third.
 *
 * <p>The message is built by the validator, because it names the units that would fit, so the
 * default below is only what a caller sees if that ever stops happening.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PortionFitsYieldValidator.class)
public @interface PortionFitsYield {

	String message() default "Choose a portion unit that fits what the recipe is measured in.";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
