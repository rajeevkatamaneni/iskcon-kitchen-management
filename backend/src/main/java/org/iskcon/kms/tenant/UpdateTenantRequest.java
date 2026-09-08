package org.iskcon.kms.tenant;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * What a temple can be corrected to after it has been provisioned (T-008, docket A1 + A2).
 *
 * <p>Until this existed, {@link ProvisionTenantRequest} was the only way any of these fields were
 * ever written: a temple provisioned with a misspelled name could be fixed only by deleting it and
 * starting again, and {@code is_80g_approved} — a legal status a donation receipt quotes — could
 * never be recorded at all after the insert.
 *
 * <p><strong>Only three of these seven fields may actually change</strong> — {@code name},
 * {@code address} and {@code is80gApproved}. {@code latitude}, {@code longitude}, {@code timezone}
 * and {@code currency} were frozen by {@code docs/work/DECISIONS.md} D-17, and
 * {@link TenantUpdateService#rejectFrozenFieldChanges} refuses any request whose value for one of
 * them differs from what is stored. They are nevertheless <em>required</em> here, for the same
 * reason {@code slug} is declared below: a whole-record replacement means the caller sends the
 * temple as it is, and a field silently absent would be indistinguishable from a field silently
 * dropped. Removing them from this record instead would let a caller who did send a new latitude be
 * told the save succeeded, with the temple exactly where it was — Jackson discards an unknown
 * property without a word. Declared and refused, that caller gets an answer it can read.
 *
 * <p><strong>Deliberately not a partial of the provisioning request.</strong> Three groups of its
 * fields are absent, each for its own reason:
 *
 * <ul>
 *   <li>The administrator's name, email and phone describe a <em>person</em>, not a temple. They
 *       are corrected on that person's own record, where the audit trail says whose details
 *       changed rather than that "the temple changed".
 *   <li>{@code locale} is the temple's own choice of language and already has a home the temple
 *       itself reaches, on its settings. An operator does not pick a temple's language.
 *   <li>{@code slug} is {@code updatable=false} on {@link Tenant} and stays that way — see below.
 * </ul>
 *
 * <p><strong>Why {@code slug} is nevertheless declared here.</strong> Spring Boot leaves Jackson's
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} off, so a client that sent a slug would have it silently
 * dropped and would be told the save succeeded — with the web address unchanged and nothing
 * anywhere saying why. Naming the field is what turns that silence into a refusal the caller can
 * read. It is not part of the request's public shape and {@code frontend/lib/api.ts}'s
 * {@code UpdateTenantInput} does not carry it; anything that arrives in it is a mistake, and this
 * record's job is to say so out loud.
 *
 * <p>Validation messages are user-facing and follow the same rules as the error codes: plain
 * language, and they say what to do rather than what is wrong. They are copied from
 * {@link ProvisionTenantRequest} verbatim on purpose — a latitude rejected on the correction screen
 * should be rejected in the same words it was rejected in on the form that created the temple.
 *
 * <p>Every field is required. This is a whole-record replacement rather than a patch of the fields
 * somebody happened to touch: the screen loads the temple as it is and sends all of it back, so a
 * field omitted by a caller is a caller bug, and treating an omission as "leave it alone" would
 * make an accidentally-dropped {@code is80gApproved} indistinguishable from a deliberate one. The
 * method is {@code PATCH} because it addresses a subset of the row's columns — the slug, the
 * locale and the timestamps are not the caller's to send — not because the body is optional.
 */
public record UpdateTenantRequest(
		@NotBlank(message = "Enter the temple's name.")
		@Size(max = 200, message = "That name is too long.")
		String name,

		@Size(max = 500, message = "That address is too long.")
		String address,

		@NotNull(message = "Enter the temple's latitude.")
		@DecimalMin(value = "-90", message = "Latitude must be between -90 and 90.")
		@DecimalMax(value = "90", message = "Latitude must be between -90 and 90.")
		BigDecimal latitude,

		@NotNull(message = "Enter the temple's longitude.")
		@DecimalMin(value = "-180", message = "Longitude must be between -180 and 180.")
		@DecimalMax(value = "180", message = "Longitude must be between -180 and 180.")
		BigDecimal longitude,

		@NotBlank(message = "Choose the temple's timezone.")
		String timezone,

		@NotBlank(message = "Choose a currency.")
		@Pattern(regexp = "^[A-Z]{3}$", message = "Use a three-letter currency code, for example INR.")
		String currency,

		@NotNull(message = "Say whether this temple is approved for 80G receipts.")
		Boolean is80gApproved,

		/**
		 * Never sent, and refused rather than ignored when it is. See the class note: the point of
		 * the field is that a slug arriving here fails loudly instead of vanishing.
		 */
		String slug) {

	/** True when the caller tried to change the one field that is not theirs to change. */
	public boolean carriesSlug() {
		return slug != null && !slug.isBlank();
	}
}
