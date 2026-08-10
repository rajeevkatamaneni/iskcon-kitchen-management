package org.iskcon.kms.audit;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;

/**
 * The position of the last event on a page: where the next page resumes.
 *
 * <p>Encodes {@code (created_at, id)} — the tie-broken sort key — as one opaque token. Opaque so
 * a caller treats it as a bookmark to echo back, not a field to hand-edit; malformed tokens are
 * rejected as ordinary validation rather than becoming a 500.
 */
public record AuditCursor(Instant createdAt, UUID id) {

	private static final String SEPARATOR = "|";

	public String encode() {
		String raw = createdAt.toString() + SEPARATOR + id;
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	public static AuditCursor decode(String token) {
		try {
			String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
			int split = raw.lastIndexOf(SEPARATOR);
			return new AuditCursor(
					Instant.parse(raw.substring(0, split)),
					UUID.fromString(raw.substring(split + 1)));
		} catch (RuntimeException e) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED, java.util.Map.of("field", "cursor"), e);
		}
	}
}
