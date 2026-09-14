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
 * <p>The office typed 400 and 640 went out. Rajeev settled the shape on 2026-09-07 — <strong>not a
 * reopening but a correction</strong>. The original recording is never overwritten as a record; the
 * stock ledger keeps every draw it made and gains the reversals beside them, and each dish keeps the
 * figure it was first given (V106).
 *
 * <p><strong>Why this mirrors {@link RecordMealRequest} instead of sending a delta.</strong> A
 * correction restates the whole meal, dish by dish, exactly as the recording did. A delta would need
 * the client and the server to agree beforehand about what the current figures are — and a meal is a
 * <em>set</em> of stock movements across (ingredient, batch) draws. Restating the whole meal means the
 * server works the difference out from what it holds, which is the only copy either side should trust.
 *
 * <p>Addressed by the meal's id in the path, like recording (D-27).
 *
 * @param note   why the figures are being changed. Required and non-empty, at the endpoint and again
 *               in {@code meals_correction_shape}: a correction with no reason is unreadable a month
 *               later, which is exactly when somebody asks.
 * @param dishes every dish the recording spoke about. Naming them all is required, for the same
 *               reason it is when recording.
 */
public record CorrectMealRequest(
		@NotBlank(message = "Say what is being corrected and why.")
		@Size(max = 2000, message = "That note is too long.")
		String note,

		@Valid @NotEmpty(message = "Choose at least one dish to correct.") List<DishCorrection> dishes) {

	/**
	 * One dish as it should have been recorded. Identical in shape to
	 * {@link RecordMealRequest.DishRecord} and validated by the same two methods, so a figure refused
	 * when recording is refused when correcting.
	 *
	 * @param dishId           which dish of the meal this correction is for.
	 * @param actualServings   how much was actually cooked; the figure stock is re-drawn against.
	 * @param consumedQuantity how much of it went out. Null is kept as null.
	 * @param notMade          the dish never went into a pot. Correcting a dish <em>to</em> this
	 *                         reverses everything it drew; correcting one <em>out of</em> it draws
	 *                         stock for the first time.
	 */
	public record DishCorrection(
			@NotNull(message = "Say which dish this correction is for.") UUID dishId,
			BigDecimal actualServings,
			BigDecimal consumedQuantity,
			boolean notMade) {
	}
}
