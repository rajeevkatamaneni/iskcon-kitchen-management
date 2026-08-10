package org.iskcon.kms.profile;

import jakarta.validation.Valid;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A user's own account. No permission annotation and none needed: every method acts on the
 * authenticated principal's own row, so authentication (which the security config already
 * requires for anything non-public) is the whole of the authorisation.
 */
@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {

	private final ProfileService profileService;

	public ProfileController(ProfileService profileService) {
		this.profileService = profileService;
	}

	@GetMapping
	public ProfileView get(@AuthenticationPrincipal AuthenticatedUser actor) {
		return profileService.currentProfile(actor);
	}

	/** Change the preferred notification channel. Returns the updated profile. */
	@PatchMapping
	public ProfileView update(
			@Valid @RequestBody UpdateChannelRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return profileService.changeChannel(actor, request.preferredChannel());
	}

	/** Record consent to be contacted, against the current wording. Returns the updated profile. */
	@PostMapping("/consent")
	public ProfileView consent(@AuthenticationPrincipal AuthenticatedUser actor) {
		return profileService.giveConsent(actor);
	}
}
