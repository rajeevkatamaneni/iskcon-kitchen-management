package org.iskcon.kms.payment;

/**
 * Builds a {@link PaymentGateway} for one temple's own credentials (E7).
 *
 * <p>The gateway used to be a singleton chosen once at startup, which was right while the platform
 * had one merchant account. A temple collects into its own account now, so the provider is a
 * property of the temple and the client has to be made from that temple's keys.
 */
public interface PaymentGatewayFactory {

	/** The provider this builds for: matches {@code tenant_settings.payment_provider}. */
	String provider();

	PaymentGateway forCredentials(String keyId, String keySecret);
}
