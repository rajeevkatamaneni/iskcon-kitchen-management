package org.iskcon.kms.payment;

import java.time.Instant;

/**
 * A temple's payment gateway as its administrator sees it (E7).
 *
 * <p>Everything here is safe to show: the provider, the key id — which is public by design, since it
 * is handed to the browser to open checkout — the webhook URL their provider must call, and two
 * dates. The key secret is not here and no endpoint returns it. The webhook secret is fetched only
 * by its own audited reveal, because a temple cannot configure their provider without it.
 *
 * @param configured        whether this temple can take a payment at all
 * @param provider          the chosen provider, null until one is
 * @param keyId             the provider's public key id
 * @param keySecretSavedAt  when a key secret was last stored, so the screen can say "saved 10 Aug"
 * @param webhookUrl        the address this temple's provider must be told to call
 * @param verifiedAt        when the credentials last answered the provider
 * @param webhookSeenAt     when a correctly signed webhook last arrived — the only proof the return
 *                          path works, and a different question from {@code verifiedAt}
 */
public record TenantPaymentSettings(
		boolean configured,
		String provider,
		String keyId,
		Instant keySecretSavedAt,
		String webhookUrl,
		Instant verifiedAt,
		Instant webhookSeenAt) {

	/** A temple that has not set anything up yet. */
	public static TenantPaymentSettings none() {
		return new TenantPaymentSettings(false, null, null, null, null, null, null);
	}
}
