package org.iskcon.kms.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Verifies a payment webhook really came from the provider (E7-S9). Razorpay signs the raw body with
 * the webhook secret as a hex HMAC-SHA256 in {@code X-Razorpay-Signature} — a standard scheme, so the
 * one verifier serves both the real provider (secret from Secrets Manager) and the hermetic tests
 * (the dev secret, which lets a test sign its own fixtures). The signature is the whole of the trust:
 * an unsigned or wrongly-signed body is refused before anything is read from it.
 */
@Component
public class PaymentWebhookVerifier {

	private static final String ALGORITHM = "HmacSHA256";

	private final byte[] secret;

	public PaymentWebhookVerifier(
			@Value("${kms.payments.razorpay.webhook-secret:dev-payment-webhook-secret}") String secret) {
		this.secret = secret.getBytes(StandardCharsets.UTF_8);
	}

	public boolean isValid(byte[] body, String signatureHeader) {
		if (signatureHeader == null || signatureHeader.isBlank()) {
			return false;
		}
		String expected = hexHmac(body);
		return MessageDigest.isEqual(
				expected.getBytes(StandardCharsets.UTF_8),
				signatureHeader.trim().getBytes(StandardCharsets.UTF_8));
	}

	/** The signature a caller with the secret would produce for this body — used by tests to sign fixtures. */
	public String sign(byte[] body) {
		return hexHmac(body);
	}

	private String hexHmac(byte[] body) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(secret, ALGORITHM));
			byte[] digest = mac.doFinal(body);
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16));
				hex.append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		} catch (Exception e) {
			throw new IllegalStateException("HMAC-SHA256 unavailable", e);
		}
	}
}
