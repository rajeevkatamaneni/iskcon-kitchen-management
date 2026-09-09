package org.iskcon.kms.meal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Correct what a recorded meal actually served (T-007, docket S5/M2).
 *
 * <p>The office typed 400 and 640 went out. Until this existed that was permanent: recording was a
 * one-way door, and the store room stayed drawn against a figure everybody in the building knew was
 * wrong. Rajeev settled the shape on 2026-09-07 — <strong>not a reopening but a correction</strong>.
 * The original recording is never overwritten as a record; the stock ledger keeps every draw it made
 * and gains the reversals beside them, and each dish keeps the figure it was first given (V106).
 *
 * <p><strong>Why this mirrors {@link RecordMealRequest} instead of sending a delta.</strong> A
 * correction restates the whole meal, dish by dish, exactly as the recording did. A delta would need
 * the client and the server to agree beforehand about what the current figures are — and a meal is a
 * <em>set</em> of stock movements across (ingredient, batch) draws, which is precisely the thing
 * nobody could enumerate before this task. Restating the whole meal means the server can work the
 * difference out from what it holds, which is the only copy either side should trust.
 *
 * <p><strong>Why there is no {@code planDate} / {@code mealKind} / {@code eventName} here.</strong>
 * {@code RecordMealRequest} carries all three because a meal that has never been recorded has no row
 * of its own to point at. A meal that <em>has</em> been recorded always does, so this is addressed by
 * that row's id in the path and the identity cannot be ambiguous — which is also why V89's
 * three-part key, and the event-name trap that key exists for, cannot bite here.
 *
 * @param note   why the figures are being changed. Required and non-empty, at the endpoint and again
 *               in {@code meal_services_correction_shape}: a correction with no reason is unreadable
 *               a month later, which is exactly when somebody asks. Unlike the recording note, which
 *               is optional — recording says what happened, correcting says why what we said was
 *               wrong, and only the second is meaningless without a sentence.
 * @param dishes every dish the recording spoke about. Naming them all is required rather than
 *               optional, for the same reason it is when recording: a dish left out is a dish nobody
 *               said anything about, and quietly deciding on the office's behalf that it was
 *               unchanged is the one thing this form must not do.
 */
public record CorrectMealRequest(
		@NotBlank @Size(max = 2000) String note,

		@Valid @NotEmpty List<DishCorrection> dishes) {

	/**
	 * One dish as it should have been recorded: how much was cooked, and how much of it was eaten.
	 *
	 * <p>Identical in shape to {@link RecordMealRequest.DishRecord} and validated by the same two
	 * methods, so that a figure refused when recording is refused when correcting and the office
	 * cannot get a number in through the second door that the first one turned away.
	 *
	 * @param actualServings   how much was actually cooked, in the preparation's own yield unit. This
	 *                         is the figure stock is re-drawn against. Ignored — and stored as zero —
	 *                         when the dish was not made after all.
	 * @param consumedQuantity how much of it went out. Null is kept as null: a card that did not say
	 *                         what came back is not a card saying nothing came back.
	 * @param notMade          the dish never went into a pot. Correcting a dish <em>to</em> this
	 *                         reverses everything it drew and leaves nothing in its place; correcting
	 *                         one <em>out of</em> it draws stock for the first time.
	 */
	public record DishCorrection(
			@NotNull UUID mealPlanId,
			BigDecimal actualServings,
			BigDecimal consumedQuantity,
			boolean notMade) {
	}
}
