package org.iskcon.kms.staff;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.security.PanCipher;
import org.iskcon.kms.user.User;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hiring, employing and letting go of staff (E6-S8).
 *
 * <p>This is the only door into the temple's own roles. Devotees register themselves and hold one
 * role by definition (E1-S12, E1-S17); being hired is how anyone comes to hold more, and employment
 * ending is how they stop. Keeping both on one screen means an admin never has to know that "make
 * this person kitchen staff" and "hire this person" were ever separate ideas.
 *
 * <p>An employment record does <em>not</em> require an app account. {@code user_id} is null for the
 * staff a temple gave no login, and their name and contact details live on the record itself.
 * Granting access later creates the account; ending employment either returns them to being an
 * ordinary devotee or disables them outright, which is a genuine difference and the admin's to make.
 *
 * <p>Nothing here is ever deleted. A former cook is still the actor on last year's stock adjustment
 * and still the name on a shift roster.
 */
@Service
public class StaffEmploymentService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;
	private final PanCipher panCipher;
	/** Only for the ban a dismissal may raise (B9); the check at a hire is run before this service. */
	private final org.iskcon.kms.ban.EmploymentBanService bans;

	public StaffEmploymentService(JdbcTemplate jdbc, AuditService auditService, PanCipher panCipher,
			org.iskcon.kms.ban.EmploymentBanService bans) {
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.panCipher = panCipher;
		this.bans = bans;
	}

	// ---- Reading --------------------------------------------------------

	/**
	 * The register: current staff, then former staff, each by name.
	 *
	 * <p>A former row also says whether this temple raised a ban record about the person (B9), which
	 * is what lets the screen draw an ordinary leaving and a dismissal with a warning attached one
	 * glance apart. The ids come from the ban table itself rather than from a column on the staff
	 * profile: a flag stored beside the employment would be a second copy of a fact that can be
	 * retracted, and the two would drift the first time somebody took a record back.
	 *
	 * <p>The set is fetched once for the whole register rather than a row at a time. A temple has a
	 * handful of these at most, and asking per former employee would be one query per person to
	 * answer a question about a page.
	 */
	@Transactional(readOnly = true)
	public StaffRegisterView register() {
		List<StaffProfileView> all = jdbc.query(SELECT + " ORDER BY sp.full_name", MAPPER);
		Set<UUID> banned = bans.staffProfilesWithARecord();
		List<StaffProfileView> current = new ArrayList<>();
		List<FormerStaffView> former = new ArrayList<>();
		for (StaffProfileView row : all) {
			if (row.isFormer()) {
				former.add(new FormerStaffView(row, banned.contains(row.id())));
			} else {
				current.add(row);
			}
		}
		return new StaffRegisterView(current, former);
	}

	@Transactional(readOnly = true)
	public StaffProfileView get(UUID id) {
		return find(id).orElseThrow(() -> notFound(id));
	}

	/** The picklist, served rather than retyped in the browser where it would drift. */
	public List<JobTitleOption> jobTitles() {
		return JobTitle.choosable().stream()
				.map(t -> new JobTitleOption(t, t.label(), t.group(),
						t.suggestedAccess() == null ? null : SystemAccess.of(t.suggestedAccess())))
				.toList();
	}

	// ---- Hiring ---------------------------------------------------------

	@Transactional
	public UUID hire(AuthenticatedUser actor, HireStaffRequest request) {
		requireOtherTitleNamed(request.jobTitle(), request.jobTitleOther());

		UUID userId = request.existingUserId();
		String fullName = request.fullName().trim();
		String phone = trimToNull(request.phone());
		String email = lower(trimToNull(request.email()));

		if (userId != null) {
			// Promoting a devotee this temple already knows: take their details as the account holds
			// them unless the form corrected them, and refuse if they are already employed.
			Map<String, Object> existing = userRow(userId);
			phone = phone != null ? phone : (String) existing.get("phone");
			email = email != null ? email : (String) existing.get("email");
			if (employmentFor(userId).isPresent()) {
				throw new ApplicationException(ErrorCode.PERSON_ALREADY_EMPLOYED, Map.of("userId", userId));
			}
		}

		if (request.systemAccess() != null) {
			// users.email is NOT NULL and a person cannot be offered a channel we have no address
			// for, so access without an address is refused here rather than at the database.
			requireContactable(email, phone);
			userId = userId != null
					? promote(userId, request.systemAccess())
					: createAccount(fullName, email, phone, request.systemAccess());
		}

		UUID id = UUID.randomUUID();
		byte[] panCiphertext = request.pan() == null ? null : panCipher.encrypt(normalisePan(request.pan()));

		jdbc.update("""
				INSERT INTO staff_profiles (
					id, tenant_id, user_id, full_name, phone, email, job_title, job_title_other,
					employment_type, date_of_joining, date_of_birth, address,
					emergency_contact_name, emergency_contact_relationship, emergency_contact_phone,
					pan_ciphertext, pan_last4, employment_status, monthly_salary, notes)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
					?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
				""",
				id, userId, fullName, phone, email,
				request.jobTitle().name(), trimToNull(request.jobTitleOther()),
				request.employmentType().name(), request.dateOfJoining(), request.dateOfBirth(),
				trimToNull(request.address()), trimToNull(request.emergencyContactName()),
				trimToNull(request.emergencyContactRelationship()), trimToNull(request.emergencyContactPhone()),
				panCiphertext, last4(request.pan()),
				// Written through as it arrived, null included: no pay agreed is not the same fact
				// as pay of nothing, and the termination screen has to be able to tell them apart.
				request.monthlySalary(), trimToNull(request.notes()));

		seedWeekOfDaysOff(id);

		auditService.record(actor, AuditAction.STAFF_HIRED, AuditEntityType.STAFF_MEMBER, id,
				null, auditShape(fullName, request.jobTitle(), request.employmentType(),
						request.dateOfJoining(), request.systemAccess(), request.monthlySalary()),
				"Hired as " + titleLabel(request.jobTitle(), request.jobTitleOther()) + ".");
		return id;
	}

	// ---- Editing, including promotions ----------------------------------

	@Transactional
	public void update(AuthenticatedUser actor, UUID id, UpdateStaffRequest request) {
		StaffProfileView before = find(id).orElseThrow(() -> notFound(id));
		requireOtherTitleNamed(request.jobTitle(), request.jobTitleOther());
		requireStillEmployed(before);

		String phone = trimToNull(request.phone());
		String email = lower(trimToNull(request.email()));
		UUID userId = before.userId();

		if (request.systemAccess() != before.systemAccess()) {
			requireNotSelf(actor, before, ErrorCode.CANNOT_CHANGE_OWN_ROLE);
			if (request.systemAccess() == null) {
				// Access withdrawn without the employment ending — a cook moved to the store room
				// and no longer needs the app. They stay a devotee of this temple.
				demoteToDevotee(userId);
			} else {
				requireContactable(email, phone);
				userId = userId != null
						? promote(userId, request.systemAccess())
						: createAccount(request.fullName().trim(), email, phone, request.systemAccess());
			}
		}

		// Read for the audit log alone. Salary is deliberately absent from StaffProfileView — that view
		// is shared with the roster and with a person's own schedule — so the before-figure has to be
		// fetched here rather than taken from `before`.
		BigDecimal salaryBefore = jdbc.queryForObject(
				"SELECT monthly_salary FROM staff_profiles WHERE id = ?", BigDecimal.class, id);

		// A null PAN means "leave it alone" and "" means "clear it" — the form never shows the stored
		// value, so treating absent as empty would erase it on every unrelated edit.
		boolean touchPan = request.pan() != null;
		byte[] panCiphertext = null;
		String panLast4 = null;
		if (touchPan && !request.pan().isBlank()) {
			panCiphertext = panCipher.encrypt(normalisePan(request.pan()));
			panLast4 = last4(request.pan());
		}

		jdbc.update("""
				UPDATE staff_profiles SET
					user_id = ?, full_name = ?, phone = ?, email = ?, job_title = ?, job_title_other = ?,
					employment_type = ?, date_of_joining = ?, date_of_birth = ?, address = ?,
					emergency_contact_name = ?, emergency_contact_relationship = ?, emergency_contact_phone = ?,
					pan_ciphertext = CASE WHEN ? THEN ? ELSE pan_ciphertext END,
					pan_last4      = CASE WHEN ? THEN ? ELSE pan_last4 END,
					monthly_salary = ?,
					notes = ?, updated_at = now()
				WHERE id = ?
				""",
				userId, request.fullName().trim(), phone, email,
				request.jobTitle().name(), trimToNull(request.jobTitleOther()),
				request.employmentType().name(), request.dateOfJoining(), request.dateOfBirth(),
				trimToNull(request.address()), trimToNull(request.emergencyContactName()),
				trimToNull(request.emergencyContactRelationship()), trimToNull(request.emergencyContactPhone()),
				touchPan, panCiphertext, touchPan, panLast4,
				// Unlike the PAN, an absent salary is not "leave it alone": the form shows the stored
				// figure, so a cleared box means the temple no longer has an agreed one.
				request.monthlySalary(),
				trimToNull(request.notes()), id);

		auditService.record(actor, AuditAction.STAFF_UPDATED, AuditEntityType.STAFF_MEMBER, id,
				auditShape(before.fullName(), before.jobTitle(), before.employmentType(),
						before.dateOfJoining(), before.systemAccess(), salaryBefore),
				auditShape(request.fullName().trim(), request.jobTitle(), request.employmentType(),
						request.dateOfJoining(), request.systemAccess(), request.monthlySalary()),
				describeChange(before, request));
	}

	// ---- Ending employment ----------------------------------------------

	@Transactional
	public void endEmployment(AuthenticatedUser actor, UUID id, EndEmploymentRequest request) {
		StaffProfileView before = find(id).orElseThrow(() -> notFound(id));
		requireStillEmployed(before);
		// An admin ending their own employment and revoking their own sign-in locks the temple out
		// of itself, with nobody left holding MANAGE_STAFF to undo it.
		requireNotSelf(actor, before, ErrorCode.CANNOT_DISABLE_SELF);
		if (!request.status().isFormer()) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "status", "reason", "ending employment needs a reason it ended"));
		}

		jdbc.update("""
				UPDATE staff_profiles SET employment_status = ?, last_working_day = ?, end_reason = ?,
					updated_at = now() WHERE id = ?
				""", request.status().name(), request.lastWorkingDay(), trimToNull(request.reason()), id);

		if (before.userId() != null) {
			if (request.revokeSignIn()) {
				jdbc.update("UPDATE users SET status = 'DISABLED', updated_at = now() WHERE id = ?",
						before.userId());
			} else {
				demoteToDevotee(before.userId());
			}
		}

		auditService.record(actor, AuditAction.STAFF_EMPLOYMENT_ENDED, AuditEntityType.STAFF_MEMBER, id,
				Map.of("employmentStatus", before.employmentStatus().name()),
				Map.of("employmentStatus", request.status().name(),
						"lastWorkingDay", request.lastWorkingDay().toString(),
						"signInRevoked", request.revokeSignIn()),
				trimToNull(request.reason()));

		// The ban, if the admin deliberately asked for one (B9). Last, and inside this same
		// transaction: it is a decision made at the dismissal, and an employment that ended without
		// the record the admin asked for is worse than neither having happened.
		if (request.ban() != null) {
			bans.raise(actor, id, request.ban());
		}
	}

	// ---- The audited PAN read -------------------------------------------

	/**
	 * The whole PAN, in clear, for the one admin who needs to type it onto a form. Recorded every
	 * time, exactly as a donor's is (E7-S4) — access to somebody's tax number is never silent.
	 */
	@Transactional
	public String revealPan(AuthenticatedUser actor, UUID id) {
		StaffProfileView staff = find(id).orElseThrow(() -> notFound(id));
		byte[] ciphertext = jdbc.query("SELECT pan_ciphertext FROM staff_profiles WHERE id = ?",
				(rs, n) -> rs.getBytes("pan_ciphertext"), id).stream().findFirst().orElse(null);
		if (ciphertext == null) {
			return null;
		}
		String pan = panCipher.decrypt(ciphertext);
		auditService.record(actor, AuditAction.STAFF_PAN_VIEWED, AuditEntityType.STAFF_MEMBER, id,
				null, Map.of("panLast4", staff.panLast4() == null ? "" : staff.panLast4()), null);
		return pan;
	}

	// ---------------------------------------------------------------------

	/** Promotes an existing account, keeping every reference to it intact. */
	private UUID promote(UUID userId, SystemAccess access) {
		int updated = jdbc.update(
				"UPDATE users SET role = ?, status = 'ACTIVE', updated_at = now() WHERE id = ?",
				access.role().name(), userId);
		if (updated == 0) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("userId", userId));
		}
		return userId;
	}

	/** Takes a temple role away without taking the person away: they go back to being a devotee. */
	private void demoteToDevotee(UUID userId) {
		if (userId != null) {
			jdbc.update("UPDATE users SET role = 'VOLUNTEER', updated_at = now() WHERE id = ?", userId);
		}
	}

	/**
	 * Creates the account for a hire the temple had no record of. Pending until their first sign-in
	 * claims it (E1-S6), exactly as provisioning creates a temple's first admin.
	 */
	private UUID createAccount(String fullName, String email, String phone, SystemAccess access) {
		UUID id = UUID.randomUUID();
		try {
			jdbc.update("""
					INSERT INTO users (id, tenant_id, firebase_uid, full_name, email, phone, role, status)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						?, ?, ?, ?, ?, 'ACTIVE')
					""", id, "pending:" + UUID.randomUUID(), fullName, email, phone, access.role().name());
		} catch (DuplicateKeyException e) {
			throw new ApplicationException(
					ErrorCode.EMAIL_ALREADY_REGISTERED, Map.of("email", email), e);
		}
		return id;
	}

	private void seedWeekOfDaysOff(UUID profileId) {
		for (int day = 1; day <= 7; day++) {
			jdbc.update("""
					INSERT INTO staff_schedule_template (id, tenant_id, staff_profile_id, day_of_week, working)
					VALUES (gen_random_uuid(), NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, false)
					""", profileId, day);
		}
	}

	private Map<String, Object> userRow(UUID userId) {
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT id, full_name, email, phone, role FROM users WHERE id = ?", userId);
		if (rows.isEmpty()) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("userId", userId));
		}
		return rows.get(0);
	}

	private Optional<UUID> employmentFor(UUID userId) {
		return jdbc.query("SELECT id FROM staff_profiles WHERE user_id = ?",
				(rs, n) -> rs.getObject("id", UUID.class), userId).stream().findFirst();
	}

	private static void requireOtherTitleNamed(JobTitle title, String other) {
		if (title == JobTitle.OTHER && trimToNull(other) == null) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "jobTitleOther", "reason", "name the job this temple is hiring for"));
		}
	}

	private static void requireContactable(String email, String phone) {
		if (email == null || phone == null) {
			throw new ApplicationException(ErrorCode.STAFF_ACCESS_NEEDS_CONTACT,
					Map.of("hasEmail", email != null, "hasPhone", phone != null));
		}
	}

	/**
	 * The two guards that matter are the same guard: an admin may not take their own access away.
	 * The last administrator of a temple doing so leaves nobody able to put it back, and the fix
	 * would be a database edit by a platform operator.
	 */
	private static void requireNotSelf(AuthenticatedUser actor, StaffProfileView staff, ErrorCode code) {
		if (staff.userId() != null && staff.userId().equals(actor.getUserId())) {
			throw new ApplicationException(code, Map.of("staffId", staff.id()));
		}
	}

	private static void requireStillEmployed(StaffProfileView staff) {
		if (staff.isFormer()) {
			throw new ApplicationException(ErrorCode.EMPLOYMENT_ALREADY_ENDED,
					Map.of("staffId", staff.id(), "employmentStatus", staff.employmentStatus().name()));
		}
	}

	private Optional<StaffProfileView> find(UUID id) {
		return jdbc.query(SELECT + " WHERE sp.id = ?", MAPPER, id).stream().findFirst();
	}

	private ApplicationException notFound(UUID id) {
		return new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("staffId", id));
	}

	/**
	 * What the audit log records — the facts that matter, never the PAN or the address.
	 *
	 * <p>The salary is one of them (B8). Somebody's pay being raised, cut or first agreed is exactly
	 * the kind of change a temple has to be able to attribute later, and "NONE" is recorded for no
	 * agreed figure rather than a zero that would read as a decision nobody made.
	 */
	private static Map<String, Object> auditShape(
			String fullName, JobTitle title, EmploymentType type, LocalDate joined, SystemAccess access,
			BigDecimal monthlySalary) {
		Map<String, Object> shape = new LinkedHashMap<>();
		shape.put("fullName", fullName);
		shape.put("jobTitle", title.name());
		shape.put("employmentType", type.name());
		shape.put("dateOfJoining", joined == null ? null : joined.toString());
		shape.put("systemAccess", access == null ? "NONE" : access.name());
		shape.put("monthlySalary", monthlySalary == null ? "NONE" : monthlySalary.toPlainString());
		return shape;
	}

	/** A sentence for the log when the change is one a reader would want named. */
	private static String describeChange(StaffProfileView before, UpdateStaffRequest after) {
		List<String> parts = new ArrayList<>();
		if (before.jobTitle() != after.jobTitle()) {
			parts.add("job title " + before.jobTitle().label() + " → " + after.jobTitle().label());
		}
		if (before.systemAccess() != after.systemAccess()) {
			parts.add("access " + accessLabel(before.systemAccess()) + " → " + accessLabel(after.systemAccess()));
		}
		return parts.isEmpty() ? null : String.join("; ", parts);
	}

	private static String accessLabel(SystemAccess access) {
		return access == null ? "none" : access.label();
	}

	/**
	 * What to print for a job title. Package-private rather than private because the leave queue
	 * prints it too, and two spellings of "the temple's words if it gave any" is how one screen comes
	 * to show "Cook" where the register beside it shows the temple's own "Rasoi in-charge".
	 */
	static String titleLabel(JobTitle title, String other) {
		String named = trimToNull(other);
		return named != null ? named : title.label();
	}

	private static String normalisePan(String pan) {
		return pan.trim().toUpperCase(Locale.ROOT);
	}

	private static String last4(String pan) {
		String p = trimToNull(pan);
		return p == null ? null : normalisePan(p).substring(6);
	}

	private static String lower(String s) {
		return s == null ? null : s.toLowerCase(Locale.ROOT);
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	// ---------------------------------------------------------------------

	/**
	 * Left join, deliberately: an employment record without an account is ordinary here, and an inner
	 * join would silently drop every member of staff the temple gave no login.
	 */
	static final String SELECT = """
			SELECT sp.id, sp.user_id, sp.full_name, sp.phone, sp.email, sp.job_title, sp.job_title_other,
			       sp.employment_type, sp.date_of_joining, sp.date_of_birth, sp.address,
			       sp.emergency_contact_name, sp.emergency_contact_relationship, sp.emergency_contact_phone,
			       sp.pan_last4, sp.employment_status, sp.last_working_day, sp.end_reason, sp.notes,
			       sp.created_at, u.role AS user_role
			FROM staff_profiles sp LEFT JOIN users u ON u.id = sp.user_id
			""";

	static final RowMapper<StaffProfileView> MAPPER = (rs, n) -> {
		JobTitle title = JobTitle.valueOf(rs.getString("job_title"));
		String other = rs.getString("job_title_other");
		String role = rs.getString("user_role");
		return new StaffProfileView(
				rs.getObject("id", UUID.class),
				rs.getObject("user_id", UUID.class),
				rs.getString("full_name"),
				rs.getString("phone"),
				rs.getString("email"),
				title,
				other,
				titleLabel(title, other),
				EmploymentType.valueOf(rs.getString("employment_type")),
				rs.getObject("date_of_joining", LocalDate.class),
				rs.getObject("date_of_birth", LocalDate.class),
				rs.getString("address"),
				rs.getString("emergency_contact_name"),
				rs.getString("emergency_contact_relationship"),
				rs.getString("emergency_contact_phone"),
				rs.getString("pan_last4"),
				role == null ? null : SystemAccess.of(User.Role.valueOf(role)),
				EmploymentStatus.valueOf(rs.getString("employment_status")),
				rs.getObject("last_working_day", LocalDate.class),
				rs.getString("end_reason"),
				rs.getString("notes"),
				toInstant(rs.getObject("created_at", OffsetDateTime.class)));
	};

	private static java.time.Instant toInstant(OffsetDateTime odt) {
		return odt == null ? null : odt.toInstant();
	}
}
