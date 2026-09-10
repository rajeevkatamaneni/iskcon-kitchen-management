package org.iskcon.kms.equipment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Bring a scrapped machine back onto the register, as the condition it comes back in, with the
 * reason it came back (D-15).
 *
 * <p>Shaped exactly like {@link ChangeConditionRequest} — a condition and a reason of at most 500
 * characters — and deliberately a second type rather than a flag on that one. The guard in
 * {@code changeCondition} is the thing that keeps a scrapped item inert, and a request body that
 * could switch that guard off would be a guard in name only. Two request types means the refusal
 * stays unconditional in the code that enforces it.
 *
 * <p>The reason is required for the same reason it is required on every other state change, and
 * more so here: Rajeev asked for a reinstatement to be visible in the audit trail, and a
 * reinstatement with no why is a row that answers nothing when somebody reads it in a year.
 */
public record ReinstateEquipmentRequest(
		@NotNull(message = "Choose the condition it is back in.") EquipmentCondition condition,
		@NotBlank(message = "Say why it is being brought back into use.")
		@Size(max = 500, message = "That reason is too long.")
		String reason) {
}
