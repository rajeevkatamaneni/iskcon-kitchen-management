package org.iskcon.kms.today;

import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The temple's morning screen (E4-S8): what today needs, in one request.
 *
 * <p>Behind {@code MANAGE_MEAL_PLANS} — the permission both temple roles hold and no volunteer does.
 * A volunteer has their own shifts and nothing to run; a platform operator has no temple day at all.
 * Where the reader may not see a figure, {@link TodayService} leaves it out.
 */
@RestController
@RequestMapping("/api/v1/today")
public class TodayController {

	private final TodayService todayService;

	public TodayController(TodayService todayService) {
		this.todayService = todayService;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_MEAL_PLANS')")
	public TodayView today(@AuthenticationPrincipal AuthenticatedUser actor) {
		return todayService.today(actor);
	}
}
