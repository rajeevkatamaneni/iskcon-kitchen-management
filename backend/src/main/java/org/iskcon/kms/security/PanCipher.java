package org.iskcon.kms.security;

import org.iskcon.kms.security.PanCipher;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Column-level encryption for a PAN (E7-S4, SYSTEM_DESIGN §7). A PAN is PII a temple must hold —
 * a donor's for 80G, an employee's for its own records — but must never expose casually, so it is
 * encrypted in the application before it touches the database and decrypted only for an audited
 * Temple-Admin read: the database never sees the clear value, and a dump leaks nothing without the
 * key.
 *
 * <p>Lives here rather than under {@code donation} because staff hold PANs too (E6-S8), and a
 * donations class encrypting an employee's tax number reads as an accident.
 *
 * <p>AES-GCM (authenticated) with a random 96-bit IV per value; the stored bytes are {@code iv‖ct}.
 * The key comes from config — the deployment injects it from Secrets Manager; the dev default exists
 * only so the suite runs.
 */
@Component
public class PanCipher {

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";
	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;

	private final SecretKeySpec key;
	private final SecureRandom random = new SecureRandom();

	public PanCipher(@Value("${kms.security.column-encryption-key:ZGV2LWNvbHVtbi1lbmNyeXB0aW9uLWtleS0zMmI=}") String base64Key) {
		byte[] raw = Base64.getDecoder().decode(base64Key);
		// AES-256 wants 32 bytes; pad/truncate a short dev key deterministically so dev just works.
		byte[] keyBytes = new byte[32];
		System.arraycopy(raw, 0, keyBytes, 0, Math.min(raw.length, 32));
		this.key = new SecretKeySpec(keyBytes, "AES");
	}

	public byte[] encrypt(String plaintext) {
		try {
			byte[] iv = new byte[IV_BYTES];
			random.nextBytes(iv);
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			byte[] out = new byte[iv.length + ct.length];
			System.arraycopy(iv, 0, out, 0, iv.length);
			System.arraycopy(ct, 0, out, iv.length, ct.length);
			return out;
		} catch (Exception e) {
			throw new IllegalStateException("PAN encryption failed", e);
		}
	}

	/**
	 * A deterministic, keyed fingerprint of a PAN (E7-S7) — a blind index for matching a donor across
	 * gifts without storing the PAN in a matchable form. HMAC-SHA256 with the same key, hex-encoded.
	 */
	public String fingerprint(String plaintext) {
		try {
			javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key.getEncoded(), "HmacSHA256"));
			byte[] digest = mac.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16));
				hex.append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		} catch (Exception e) {
			throw new IllegalStateException("PAN fingerprint failed", e);
		}
	}

	public String decrypt(byte[] stored) {
		try {
			byte[] iv = new byte[IV_BYTES];
			System.arraycopy(stored, 0, iv, 0, IV_BYTES);
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			byte[] pt = cipher.doFinal(stored, IV_BYTES, stored.length - IV_BYTES);
			return new String(pt, StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw new IllegalStateException("PAN decryption failed", e);
		}
	}
}
