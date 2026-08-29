package org.iskcon.kms.theme;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The catalogue of theme packs a temple can choose from.
 *
 * <p>Read-only, and read by the one person who can act on it. A kitchen assistant sees the colours
 * their temple wears — that arrives with their session, on {@code /whoami} — but has no reason to
 * see the fifteen it does not, and a list of choices in front of somebody who cannot make one is
 * an invitation to ask for something.
 *
 * <p>There is no write endpoint here, and that is the current state of the feature rather than an
 * oversight. Packs are design work: they are produced and contrast-checked outside the application
 * and arrive by migration. The database refuses a write from anybody but a platform operator
 * regardless (V72), so the rule holds whether or not a screen for it is ever built.
 */
@RestController
@RequestMapping("/api/v1/themes")
public class ThemeController {

	private final ThemeService service;

	public ThemeController(ThemeService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public List<ThemePackView> list() {
		return service.catalogue();
	}
}
