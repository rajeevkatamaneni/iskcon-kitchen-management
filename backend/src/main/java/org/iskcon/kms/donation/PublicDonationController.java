package org.iskcon.kms.donation;

import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public, unauthenticated donation surface (E7-S1/S2). The tenant is resolved server-side from
 * the page slug — never from a client-supplied id — and set as the context for the donation. The
 * page-info endpoint gives the public page its branding and 80G flag without exposing anything private.
 */
@RestController
@RequestMapping("/api/v1/public/t/{slug}")
public class PublicDonationController {

	private final JdbcTemplate jdbc;
	private final MonetaryDonationService donationService;
	private final org.iskcon.kms.wishlist.WishlistService wishlistService;

	public PublicDonationController(JdbcTemplate jdbc, MonetaryDonationService donationService,
			org.iskcon.kms.wishlist.WishlistService wishlistService) {
		this.jdbc = jdbc;
		this.donationService = donationService;
		this.wishlistService = wishlistService;
	}

	/**
	 * Public page identity, 80G flag, and the two figures that turn a donation into a plate of
	 * prasadam: how many are being served today, and what one costs (E7-S1).
	 *
	 * <p>Both are computed from what the temple has actually done, and both are null when it has not
	 * done enough of it yet. A page that invents a cost per plate is worse than one that leaves the
	 * sentence out.
	 */
	@GetMapping("/donation-page")
	public Map<String, Object> page(@PathVariable String slug) {
		return withTenant(slug, tenantId -> {
			Map<String, Object> row = jdbc.queryForMap(
					"SELECT name, is_80g_approved FROM tenants WHERE id = ?", tenantId);

			Map<String, Object> page = new java.util.LinkedHashMap<>();
			page.put("templeName", row.get("name"));
			page.put("is80gApproved", row.get("is_80g_approved"));
			page.put("presets", java.util.List.of(500, 1100, 2500, 5000));
			page.put("platesToday", platesToday());
			page.put("costPerPlateInr", costPerPlate());
			page.put("spendShares", spendShares());
			return page;
		});
	}

	/** Plates the kitchen is cooking today, across every meal on the plan. Null if nothing is planned. */
	private Integer platesToday() {
		Integer plates = jdbc.queryForObject("""
				SELECT COALESCE(SUM(target_servings), 0)::int FROM meal_plans
				WHERE plan_date = CURRENT_DATE AND status <> 'CANCELLED'
				""", Integer.class);
		return plates == null || plates == 0 ? null : plates;
	}

	/**
	 * What one plate costs: the last month's kitchen spend over the plates it produced. Null until
	 * the temple has both — a made-up number here would be quoted back at them by a donor.
	 */
	private java.math.BigDecimal costPerPlate() {
		java.math.BigDecimal spend = jdbc.queryForObject("""
				SELECT COALESCE(SUM(amount), 0) FROM vendor_invoices
				WHERE invoice_date >= CURRENT_DATE - INTERVAL '30 days'
				""", java.math.BigDecimal.class);
		Integer plates = jdbc.queryForObject("""
				SELECT COALESCE(SUM(target_servings), 0)::int FROM meal_plans
				WHERE plan_date >= CURRENT_DATE - INTERVAL '30 days' AND status = 'COOKED'
				""", Integer.class);
		if (spend == null || plates == null || spend.signum() <= 0 || plates <= 0) {
			return null;
		}
		return spend.divide(java.math.BigDecimal.valueOf(plates), 0, java.math.RoundingMode.HALF_UP);
	}

	/**
	 * Where last month's money went, by the temple's own ingredient categories rather than invented
	 * buckets — the three largest, with the rest gathered up. Empty until there is spending to show.
	 */
	private java.util.List<Map<String, Object>> spendShares() {
		java.util.List<Map<String, Object>> rows = jdbc.query("""
				SELECT i.category AS label, SUM(pol.quantity * COALESCE(pol.expected_price, 0)) AS spend
				FROM purchase_order_lines pol
				JOIN purchase_orders po ON po.id = pol.po_id
				JOIN ingredients i ON i.id = pol.ingredient_id
				WHERE po.created_at >= CURRENT_DATE - INTERVAL '30 days'
				GROUP BY i.category
				HAVING SUM(pol.quantity * COALESCE(pol.expected_price, 0)) > 0
				ORDER BY spend DESC
				""", (rs, n) -> Map.<String, Object>of(
						"label", rs.getString("label"), "spend", rs.getBigDecimal("spend")));
		if (rows.isEmpty()) {
			return java.util.List.of();
		}

		java.math.BigDecimal total = rows.stream()
				.map(r -> (java.math.BigDecimal) r.get("spend"))
				.reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

		java.util.List<Map<String, Object>> shares = new java.util.ArrayList<>();
		int shown = Math.min(3, rows.size());
		for (int i = 0; i < shown; i++) {
			shares.add(share((String) rows.get(i).get("label"),
					(java.math.BigDecimal) rows.get(i).get("spend"), total));
		}
		if (rows.size() > shown) {
			java.math.BigDecimal rest = rows.subList(shown, rows.size()).stream()
					.map(r -> (java.math.BigDecimal) r.get("spend"))
					.reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
			shares.add(share("Everything else", rest, total));
		}
		return shares;
	}

	private static Map<String, Object> share(String label, java.math.BigDecimal spend, java.math.BigDecimal total) {
		int percent = spend.multiply(java.math.BigDecimal.valueOf(100))
				.divide(total, 0, java.math.RoundingMode.HALF_UP).intValue();
		return Map.of("label", label, "percent", percent);
	}

	/** Starts a one-time donation (E7-S2): creates the order + PENDING record for hosted checkout. */
	@PostMapping("/donations")
	public ResponseEntity<DonationCheckout> donate(
			@PathVariable String slug, @Valid @RequestBody PublicDonationRequest request) {
		DonationCheckout checkout = withTenant(slug,
				tenantId -> donationService.startCheckout(request.toDonor(), request.amountInr(), null));
		return ResponseEntity.status(HttpStatus.CREATED).body(checkout);
	}

	/** The public wish list for a temple (E7-S6): active and briefly-visible fulfilled items. */
	@GetMapping("/wishlist")
	public java.util.List<org.iskcon.kms.wishlist.WishlistItemView> wishlist(@PathVariable String slug) {
		return withTenant(slug, tenantId -> wishlistService.publicList());
	}

	/**
	 * Starts a wish-list contribution (E7-S6): whole units, or an amount towards the cost, reusing
	 * the donation pipeline either way.
	 */
	@PostMapping("/wishlist/{itemId}/sponsor")
	public ResponseEntity<DonationCheckout> sponsor(
			@PathVariable String slug, @PathVariable UUID itemId,
			@Valid @RequestBody SponsorRequest request) {
		DonationCheckout checkout = withTenant(slug,
				tenantId -> donationService.startWishlistCheckout(
						request.toDonor(), itemId, request.quantity(), request.amountInr(), null));
		return ResponseEntity.status(HttpStatus.CREATED).body(checkout);
	}

	/** Named sponsors of an item, for public "Sponsored by …" recognition (E7-S6). */
	@GetMapping("/wishlist/{itemId}/sponsors")
	public java.util.List<String> sponsors(@PathVariable String slug, @PathVariable UUID itemId) {
		return withTenant(slug, tenantId -> wishlistService.publicSponsors(itemId));
	}

	// ---------------------------------------------------------------------

	private <T> T withTenant(String slug, java.util.function.Function<UUID, T> action) {
		UUID tenantId = jdbc.query(
				"SELECT id FROM tenants WHERE slug = ?",
				(rs, n) -> rs.getObject("id", UUID.class), slug).stream().findFirst()
				.orElseThrow(() -> new ApplicationException(ErrorCode.TENANT_NOT_FOUND, Map.of("slug", slug)));
		TenantContext.set(tenantId);
		try {
			return action.apply(tenantId);
		} finally {
			TenantContext.clear();
		}
	}
}
