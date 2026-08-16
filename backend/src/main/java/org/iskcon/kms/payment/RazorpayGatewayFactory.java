package org.iskcon.kms.payment;

import org.springframework.stereotype.Component;

/** Builds a Razorpay client from one temple's own key id and secret (E7). */
@Component
public class RazorpayGatewayFactory implements PaymentGatewayFactory {

	@Override
	public String provider() {
		return "RAZORPAY";
	}

	@Override
	public PaymentGateway forCredentials(String keyId, String keySecret) {
		return new RazorpayPaymentGateway(keyId, keySecret);
	}
}
