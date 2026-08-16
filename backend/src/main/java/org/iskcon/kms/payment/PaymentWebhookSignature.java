package org.iskcon.kms.payment;

/**
 * How one provider proves a webhook came from it (E7-S9).
 *
 * <p>Verification used to be Razorpay's scheme hardcoded against one platform-wide secret, which was
 * right while the platform had one merchant account. Each temple signs with its own secret now, and
 * each provider signs differently — Razorpay puts a hex HMAC-SHA256 of the raw body in one header,
 * Stripe puts a timestamped, versioned signature in another. So the scheme belongs behind a port
 * alongside the gateway rather than baked into the controller.
 */
public interface PaymentWebhookSignature {

	/** The provider this speaks for: matches {@code tenant_settings.payment_provider}. */
	String provider();

	/** The header this provider signs in. */
	String header();

	/**
	 * Whether these exact bytes were signed with this secret.
	 *
	 * <p>Takes the raw body, never a parsed object: a signature is over the bytes as they arrived,
	 * and anything that re-serialises them first is verifying something the provider never signed.
	 */
	boolean isValid(byte[] rawBody, String headerValue, String secret);
}
