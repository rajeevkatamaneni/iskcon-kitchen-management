package org.iskcon.kms.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Razorpay's scheme: a hex HMAC-SHA256 of the raw body in {@code X-Razorpay-Signature}.
 *
 * <p>The same arithmetic {@link PaymentWebhookVerifier} has always done, with the secret passed in
 * rather than read from configuration — because the secret now belongs to the temple the webhook is
 * for, not to the deployment.
 */
@Component
public class RazorpayWebhookSignature implements PaymentWebhookSignature {

	private static final String ALGORITHM = "HmacSHA256";

	@Override
	public String provider() {
		return "RAZORPAY";
	}

	@Override
	public String header() {
		return "X-Razorpay-Signature";
	}

	@Override
	public boolean isValid(byte[] rawBody, String headerValue, String secret) {
		if (headerValue == null || headerValue.isBlank() || secret == null || secret.isBlank()) {
			return false;
		}
		String expected = hexHmac(rawBody, secret);
		// Constant-time: a byte-by-byte comparison that returns early leaks the signature one
		// character at a time to anyone willing to time it.
		return MessageDigest.isEqual(
				expected.getBytes(StandardCharsets.UTF_8),
				headerValue.trim().getBytes(StandardCharsets.UTF_8));
	}

	private static String hexHmac(byte[] body, String secret) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
			byte[] digest = mac.doFinal(body);
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		} catch (Exception e) {
			throw new IllegalStateException("Could not compute a webhook signature", e);
		}
	}
}
