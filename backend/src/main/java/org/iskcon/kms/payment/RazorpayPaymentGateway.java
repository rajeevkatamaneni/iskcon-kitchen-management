package org.iskcon.kms.payment;

import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import java.util.Map;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Real payment collection via Razorpay (E7), selected with {@code kms.payments.provider=razorpay}.
 * The key id and secret come from config — the deployment injects them from Secrets Manager; they
 * never live in code. This creates the order the hosted checkout collects against; it never touches
 * card/UPI credentials and never moves money out.
 *
 * <p>One of several possible {@link PaymentGateway} implementations — a different temple could run a
 * different provider by adding its adapter and selecting it, with no change to the donation logic.
 */
@Component
@ConditionalOnProperty(name = "kms.payments.provider", havingValue = "razorpay")
public class RazorpayPaymentGateway implements PaymentGateway {

	private final RazorpayClient client;
	private final String keyId;

	public RazorpayPaymentGateway(
			@Value("${kms.payments.razorpay.key-id}") String keyId,
			@Value("${kms.payments.razorpay.key-secret}") String keySecret) {
		this.keyId = keyId;
		try {
			this.client = new RazorpayClient(keyId, keySecret);
		} catch (RazorpayException e) {
			throw new IllegalStateException("Could not initialise the Razorpay client", e);
		}
	}

	@Override
	public String name() {
		return "razorpay";
	}

	@Override
	public String publicKey() {
		return keyId;
	}

	@Override
	public PaymentOrder createOrder(long amountMinorUnits, String currency, String receipt,
			Map<String, String> notes) {
		try {
			JSONObject options = new JSONObject();
			options.put("amount", amountMinorUnits);
			options.put("currency", currency);
			options.put("receipt", receipt);
			if (notes != null && !notes.isEmpty()) {
				JSONObject notesJson = new JSONObject();
				notes.forEach(notesJson::put);
				options.put("notes", notesJson);
			}
			Order order = client.orders.create(options);
			return new PaymentOrder(order.get("id"), amountMinorUnits, currency);
		} catch (RazorpayException e) {
			throw new ApplicationException(ErrorCode.PAYMENT_GATEWAY_ERROR, Map.of(), e);
		}
	}

	@Override
	public PaymentStatus fetchPaymentStatus(String paymentId) {
		try {
			com.razorpay.Payment payment = client.payments.fetch(paymentId);
			String status = payment.get("status");
			if ("captured".equals(status) || "authorized".equals(status)) {
				return PaymentStatus.CAPTURED;
			}
			return "failed".equals(status) ? PaymentStatus.FAILED : PaymentStatus.UNKNOWN;
		} catch (RazorpayException e) {
			return PaymentStatus.UNKNOWN;
		}
	}
}
