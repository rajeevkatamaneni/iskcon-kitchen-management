package org.iskcon.kms.profile;

import java.time.Instant;

/**
 * A user's own account as they see it on the profile screen.
 *
 * <p>Contact details are shown but not editable here (E1-S8): changing a phone needs a fresh OTP
 * and changing an email collides with the sign-in identity, so both are a later increment. What
 * this screen changes is the preferred channel and consent.
 */
public record ProfileView(
		String fullName,
		String email,
		String phone,
		String preferredChannel,

		// When and to which wording the person last consented; null until they do.
		Instant consentAt,
		String consentVersion,

		// True when consent is missing or was given against an older wording — the cue for the
		// screen to show the consent prompt. A soft gate: it never blocks using the app, only
		// governs whether notifications (E1-S10) may be sent.
		boolean consentNeeded,
		String currentConsentVersion,
		String consentText,

		String role) {
}
