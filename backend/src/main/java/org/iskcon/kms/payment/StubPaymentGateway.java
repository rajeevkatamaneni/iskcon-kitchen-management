package org.iskcon.kms.payment;

import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The default payment gateway: a deterministic fake that lets the whole donation pipeline — order
 * creation, webhook confirmation, idempotency, reconciliation — be built and tested without a
 * network or credentials. Real collection is {@link RazorpayPaymentGateway}
 * ({@code kms.payments.provider=razorpay}). Keeping this the default is what keeps the test suite
 * hermetic; a webhook fixture in tests references the {@code order_stub_*} id this returns.
 */
@Component
@ConditionalOnProperty(name = "kms.payments.provider", havingValue = "stub", matchIfMissing = true)
public class StubPaymentGateway implements PaymentGateway {

	@Override
	public String name() {
		return "stub";
	}

	@Override
	public PaymentOrder createOrder(long amountMinorUnits, String currency, String receipt,
			Map<String, String> notes) {
		return new PaymentOrder("order_stub_" + UUID.randomUUID(), amountMinorUnits, currency);
	}
}
