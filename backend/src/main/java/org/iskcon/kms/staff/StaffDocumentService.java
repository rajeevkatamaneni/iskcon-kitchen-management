package org.iskcon.kms.staff;

import java.io.IOException;
import java.io.InputStream;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.attachment.AttachmentFileType;
import org.iskcon.kms.attachment.AttachmentService;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.document.DocumentStorage;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.error.ErrorResponse;
import org.iskcon.kms.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * The papers on a staff record (T-428): their photograph, and the scans of their PAN and Aadhaar
 * cards.
 *
 * <h2>The same storage, a table of its own</h2>
 *
 * <p>V144's header settled this for us — "The storage service is shared; the table is not." The bytes
 * go through the same {@link DocumentStorage} the copy of a vendor's bill does, under the same
 * {@code tenants/<tenantId>/...} prefix; the content type is decided by the same
 * {@link AttachmentFileType}, from the bytes, never from the browser; the ceiling is
 * {@link AttachmentService#MAX_BYTES}; and the two refusals are the ones that already exist,
 * {@code KMS-400165} and {@code KMS-400166}. What is not shared is {@code attachments} itself, whose
 * parents are an invoice and a payment and whose kinds are a bill and three flavours of payment
 * proof. A person is neither.
 *
 * <h2>Attached straight away, not claimed on save</h2>
 *
 * <p>Unlike a bill, which is uploaded while a form that has not been saved is still open, a staff
 * document is attached to a record that already exists. So there is no unclaimed state and no claim:
 * the upload writes the row against the person, and it is theirs from that moment. That is also why
 * the screen offers this on the record itself rather than inside the edit form — an upload that a
 * Cancel could not take back has no business sitting beside a Cancel.
 *
 * <h2>One of each kind, and what replacing costs</h2>
 *
 * <p>A photograph is <em>the</em> photograph. Uploading a second one of a kind replaces the first, in
 * one transaction, and the replacement is audited as a removal and an addition because that is what
 * happened. The old object stays in the bucket with nothing pointing at it: {@link DocumentStorage}
 * has no delete, and V144 already accepts the same waste for an upload that was abandoned. It is
 * waste and not a leak — there is no way to reach an object whose key is in no row.
 *
 * <h2>Reading one is an event</h2>
 *
 * <p>{@link #open} writes {@link AuditAction#STAFF_DOCUMENT_VIEWED} before it hands the bytes back,
 * in the shape {@code StaffEmploymentService.revealPan} uses: a null before, and an after that names
 * the kind and nothing from inside the file. The audit log has a wider readership than MANAGE_STAFF
 * does, and copying anything out of an identity document into it would hand that document to a
 * second audience — the point {@code STAFF_CONDUCT_NOTE_ADDED} already makes about a note's words.
 */
@Service
public class StaffDocumentService {

	private static final String COLUMNS = "id, kind, content_type, size_bytes, original_name, uploaded_at";

	private final JdbcTemplate jdbc;
	private final DocumentStorage storage;
	private final AuditService auditService;

	public StaffDocumentService(JdbcTemplate jdbc, DocumentStorage storage, AuditService auditService) {
		this.jdbc = jdbc;
		this.storage = storage;
		this.auditService = auditService;
	}

	/** A stored file on its way out: what it is, what it was called, and its bytes. */
	public record StaffDocumentFile(String contentType, String originalName, long sizeBytes, InputStream content) {
	}

	// ---------------------------------------------------------------------------------------------
	// Reading the list
	// ---------------------------------------------------------------------------------------------

	/** Every document on one person, photo first, then PAN, then Aadhaar — the order they are shown in. */
	@Transactional(readOnly = true)
	public List<StaffDocumentView> documentsFor(UUID staffProfileId) {
		return jdbc.query("SELECT " + COLUMNS + """
				 FROM staff_documents WHERE staff_profile_id = ?
				ORDER BY CASE kind WHEN 'PHOTO' THEN 1 WHEN 'PAN_SCAN' THEN 2 ELSE 3 END
				""", VIEW, staffProfileId);
	}

	/**
	 * The documents on each of these people, in one query, so the register could draw a photograph
	 * beside every name without asking once per row.
	 *
	 * <p>Every id asked for is a key in the answer, with an empty list for somebody who has none, so
	 * the caller never has to tell "none" from "not asked".
	 */
	@Transactional(readOnly = true)
	public Map<UUID, List<StaffDocumentView>> ofStaff(Collection<UUID> staffProfileIds) {
		Map<UUID, List<StaffDocumentView>> byStaff = new LinkedHashMap<>();
		for (UUID id : staffProfileIds) {
			byStaff.put(id, new java.util.ArrayList<>());
		}
		if (byStaff.isEmpty()) {
			return byStaff;
		}
		jdbc.query(con -> {
			var statement = con.prepareStatement("SELECT staff_profile_id, " + COLUMNS + """
					 FROM staff_documents WHERE staff_profile_id = ANY (?)
					ORDER BY staff_profile_id,
						CASE kind WHEN 'PHOTO' THEN 1 WHEN 'PAN_SCAN' THEN 2 ELSE 3 END
					""");
			statement.setArray(1, con.createArrayOf("uuid", byStaff.keySet().toArray()));
			return statement;
		}, (ResultSet rs) -> {
			byStaff.get(rs.getObject("staff_profile_id", UUID.class)).add(VIEW.mapRow(rs, 0));
		});
		return byStaff;
	}

	// ---------------------------------------------------------------------------------------------
	// Attaching one
	// ---------------------------------------------------------------------------------------------

	/**
	 * Stores one file against a person, replacing whatever was there of the same kind.
	 *
	 * <p>The checks are in the order that costs least: nothing sent, then too large (known without
	 * reading a byte), then what the bytes actually are. The row is written before the bytes, inside
	 * one transaction, so a storage failure rolls the row back and never leaves a record of a file
	 * that is not there — {@link AttachmentService#upload}'s reasoning, followed here.
	 *
	 * <p>The temple is the request's, from the verified token; nothing in the request names it.
	 *
	 * @throws ApplicationException {@code KMS-400030} when that person is not this temple's;
	 *     {@code KMS-400165} when the bytes are not a photo or a PDF; {@code KMS-400166} over 10 MB
	 */
	@Transactional
	public StaffDocumentView attach(
			AuthenticatedUser actor, UUID staffProfileId, StaffDocumentKind kind, MultipartFile file) {

		String personName = requireStaffOfThisTemple(staffProfileId);

		if (file == null || file.isEmpty()) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("kind", kind),
					List.of(new ErrorResponse.FieldError("file", "Choose a file to upload.")), null);
		}
		if (file.getSize() > AttachmentService.MAX_BYTES) {
			throw new ApplicationException(ErrorCode.ATTACHMENT_TOO_LARGE,
					Map.of("kind", kind, "sizeBytes", file.getSize()));
		}

		byte[] bytes;
		try {
			bytes = file.getBytes();
		} catch (IOException e) {
			throw new IllegalStateException("could not read an upload the container had already received", e);
		}
		String contentType = AttachmentFileType.of(bytes)
				.orElseThrow(() -> new ApplicationException(ErrorCode.ATTACHMENT_TYPE_NOT_ALLOWED,
						Map.of("kind", kind, "declaredType", String.valueOf(file.getContentType()),
								"name", String.valueOf(file.getOriginalFilename()))));

		// Whatever was there of this kind goes, so the unique index can do its job and the audit says
		// a document was replaced rather than silently overwritten.
		removeExisting(actor, staffProfileId, kind, personName);

		UUID tenantId = TenantContext.get()
				.orElseThrow(() -> new IllegalStateException("an upload reached the service with no temple set"));
		UUID id = UUID.randomUUID();
		String key = "tenants/" + tenantId + "/staff-documents/" + id;

		StaffDocumentView view = jdbc.queryForObject("""
				INSERT INTO staff_documents (id, tenant_id, staff_profile_id, kind, storage_key,
					content_type, size_bytes, original_name, uploaded_by)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?, ?)
				RETURNING %s
				""".formatted(COLUMNS), VIEW, id, staffProfileId, kind.name(), key, contentType,
				(long) bytes.length, AttachmentService.cleanName(file.getOriginalFilename()), actor.getUserId());

		storage.store(key, bytes, contentType);

		// What was attached, and nothing from inside it. The size and the type are ours, not the
		// person's: they say a file arrived and what sort of file it was.
		auditService.record(actor, AuditAction.STAFF_DOCUMENT_ADDED, AuditEntityType.STAFF_MEMBER,
				staffProfileId, null,
				Map.of("kind", kind.name(), "contentType", contentType, "sizeBytes", (long) bytes.length),
				kind.label() + " attached to " + personName + "'s record.");
		return view;
	}

	// ---------------------------------------------------------------------------------------------
	// Reading one back, and taking one off
	// ---------------------------------------------------------------------------------------------

	/**
	 * One document's bytes, and the audit row that says who read them.
	 *
	 * <p>The audit is written first, deliberately. If storage then fails, the log says somebody asked
	 * for the document and they did not get it, which is the honest record; the other way round, a
	 * failure to write the log would hand over an Aadhaar scan with nothing saying so.
	 *
	 * @throws ApplicationException {@code KMS-400030} when the document is not this person's, or the
	 *     person not this temple's
	 */
	@Transactional
	public StaffDocumentFile open(AuthenticatedUser actor, UUID staffProfileId, UUID documentId) {
		String personName = requireStaffOfThisTemple(staffProfileId);
		Stored stored = jdbc.query("""
				SELECT kind, storage_key, content_type, original_name, size_bytes FROM staff_documents
				WHERE id = ? AND staff_profile_id = ?
				""", STORED, documentId, staffProfileId).stream().findFirst()
				.orElseThrow(() -> new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND,
						Map.of("staffProfileId", staffProfileId, "documentId", documentId)));

		auditService.record(actor, AuditAction.STAFF_DOCUMENT_VIEWED, AuditEntityType.STAFF_MEMBER,
				staffProfileId, null, Map.of("kind", stored.kind().name()),
				personName + "'s " + stored.kind().label().toLowerCase(Locale.ROOT) + " was opened.");

		return new StaffDocumentFile(stored.contentType(), stored.originalName(), stored.sizeBytes(),
				storage.open(stored.storageKey()));
	}

	/** Takes one off a record. The bytes stay in the bucket unreferenced; see the class note. */
	@Transactional
	public void remove(AuthenticatedUser actor, UUID staffProfileId, UUID documentId) {
		String personName = requireStaffOfThisTemple(staffProfileId);
		StaffDocumentKind kind = jdbc.query(
				"SELECT kind FROM staff_documents WHERE id = ? AND staff_profile_id = ?",
				(rs, n) -> StaffDocumentKind.valueOf(rs.getString("kind")), documentId, staffProfileId)
				.stream().findFirst()
				.orElseThrow(() -> new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND,
						Map.of("staffProfileId", staffProfileId, "documentId", documentId)));

		jdbc.update("DELETE FROM staff_documents WHERE id = ? AND staff_profile_id = ?",
				documentId, staffProfileId);
		auditService.record(actor, AuditAction.STAFF_DOCUMENT_REMOVED, AuditEntityType.STAFF_MEMBER,
				staffProfileId, Map.of("kind", kind.name()), null,
				kind.label() + " taken off " + personName + "'s record.");
	}

	// ---------------------------------------------------------------------------------------------

	/**
	 * Deletes whatever this person already has of this kind, and says so on the log.
	 *
	 * <p>Read through RLS first, so another temple's row is simply not there rather than being
	 * deleted by id. Called inside {@link #attach}'s transaction, so a replacement that then fails to
	 * store leaves the original row in place.
	 */
	private void removeExisting(AuthenticatedUser actor, UUID staffProfileId, StaffDocumentKind kind,
			String personName) {
		List<UUID> existing = jdbc.queryForList(
				"SELECT id FROM staff_documents WHERE staff_profile_id = ? AND kind = ?",
				UUID.class, staffProfileId, kind.name());
		if (existing.isEmpty()) {
			return;
		}
		jdbc.update("DELETE FROM staff_documents WHERE staff_profile_id = ? AND kind = ?",
				staffProfileId, kind.name());
		auditService.record(actor, AuditAction.STAFF_DOCUMENT_REMOVED, AuditEntityType.STAFF_MEMBER,
				staffProfileId, Map.of("kind", kind.name()), null,
				kind.label() + " on " + personName + "'s record was replaced.");
	}

	/**
	 * That the person exists and is this temple's, and their name for the audit line.
	 *
	 * <p>Read through RLS, which is the check: another temple's record is invisible here, so it is
	 * answered as missing rather than as forbidden. A foreign key would not have done — PostgreSQL
	 * checks those as the table owner, past RLS, exactly as {@code AttachmentService} records.
	 */
	private String requireStaffOfThisTemple(UUID staffProfileId) {
		return jdbc.queryForList("SELECT full_name FROM staff_profiles WHERE id = ?", String.class, staffProfileId)
				.stream().findFirst()
				.orElseThrow(() -> new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND,
						Map.of("staffProfileId", staffProfileId)));
	}

	private record Stored(StaffDocumentKind kind, String storageKey, String contentType, String originalName,
			long sizeBytes) {
	}

	private static final RowMapper<Stored> STORED = (rs, n) -> new Stored(
			StaffDocumentKind.valueOf(rs.getString("kind")),
			rs.getString("storage_key"),
			rs.getString("content_type"),
			rs.getString("original_name"),
			rs.getLong("size_bytes"));

	private static final RowMapper<StaffDocumentView> VIEW = (rs, n) -> new StaffDocumentView(
			rs.getObject("id", UUID.class),
			StaffDocumentKind.valueOf(rs.getString("kind")),
			rs.getString("content_type"),
			rs.getLong("size_bytes"),
			rs.getString("original_name"),
			rs.getObject("uploaded_at", OffsetDateTime.class).toInstant());
}
