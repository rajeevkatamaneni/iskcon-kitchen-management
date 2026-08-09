package org.iskcon.kms.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Returns the caller's own identity as this application understands it.
 *
 * <p>Small but genuinely useful: it is how a client learns its role and temple after sign-in,
 * and it gives both the test suite and manual verification a single endpoint that exercises
 * the whole authentication path without depending on any business feature.
 */
@RestController
@RequestMapping("/api/v1")
public class WhoAmIController {

	@GetMapping("/whoami")
	public ResponseEntity<Map<String, Object>> whoAmI(@AuthenticationPrincipal AuthenticatedUser user) {
		if (user == null) {
			return ResponseEntity.status(401).build();
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("userId", user.getUserId());
		body.put("tenantId", user.getTenantId());
		body.put("role", user.getRole());

		return ResponseEntity.ok(body);
	}
}
