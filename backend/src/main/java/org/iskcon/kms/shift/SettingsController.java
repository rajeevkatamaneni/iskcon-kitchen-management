package org.iskcon.kms.shift;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
	private final ObjectMapper json;

	public SettingsController(TenantSettingsService service, ObjectMapper json) {
		this.service = service;
		this.json = json;
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

	/**
	 * How the temple has arranged its own left-hand menu (T-420, Rajeev 2026-09-19).
	 *
	 * <p>Behind {@code MANAGE_TEMPLE_SETTINGS} like everything else here — the Temple Admin's alone —
	 * and saved for the whole temple rather than for the person who saved it, because a kitchen where
	 * two people see two different menus is a kitchen where they cannot describe a screen to each
	 * other over the noise of a Sunday feast. That is the same argument the theme above rests on.
	 *
	 * <p><strong>It decides order and grouping, never access.</strong> Nothing about what a person may
	 * open passes through here: the permissions the role holds still decide that, applied after the
	 * arrangement is merged with the standard menu in the browser. A temple cannot hide a screen by
	 * leaving it out — an unplaced destination is put back by the merge — and cannot reveal one by
	 * putting it in.
	 *
	 * <p>The arrangement is handed on as JSON rather than as the bound record, and the service reads
	 * it again before it writes. What is checked is then exactly what is stored, whatever route the
	 * document arrived by.
	 */
	@PutMapping("/menu-layout")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public ResponseEntity<Void> setMenuLayout(@Valid @RequestBody UpdateMenuLayoutRequest request) {
		service.setMenuLayout(asJson(request));
		return ResponseEntity.noContent().build();
	}

	/**
	 * Puts the standard menu back.
	 *
	 * <p>204 whether or not there was anything to clear, including for a temple that has never saved
	 * a setting of any kind. "It is already standard" is the outcome the person asked for, so a 404
	 * would be this endpoint disagreeing with itself.
	 */
	@DeleteMapping("/menu-layout")
	@PreAuthorize("hasAuthority('MANAGE_TEMPLE_SETTINGS')")
	public ResponseEntity<Void> clearMenuLayout() {
		service.clearMenuLayout();
		return ResponseEntity.noContent().build();
	}

	private String asJson(UpdateMenuLayoutRequest request) {
		try {
			return json.writeValueAsString(request);
		}
		catch (JsonProcessingException e) {
			// A record Jackson has just built out of the request body, so there is nothing here a
			// person could act on beyond "send it again" — which is what the code already says.
			throw new ApplicationException(ErrorCode.MENU_LAYOUT_NOT_UNDERSTOOD,
					Map.of("reason", "the bound arrangement would not serialise"), e);
		}
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

	/**
	 * The whole of the temple's menu, as it wants it (T-420).
	 *
	 * <p>Sent whole and replaced whole. There is no "move this item" call, because an arrangement is
	 * one decision made on one screen with a Save on it, and a stream of moves would let two people
	 * arranging at once leave the menu in a state neither of them chose.
	 *
	 * <p><strong>What is checked here and what is not.</strong> Everything in this record is a bound
	 * a person can be shown against the thing they did: too many groups, a heading that is blank or
	 * too long, a group holding more items than a group can hold. Those come back as field errors,
	 * in the product's own words. What is refused with a code instead — in
	 * {@code TenantSettingsService} — is the arrangement as a whole: a version this release does not
	 * write, an id that could not be an id, two groups claiming one id, one destination placed twice.
	 * There is no box to point at for any of those.
	 *
	 * <p><strong>{@code version} carries no constraint on purpose.</strong> A version this
	 * application does not recognise is not a filled-in box that is wrong; it is a document it cannot
	 * read, and the person has no control anywhere that sets it. So it is refused with
	 * {@code KMS-400189}, whose next step — arrange it again and save — is the one that actually
	 * helps, rather than with a field error naming a field no screen shows.
	 */
	public record UpdateMenuLayoutRequest(
			int version,

			@NotNull(message = "Arrange the menu into at least one group.")
			@Size(min = 1, max = 20, message = "A menu has between 1 and 20 groups.")
			@Valid List<MenuLayoutGroupRequest> groups) {

		/** Every destination the temple has placed, counted across the whole arrangement. */
		@AssertTrue(message = "A menu arrangement holds up to 200 items in all.")
		public boolean isWithinTheOverallItemCount() {
			if (groups == null) {
				return true;
			}
			return groups.stream()
					.filter(group -> group != null && group.items() != null)
					.mapToInt(group -> group.items().size())
					.sum() <= 200;
		}
	}

	/**
	 * One group of the temple's menu.
	 *
	 * <p>{@code id} is a stable handle and is never shown: renaming a group changes the heading and
	 * leaves the id alone, so the rename does not read as a different group. Its shape is checked in
	 * the service, with the item ids, because neither is a box on the screen.
	 *
	 * <p>{@code title} is null for the small unheaded block at the top of the standard menu, which is
	 * why blankness is asked about separately below: null means "this group has no heading", and a
	 * string of spaces means somebody pressed the space bar in the heading box. They are different
	 * facts and only one of them is allowed.
	 */
	public record MenuLayoutGroupRequest(
			String id,

			String title,

			@NotNull(message = "Each group needs its list of menu items.")
			@Size(max = 100, message = "A group holds up to 100 menu items.")
			List<String> items) {

		/**
		 * Named as a question about the group rather than about the field, following the two other
		 * cross-field rules in this application ({@code ClosePoRequest.isNamedOutcomeExplained} and
		 * {@code UpdatePreferenceRequest.isExactlyOneThing}).
		 */
		@AssertTrue(message = "Give the group a heading, or leave it without one. Spaces alone are not a heading.")
		public boolean isHeadingWrittenOrAbsent() {
			return title == null || !title.isBlank();
		}

		/** Measured after trimming, because that is what gets stored and what the menu shows. */
		@AssertTrue(message = "A group heading is 40 characters or fewer.")
		public boolean isHeadingShortEnough() {
			return title == null || title.trim().length() <= 40;
		}
	}
}
