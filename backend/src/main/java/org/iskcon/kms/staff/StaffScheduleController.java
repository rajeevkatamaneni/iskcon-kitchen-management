package org.iskcon.kms.staff;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff profiles and schedules (E6-S1). Admin management is behind {@code MANAGE_STAFF_SCHEDULE};
 * a staff member reads their own schedule through {@code /me} with {@code VIEW_OWN_SHIFTS}.
 */
@RestController
@RequestMapping("/api/v1/staff")
public class StaffScheduleController {

	private final StaffScheduleService service;

	public StaffScheduleController(StaffScheduleService service) {
		this.service = service;
	}

	@GetMapping("/profiles")
	@PreAuthorize("hasAuthority('MANAGE_STAFF_SCHEDULE')")
	public List<StaffProfileView> list() {
		return service.listProfiles();
	}

	@PostMapping("/profiles")
	@PreAuthorize("hasAuthority('MANAGE_STAFF_SCHEDULE')")
	public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateStaffProfileRequest request) {
		UUID id = service.createProfile(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
	}

	@GetMapping("/profiles/{id}")
	@PreAuthorize("hasAuthority('MANAGE_STAFF_SCHEDULE')")
	public StaffProfileDetailView get(@PathVariable UUID id) {
		return service.getProfile(id);
	}

	@PutMapping("/profiles/{id}")
	@PreAuthorize("hasAuthority('MANAGE_STAFF_SCHEDULE')")
	public ResponseEntity<Void> update(
			@PathVariable UUID id, @Valid @RequestBody UpdateStaffProfileRequest request) {
		service.updateProfile(id, request);
		return ResponseEntity.noContent().build();
	}

	@PutMapping("/profiles/{id}/template")
	@PreAuthorize("hasAuthority('MANAGE_STAFF_SCHEDULE')")
	public ResponseEntity<Void> setTemplate(
			@PathVariable UUID id, @Valid @RequestBody SetScheduleTemplateRequest request) {
		UUID affected = service.setTemplate(id, request);
		service.notifyScheduleChange(affected);
		return ResponseEntity.noContent().build();
	}

	@PutMapping("/profiles/{id}/exceptions")
	@PreAuthorize("hasAuthority('MANAGE_STAFF_SCHEDULE')")
	public ResponseEntity<Void> setException(
			@PathVariable UUID id, @Valid @RequestBody SetScheduleExceptionRequest request) {
		UUID affected = service.setException(id, request);
		service.notifyScheduleChange(affected);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/profiles/{id}/exceptions/{exceptionId}")
	@PreAuthorize("hasAuthority('MANAGE_STAFF_SCHEDULE')")
	public ResponseEntity<Void> deleteException(
			@PathVariable UUID id, @PathVariable UUID exceptionId) {
		UUID affected = service.deleteException(id, exceptionId);
		service.notifyScheduleChange(affected);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/schedule/week")
	@PreAuthorize("hasAuthority('MANAGE_STAFF_SCHEDULE')")
	public WeekScheduleView week(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
		return service.weekView(weekStart);
	}

	/** The signed-in staff member's own schedule. */
	@GetMapping("/schedule/me")
	@PreAuthorize("hasAuthority('VIEW_OWN_SHIFTS')")
	public StaffProfileDetailView mySchedule(@AuthenticationPrincipal AuthenticatedUser actor) {
		return service.scheduleForUser(actor.getUserId())
				.orElseThrow(() -> new ApplicationException(
						ErrorCode.RESOURCE_NOT_FOUND, Map.of("userId", actor.getUserId())));
	}
}
