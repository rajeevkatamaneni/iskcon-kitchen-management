package org.iskcon.kms.payment;

/**
 * Asks a provider whether a temple's credentials are real, without opening a checkout (E7).
 *
 * <p>This is the Test button on the Settings screen, and it is deliberately narrow: it proves the
 * outbound half of the connection — that the key and secret reach the provider and are accepted. It
 * cannot prove the inbound half, because that requires the provider to call us, and no button an
 * administrator presses can make that happen. The screen reports those two as separate facts for
 * exactly this reason.
 */
public interface PaymentProviderProbe {

	/** The provider this probe speaks for: matches {@code tenant_settings.payment_provider}. */
	String provider();

	/**
	 * Reaches the provider with these credentials.
	 *
	 * @throws PaymentCredentialsRejected when the provider refuses them, or cannot be reached
	 */
	void verify(String keyId, String keySecret);

	/** The provider would not accept these credentials, with a reason fit to show an administrator. */
	class PaymentCredentialsRejected extends RuntimeException {
		public PaymentCredentialsRejected(String message, Throwable cause) {
			super(message, cause);
		}

		public PaymentCredentialsRejected(String message) {
			super(message);
		}
	}
}
