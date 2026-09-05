package org.iskcon.kms.geo;

import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The default: no suggestions, and the delivery address stays the plain text box it has always been.
 *
 * <p>Not a degraded mode and not an error. A temple with no Places key types an address as it always
 * did; the plan saves, the card prints, and the only thing missing is the travel estimate that
 * needed coordinates. Nothing on any screen tells anybody off about it.
 */
@Component
@ConditionalOnProperty(name = "kms.places.provider", havingValue = "none", matchIfMissing = true)
public class NoPlaceSuggestionProvider implements PlaceSuggestionProvider {

	@Override
	public boolean configured() {
		return false;
	}

	@Override
	public List<Suggestion> suggest(String typed, String sessionToken) {
		return List.of();
	}

	@Override
	public Optional<Place> resolve(String placeId, String sessionToken) {
		return Optional.empty();
	}
}
