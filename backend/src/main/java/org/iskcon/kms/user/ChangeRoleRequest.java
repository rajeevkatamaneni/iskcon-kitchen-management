package org.iskcon.kms.user;

import jakarta.validation.constraints.NotBlank;

/**
 * The body of a role-change request: the role to assign.
 *
 * <p>Carried as a string rather than the {@link User.Role} enum on purpose. Binding straight to
 * the enum turns an unrecognised value into a deserialization failure that surfaces as a generic
 * 500; taking a string lets the service reject it as ordinary validation with a quotable code.
 */
public record ChangeRoleRequest(
		@NotBlank(message = "A role is required.") String role) {
}
