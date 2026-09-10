package org.iskcon.kms.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body of a correction: why the original movement was wrong. Required — a ledger correction that
 * doesn't say why it happened is exactly the kind of unexplained change the append-only design
 * exists to prevent.
 */
public record CompensateRequest(
		@NotBlank(message = "Say what is being put right.")
		@Size(max = 500, message = "That note is too long.")
		String note) {
}
