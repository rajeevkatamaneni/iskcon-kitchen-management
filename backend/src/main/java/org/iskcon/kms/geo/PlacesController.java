package org.iskcon.kms.geo;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Address suggestions, proxied (2026-09-05).
 *
 * <p>The browser asks us and we ask Google, so the Maps key stays on the server — see
 * {@link PlaceSuggestionProvider} for why that is worth a round trip. Both endpoints answer with an
 * empty result rather than an error when there is no map service: an address box with no suggestions
 * is the plain text box every temple had before this, which works.
 *
 * <p>Behind {@code MANAGE_MEAL_PLANS} because the one thing this is for is typing a delivery address
 * onto a meal plan. It is a paid lookup, so it is not left open to anybody with a session.
 */
@RestController
@RequestMapping("/api/v1/places")
public class PlacesController {

	private final PlaceSuggestionProvider places;

	public PlacesController(PlaceSuggestionProvider places) {
		this.places = places;
	}

	/**
	 * Whether the address box should offer suggestions at all.
	 *
	 * <p>Asked once when the form opens, so the screen can decide between a picker and a plain box
	 * before anybody types — rather than showing a picker that will never suggest anything.
	 */
	@GetMapping("/available")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public Availability available() {
		return new Availability(places.configured());
	}

	public record Availability(boolean available) {
	}

	/**
	 * @param session a token the caller generates once per search and keeps until something is
	 *                picked. It is what makes a whole search bill as one lookup instead of one per
	 *                keystroke.
	 */
	@GetMapping("/suggest")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public List<PlaceSuggestionProvider.Suggestion> suggest(
			@RequestParam String q, @RequestParam(required = false) String session) {
		return places.suggest(q, session);
	}

	/** The address and coordinates behind a picked suggestion. 204 where it could not be resolved. */
	@GetMapping("/{placeId}")
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public org.springframework.http.ResponseEntity<PlaceSuggestionProvider.Place> resolve(
			@PathVariable String placeId, @RequestParam(required = false) String session) {
		return places.resolve(placeId, session)
				.map(org.springframework.http.ResponseEntity::ok)
				.orElseGet(() -> org.springframework.http.ResponseEntity.noContent().build());
	}
}
