package org.iskcon.kms.ingredient;

import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * An ingredient's market rate (R-ING-3): setting it by hand, and the pre-fill for the stock-take box.
 *
 * <p><strong>Its own controller, under the ingredient's path.</strong> {@code IngredientController}
 * is the catalogue's descriptive CRUD behind {@code MANAGE_RECIPES}; a market rate is a price, and
 * the one screen that must be able to ask for it — "Add to inventory" — belongs to the people who
 * count the store room, not necessarily to the people who write recipes.
 *
 * <p><strong>Both behind {@code MANAGE_INVENTORY}.</strong> The requirements document names no
 * permission for the market rate; the conductor ruled on 2026-09-19 that anyone with
 * {@code MANAGE_INVENTORY} may edit it, the same people who already set it at stock-take, since the
 * value box on "Add to inventory" sets it too. A narrower permission on the ingredient page than on
 * the stock-take box would be a locked door beside an open one. The suggestion exists only to
 * pre-fill that box, so it sits behind the same permission.
 */
@RestController
@RequestMapping("/api/v1/ingredients/{id}")
public class MarketRateController {

	private final MarketRateService marketRateService;

	public MarketRateController(MarketRateService marketRateService) {
		this.marketRateService = marketRateService;
	}

	/** Typed on the ingredient's page; source {@code MANUAL}. Blank, 0 or negative is KMS-400161. */
	@PutMapping("/market-rate")
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public ResponseEntity<Void> setMarketRate(
			@PathVariable UUID id,
			@RequestBody SetMarketRateRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		marketRateService.set(actor, id, request.marketRate(), MarketRateService.Source.MANUAL);
		return ResponseEntity.noContent().build();
	}

	/** What the stock-take box starts with: preferred vendor's list price, else the market rate. */
	@GetMapping("/stock-value-suggestion")
	@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
	public StockValueSuggestion stockValueSuggestion(@PathVariable UUID id) {
		return marketRateService.suggestion(id);
	}
}
