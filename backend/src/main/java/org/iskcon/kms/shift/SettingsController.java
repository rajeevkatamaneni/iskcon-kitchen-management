package org.iskcon.kms.shift;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Per-tenant settings (E6-S7), behind {@code MANAGE_TEMPLE_SETTINGS}. */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

	private final TenantSettingsService service;

	public SettingsController(TenantSettingsService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public Map<String, Object> get() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("volunteerBroadcastDailyLimit", service.volunteerBroadcastDailyLimit());
		body.put("locale", service.locale());
		// Null until somebody chooses, which is not the same as choosing the default. The screen
		// shows what the temple is wearing either way; this is what it has actually said.
		body.put("themeId", service.themeId());
		// How much notice this temple wants, on the three things that warn ahead of a date
		// (V85, and V87 for the servicing one).
		body.put("stockExpiryWarningDays", service.stockExpiryWarningDays());
		body.put("contractEndWarningDays", service.contractEndWarningDays());
		body.put("equipmentServiceWarningDays", service.equipmentServiceWarningDays());
		return body;
	}

	/**
	 * The colour scheme the whole temple wears (2026-08-28).
	 *
	 * <p>Behind the same permission as every other setting here, because that is exactly what it
	 * is. The themes themselves live in the frontend and never reach this database — all that is
	 * recorded here is which one the temple picked, as an opaque identifier.
	 */
	@PutMapping("/theme")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public ResponseEntity<Void> setTheme(@Valid @RequestBody UpdateThemeRequest request) {
		service.setThemeId(request.themeId());
		return ResponseEntity.noContent().build();
	}

	/**
	 * The language the temple works in — what a job card prints in when the person at the printer
	 * does not choose otherwise (build brief §3).
	 */
	@PutMapping("/language")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public ResponseEntity<Void> setLanguage(@Valid @RequestBody UpdateLanguageRequest request) {
		service.setLanguage(request.language());
		return ResponseEntity.noContent().build();
	}

	/**
	 * How much notice the temple wants before a batch expires, before a vendor's agreement runs out,
	 * and before a machine falls due for service (V85, E5-S1 D2; V87, E3-S10 D5).
	 *
	 * <p>One endpoint for three settings, which is a departure from the one-setting-one-endpoint
	 * shape above and is the point rather than an oversight. The first two were a single shared
	 * constant until V85, and they separated because seven days is the wrong notice for a contract —
	 * not because they stopped being one decision. Saving them together is what keeps anyone from
	 * moving one and forgetting the others.
	 *
	 * <p>All three are required. The servicing horizon was optional while the settings screen had no
	 * control for it; E3-S11 gave it one, so a body naming fewer than three is now refused rather
	 * than applied in part.
	 */
	@PutMapping("/warning-horizons")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public ResponseEntity<Void> setWarningHorizons(@Valid @RequestBody UpdateWarningHorizonsRequest request) {
		service.setWarningHorizons(request.stockExpiryWarningDays(), request.contractEndWarningDays(),
				request.equipmentServiceWarningDays());
		return ResponseEntity.noContent().build();
	}

	@PutMapping("/volunteer-broadcast-limit")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public ResponseEntity<Void> setBroadcastLimit(@Valid @RequestBody UpdateBroadcastLimitRequest request) {
		service.setVolunteerBroadcastDailyLimit(request.limit());
		return ResponseEntity.noContent().build();
	}

	/**
	 * How many days ahead each of the three warnings starts.
	 *
	 * <p>1 to 365 on all three. Zero would warn on the morning the thing had already expired, already
	 * ended or already fell due, and a year warns about everything a temple holds, has signed or
	 * owns, which is the same as warning about nothing. The database carries the same bounds as a
	 * CHECK.
	 */
	public record UpdateWarningHorizonsRequest(
			@Min(value = 1, message = "A stock warning is between 1 and 365 days ahead.")
			@Max(value = 365, message = "A stock warning is between 1 and 365 days ahead.")
			int stockExpiryWarningDays,

			@Min(value = 1, message = "A contract warning is between 1 and 365 days ahead.")
			@Max(value = 365, message = "A contract warning is between 1 and 365 days ahead.")
			int contractEndWarningDays,

			// The servicing horizon (V87, E3-S10 D5). It was an Integer, and optional, for as long
			// as the settings form was built for two horizons — a plain int then would have had
			// every save from that form silently reset a third setting it was not showing. E3-S11
			// gave it a control, the screen posts all three, and the transition is over. A body
			// that leaves it out now reads as zero and is refused by the bound below, which is the
			// right answer: a caller that does not say what the horizon should be is not entitled
			// to change the other two.
			@Min(value = 1, message = "A service warning is between 1 and 365 days ahead.")
			@Max(value = 365, message = "A service warning is between 1 and 365 days ahead.")
			int equipmentServiceWarningDays) {
	}

	/** The new daily broadcast cap. */
	public record UpdateBroadcastLimitRequest(
			@Positive(message = "A daily limit of at least one message is needed.") int limit) {
	}

	/** An ISO 639-1 code — {@code kn}, {@code hi}, {@code en}. The region is added on the way in. */
	public record UpdateLanguageRequest(
			@NotBlank(message = "Choose a language.") String language) {
	}

	/** A theme's identifier — {@code temple-terracotta}, {@code harbour-blue}. */
	public record UpdateThemeRequest(
			@NotBlank(message = "Choose a theme.") String themeId) {
	}
}
