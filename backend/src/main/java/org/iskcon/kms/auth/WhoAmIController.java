package org.iskcon.kms.auth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.kitchen.KitchenOrder;
import org.iskcon.kms.shift.TenantSettingsService;
import org.iskcon.kms.user.User;
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
	private final TenantSettingsService settings;
	private final KitchenOrder kitchenOrder;

	public WhoAmIController(JdbcTemplate jdbc, TenantSettingsService settings, KitchenOrder kitchenOrder) {
		this.jdbc = jdbc;
		this.settings = settings;
		this.kitchenOrder = kitchenOrder;
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
		// The temple's public pages — giving, the wish list — live under its slug, and a devotee's
		// menu has to be able to reach them.
		body.put("tenantSlug", templeSlug(user));
		// Every temple this person serves at, oldest first — the first is their home. The menu needs
		// the whole set to offer a switch; the request itself still speaks for one of them.
		body.put("temples", temples(user));
		// Which colours this temple works in. Carried here rather than fetched separately because
		// every person who serves at a temple sees the same ones whatever their role, so the one
		// request every session already makes is the request that should answer it — and switching
		// temples repaints without anybody arranging for it to.
		//
		// The identifier only. The palettes live in the frontend, so there is nothing to send: the
		// browser already holds every colour and needs to be told which set to use. Null when the
		// temple has never chosen, and null for an operator — who has no app.tenant_id, so the
		// row-level policy matches nothing without any special case here. Both mean "the default",
		// which the resolver on the other side supplies.
		body.put("themeId", settings.themeId());
		// The temple's own clock, and the browser cannot work it out for itself.
		//
		// Rajeev, 2026-09-05: "ALL Date and Time values for that Temple MUST be in that Time zone
		// irrespective of where the Temples dedicated tenant is being accessed from." The frontend
		// had `Asia/Kolkata` as a module constant, which is right for every temple onboarded so far
		// and wrong for the first one that is not — and being a constant, it was wrong in seventeen
		// files at once. It rides on the session for the same reason the theme does: every screen
		// needs it, and one request a session already asks for it.
		//
		// Null for a platform operator, who belongs to no temple. The resolver on the other side
		// falls back to the platform's own zone rather than to the reader's, because an operator
		// comparing two temples wants one clock, not their laptop's.
		body.put("timezone", templeTimezone(user));
		// Which kitchen this person works in, and whether the meal planner is open to them (Epic 12).
		//
		// The kitchen is the one on their current staff record: null for somebody with none — a Temple
		// Admin usually, a devotee, a former employee, and a platform operator always, whose missing
		// app.tenant_id makes the row policy match nothing. The planner opens a new meal with this
		// kitchen's section and lists it first on every meal.
		//
		// canPlanMeals is the very rule PlannerKitchenGuard enforces on the server — the permission,
		// then KitchenOrder.mayPlan — asked through the same method so the menu and the guard cannot
		// disagree. It exists so the menu does not offer a door that answers KMS-400183; it is not the
		// protection, the guard is.
		Optional<UUID> kitchen = user.getTenantId() == null
				? Optional.empty() : kitchenOrder.kitchenOf(user.getUserId());
		body.put("kitchenId", kitchen.orElse(null));
		body.put("kitchenName", kitchen.map(this::kitchenName).orElse(null));
		body.put("canPlanMeals", canPlanMeals(user));

		return ResponseEntity.ok(body);
	}

	/**
	 * Whether the meal planner is open to this person: {@code MANAGE_MEAL_PLANS}, and then the kitchen
	 * rule — the Temple Admin plans for every kitchen; anybody else only while their current staff
	 * record is in a kitchen that is open and plans its meals here. Asked of the authorities the request
	 * was authenticated with, which are the ones {@code RolePermissions} gives the role.
	 */
	private boolean canPlanMeals(AuthenticatedUser user) {
		if (user.getTenantId() == null || user.getUserId() == null) {
			return false;
		}
		boolean holdsPermission = user.getAuthorities().stream()
				.anyMatch(a -> Permission.MANAGE_MEAL_PLANS.name().equals(a.getAuthority()));
		return holdsPermission
				&& kitchenOrder.mayPlan(user.getUserId(), user.getRole() == User.Role.TEMPLE_ADMIN);
	}

	private String kitchenName(UUID kitchenId) {
		return jdbc.query("SELECT name FROM kitchens WHERE id = ?",
				rs -> rs.next() ? rs.getString(1) : null, kitchenId);
	}

	/**
	 * The person's memberships. Read through the same narrow escape the sign-in path uses: the
	 * policy in V2 exposes rows carrying the caller's own verified uid, and nothing else.
	 */
	private List<Map<String, Object>> temples(AuthenticatedUser user) {
		if (user.getFirebaseUid() == null) {
			return List.of();
		}
		return jdbc.query("""
				SELECT u.tenant_id, t.name
				FROM users u JOIN tenants t ON t.id = u.tenant_id
				WHERE u.firebase_uid = ? AND u.status = 'ACTIVE' AND u.tenant_id IS NOT NULL
				ORDER BY u.created_at
				""", (rs, n) -> Map.<String, Object>of(
						"id", rs.getObject("tenant_id", java.util.UUID.class),
						"name", rs.getString("name")),
				user.getFirebaseUid());
	}

	private String templeSlug(AuthenticatedUser user) {
		if (user.getTenantId() == null) {
			return null;
		}
		return jdbc.query("SELECT slug FROM tenants WHERE id = ?",
				rs -> rs.next() ? rs.getString(1) : null, user.getTenantId());
	}

	/**
	 * The IANA zone this temple keeps its day in — "Asia/Kolkata", and one day something else.
	 *
	 * <p>Stored on the temple since V1 and asked for at provisioning, so there is nothing to derive:
	 * a zone worked out from latitude and longitude would be a guess standing in for an answer the
	 * temple has already given.
	 */
	private String templeTimezone(AuthenticatedUser user) {
		if (user.getTenantId() == null) {
			return null;
		}
		return jdbc.query("SELECT timezone FROM tenants WHERE id = ?",
				rs -> rs.next() ? rs.getString(1) : null, user.getTenantId());
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
