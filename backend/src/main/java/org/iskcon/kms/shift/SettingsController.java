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
		// How much notice this temple wants, on the two things that warn ahead of a date (V85).
		body.put("stockExpiryWarningDays", service.stockExpiryWarningDays());
		body.put("contractEndWarningDays", service.contractEndWarningDays());
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
	 * How much notice the temple wants before a batch expires and before a vendor's agreement runs
	 * out (V85, E5-S1 D2).
	 *
	 * <p>One endpoint for two settings, which is a departure from the one-setting-one-endpoint
	 * shape above and is the point rather than an oversight. These two were a single shared
	 * constant until now, and they are separating because seven days is the wrong notice for a
	 * contract — not because they stopped being one decision. Saving them together is what keeps
	 * anyone from moving one and forgetting the other.
	 */
	@PutMapping("/warning-horizons")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public ResponseEntity<Void> setWarningHorizons(@Valid @RequestBody UpdateWarningHorizonsRequest request) {
		service.setWarningHorizons(request.stockExpiryWarningDays(), request.contractEndWarningDays());
		return ResponseEntity.noContent().build();
	}

	@PutMapping("/volunteer-broadcast-limit")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public ResponseEntity<Void> setBroadcastLimit(@Valid @RequestBody UpdateBroadcastLimitRequest request) {
		service.setVolunteerBroadcastDailyLimit(request.limit());
		return ResponseEntity.noContent().build();
	}

	/**
	 * How many days ahead each of the two warnings starts.
	 *
	 * <p>1 to 365 on both. Zero would warn on the morning the thing had already expired or already
	 * ended, and a year warns about everything a temple holds or has signed, which is the same as
	 * warning about nothing. The database carries the same bounds as a CHECK.
	 */
	public record UpdateWarningHorizonsRequest(
			@Min(value = 1, message = "A stock warning is between 1 and 365 days ahead.")
			@Max(value = 365, message = "A stock warning is between 1 and 365 days ahead.")
			int stockExpiryWarningDays,

			@Min(value = 1, message = "A contract warning is between 1 and 365 days ahead.")
			@Max(value = 365, message = "A contract warning is between 1 and 365 days ahead.")
			int contractEndWarningDays) {
	}

	/** The new daily broadcast cap. */
	public record UpdateBroadcastLimitRequest(@Positive int limit) {
	}

	/** An ISO 639-1 code — {@code kn}, {@code hi}, {@code en}. The region is added on the way in. */
	public record UpdateLanguageRequest(@NotBlank String language) {
	}

	/** A theme's identifier — {@code temple-terracotta}, {@code harbour-blue}. */
	public record UpdateThemeRequest(@NotBlank String themeId) {
	}
}
