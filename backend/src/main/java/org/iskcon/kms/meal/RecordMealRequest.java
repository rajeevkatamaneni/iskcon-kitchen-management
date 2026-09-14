package org.iskcon.kms.meal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Record what actually went out at one meal (B5), when the signed job card comes back to the office.
 *
 * <p>One request for the whole meal, not one per dish. The person typing this has a sheet in front
 * of them with every dish on it, and the figure that matters — what actually went out, against what
 * was planned — is only worth collecting if collecting it takes one form and one press.
 *
 * <p><strong>No date, kind or event name any more (D-27).</strong> This used to carry all three,
 * because a meal that had never been recorded had no row of its own and those three texts were the
 * only way to say which meal was meant — which is how two unnamed events on one Saturday came to be
 * one recording. The meal has its own id now, and the path carries it.
 *
 * @param dishes every dish the meal currently has still to cook. Naming them all is required rather
 *               than optional: a dish left out of the request is a dish nobody said anything about,
 *               and quietly deciding on the office's behalf whether it was cooked is the one thing
 *               this form must not do.
 */
public record RecordMealRequest(
		/** Anything the office wants on the record — "ran short, sent out at 220". */
		@Size(max = 2000, message = "That note is too long.") String note,

		@Valid @NotEmpty(message = "Say what happened to every dish on the card.") List<DishRecord> dishes) {

	/**
	 * One dish as the card came back: how much was cooked, and how much of it was eaten.
	 *
	 * <p>Both are in the preparation's own yield unit, so the three figures the office reads down one
	 * row (planned, cooked, consumed) are the same kind of thing.
	 *
	 * @param dishId         which dish of the meal this row is for.
	 * @param actualServings how much was actually cooked — the figure stock is drawn against. Ignored,
	 *                       and stored as zero, when the dish was not made.
	 * @param consumedQuantity how much of it went out. Null where the card did not say; null is left
	 *                       as null rather than assumed equal to the cooked figure.
	 * @param notMade        the dish never went into a pot, so it draws nothing from stock.
	 */
	public record DishRecord(
			@NotNull(message = "Say which dish this row is for.") UUID dishId,
			BigDecimal actualServings,
			BigDecimal consumedQuantity,
			boolean notMade) {
	}
}
