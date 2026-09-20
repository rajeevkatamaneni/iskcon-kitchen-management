package org.iskcon.kms.staff;

import java.time.Instant;
import java.util.UUID;

/**
 * One file on a staff record, as the screens see it (T-428): {@code StaffDocumentView} in
 * {@code frontend/lib/api.ts}, field for field.
 *
 * <p>The same five facts {@code AttachmentView} carries, for the same reason — the upload box and
 * the thumbnail on the front end are shared, and they need a name to print, a type to decide whether
 * they can draw it, and an id to fetch it by. The storage key is deliberately absent: it is where the
 * bytes live, which is ours to know, and the file is only ever reached through an endpoint that
 * checks MANAGE_STAFF first and writes an audit row for the read.
 *
 * @param contentType what the file's own bytes say it is, never what the browser declared
 * @param originalName as the uploader's device named it ("IMG_2041.jpg"), for display; null when
 *     the device sent none
 */
public record StaffDocumentView(
		UUID id,
		StaffDocumentKind kind,
		String contentType,
		long sizeBytes,
		String originalName,
		Instant uploadedAt) {
}
