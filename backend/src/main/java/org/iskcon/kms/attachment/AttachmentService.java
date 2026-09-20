package org.iskcon.kms.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
 * Uploaded files: the copy of a bill on an invoice (R-INV-2), and the proof on a payment (R-PAY-2).
 *
 * <h2>Upload first, claim on save</h2>
 *
 * <p>A file is uploaded the moment it is chosen, while the invoice or payment form is still open, and
 * stored as an {@code attachments} row with no parent. The form then sends the attachment's id with
 * everything else, and the service saving the invoice or payment <em>claims</em> it — sets the
 * parent — inside its own transaction. So a bill that fails to save leaves its upload unclaimed rather
 * than half-attached, and a photo from a phone on a slow connection is on its way up while the person
 * types the rest of the form, rather than holding up the Save.
 *
 * <p>V144 allows the parentless row for exactly this. What it does not do is clean them up: an upload
 * that was chosen and then abandoned stays in storage with no parent. Nothing reads such a row, so it
 * is waste and not a leak; sweeping them is left to a later job.
 *
 * <h2>The Java API for the invoice and payment services</h2>
 *
 * <ul>
 *   <li>{@link #claimBill} — on saving an invoice.</li>
 *   <li>{@link #claimForPayment} — on saving a payment, once per required file.</li>
 *   <li>{@link #billOf} — for an invoice's detail.</li>
 *   <li>{@link #ofPayments} — for a list of payments, in one query.</li>
 * </ul>
 *
 * <p>Every claim refuses, with KMS-400167, an upload that is missing, already claimed, of a kind other
 * than the one the caller says it needs, or another temple's. The last needs no code: RLS hides
 * another temple's row, so to this service it is simply missing. The parent is a different matter —
 * a foreign key is checked by PostgreSQL as the table owner, past RLS, so an id belonging to another
 * temple's invoice would satisfy it. That is why each claim first reads its parent through RLS.
 */
@Service
public class AttachmentService {

	/** R-INV-2 and KMS-400166: ten megabytes, as a person would count them on a phone. */
	public static final long MAX_BYTES = 10L * 1024 * 1024;

	/** Longest original name kept. Only ever displayed, and a longer one is a device's accident. */
	private static final int MAX_NAME_LENGTH = 200;

	private static final String COLUMNS = "id, kind, content_type, size_bytes, original_name, uploaded_at";

	private final JdbcTemplate jdbc;
	private final DocumentStorage storage;

	public AttachmentService(JdbcTemplate jdbc, DocumentStorage storage) {
		this.jdbc = jdbc;
		this.storage = storage;
	}

	/** A stored file on its way out: what it is, what it was called, and its bytes. */
	public record AttachmentFile(String contentType, String originalName, long sizeBytes, InputStream content) {
	}

	// ---------------------------------------------------------------------------------------------
	// Upload
	// ---------------------------------------------------------------------------------------------

	/**
	 * Stores one upload with no parent, and returns it.
	 *
	 * <p>The checks are in the order that costs least: nothing sent, then too large (known without
	 * reading a byte), then what the bytes are. The row is written before the bytes, inside one
	 * transaction, so a storage failure rolls the row back and never leaves a record of a file that
	 * is not there. The reverse — a file stored and its row not committed — can happen only if the
	 * commit itself fails, and leaves an object nothing points at, which is the harmless way round.
	 *
	 * <p>The temple is the request's, from the verified token; nothing in the request names it. It is
	 * both the row's {@code tenant_id} (RLS refuses any other) and the first part of the storage key,
	 * so one temple's files sit under one prefix in the bucket.
	 */
	@Transactional
	public AttachmentView upload(AuthenticatedUser actor, AttachmentKind kind, MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("kind", kind),
					List.of(new ErrorResponse.FieldError("file", "Choose a file to upload.")), null);
		}
		if (file.getSize() > MAX_BYTES) {
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

		UUID tenantId = TenantContext.get()
				.orElseThrow(() -> new IllegalStateException("an upload reached the service with no temple set"));
		UUID id = UUID.randomUUID();
		String key = "tenants/" + tenantId + "/attachments/" + id;

		AttachmentView view = jdbc.queryForObject("""
				INSERT INTO attachments (id, tenant_id, kind, storage_key, content_type, size_bytes,
					original_name, uploaded_by)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?)
				RETURNING %s
				""".formatted(COLUMNS), VIEW, id, kind.name(), key, contentType, (long) bytes.length,
				cleanName(file.getOriginalFilename()), actor.getUserId());

		storage.store(key, bytes, contentType);
		return view;
	}

	// ---------------------------------------------------------------------------------------------
	// Claims — for the invoice and payment services
	// ---------------------------------------------------------------------------------------------

	/**
	 * Makes an unclaimed INVOICE_BILL upload the copy of the bill on {@code invoiceId}.
	 *
	 * <p>Call it inside the transaction that saves the invoice, after the invoice row is written, so
	 * that a refusal here rolls the invoice back with it. An invoice has one bill: a second claim onto
	 * an invoice that already has one is refused too.
	 *
	 * @throws ApplicationException KMS-400167 when the upload is missing, already claimed, not a bill,
	 *     or another temple's; KMS-400030 when the invoice is not this temple's
	 */
	@Transactional
	public AttachmentView claimBill(UUID attachmentId, UUID invoiceId) {
		lockParent("vendor_invoices", invoiceId);
		return claim(attachmentId, AttachmentKind.INVOICE_BILL, "invoice_id", invoiceId);
	}

	/**
	 * Makes an unclaimed upload of {@code expectedKind} one of the files on {@code paymentId}.
	 *
	 * <p>The caller says which kind it needs — PAYMENT_PROOF for a UPI, bank or cheque payment,
	 * CASH_SIGNED_NOTE and CASH_RECEIVER_PHOTO for cash — and an upload of any other kind is refused.
	 * That is what stops the ID photo being sent as the signed note. A payment has at most one file of
	 * each kind. Call it inside the transaction that saves the payment, as {@link #claimBill} says.
	 *
	 * @throws IllegalArgumentException when {@code expectedKind} is INVOICE_BILL, which is a mistake
	 *     in the calling code and not something a person did
	 * @throws ApplicationException KMS-400167 when the upload is missing, already claimed, of another
	 *     kind, or another temple's; KMS-400030 when the payment is not this temple's
	 */
	@Transactional
	public AttachmentView claimForPayment(UUID attachmentId, AttachmentKind expectedKind, UUID paymentId) {
		if (expectedKind == null || expectedKind.belongsOnAnInvoice()) {
			throw new IllegalArgumentException("a payment's file cannot be " + expectedKind);
		}
		lockParent("invoice_payments", paymentId);
		return claim(attachmentId, expectedKind, "payment_id", paymentId);
	}

	/** The copy of the bill on an invoice, or empty for an invoice recorded before bills were uploaded. */
	@Transactional(readOnly = true)
	public Optional<AttachmentView> billOf(UUID invoiceId) {
		return jdbc.query("SELECT " + COLUMNS + """
				 FROM attachments WHERE invoice_id = ? AND kind = 'INVOICE_BILL'
				ORDER BY uploaded_at DESC LIMIT 1
				""", VIEW, invoiceId).stream().findFirst();
	}

	/**
	 * The files on each of these payments, in one query, for the payment list's thumbnails (R-PAY-3).
	 *
	 * <p>Every id asked for is a key in the answer, with an empty list when the payment has no files
	 * (every payment recorded before stage 6, and every reversal), so the caller never has to tell
	 * "none" from "not asked". Within a payment the files come in a fixed order — proof, then the
	 * signed note, then the photo — which is the order the screen shows them in.
	 */
	@Transactional(readOnly = true)
	public Map<UUID, List<AttachmentView>> ofPayments(Collection<UUID> paymentIds) {
		Map<UUID, List<AttachmentView>> byPayment = new LinkedHashMap<>();
		for (UUID id : paymentIds) {
			byPayment.put(id, new java.util.ArrayList<>());
		}
		if (byPayment.isEmpty()) {
			return byPayment;
		}
		jdbc.query(con -> {
			var statement = con.prepareStatement("SELECT payment_id, " + COLUMNS + """
					 FROM attachments WHERE payment_id = ANY (?)
					ORDER BY payment_id,
						CASE kind WHEN 'PAYMENT_PROOF' THEN 1 WHEN 'CASH_SIGNED_NOTE' THEN 2 ELSE 3 END,
						uploaded_at
					""");
			statement.setArray(1, con.createArrayOf("uuid", byPayment.keySet().toArray()));
			return statement;
		}, (ResultSet rs) -> {
			byPayment.get(rs.getObject("payment_id", UUID.class)).add(VIEW.mapRow(rs, 0));
		});
		return byPayment;
	}

	// ---------------------------------------------------------------------------------------------
	// Reading a file back
	// ---------------------------------------------------------------------------------------------

	/** The invoice's copy of the bill. KMS-400030 when it has none, or is not this temple's. */
	@Transactional(readOnly = true)
	public AttachmentFile openBill(UUID invoiceId) {
		return jdbc.query("""
				SELECT storage_key, content_type, original_name, size_bytes FROM attachments
				WHERE invoice_id = ? AND kind = 'INVOICE_BILL'
				ORDER BY uploaded_at DESC LIMIT 1
				""", this::open, invoiceId).stream().findFirst()
				.orElseThrow(() -> new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("invoiceId", invoiceId)));
	}

	/**
	 * One file on a payment — only if it is on that payment and the payment is on that invoice, so an
	 * address that mixes up the three ids finds nothing rather than somebody else's proof.
	 */
	@Transactional(readOnly = true)
	public AttachmentFile openPaymentFile(UUID invoiceId, UUID paymentId, UUID attachmentId) {
		return jdbc.query("""
				SELECT a.storage_key, a.content_type, a.original_name, a.size_bytes
				FROM attachments a JOIN invoice_payments p ON p.id = a.payment_id
				WHERE a.id = ? AND a.payment_id = ? AND p.invoice_id = ?
				""", this::open, attachmentId, paymentId, invoiceId).stream().findFirst()
				.orElseThrow(() -> new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND,
						Map.of("invoiceId", invoiceId, "paymentId", paymentId, "attachmentId", attachmentId)));
	}

	// ---------------------------------------------------------------------------------------------

	/**
	 * Reads the parent through RLS and holds it until the claim commits.
	 *
	 * <p>Reading it is the check that it is this temple's (see the class note on foreign keys).
	 * Locking it is what makes "one bill per invoice" and "one of each kind per payment" hold when two
	 * saves race: every claim onto the same parent queues here, so the second one's NOT EXISTS sees
	 * the first one's file. {@code FOR NO KEY UPDATE} rather than {@code FOR UPDATE} because nothing
	 * about the parent's key changes, and the weaker lock does not block other rows' foreign-key checks
	 * against it. {@code invoice_payments} is append-only, but that is a trigger on UPDATE and DELETE,
	 * and a row lock fires neither.
	 */
	private void lockParent(String table, UUID parentId) {
		List<UUID> found = jdbc.queryForList(
				"SELECT id FROM " + table + " WHERE id = ? FOR NO KEY UPDATE", UUID.class, parentId);
		if (found.isEmpty()) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of(table, String.valueOf(parentId)));
		}
	}

	/**
	 * The claim itself: one conditional UPDATE, so "unclaimed and of this kind" is decided in the same
	 * statement that claims it. Two saves claiming one upload for two different parents cannot both
	 * win: the second waits on the first's row lock and, under READ COMMITTED, re-reads the row and
	 * finds it claimed.
	 */
	private AttachmentView claim(UUID attachmentId, AttachmentKind kind, String parentColumn, UUID parentId) {
		List<AttachmentView> claimed = attachmentId == null ? List.of() : jdbc.query("""
				UPDATE attachments SET %1$s = ?
				WHERE id = ? AND kind = ? AND invoice_id IS NULL AND payment_id IS NULL
				  AND NOT EXISTS (SELECT 1 FROM attachments o WHERE o.%1$s = ? AND o.kind = ?)
				RETURNING %2$s
				""".formatted(parentColumn, COLUMNS), VIEW, parentId, attachmentId, kind.name(), parentId, kind.name());
		if (claimed.isEmpty()) {
			throw new ApplicationException(ErrorCode.ATTACHMENT_NOT_USABLE,
					Map.of("attachmentId", String.valueOf(attachmentId), "expectedKind", kind, parentColumn, parentId));
		}
		return claimed.get(0);
	}

	private AttachmentFile open(ResultSet rs, int row) throws SQLException {
		return new AttachmentFile(rs.getString("content_type"), rs.getString("original_name"),
				rs.getLong("size_bytes"), storage.open(rs.getString("storage_key")));
	}

	/**
	 * The name as the device sent it, made safe to display and to put in a download header: only the
	 * last path segment (some browsers have sent the whole path), no control characters, trimmed, and
	 * no longer than a name needs to be. Null when nothing is left.
	 *
	 * <p>Public since T-428, so the staff-document service cleans a device's filename in exactly the
	 * same way. Its table is its own (V155); this rule is not something to have two of.
	 */
	public static String cleanName(String sent) {
		if (sent == null) {
			return null;
		}
		String name = sent.substring(Math.max(sent.lastIndexOf('/'), sent.lastIndexOf('\\')) + 1)
				.replaceAll("\\p{Cntrl}", "")
				.strip();
		if (name.length() > MAX_NAME_LENGTH) {
			name = name.substring(0, MAX_NAME_LENGTH).strip();
		}
		return name.isEmpty() ? null : name;
	}

	private static final RowMapper<AttachmentView> VIEW = (rs, n) -> new AttachmentView(
			rs.getObject("id", UUID.class),
			AttachmentKind.valueOf(rs.getString("kind")),
			rs.getString("content_type"),
			rs.getLong("size_bytes"),
			rs.getString("original_name"),
			rs.getObject("uploaded_at", OffsetDateTime.class).toInstant());
}
