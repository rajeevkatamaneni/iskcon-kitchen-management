package org.iskcon.kms.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
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

	private final JdbcTemplate jdbc;

	public WhoAmIController(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@GetMapping("/whoami")
	public ResponseEntity<Map<String, Object>> whoAmI(@AuthenticationPrincipal AuthenticatedUser user) {
		if (user == null) {
			return ResponseEntity.status(401).build();
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("userId", user.getUserId());
		body.put("tenantId", user.getTenantId());
		body.put("role", user.getRole());
		// The person's own name, so the app can address them by it rather than as "you". The
		// principal has carried it all along; only this endpoint never passed it on.
		body.put("fullName", user.getFullName());
		// The temple's own name, so its menu says whose kitchen this is rather than "Your temple".
		// Read here rather than carried on the principal: a rename should show on the next request,
		// not on the next sign-in. A platform operator belongs to no temple, hence the null.
		body.put("tenantName", templeName(user));

		return ResponseEntity.ok(body);
	}

	private String templeName(AuthenticatedUser user) {
		if (user.getTenantId() == null) {
			return null;
		}
		// The tenants table is the one place RLS does not scope for us, so it is queried by id.
		return jdbc.query(
						"SELECT name FROM tenants WHERE id = ?",
						rs -> rs.next() ? rs.getString(1) : null,
						user.getTenantId());
	}
}
