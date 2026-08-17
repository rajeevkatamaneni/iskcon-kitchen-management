package org.iskcon.kms.payment;

import java.util.List;

/**
 * A provider that will let us register our own webhook, so an administrator does not have to (E7).
 *
 * <p>Registering the webhook by hand is the step of setting up payments most likely to be got wrong,
 * and it fails silently: the temple takes money and never records it, because the only thing that
 * confirms a donation is the callback nobody configured. We know the URL, we generated the secret,
 * and we know which events we handle — so where a provider permits it, we should do it ourselves
 * rather than write instructions and hope.
 *
 * <p>Deliberately a separate capability rather than a method on {@link PaymentProviderProbe},
 * because providers genuinely differ and a default implementation would have to lie. Razorpay's
 * webhook API is a <em>partner</em> API, keyed by a sub-merchant account id and available to
 * platforms that onboard merchants beneath them — a temple's own merchant credentials cannot call
 * it. So {@code RazorpayProbe} does not implement this, and the Settings screen shows that temple
 * the manual steps instead. Stripe permits an account to create its own webhook endpoints, so its
 * adapter will.
 *
 * <p>Registration is best-effort by construction: a provider that refuses must never prevent an
 * administrator storing credentials that are otherwise perfectly good. The manual path stays.
 */
public interface WebhookRegistrar {

	/**
	 * Registers, or brings up to date, the webhook this application needs.
	 *
	 * <p>Must be idempotent for a given {@code url}: an administrator correcting a typo in their key
	 * id saves again, and that must not leave the provider holding two webhooks for the same address.
	 *
	 * @param keyId     the credentials to register with — the same pair just verified
	 * @param keySecret the credentials to register with
	 * @param url       this temple's webhook address
	 * @param secret    the secret the provider must sign its deliveries with — ours, so the signature
	 *                  check on the way back in can succeed
	 * @param events    the event types we handle; a provider sending more is wasteful, fewer is broken
	 * @throws PaymentProviderProbe.PaymentCredentialsRejected when the provider refused to register it
	 */
	void registerWebhook(String keyId, String keySecret, String url, String secret, List<String> events);
}
