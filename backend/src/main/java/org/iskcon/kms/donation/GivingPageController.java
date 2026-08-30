package org.iskcon.kms.donation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.iskcon.kms.wishlist.WishlistItemView;
import org.iskcon.kms.wishlist.WishlistService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the giving screen needs before anybody gives anything: who this temple is, whether it can
 * issue an 80G certificate, what the kitchen is cooking today, what a plate costs, and where last
 * month's money went.
 *
 * <p>These two endpoints were the readable half of a public, unauthenticated controller that took
 * the temple from a slug in the address. That controller was withdrawn on 2026-08-29 when giving
 * became something only a signed-in devotee can do — but these two are not part of what was
 * withdrawn. They are what the signed-in giving page has always been drawing, and deleting them
 * with the rest would have left `/donate` blank: no temple name, no suggested amounts, no 80G flag,
 * no plates, no equipment list.
 *
 * <p>So the tenant no longer comes from the address at all. It comes from the verified token, the
 * way it does everywhere else in this application, and the slug is gone from both the path and the
 * client. That is the part of this move worth noticing: an endpoint that took a tenant from the URL
 * and one that takes it from an identity are different endpoints, whatever their body returns.
 *
 * <p>Behind {@code isAuthenticated()} rather than a permission. Giving is not a privilege — every
 * devotee and volunteer at a temple can give to it, and the ones who can also *record* somebody
 * else's gift are a different, permissioned surface (see {@code DonationController}).
 */
@RestController
@RequestMapping("/api/v1/donations")
public class GivingPageController {

	private final JdbcTemplate jdbc;
	private final WishlistService wishlistService;

	public GivingPageController(JdbcTemplate jdbc, WishlistService wishlistService) {
		this.jdbc = jdbc;
		this.wishlistService = wishlistService;
	}

	/**
	 * The temple's identity, its 80G flag, and the two figures that turn a donation into a plate of
	 * prasadam: how many are being served today, and what one costs (E7-S1).
	 *
	 * <p>Both are computed from what the temple has actually done, and both are null when it has not
	 * done enough of it yet. A page that invents a cost per plate is worse than one that leaves the
	 * sentence out.
	 */
	@GetMapping("/page")
	@PreAuthorize("isAuthenticated()")
	public Map<String, Object> page() {
		Map<String, Object> row = jdbc.queryForMap("""
				SELECT name, is_80g_approved FROM tenants
				WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""");

		Map<String, Object> page = new LinkedHashMap<>();
		page.put("templeName", row.get("name"));
		page.put("is80gApproved", row.get("is_80g_approved"));
		page.put("presets", List.of(500, 1100, 2500, 5000));
		page.put("platesToday", platesToday());
		page.put("costPerPlateInr", costPerPlate());
		page.put("spendShares", spendShares());
		return page;
	}

	/** The equipment a temple is hoping for: active items, and briefly the ones just fulfilled (E7-S6). */
	@GetMapping("/wishlist")
	@PreAuthorize("isAuthenticated()")
	public List<WishlistItemView> wishlist() {
		return wishlistService.forGiving();
	}

	// ---------------------------------------------------------------------

	/**
	 * Plates the kitchen is cooking today, across every meal on the plan. Null if nothing is planned.
	 *
	 * <p><strong>Per meal, never per dish.</strong> {@code meal_plans} holds one row per preparation,
	 * so summing it counted a three-dish lunch for 250 as 750 plates — against the rule stated in
	 * {@code ServedMeal}: "three dishes at 250 servings each is 250 plates, not 750". That number is
	 * shown to donors, and {@link #costPerPlate} divides by it, so the error understated the cost of
	 * a plate by roughly the number of dishes in a meal.
	 *
	 * <p>A meal's plates come from its head count, as {@code ServedMealService.platesOf} has done
	 * since V51. The pre-V51 fallback to the planned figure is kept, but only for a recipe measured
	 * in servings — since V69 a target may be litres or kilograms, and neither is a number of
	 * people. A meal that can answer neither way contributes nothing rather than a guess.
	 */
	private Integer platesToday() {
		Integer plates = jdbc.queryForObject(
				PLATES_PER_MEAL.formatted("mp.plan_date = CURRENT_DATE AND mp.status <> 'CANCELLED'"),
				Integer.class);
		return plates == null || plates == 0 ? null : plates;
	}

	/**
	 * Plates per meal, summed. One row per preparation collapses to one figure per
	 * {@code (date, meal kind)} by taking the largest — the kitchen cooks for whoever turns up, and
	 * dishes of one meal disagree only when one was added against a changed head count.
	 */
	private static final String PLATES_PER_MEAL = """
			SELECT COALESCE(SUM(plates), 0)::int FROM (
				SELECT max(
					CASE
						WHEN mp.adults IS NULL AND mp.children IS NULL AND mp.seniors IS NULL
							THEN CASE WHEN r.base_yield_unit = 'SERVINGS' THEN mp.target_yield END
						ELSE coalesce(mp.adults, 0)
							+ 0.6 * coalesce(mp.children, 0)
							+ 0.8 * coalesce(mp.seniors, 0)
					END
				) AS plates
				FROM meal_plans mp
				JOIN recipes r ON r.id = mp.recipe_id
				WHERE %s
				GROUP BY mp.plan_date, mp.meal_kind
			) per_meal
			""";

	/**
	 * What one plate costs: the last month's kitchen spend over the plates it produced. Null until
	 * the temple has both — a made-up number here would be quoted back at them by a donor.
	 */
	private BigDecimal costPerPlate() {
		BigDecimal spend = jdbc.queryForObject("""
				SELECT COALESCE(SUM(amount), 0) FROM vendor_invoices
				WHERE invoice_date >= CURRENT_DATE - INTERVAL '30 days'
				""", BigDecimal.class);
		Integer plates = jdbc.queryForObject(
				PLATES_PER_MEAL.formatted(
						"mp.plan_date >= CURRENT_DATE - INTERVAL '30 days' AND mp.status = 'COOKED'"),
				Integer.class);
		if (spend == null || plates == null || spend.signum() <= 0 || plates <= 0) {
			return null;
		}
		return spend.divide(BigDecimal.valueOf(plates), 0, RoundingMode.HALF_UP);
	}

	/**
	 * Where last month's money went, by the temple's own ingredient categories rather than invented
	 * buckets — the three largest, with the rest gathered up. Empty until there is spending to show.
	 */
	private List<Map<String, Object>> spendShares() {
		List<Map<String, Object>> rows = jdbc.query("""
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
			return List.of();
		}

		BigDecimal total = rows.stream()
				.map(r -> (BigDecimal) r.get("spend"))
				.reduce(BigDecimal.ZERO, BigDecimal::add);

		List<Map<String, Object>> shares = new ArrayList<>();
		int shown = Math.min(3, rows.size());
		for (int i = 0; i < shown; i++) {
			shares.add(share((String) rows.get(i).get("label"),
					(BigDecimal) rows.get(i).get("spend"), total));
		}
		if (rows.size() > shown) {
			BigDecimal rest = rows.subList(shown, rows.size()).stream()
					.map(r -> (BigDecimal) r.get("spend"))
					.reduce(BigDecimal.ZERO, BigDecimal::add);
			shares.add(share("Everything else", rest, total));
		}
		return shares;
	}

	private static Map<String, Object> share(String label, BigDecimal spend, BigDecimal total) {
		int percent = spend.multiply(BigDecimal.valueOf(100))
				.divide(total, 0, RoundingMode.HALF_UP).intValue();
		return Map.of("label", label, "percent", percent);
	}
}
