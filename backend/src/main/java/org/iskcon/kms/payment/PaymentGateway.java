package org.iskcon.kms.payment;

import java.util.Map;

/**
 * The one seam between this application and whichever payment provider a deployment uses (E7). The
 * donation stories depend on this interface, never on Razorpay directly, so swapping providers — or
 * running a different provider per temple in the future — is a matter of adding one implementation
 * and selecting it by config, not touching the donation logic.
 *
 * <p>Only outbound calls live here (creating an order the hosted checkout will collect against). We
 * never handle raw card/UPI credentials — the provider's hosted checkout does — and money only ever
 * flows <em>in</em>; the system never moves temple funds out. Inbound confirmation arrives as
 * signature-verified webhooks, handled by {@code PaymentWebhookService}.
 *
 * <p>Implementations: {@code StubPaymentGateway} (default, keeps tests hermetic) and
 * {@code RazorpayPaymentGateway} (selected with {@code kms.payments.provider=razorpay}).
 */
public interface PaymentGateway {

	/** Short provenance name recorded on donations: 'stub', 'razorpay', … */
	String name();

	/** The publishable key the client opens hosted checkout with (never the secret). */
	String publicKey();

	/**
	 * Creates an order to collect the given amount, returning the provider's order id for the client
	 * to open checkout against. {@code notes} are provider metadata (we put the donation id there) so
	 * a webhook can be tied back to the local record.
	 */
	PaymentOrder createOrder(long amountMinorUnits, String currency, String receipt, Map<String, String> notes);

	/** The provider's view of a payment, for the daily reconciliation (E7-S9). */
	PaymentStatus fetchPaymentStatus(String paymentId);

	/** Creates a recurring subscription/mandate at the given frequency (E7-S3). */
	SubscriptionResult createSubscription(String frequency, long amountMinorUnits, String currency,
			Map<String, String> notes);

	/** Cancels a subscription's mandate so no further cycles charge (E7-S3). */
	void cancelSubscription(String subscriptionId);

	/** What the provider says a payment is. {@code UNKNOWN} covers "not found" — a reconciliation flag. */
	enum PaymentStatus { CAPTURED, FAILED, UNKNOWN }
}
