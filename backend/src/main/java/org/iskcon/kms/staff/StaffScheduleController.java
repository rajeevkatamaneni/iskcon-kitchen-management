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
 * The staff register and their schedules (E6-S1, E6-S8).
 *
 * <p>Two permissions, because they are two different acts. Deciding <em>who works here</em> —
 * hiring, promoting, letting go, reading a PAN — is {@code MANAGE_STAFF}. Editing <em>when they
 * work</em> is {@code MANAGE_STAFF_SCHEDULE}. Today one role holds both; the split is here so that
 * when a kitchen manager is given the roster (BL-4) they are not handed everyone's date of birth
 * with it. A staff member reads their own schedule through {@code /me} with {@code VIEW_OWN_SHIFTS}.
 */
@RestController
@RequestMapping("/api/v1/staff")
public class StaffScheduleController {

	private final StaffScheduleService service;
	private final StaffEmploymentService employment;

	public StaffScheduleController(StaffScheduleService service, StaffEmploymentService employment) {
		this.service = service;
		this.employment = employment;
	}

	// ---- The register (E6-S8) -------------------------------------------

	/** Everyone who works here, and everyone who used to, in two lists. */
	@GetMapping("/register")
	@PreAuthorize("hasAuthority('MANAGE_STAFF')")
	public StaffRegisterView register() {
		return employment.register();
	}

	/** The job-title picklist and the access each one suggests — served, never retyped in TypeScript. */
	@GetMapping("/job-titles")
	@PreAuthorize("hasAuthority('MANAGE_STAFF')")
	public List<JobTitleOption> jobTitles() {
		return employment.jobTitles();
	}

	@PostMapping("/members")
	@PreAuthorize("hasAuthority('MANAGE_STAFF')")
	public ResponseEntity<Map<String, Object>> hire(
			@Valid @RequestBody HireStaffRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(Map.of("id", employment.hire(actor, request)));
	}

	@PutMapping("/members/{id}")
	@PreAuthorize("hasAuthority('MANAGE_STAFF')")
	public ResponseEntity<Void> updateMember(
			@PathVariable UUID id,
			@Valid @RequestBody UpdateStaffRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		employment.update(actor, id, request);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/members/{id}/end-employment")
	@PreAuthorize("hasAuthority('MANAGE_STAFF')")
	public ResponseEntity<Void> endEmployment(
			@PathVariable UUID id,
			@Valid @RequestBody EndEmploymentRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		employment.endEmployment(actor, id, request);
		return ResponseEntity.noContent().build();
	}

	/**
	 * The whole PAN, in clear. Its own request rather than a field on the record, so that reading
	 * somebody's tax number is a deliberate act and lands on the audit trail as one.
	 */
	@GetMapping("/members/{id}/pan")
	@PreAuthorize("hasAuthority('MANAGE_STAFF')")
	public Map<String, String> revealPan(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		String pan = employment.revealPan(actor, id);
		return pan == null ? Map.of() : Map.of("pan", pan);
	}

	// ---- Schedules (E6-S1) ----------------------------------------------

	@GetMapping("/profiles/{id}")
	@PreAuthorize("hasAuthority('MANAGE_STAFF_SCHEDULE')")
	public StaffProfileDetailView get(@PathVariable UUID id) {
		return service.getProfile(id);
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
