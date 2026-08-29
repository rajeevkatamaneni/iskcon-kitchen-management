package org.iskcon.kms.shift;

import jakarta.validation.Valid;
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

	@PutMapping("/volunteer-broadcast-limit")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public ResponseEntity<Void> setBroadcastLimit(@Valid @RequestBody UpdateBroadcastLimitRequest request) {
		service.setVolunteerBroadcastDailyLimit(request.limit());
		return ResponseEntity.noContent().build();
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
