package org.iskcon.kms.payment;

import java.util.Map;
import java.util.Optional;

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
 *
 * <p><strong>There is deliberately nothing here about subscriptions or mandates.</strong> Recurring
 * giving was moved out of Phase 1 whole on 2026-09-10 (T-111): a donor could start a recurring
 * charge and had no way to stop it from inside the application, because the screen that would have
 * cancelled it was blocked on a next-charge date the provider holds and we never stored. Two port
 * methods that nothing could reach are worse than two methods added back when the feature is
 * actually built, so they went with the rest of it. The Razorpay implementation of both — which is
 * the part with real knowledge in it — is in git history at the commit that removed this.
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

	/**
	 * Whether money was taken against an order, asked of the provider directly (E7-S2).
	 *
	 * <p>Confirmation normally arrives as a signed webhook, and that remains the only thing that can
	 * make a donation COMPLETED on its own. This exists for the case where the webhook never came —
	 * a dashboard where it was never registered, an outage, retries given up on — because the sweep
	 * that writes off abandoned checkouts would otherwise write off a gift the donor really made,
	 * leaving the money at the provider and no trace of it in the temple's books.
	 *
	 * <p>Empty means the provider says nothing has been paid. An implementation that cannot reach
	 * the provider must throw rather than return empty: "I could not ask" and "nothing was paid" are
	 * different answers, and only one of them makes it safe to write a donation off.
	 */
	Optional<CapturedPayment> findCapturedPayment(String orderId);

	/** A payment the provider has taken: its id, and how it was paid ('upi', 'card', …). */
	record CapturedPayment(String paymentId, String method) {
	}

	/** What the provider says a payment is. {@code UNKNOWN} covers "not found" — a reconciliation flag. */
	enum PaymentStatus { CAPTURED, FAILED, UNKNOWN }
}
