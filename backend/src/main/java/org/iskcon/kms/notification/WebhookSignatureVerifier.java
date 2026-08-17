package org.iskcon.kms.notification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Verifies that a delivery webhook really came from the provider and not from anyone who found the
 * URL. The provider signs the raw request body with a shared secret (Meta's {@code
 * X-Hub-Signature-256}: {@code sha256=<hex hmac>}); we recompute it over the exact bytes received
 * and compare in constant time.
 */
@Component
public class WebhookSignatureVerifier {

	private static final String PREFIX = "sha256=";
	private static final String ALGORITHM = "HmacSHA256";

	private final byte[] secret;

	public WebhookSignatureVerifier(
			@Value("${kms.notifications.whatsapp.app-secret:dev-webhook-secret}") String secret) {
		this.secret = secret.getBytes(StandardCharsets.UTF_8);
	}

	/** Verifies against the deployment-wide secret. */
	public boolean isValid(byte[] body, String signatureHeader) {
		return isValid(body, signatureHeader, secret);
	}

	/**
	 * Verifies against one temple's own app secret.
	 *
	 * <p>Every temple connects its own Meta app, so the secret a callback is signed with belongs to
	 * whichever temple it is about — which is why the callback URL carries a token identifying the
	 * temple, read before any of this can run.
	 */
	public boolean isValid(byte[] body, String signatureHeader, String appSecret) {
		return isValid(body, signatureHeader, appSecret.getBytes(StandardCharsets.UTF_8));
	}

	private boolean isValid(byte[] body, String signatureHeader, byte[] key) {
		if (signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
			return false;
		}
		String expected = PREFIX + hexHmac(body, key);
		// Constant-time comparison so a caller cannot learn the signature byte by byte from timing.
		return MessageDigest.isEqual(
				expected.getBytes(StandardCharsets.UTF_8),
				signatureHeader.getBytes(StandardCharsets.UTF_8));
	}

	private String hexHmac(byte[] body, byte[] key) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(key, ALGORITHM));
			byte[] digest = mac.doFinal(body);

			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16));
				hex.append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		} catch (Exception e) {
			// A JVM without HmacSHA256 is not a condition to paper over.
			throw new IllegalStateException("HMAC-SHA256 unavailable", e);
		}
	}
}
