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

		// Asked again here although the controller asked first, so a hire reached any other way is held
		// to the same rule. The controller's call is the one that matters for ordering: see there. After
		// the account checks above, so naming another temple's devotee is still answered as that.
		requireUsableKitchen(request.kitchenId());

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
					pan_ciphertext, pan_last4, employment_status, monthly_salary, notes,
					kitchen_id, kitchen_needs_check)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
					?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, false)
				""",
				id, userId, fullName, phone, email,
				request.jobTitle().name(), trimToNull(request.jobTitleOther()),
				request.employmentType().name(), request.dateOfJoining(), request.dateOfBirth(),
				trimToNull(request.address()), trimToNull(request.emergencyContactName()),
				trimToNull(request.emergencyContactRelationship()), trimToNull(request.emergencyContactPhone()),
				panCiphertext, last4(request.pan()),
				// Written through as it arrived, null included: no pay agreed is not the same fact
				// as pay of nothing, and the termination screen has to be able to tell them apart.
				// The admin chose this kitchen on the form, so it is not a guess and is not flagged for
				// checking — unlike the kitchens V150 filled in for everybody already employed.
				request.monthlySalary(), trimToNull(request.notes()), request.kitchenId());

		seedWeekOfDaysOff(id);

		// The kitchen in the audit shape is read back from the row, not taken from the request
		// (docs/work/README.md, lesson 2): the trail says what was stored.
		StaffProfileView stored = find(id).orElseThrow(() -> notFound(id));
		auditService.record(actor, AuditAction.STAFF_HIRED, AuditEntityType.STAFF_MEMBER, id,
				null, auditShape(fullName, request.jobTitle(), request.employmentType(),
						request.dateOfJoining(), request.systemAccess(), request.monthlySalary(), stored),
				"Hired as " + titleLabel(request.jobTitle(), request.jobTitleOther()) + ".");
		return id;
	}

	// ---- Editing, including promotions ----------------------------------

	@Transactional
	public void update(AuthenticatedUser actor, UUID id, UpdateStaffRequest request) {
		StaffProfileView before = find(id).orElseThrow(() -> notFound(id));
		requireOtherTitleNamed(request.jobTitle(), request.jobTitleOther());
		requireStillEmployed(before);
		// Required on every edit, not only when it changes: the form always shows the kitchen and
		// always sends it, so an edit without one is a form that lost it, and quietly keeping the old
		// value would hide that.
		requireUsableKitchen(request.kitchenId());

		String phone = trimToNull(request.phone());
		String email = lower(trimToNull(request.email()));
		UUID userId = before.userId();

		// Held rather than re-tested at the bottom: `userId` is reassigned by the branch below, and
		// `before` is a snapshot, so by the time the audit is written the only honest record of
		// whether a privilege changed is the answer taken here.
		boolean accessChanged = request.systemAccess() != before.systemAccess();

		if (accessChanged) {
			requireNotChangingOwnAccess(actor, before, request.systemAccess());
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
					notes = ?,
					kitchen_id = ?, kitchen_needs_check = false,
					updated_at = now()
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
				// Saving the record is the admin looking at it, kitchen included — the form shows the
				// kitchen and they sent it — so it comes off the check list whether or not it moved.
				trimToNull(request.notes()), request.kitchenId(), id);

		StaffProfileView after = find(id).orElseThrow(() -> notFound(id));
		auditService.record(actor, AuditAction.STAFF_UPDATED, AuditEntityType.STAFF_MEMBER, id,
				auditShape(before.fullName(), before.jobTitle(), before.employmentType(),
						before.dateOfJoining(), before.systemAccess(), salaryBefore, before),
				auditShape(request.fullName().trim(), request.jobTitle(), request.employmentType(),
						request.dateOfJoining(), request.systemAccess(), request.monthlySalary(), after),
				describeChange(before, request, after));

		if (accessChanged) {
			// A second event for the same request, deliberately, and not a replacement for the one
			// above. Two different acts arrive together here: someone's profile was edited, and
			// someone's privileges changed. The first is filed against the staff record and carries
			// the whole shape of the edit; this one is filed against the account, says only what the
			// access went from and to, and is what somebody reviewing the log for privilege changes
			// is actually looking for — a role change must not have to be found by reading every
			// corrected phone number.
			//
			// This is the only place the application writes ROLE_CHANGED for a temple role: the
			// staff form is the one door a temple role changes through. `userId` is non-null on
			// every path that reaches here — prior access implies an account, and new access has
			// just promoted or created one — which the NOT NULL on audit_events.entity_id relies on.
			auditService.record(actor, AuditAction.ROLE_CHANGED, AuditEntityType.USER, userId,
					Map.of("role", accessRole(before.systemAccess())),
					Map.of("role", accessRole(request.systemAccess())),
					"Access " + accessLabel(before.systemAccess()) + " → "
							+ accessLabel(request.systemAccess()) + " for "
							+ request.fullName().trim() + ".");
		}
	}

	// ---- Ending employment ----------------------------------------------

	@Transactional
	public void endEmployment(AuthenticatedUser actor, UUID id, EndEmploymentRequest request) {
		StaffProfileView before = find(id).orElseThrow(() -> notFound(id));
		requireStillEmployed(before);
		requireNotEndingOwnEmployment(actor, before, request);
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

	// ---- Taking somebody back on ----------------------------------------

	/**
	 * Brings a former member of staff back (T-014). The one way out of a state that was otherwise
	 * permanent.
	 *
	 * <p><strong>This does not loosen the guard on {@link #update}, and the distinction is the whole
	 * design.</strong> {@code requireStillEmployed} still refuses every edit to a former employee's
	 * record, unconditionally; nothing a caller can put in an update body reaches past it. A
	 * reinstatement is a different act with a different name and a different audit action, and it has
	 * to be asked for deliberately. This is the same shape as {@code EquipmentService.reinstate}
	 * (D-15): the ordinary path stays closed, and there is exactly one explicit, named, audited way
	 * back.
	 *
	 * <p><strong>It needed no migration, which was worth establishing rather than assuming.</strong>
	 * The end state is three existing columns — {@code employment_status}, {@code last_working_day}
	 * and {@code end_reason} (V57) — and the last two are nullable, so the inverse of ending an
	 * employment is representable in the schema exactly as it stands. The one thing that might have
	 * forced a column is {@code dateOfRejoining}, and it does not:
	 *
	 * <ul>
	 *   <li>It cannot go on {@code date_of_joining}, which holds the day they first joined. That is
	 *       a fact about the person that overwriting would destroy.
	 *   <li>A {@code date_of_rejoining} column would hold only the <em>most recent</em> one and lose
	 *       every earlier return — which is the same objection that rules out the line above.
	 *       Somebody can leave and come back more than once, so this is an <em>event</em> and not an
	 *       attribute, and a single column is the wrong shape for it.
	 *   <li>{@code audit_events} is append-only and already holds the matching {@code lastWorkingDay}
	 *       on {@link AuditAction#STAFF_EMPLOYMENT_ENDED}. The ENDED/REINSTATED pair is therefore the
	 *       durable record of the whole cycle, with an actor, a timestamp and a reason on each half —
	 *       precisely what {@code equipment_state_changes} is for a machine.
	 *   <li>Nothing in the product computes anything from {@code date_of_joining}: it is displayed on
	 *       the record and the register and carried into the audit shape, and that is all. So a
	 *       stored rejoining date would have had no reader.
	 * </ul>
	 *
	 * <p><strong>The access they come back with is asked for, not restored.</strong> Ending an
	 * employment either disabled the account or demoted it, keeping no note of what it had been, so
	 * there is nothing to put back. {@code promote} sets the role <em>and</em> {@code status =
	 * 'ACTIVE'}, which is what returns sign-in to somebody whose account was disabled. Asking for no
	 * access demotes instead, and deliberately does <em>not</em> re-enable a disabled account: they
	 * came back without a login, and that is what that means. That branch is doing real work rather
	 * than being defensive — {@link #endEmployment} sets {@code status = 'DISABLED'} but leaves
	 * {@code role} alone, so a dismissed administrator still carries TEMPLE_ADMIN on their users row
	 * and reinstating them without a login must take it off.
	 *
	 * <p>Two refusals, and they are different mistakes. Somebody still employed has nothing to
	 * reinstate ({@code EMPLOYMENT_NOT_ENDED}). Somebody this temple raised a B9 record against when
	 * they left is refused outright rather than warned — see {@link #requireNoRecordOnFile}.
	 *
	 * <p>No self-guard, unlike {@link #endEmployment}. Reinstating oneself is unreachable rather than
	 * permitted: a former employee either holds VOLUNTEER, which does not carry MANAGE_STAFF, or a
	 * disabled account, which cannot authenticate at all.
	 */
	@Transactional
	public void reinstate(AuthenticatedUser actor, UUID id, ReinstateStaffRequest request) {
		StaffProfileView before = find(id).orElseThrow(() -> notFound(id));
		requireEmploymentEnded(before);
		requireNoRecordOnFile(actor, before, request);
		// They come back to the kitchen their record still names (Epic 12) — the column is NOT NULL, so
		// leaving took nothing away. That kitchen may have been archived while they were gone, which is
		// allowed because archiving refuses only a kitchen with *current* staff. Coming back into it
		// would make them current staff of a closed kitchen, the state KMS-400185 exists to prevent, so
		// it is refused with the kitchen's own code: restore the kitchen first, reinstate, then move
		// them. The request has no kitchen of its own to choose instead (open question in T-357's proof).
		requireUsableKitchen(before.kitchenId());

		// The ending is cleared, not merely overwritten. Leaving last_working_day and end_reason
		// behind on an ACTIVE row would leave the record contradicting itself, and the record screen
		// draws both of them from the row rather than from the status.
		jdbc.update("""
				UPDATE staff_profiles SET employment_status = 'ACTIVE', last_working_day = NULL,
					end_reason = NULL, updated_at = now() WHERE id = ?
				""", id);

		UUID userId = before.userId();
		if (request.systemAccess() != null) {
			// users.email is NOT NULL and a person cannot be offered a channel we have no address
			// for, so this is refused here rather than at the database, exactly as at a hire. The
			// contact details come off the employment record, which survived the person leaving.
			requireContactable(before.email(), before.phone());
			userId = userId != null
					? promote(userId, request.systemAccess())
					: createAccount(before.fullName(), before.email(), before.phone(), request.systemAccess());
		} else {
			demoteToDevotee(userId);
		}

		auditService.record(actor, AuditAction.STAFF_EMPLOYMENT_REINSTATED, AuditEntityType.STAFF_MEMBER,
				id,
				Map.of("employmentStatus", before.employmentStatus().name(),
						"lastWorkingDay", before.lastWorkingDay() == null
								? "" : before.lastWorkingDay().toString()),
				Map.of("employmentStatus", EmploymentStatus.ACTIVE.name(),
						"dateOfRejoining", request.dateOfRejoining().toString(),
						// The whole of what a reader asking "did they get their login back?" needs,
						// said as a fact rather than left to be inferred from the access name.
						"systemAccess", accessRole(request.systemAccess()),
						"signInRestored", request.systemAccess() != null),
				trimToNull(request.reason()));
	}

	// ---- Which kitchen (Epic 12) ------------------------------------------

	/**
	 * Refuses a kitchen a staff record cannot be put in, and says which of three mistakes it was.
	 *
	 * <ul>
	 *   <li>None given: {@code KMS-400184}. Every staff member belongs to exactly one kitchen (Rajeev,
	 *       2026-09-19), so the form cannot be saved without one.
	 *   <li>Not a kitchen of this temple: {@code KMS-400108}. The lookup runs under the row policy, so
	 *       another temple's kitchen id finds nothing and is refused as unknown. That matters because the
	 *       foreign key is checked as the table owner and does not know about temples: without this, a
	 *       staff record here could point at a kitchen somewhere else.
	 *   <li>Archived: {@code KMS-400109}. Nobody should be put to work in a kitchen the temple closed.
	 * </ul>
	 *
	 * <p>A kitchen that does not plan its meals here is allowed. People work in every kitchen — the one
	 * that only draws from the store included — and what that decides is whether they get the planner,
	 * not whether they can be employed.
	 *
	 * <p>Public because the hire endpoint asks it before the cross-temple ban check runs: a hire refused
	 * for a missing kitchen should not leave a ban check on the platform log for a hire that never had
	 * a chance of happening.
	 */
	@Transactional(readOnly = true)
	public void requireUsableKitchen(UUID kitchenId) {
		if (kitchenId == null) {
			throw new ApplicationException(ErrorCode.STAFF_NEEDS_A_KITCHEN, Map.of("field", "kitchenId"));
		}
		List<String> status = jdbc.queryForList(
				"SELECT status FROM kitchens WHERE id = ?", String.class, kitchenId);
		if (status.isEmpty()) {
			throw new ApplicationException(ErrorCode.KITCHEN_NOT_FOUND, Map.of("kitchenId", kitchenId));
		}
		if (!"ACTIVE".equals(status.get(0))) {
			throw new ApplicationException(ErrorCode.KITCHEN_ARCHIVED, Map.of("kitchenId", kitchenId));
		}
	}

	/**
	 * The Temple Admin's "Check these kitchen assignments" list: people currently employed whose kitchen
	 * the system chose and nobody has looked at since. Former staff are left off — there is nothing to
	 * decide about where somebody who has left works.
	 */
	@Transactional(readOnly = true)
	public List<StaffKitchenCheckView> kitchenChecks() {
		return jdbc.query("""
				SELECT sp.id, sp.full_name, sp.job_title, sp.job_title_other, sp.kitchen_id, k.name AS kitchen_name
				FROM staff_profiles sp JOIN kitchens k ON k.id = sp.kitchen_id
				WHERE sp.kitchen_needs_check AND sp.employment_status = 'ACTIVE'
				ORDER BY lower(sp.full_name), sp.id
				""", (rs, n) -> new StaffKitchenCheckView(
						rs.getObject("id", UUID.class),
						rs.getString("full_name"),
						titleLabel(JobTitle.valueOf(rs.getString("job_title")), rs.getString("job_title_other")),
						rs.getObject("kitchen_id", UUID.class),
						rs.getString("kitchen_name")));
	}

	/**
	 * Puts one person in a kitchen and marks it checked — the check list's "change", and the same act
	 * wherever else a kitchen is changed on its own.
	 *
	 * <p>Refused for a former employee, exactly as {@link #update} is: the record of somebody who has
	 * left is closed, and a reinstatement is the way to open it. Audited as {@code STAFF_UPDATED} with
	 * the kitchen before and after as the row holds them, and written even when the kitchen did not
	 * move — choosing the same kitchen from the list is still the admin saying it is right, which is
	 * what clears the flag, and the trail should show who said so.
	 */
	@Transactional
	public void setKitchen(AuthenticatedUser actor, UUID id, SetStaffKitchenRequest request) {
		StaffProfileView before = find(id).orElseThrow(() -> notFound(id));
		requireStillEmployed(before);
		requireUsableKitchen(request.kitchenId());

		jdbc.update("""
				UPDATE staff_profiles SET kitchen_id = ?, kitchen_needs_check = false, updated_at = now()
				WHERE id = ?
				""", request.kitchenId(), id);

		StaffProfileView after = find(id).orElseThrow(() -> notFound(id));
		auditService.record(actor, AuditAction.STAFF_UPDATED, AuditEntityType.STAFF_MEMBER, id,
				kitchenShape(before), kitchenShape(after),
				before.kitchenId().equals(after.kitchenId())
						? "Kitchen checked: " + after.kitchenName() + "."
						: "Kitchen " + before.kitchenName() + " → " + after.kitchenName() + ".");
	}

	/**
	 * "These are right": marks the named records checked and leaves their kitchens alone.
	 *
	 * <p>All or nothing. Every id must be somebody currently employed at this temple; if any is not —
	 * a typo, a record that has since ended, or an id from another temple, which the row policy hides
	 * — nothing is marked and the answer is {@code RESOURCE_NOT_FOUND}. Marking the ones that were
	 * found and saying "done" would tell the admin they had confirmed a list they had not.
	 *
	 * <p>A record already checked is accepted and changes nothing, so confirming twice (two tabs, a
	 * double click) is harmless. Each record that actually changes gets its own audit entry, filed
	 * against that person, because "who said Radha's kitchen was right" is asked about one person.
	 */
	@Transactional
	public void confirmKitchenChecks(AuthenticatedUser actor, ConfirmKitchenChecksRequest request) {
		List<UUID> ids = request.staffIds().stream().distinct().toList();
		List<StaffProfileView> found = new ArrayList<>();
		for (UUID id : ids) {
			StaffProfileView staff = id == null ? null : find(id).orElse(null);
			if (staff == null || staff.isFormer()) {
				throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("staffId", String.valueOf(id)));
			}
			found.add(staff);
		}
		for (StaffProfileView staff : found) {
			if (!staff.kitchenNeedsCheck()) {
				continue;
			}
			jdbc.update("UPDATE staff_profiles SET kitchen_needs_check = false, updated_at = now() WHERE id = ?",
					staff.id());
			StaffProfileView after = find(staff.id()).orElseThrow(() -> notFound(staff.id()));
			auditService.record(actor, AuditAction.STAFF_UPDATED, AuditEntityType.STAFF_MEMBER, staff.id(),
					kitchenShape(staff), kitchenShape(after), "Kitchen checked: " + after.kitchenName() + ".");
		}
	}

	/** The kitchen half of a staff record, for the audit entries the check list writes. */
	private static Map<String, Object> kitchenShape(StaffProfileView staff) {
		Map<String, Object> shape = new LinkedHashMap<>();
		shape.put("kitchenId", staff.kitchenId().toString());
		shape.put("kitchenName", staff.kitchenName());
		shape.put("kitchenNeedsCheck", staff.kitchenNeedsCheck());
		return shape;
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

	/**
	 * The account a hire names, which must be one this temple holds.
	 *
	 * <p>The temple is named in the query, not left to RLS (T-190). {@code existingUserId} arrives in
	 * the request, and the read policy on {@code users} also shows the signed-in administrator their
	 * own accounts at other temples ({@code firebase_uid = app.auth_uid}, V2). Trusting RLS alone, a
	 * hire here could name the administrator's own account elsewhere: the role change that follows
	 * would touch nothing, because writes are temple-only, but the staff record would be written at
	 * this temple pointing at another temple's user.
	 */
	private Map<String, Object> userRow(UUID userId) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT id, full_name, email, phone, role FROM users
				WHERE id = ? AND tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", userId);
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
	 * An admin may not change their own access, and the refusal is recorded before it is thrown.
	 *
	 * <p>Somebody quietly trying to raise their own access is precisely the event an audit log
	 * exists to hold, and this is the door a temple role actually changes through — so a refused
	 * attempt leaving no trace at all would mean the only evidence of an attempted escalation is
	 * its absence. The record names who tried, whose record they tried it on, and what they were
	 * reaching for.
	 *
	 * <p>Written through {@link AuditService#recordSeparately}, on its own transaction, for the
	 * reason that method exists: the throw on the next line rolls this transaction back, and an
	 * audit row written inside it would roll back with it.
	 *
	 * <p>Kept separate from {@link #requireNotEndingOwnEmployment} rather than folded together with
	 * it, although the self-test in the two is the same line. They refuse different acts and record
	 * them as such: auditing a refused resignation under {@code ROLE_CHANGE_REJECTED} would file it
	 * as an attempted escalation, and one guard doing both would have to be told which it was.
	 */
	private void requireNotChangingOwnAccess(
			AuthenticatedUser actor, StaffProfileView staff, SystemAccess attempted) {
		if (staff.userId() == null || !staff.userId().equals(actor.getUserId())) {
			return;
		}

		auditService.recordSeparately(actor, AuditAction.ROLE_CHANGE_REJECTED, AuditEntityType.USER,
				staff.userId(),
				Map.of("role", accessRole(staff.systemAccess())),
				Map.of("role", accessRole(attempted)),
				"attempted to change their own access on staff record " + staff.id());

		throw new ApplicationException(ErrorCode.CANNOT_CHANGE_OWN_ROLE, Map.of("staffId", staff.id()));
	}

	/**
	 * An admin may not end their own employment, and the refusal is recorded before it is thrown.
	 *
	 * <p>Refused because the last administrator of a temple ending their own employment — and, with
	 * {@code revokeSignIn}, taking their own sign-in away with it — leaves nobody holding
	 * MANAGE_STAFF to put it back, and the fix would be a database edit by a platform operator.
	 *
	 * <p>Recorded because a blocked attempt is exactly what somebody reviewing the log is looking
	 * for. It is either a mistake worth being able to explain afterwards, or somebody walking a
	 * temple towards being locked out of itself; either way the only evidence of it is this row,
	 * because nothing else about the request survives. Until T-046 this refusal wrote nothing at
	 * all, and the sole trace of an attempt was its absence.
	 *
	 * <p>Written through {@link AuditService#recordSeparately}, on its own transaction, for the
	 * reason that method exists: the throw on the last line rolls this transaction back, and an
	 * audit row written inside it would roll back with it.
	 *
	 * <p>Filed under an action of its own rather than {@code STAFF_EMPLOYMENT_ENDED}, because this
	 * employment did not end and a reader filtering the log for the ones that did must not be shown
	 * it. The before and after states are the shape the successful act writes, so the two read
	 * alike — with "after" being what was asked for rather than what happened, which is the whole
	 * point of the entry. The request is taken whole rather than as loose fields so that what is
	 * recorded is what was actually submitted, including the {@code signInRevoked} flag that is the
	 * difference between an attempted resignation and an attempt to take one's own login away.
	 *
	 * <p>A sibling of {@link #requireNotChangingOwnAccess} and deliberately not merged with it. The
	 * two refuse different acts — ending an employment, and changing an access level — and file them
	 * under different actions against different entities; a single guard could only serve both by
	 * being handed everything either might need, and would then read as neither.
	 */
	private void requireNotEndingOwnEmployment(
			AuthenticatedUser actor, StaffProfileView staff, EndEmploymentRequest request) {
		if (staff.userId() == null || !staff.userId().equals(actor.getUserId())) {
			return;
		}

		auditService.recordSeparately(actor, AuditAction.STAFF_EMPLOYMENT_END_REJECTED,
				AuditEntityType.STAFF_MEMBER, staff.id(),
				Map.of("employmentStatus", staff.employmentStatus().name()),
				Map.of("employmentStatus", request.status().name(),
						"lastWorkingDay", request.lastWorkingDay().toString(),
						"signInRevoked", request.revokeSignIn()),
				"attempted to end their own employment on staff record " + staff.id());

		throw new ApplicationException(ErrorCode.CANNOT_DISABLE_SELF, Map.of("staffId", staff.id()));
	}

	private static void requireStillEmployed(StaffProfileView staff) {
		if (staff.isFormer()) {
			throw new ApplicationException(ErrorCode.EMPLOYMENT_ALREADY_ENDED,
					Map.of("staffId", staff.id(), "employmentStatus", staff.employmentStatus().name()));
		}
	}

	/**
	 * The mirror of {@link #requireStillEmployed}, for the one act that runs the other way (T-014).
	 *
	 * <p>Its own code rather than a silent success: asking to bring back somebody who never left is a
	 * mistake about who is being looked at, and quietly answering 204 would tell the admin their
	 * request did something. {@code EMPLOYMENT_NOT_ENDED} mirrors {@code EQUIPMENT_NOT_SCRAPPED},
	 * which says the same thing about a machine.
	 */
	private static void requireEmploymentEnded(StaffProfileView staff) {
		if (!staff.isFormer()) {
			throw new ApplicationException(ErrorCode.EMPLOYMENT_NOT_ENDED,
					Map.of("staffId", staff.id(), "employmentStatus", staff.employmentStatus().name()));
		}
	}

	/**
	 * A reinstatement is refused where this temple raised a B9 record against the person when they
	 * left, and the refusal is recorded before it is thrown (T-014).
	 *
	 * <p><b>Refused rather than warned</b>, which is the opposite of what a ban does at a hire and is
	 * deliberate. At a hire the finding belongs to <em>another</em> temple, the match is probabilistic,
	 * and moving the judgement from the person in the room to an algorithm would be wrong. Here the
	 * record is this temple's own, raised about this exact staff profile — there is no matching and
	 * nothing to weigh up. A temple hiring back over its own record, quietly, would go on publishing to
	 * every other temple on the platform a reason it has itself stopped believing, and that would make
	 * the record worth less everywhere. The way through is to retract it, which is a deliberate,
	 * audited act with a screen of its own, and the retraction and the reinstatement then read as the
	 * two decisions they are.
	 *
	 * <p>{@code staffProfilesWithARecord()} is the right predicate and was checked rather than
	 * assumed: it selects {@code WHERE retracted_at IS NULL}, so a taken-back record does not stand in
	 * the way — which is exactly the temple deciding the person may come back — and it is bounded by
	 * the row policy to this temple's own rows, so it is not a search of the platform.
	 *
	 * <p>Written through {@link AuditService#recordSeparately}, on its own transaction, for the reason
	 * that method exists: the throw on the last line rolls this transaction back, and an audit row
	 * written inside it would roll back with it. Somebody trying to bring back a person the temple
	 * deliberately barred is precisely the class of attempt the record exists to make visible, so it
	 * must not be the one event that leaves no trace.
	 *
	 * <p>Filed under an action of its own rather than {@code STAFF_EMPLOYMENT_REINSTATED}, because
	 * nobody was reinstated and a reader filtering the log for the ones who were must not be shown it.
	 * The after-state is what was asked for rather than what happened, which is the whole point.
	 */
	private void requireNoRecordOnFile(
			AuthenticatedUser actor, StaffProfileView staff, ReinstateStaffRequest request) {
		if (!bans.staffProfilesWithARecord().contains(staff.id())) {
			return;
		}

		auditService.recordSeparately(actor, AuditAction.STAFF_REINSTATEMENT_REJECTED,
				AuditEntityType.STAFF_MEMBER, staff.id(),
				Map.of("employmentStatus", staff.employmentStatus().name()),
				Map.of("employmentStatus", EmploymentStatus.ACTIVE.name(),
						"dateOfRejoining", request.dateOfRejoining().toString(),
						"systemAccess", accessRole(request.systemAccess())),
				"attempted to reinstate somebody this temple raised a record against, on staff record "
						+ staff.id());

		throw new ApplicationException(ErrorCode.EMPLOYMENT_RECORD_ON_FILE,
				Map.of("staffId", staff.id()));
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
			BigDecimal monthlySalary, StaffProfileView storedKitchen) {
		Map<String, Object> shape = new LinkedHashMap<>();
		shape.put("fullName", fullName);
		shape.put("jobTitle", title.name());
		shape.put("employmentType", type.name());
		shape.put("dateOfJoining", joined == null ? null : joined.toString());
		shape.put("systemAccess", access == null ? "NONE" : access.name());
		shape.put("monthlySalary", monthlySalary == null ? "NONE" : monthlySalary.toPlainString());
		// Which kitchen they work in (Epic 12), by id and by the name it had at the time, taken from a
		// read of the row: a kitchen renamed next year must not rewrite what this entry says.
		shape.put("kitchenId", storedKitchen.kitchenId().toString());
		shape.put("kitchenName", storedKitchen.kitchenName());
		return shape;
	}

	/** A sentence for the log when the change is one a reader would want named. */
	private static String describeChange(
			StaffProfileView before, UpdateStaffRequest after, StaffProfileView stored) {
		List<String> parts = new ArrayList<>();
		if (!before.kitchenId().equals(stored.kitchenId())) {
			parts.add("kitchen " + before.kitchenName() + " → " + stored.kitchenName());
		}
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
	 * The access level as the role name the audit log elsewhere speaks in, so a {@code ROLE_CHANGED}
	 * row written here and one written about a user account read the same to whoever is scrolling
	 * the log. Null — no app access at all — is recorded as "NONE" rather than omitted, matching
	 * {@link #auditShape}: having no access is a fact worth stating, and a missing key would read as
	 * something we failed to record.
	 */
	private static String accessRole(SystemAccess access) {
		return access == null ? "NONE" : access.role().name();
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
	 * Left join to users, deliberately: an employment record without an account is ordinary here, and
	 * an inner join would silently drop every member of staff the temple gave no login.
	 *
	 * <p>Inner join to kitchens, equally deliberately (Epic 12): {@code kitchen_id} is NOT NULL, so every
	 * row has one, and the only way the join could drop a row is a kitchen this temple cannot see — which
	 * {@link #requireUsableKitchen} refuses before anything is written. A left join would instead answer
	 * with a record whose kitchen is null, a shape the screen is entitled to believe never happens.
	 */
	static final String SELECT = """
			SELECT sp.id, sp.user_id, sp.full_name, sp.phone, sp.email, sp.job_title, sp.job_title_other,
			       sp.employment_type, sp.date_of_joining, sp.date_of_birth, sp.address,
			       sp.emergency_contact_name, sp.emergency_contact_relationship, sp.emergency_contact_phone,
			       sp.pan_last4, sp.employment_status, sp.last_working_day, sp.end_reason, sp.notes,
			       sp.created_at, u.role AS user_role,
			       sp.kitchen_id, k.name AS kitchen_name, sp.kitchen_needs_check
			FROM staff_profiles sp LEFT JOIN users u ON u.id = sp.user_id
			JOIN kitchens k ON k.id = sp.kitchen_id
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
				rs.getObject("kitchen_id", UUID.class),
				rs.getString("kitchen_name"),
				rs.getBoolean("kitchen_needs_check"),
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
