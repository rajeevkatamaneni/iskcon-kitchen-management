package org.iskcon.kms.communication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The link at the foot of every optional email (E8-S1).
 *
 * <p>Unsubscribing cannot require signing in. Somebody who wants to stop hearing from a temple is
 * the least likely person to go and find their password, and Gmail's bulk-sender rules require a
 * one-click withdrawal that works from the mail client itself. So the link has to carry who it is
 * for — which means it has to be unforgeable, or anyone could unsubscribe anybody.
 *
 * <p>A token is {@code base64url(tenant|user|category)} with an HMAC-SHA256 tag over the same bytes,
 * keyed on the deployment's column-encryption key. It is not secret and does not need to be: it
 * proves only that we issued it. It does not expire, because a newsletter someone finds in a
 * two-year-old mailbox should still be able to get them off the list.
 *
 * <p>What it authorises is deliberately tiny — removing one category, or all optional ones, for one
 * person. It grants no read of anything, and holding one tells you nothing you did not already have
 * by holding the email it came in.
 */
@Component
public class UnsubscribeTokens {

	private static final String HMAC = "HmacSHA256";
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

	private final SecretKeySpec key;

	public UnsubscribeTokens(
			@Value("${kms.security.column-encryption-key:ZGV2LWNvbHVtbi1lbmNyeXB0aW9uLWtleS0zMmI=}")
			String base64Key) {
		byte[] raw = Base64.getDecoder().decode(base64Key);
		byte[] keyBytes = new byte[32];
		System.arraycopy(raw, 0, keyBytes, 0, Math.min(raw.length, 32));
		this.key = new SecretKeySpec(keyBytes, HMAC);
	}

	/** A token for one category, or for every optional one when {@code category} is null. */
	public String issue(UUID tenantId, UUID userId, CommunicationCategory category) {
		String payload = tenantId + "|" + userId + "|" + (category == null ? "ALL" : category.name());
		String encoded = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
		return encoded + "." + ENCODER.encodeToString(tag(encoded));
	}

	/** What a token says, or null if it says nothing we signed. */
	public Claim verify(String token) {
		if (token == null) {
			return null;
		}
		int dot = token.lastIndexOf('.');
		if (dot <= 0) {
			return null;
		}
		String encoded = token.substring(0, dot);
		byte[] presented;
		byte[] payload;
		try {
			presented = DECODER.decode(token.substring(dot + 1));
			payload = DECODER.decode(encoded);
		} catch (IllegalArgumentException e) {
			return null;
		}
		// Constant-time: a tag comparison that returns early leaks how much of a guess was right.
		if (!MessageDigest.isEqual(presented, tag(encoded))) {
			return null;
		}

		String[] parts = new String(payload, StandardCharsets.UTF_8).split("\\|");
		if (parts.length != 3) {
			return null;
		}
		try {
			return new Claim(
					UUID.fromString(parts[0]),
					UUID.fromString(parts[1]),
					"ALL".equals(parts[2]) ? null : CommunicationCategory.parseOrNull(parts[2]),
					"ALL".equals(parts[2]));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private byte[] tag(String encoded) {
		try {
			Mac mac = Mac.getInstance(HMAC);
			mac.init(key);
			return mac.doFinal(encoded.getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			throw new IllegalStateException("Could not sign an unsubscribe token", e);
		}
	}

	/**
	 * @param category null when {@code allOptional} is true — the link that turns everything off
	 */
	public record Claim(
			UUID tenantId, UUID userId, CommunicationCategory category, boolean allOptional) {
	}
}
