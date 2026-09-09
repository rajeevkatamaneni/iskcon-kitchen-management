package org.iskcon.kms.shift;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Marking who actually turned up to one shift (B7), behind {@code MANAGE_VOLUNTEER_SHIFTS}.
 *
 * <p>The whole roster in one call rather than one call per volunteer. A coordinator marks
 * attendance standing in front of the crew with a list, and marking one at a time would make a
 * half-marked shift a normal intermediate state — which nothing downstream could interpret, since
 * an unmarked signup and an absent one are deliberately different facts.
 *
 * <p><strong>{@code attended} is a boxed {@link Boolean} with {@code @NotNull}, and that is not
 * decoration.</strong> A primitive {@code boolean} deserialises an absent JSON key to {@code false}
 * without complaint, so a client that sent {@code {"userId": "…"}} would silently record a no-show
 * against somebody who came. Boxed and required, the same payload is refused as a validation
 * failure. This is the shape wave 4c was bitten by twice, and it costs nothing to get right here.
 *
 * <p>Somebody left out of {@code marks} is left <em>unmarked</em>, not marked absent — see
 * {@code shift_signups.attended}, whose third state exists for exactly that.
 */
public record RecordAttendanceRequest(@NotEmpty @Valid List<Mark> marks) {

	/** One volunteer, and whether they came. */
	public record Mark(@NotNull UUID userId, @NotNull Boolean attended) {
	}
}
