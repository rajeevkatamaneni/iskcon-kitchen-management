package org.iskcon.kms.staff;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.attachment.AttachmentFileType;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.error.ErrorResponse;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * A staff member's photograph and the scans of their PAN and Aadhaar cards (T-428).
 *
 * <p>Its own controller rather than three more methods on {@link StaffScheduleController}, for the
 * reason {@code AttachmentController} is its own: streaming a file back needs a different set of
 * headers from every other route in this package, and mixing the two makes both harder to read. The
 * paths still sit under {@code /api/v1/staff/members/{id}} because that is what the files belong to.
 *
 * <p><b>All three are {@code MANAGE_STAFF}</b> — the permission that already governs everything else
 * on a staff record. Rajeev, 2026-09-20, asked for exactly that: access "through the same permission
 * as the rest of a staff record". No new permission was minted. There is an argument that an Aadhaar
 * scan deserves its own narrow permission by the reasoning that produced
 * {@code MANAGE_STAFF_CONDUCT_NOTES}, and the proof for this task puts it to him; splitting it is his
 * decision and not a builder's.
 *
 * <p><b>The download is not a link.</b> Each file is streamed through here, with the permission
 * checked, RLS applied and an audit row written, exactly as a vendor's bill is. There is no signed
 * URL and no public URL anywhere in this application, so there is nothing to paste into a chat.
 */
@RestController
@RequestMapping("/api/v1/staff")
public class StaffDocumentController {

	private final StaffDocumentService documents;

	public StaffDocumentController(StaffDocumentService documents) {
		this.documents = documents;
	}

	/**
	 * Attaches one file, replacing whatever was there of the same kind.
	 *
	 * <p>{@code kind} is read as text and matched here rather than bound to the enum, following
	 * {@code AttachmentController}: bound, an unknown value is answered by Spring with a list of the
	 * choices and a message nobody wrote. {@code file} is optional for the same reason — declared
	 * required, a missing part raises an exception nothing maps, and the person is told something
	 * went wrong at our end when in fact they chose nothing.
	 */
	@PostMapping("/members/{id}/documents")
	@PreAuthorize("hasAuthority('MANAGE_STAFF')")
	public ResponseEntity<StaffDocumentView> attach(
			@PathVariable UUID id,
			@RequestParam(name = "kind") String kind,
			@RequestParam(name = "file", required = false) MultipartFile file,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		StaffDocumentKind wanted = Arrays.stream(StaffDocumentKind.values())
				.filter(k -> k.name().equals(kind))
				.findFirst()
				.orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("kind", kind),
						List.of(new ErrorResponse.FieldError("kind", "Choose one of "
								+ String.join(", ", Arrays.stream(StaffDocumentKind.values()).map(Enum::name).toList())
								+ ".")), null));

		return ResponseEntity.status(HttpStatus.CREATED).body(documents.attach(actor, id, wanted, file));
	}

	/**
	 * One document's bytes. Inline, so a browser shows the photograph or the PDF rather than saving
	 * it, under the name the device gave it. The type is the one the bytes were found to be on
	 * upload, never what was declared, so a file is always served as what it is.
	 *
	 * <p>{@code no-store, private}: this is a photograph of somebody's Aadhaar card, and it has no
	 * business sitting in a shared cache or on disk behind the reader's back.
	 */
	@GetMapping("/members/{id}/documents/{documentId}")
	@PreAuthorize("hasAuthority('MANAGE_STAFF')")
	public ResponseEntity<InputStreamResource> open(
			@PathVariable UUID id,
			@PathVariable UUID documentId,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		StaffDocumentService.StaffDocumentFile file = documents.open(actor, id, documentId);
		String name = file.originalName() != null
				? file.originalName()
				: "staff-document" + AttachmentFileType.extensionFor(file.contentType());
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(file.contentType()))
				.contentLength(file.sizeBytes())
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.inline().filename(name, StandardCharsets.UTF_8).build().toString())
				.cacheControl(CacheControl.noStore().cachePrivate())
				.body(new InputStreamResource(file.content()));
	}

	/**
	 * Takes one off the record.
	 *
	 * <p>A DELETE and not a POST, unlike voiding a payment: nothing about this is a ledger entry that
	 * has to stay and be marked. A wrongly attached photograph is a wrongly attached photograph, and
	 * the fact that it was there and was taken off is on the audit log, which is where that belongs.
	 */
	@DeleteMapping("/members/{id}/documents/{documentId}")
	@PreAuthorize("hasAuthority('MANAGE_STAFF')")
	public ResponseEntity<Void> remove(
			@PathVariable UUID id,
			@PathVariable UUID documentId,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		documents.remove(actor, id, documentId);
		return ResponseEntity.noContent().build();
	}
}
