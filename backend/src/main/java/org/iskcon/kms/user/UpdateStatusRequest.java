package org.iskcon.kms.user;

import jakarta.validation.constraints.NotBlank;

/** A request to disable or re-enable a user: the target status (ACTIVE or DISABLED). */
public record UpdateStatusRequest(
		@NotBlank(message = "A status is required.") String status) {
}
