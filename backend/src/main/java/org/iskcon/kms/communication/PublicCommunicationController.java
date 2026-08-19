package org.iskcon.kms.communication;

import java.util.Map;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The web copy of a communication (E8-S2) — what the WhatsApp link points at, and what the "read
 * this in your browser" line in every email opens.
 *
 * <p>Public on purpose, and it has to be: a devotee reading a newsletter on a phone should not meet
 * a sign-in screen halfway through one, and the WhatsApp form of a communication is nothing *but*
 * this link. What protects it is that the address is unguessable and belongs to no sequence, so
 * holding one link tells you nothing about any other. Drafts have no address at all — only a sent
 * communication resolves, so nothing half-written is ever readable.
 *
 * <p>The body was sanitised on the way in, which is what makes serving it here safe rather than
 * hopeful.
 */
@RestController
@RequestMapping("/api/v1/public/communications")
public class PublicCommunicationController {

	private final CommunicationService service;

	public PublicCommunicationController(CommunicationService service) {
		this.service = service;
	}

	@GetMapping("/{token}")
	@PreAuthorize("permitAll()")
	public CommunicationService.PublicCommunicationView read(@PathVariable String token) {
		return service.publicCopy(token).orElseThrow(() ->
				// Deliberately the same answer as a token that never existed: whether a given address
				// is a real communication is not something an unauthenticated caller is owed.
				new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("communication", "unknown")));
	}
}
