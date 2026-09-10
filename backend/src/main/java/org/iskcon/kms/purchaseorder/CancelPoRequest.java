package org.iskcon.kms.purchaseorder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Cancel a purchase order (E5-S3). A reason is required.
 *
 * <p><strong>{@code vendorAbandoned} is the tick box "Vendor Never Delivered this Order"</strong>
 * (T-124, Rajeev's wording of 2026-09-09). It is the one new fact anybody has to enter for the
 * whole of that task, and it is the difference between the two things a cancellation can mean: the
 * temple changed its mind, or the supplier never came. The first is nobody's failure and is counted
 * nowhere on the vendor scorecard; the second scores that vendor 0% on-time and names them as a
 * no-show.
 *
 * <p><strong>The reason stays required either way.</strong> The box carries the fact, the sentence
 * carries the story — and a permanent mark against a supplier with no explanation beside it is
 * exactly the record somebody will want to read back in a year and be unable to.
 *
 * <p><strong>A primitive boolean, so an absent field means false, and that is deliberate here.</strong>
 * Wave 4c's T-044 is the standing warning about booleans that deserialise an absent key to false —
 * a client that forgets the field type-checks and the value arrives as a silent {@code false}. That
 * warning is about fields where false is a claim. This one is the opposite: false is the absence of
 * a claim. Every caller that does not mention the box — an older client, a script, a test written
 * before T-124 — gets the reading that blames nobody, which is the only safe direction for a
 * permanent statement about somebody else's business.
 */
public record CancelPoRequest(
		@NotBlank @Size(max = 500) String reason,
		boolean vendorAbandoned) {
}
