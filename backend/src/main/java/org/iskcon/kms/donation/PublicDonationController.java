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

	/** Public page identity + 80G flag, resolved by slug (E7-S1). */
	@GetMapping("/donation-page")
	public Map<String, Object> page(@PathVariable String slug) {
		return withTenant(slug, tenantId -> {
			Map<String, Object> row = jdbc.queryForMap(
					"SELECT name, is_80g_approved FROM tenants WHERE id = ?", tenantId);
			return Map.of("templeName", row.get("name"), "is80gApproved", row.get("is_80g_approved"),
					"presets", java.util.List.of(51, 501, 1001));
		});
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

	/** Starts a wish-list sponsorship (E7-S6): quantity × price, reusing the donation pipeline. */
	@PostMapping("/wishlist/{itemId}/sponsor")
	public ResponseEntity<DonationCheckout> sponsor(
			@PathVariable String slug, @PathVariable UUID itemId,
			@Valid @RequestBody SponsorRequest request) {
		DonationCheckout checkout = withTenant(slug,
				tenantId -> donationService.startWishlistCheckout(request.toDonor(), itemId, request.quantity()));
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
				"SELECT id FROM tenants WHERE slug = ? AND status = 'ACTIVE'",
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
