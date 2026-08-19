package org.iskcon.kms.communication;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a devotee has chosen to hear (E8-S1), on their own account page.
 *
 * <p>No permission annotation and none needed, exactly as {@code ProfileController}: every method
 * acts on the authenticated principal's own row.
 */
@RestController
@RequestMapping("/api/v1/profile/communications")
public class CommunicationPreferenceController {

	private final CommunicationPreferenceService preferences;

	public CommunicationPreferenceController(CommunicationPreferenceService preferences) {
		this.preferences = preferences;
	}

	@GetMapping
	public PreferencesView get(@AuthenticationPrincipal AuthenticatedUser actor) {
		CommunicationPreferences prefs = preferences.forUser(actor.getUserId());
		return new PreferencesView(
				prefs.optedOutOfAll(),
				java.util.Arrays.stream(CommunicationCategory.values())
						.map(c -> new CategoryChoice(
								c, c.label(), c.description(), c.isOptional(), prefs.accepts(c)))
						.toList());
	}

	@PutMapping
	public ResponseEntity<PreferencesView> update(
			@Valid @RequestBody UpdatePreferenceRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {

		if (request.allOptional() != null) {
			preferences.setAllOptional(actor.getUserId(), request.allOptional());
		} else {
			preferences.setCategory(actor.getUserId(), request.category(), request.wanted(),
					CommunicationPreferenceService.Source.PROFILE);
		}
		return ResponseEntity.ok(get(actor));
	}

	/**
	 * Every category, whether it can be declined, and whether they currently get it — including the
	 * operational one, shown so a devotee can see what will keep reaching them whatever they turn
	 * off. A preferences screen that hides the things you cannot change is a screen that makes people
	 * wonder what else it is not telling them.
	 */
	public record PreferencesView(boolean optedOutOfAll, List<CategoryChoice> categories) {
	}

	public record CategoryChoice(
			CommunicationCategory value, String label, String description,
			boolean optional, boolean subscribed) {
	}

	/**
	 * Either the blanket switch or one category — never both in one request, so that "turn everything
	 * off" and "turn the newsletter back on" cannot arrive together and race.
	 */
	public record UpdatePreferenceRequest(
			/** True means they want optional communications; false means none at all. */
			Boolean allOptional,
			CommunicationCategory category,
			boolean wanted) {

		@jakarta.validation.constraints.AssertTrue(
				message = "Say either whether you want optional communications, or which kind you mean.")
		public boolean isExactlyOneThing() {
			return (allOptional == null) != (category == null);
		}
	}
}
