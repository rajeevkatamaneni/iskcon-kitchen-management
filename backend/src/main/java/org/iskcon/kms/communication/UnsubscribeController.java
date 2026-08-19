package org.iskcon.kms.communication;

import java.util.Map;
import org.iskcon.kms.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stopping the messages, without signing in (E8-S1).
 *
 * <p>Public on purpose. Somebody who wants a temple to stop writing to them is the least likely
 * person to go and find their password, and Gmail's bulk-sender rules require a withdrawal that
 * works from the mail client itself. The token is what stands in for authentication: it proves we
 * issued the link, and it authorises exactly one thing for exactly one person.
 *
 * <p>Both verbs exist and they are not the same. {@code GET} describes what the link would do, for
 * the page a person lands on when they click it. {@code POST} does it, which is what a mail client
 * calls on its own when it honours {@code List-Unsubscribe-Post} — a link that a scanner could
 * follow and silently unsubscribe somebody would be worse than no link.
 */
@RestController
@RequestMapping("/api/v1/public/unsubscribe")
public class UnsubscribeController {

	private static final Logger log = LoggerFactory.getLogger(UnsubscribeController.class);

	private final UnsubscribeTokens tokens;
	private final CommunicationPreferenceService preferences;

	public UnsubscribeController(
			UnsubscribeTokens tokens, CommunicationPreferenceService preferences) {
		this.tokens = tokens;
		this.preferences = preferences;
	}

	/** What this link would stop. Reads nothing about the person beyond what the token already says. */
	@GetMapping
	@PreAuthorize("permitAll()")
	public ResponseEntity<Map<String, Object>> describe(@RequestParam String token) {
		UnsubscribeTokens.Claim claim = tokens.verify(token);
		if (claim == null) {
			return ResponseEntity.badRequest().body(Map.of("valid", false));
		}
		return ResponseEntity.ok(Map.of(
				"valid", true,
				"allOptional", claim.allOptional(),
				"category", claim.category() == null ? "" : claim.category().name(),
				"label", claim.category() == null
						? "every optional message from this temple" : claim.category().label()));
	}

	@PostMapping
	@PreAuthorize("permitAll()")
	@Transactional
	public ResponseEntity<Map<String, Object>> unsubscribe(@RequestParam String token) {
		UnsubscribeTokens.Claim claim = tokens.verify(token);
		if (claim == null) {
			// Deliberately says nothing about why. A tampered token is not owed an explanation.
			return ResponseEntity.badRequest().body(Map.of("done", false));
		}

		// The write is tenant-scoped like every other, and the tenant comes from the signed token
		// rather than from anything the caller typed.
		TenantContext.set(claim.tenantId());
		try {
			if (claim.allOptional()) {
				preferences.setAllOptional(claim.userId(), false);
			} else if (claim.category() != null) {
				preferences.setCategory(claim.userId(), claim.category(), false,
						CommunicationPreferenceService.Source.UNSUBSCRIBE_LINK);
			}
			log.info("Unsubscribe honoured for user {} ({})", claim.userId(),
					claim.allOptional() ? "all optional" : claim.category());
		} finally {
			TenantContext.clear();
		}

		return ResponseEntity.ok(Map.of(
				"done", true,
				"label", claim.category() == null
						? "every optional message from this temple" : claim.category().label()));
	}
}
