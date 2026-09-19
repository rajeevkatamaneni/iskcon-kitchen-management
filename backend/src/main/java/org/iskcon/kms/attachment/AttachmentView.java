package org.iskcon.kms.attachment;

import java.time.Instant;
import java.util.UUID;

/**
 * A stored upload, as the screens see it: {@code AttachmentView} in {@code frontend/lib/api.ts},
 * field for field. The storage key is deliberately absent — it is where the bytes live, which is ours
 * to know, and the file is only ever reached through an endpoint that checks the permission first.
 *
 * @param contentType what the file's own bytes say it is, never what the browser declared
 * @param originalName as the uploader's device named it ("IMG_2041.jpg"), for display; null when
 *     the device sent none
 */
public record AttachmentView(
		UUID id,
		AttachmentKind kind,
		String contentType,
		long sizeBytes,
		String originalName,
		Instant uploadedAt) {
}
