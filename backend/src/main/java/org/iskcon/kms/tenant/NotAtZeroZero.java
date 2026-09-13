package org.iskcon.kms.tenant;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A temple being added is not at latitude 0 and longitude 0 together (T-176).
 *
 * <h2>What it catches</h2>
 *
 * <p>Add a temple sent a blank coordinate box as {@code Number("")}, which is 0, and every other
 * rule let it through: {@code @NotNull} sees a value, {@code @DecimalMin}/{@code @DecimalMax} see a
 * value in range, and the column's CHECK agrees. The temple was saved in the Atlantic, about 600 km
 * south of Accra, and its Vaishnava calendar — sunrise, and so every tithi — was worked out from
 * there. It could not be put right afterwards either, because D-17 froze a temple's coordinates on
 * the edit path; the only repair was deleting the temple and adding it again. T-041 once found the
 * same defect by another route.
 *
 * <h2>Why the pair, and never one axis</h2>
 *
 * <p>0 on one axis is a real place: the equator runs through Kenya and Indonesia, the Greenwich
 * meridian through London and Accra. A rule that refused either zero would refuse a real temple.
 * Only the two together are provably wrong, because 0°N 0°E is open sea. {@code V97} made exactly
 * this argument for delivery pins and left single-zero rows alone for the same reason.
 *
 * <p>Compared by value, so {@code 0}, {@code 0.0} and {@code 0.000000} all count — a
 * {@code BigDecimal}'s scale is how it was written, not where it is.
 *
 * <h2>Why it is a class-level constraint that reports on {@code latitude}</h2>
 *
 * <p>No single field can see the pair, so the constraint sits on the record. A class-level failure
 * would normally arrive with no field name, and the screen shows field errors under the box they
 * name — so {@link NotAtZeroZeroValidator} files the violation against {@code latitude}, which makes
 * it an ordinary field error like every other one on the form. {@code GlobalExceptionHandler}
 * recognises this annotation and answers {@code KMS-400002} when it is the only thing wrong, the
 * same way it answers {@code KMS-400003} for a phone number.
 *
 * <p>{@code FieldErrorMessageTest} walks field annotations only, so it does not police this
 * message. It is written to the same rules anyway: a capital, a full stop, no jargon, twelve words
 * or fewer.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NotAtZeroZeroValidator.class)
public @interface NotAtZeroZero {

	String message() default "That puts the temple at 0, 0. Choose its real place.";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
