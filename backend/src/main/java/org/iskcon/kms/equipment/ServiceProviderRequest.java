package org.iskcon.kms.equipment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Add or edit a service provider (E3-S10 D7).
 *
 * <p>One record for both, unlike the create/update pair the equipment register uses, and for a
 * reason: there is nothing on a provider that may be set once and not changed afterwards. Where
 * those two shapes differ elsewhere in this codebase it is because something is immutable after
 * creation or because condition may not be edited as a field. Neither applies here, and two
 * identical records would be two places to keep in step.
 *
 * <p>Only the name is required. A temple may know the firm as "the man from the mixer shop" and have
 * nothing but a mobile number, and a list that refuses the row until it has an email address is a
 * list where the mobile number is written on the wall instead.
 */
public record ServiceProviderRequest(
		@NotBlank @Size(max = 200) String name,
		@Size(max = 40) String phone,
		@Size(max = 200) String email,
		@Size(max = 1000) String note) {
}
