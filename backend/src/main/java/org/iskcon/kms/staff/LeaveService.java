package org.iskcon.kms.staff;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.meal.MealCrewService;
import org.iskcon.kms.meal.MealCrewView;
import org.iskcon.kms.notification.NotificationRecipient;
import org.iskcon.kms.notification.NotificationService;
import org.iskcon.kms.notification.NotificationTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Time off, sick leave and unpaid leave (B7).
 *
 * <p>A request-and-approve log, and deliberately no more than that. Nothing here accrues, nothing
 * carries forward, and nothing is deducted from an entitlement — the temple never asked for balances
 * and a balance nobody maintains is a number that misleads whoever reads it next. What it answers is
 * the only question the kitchen actually has: is this person in on Thursday, and if not, why not.
 *
 * <p><strong>Two ways in, because there are two situations.</strong> A cook with a login asks from
 * their own account page and waits to be answered. A janitor has no app at all, so the admin or
 * manager writes it down — and that record lands already approved, because the person recording it
 * and the person who would have approved it are the same person in the same act. Leaving it PENDING
 * would put a row in a queue waiting for its author to answer themselves.
 *
 * <p><strong>Back-dating is allowed.</strong> Somebody rings in sick at six in the morning and the
 * record is written afterwards; that is how sick leave arrives, and refusing yesterday's date would
 * only teach people to type today's.
 *
 * <p><strong>Marking somebody off on the week grid is a record here</strong>, not a schedule
 * exception (build brief §4, "One concept, not two"). The grid posts to {@link
 * #recordOnBehalf}. There is exactly one answer to "why is this person not in on Thursday" and one
 * place it is kept.
 *
 * <p>The decisions return the affected staff member's user id so the controller can send the notice
 * in its own transaction — a notification that cannot be queued must never roll back the decision
 * that prompted it. Same separation as {@link StaffScheduleService#notifyScheduleChange}.
 */
@Service
public class LeaveService {

	private static final Logger log = LoggerFactory.getLogger(LeaveService.class);

	/** "12 August 2026" — the way a temple writes a date, not the way a database stores one. */
	private static final DateTimeFormatter SPOKEN_DATE =
			DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

	private final JdbcTemplate jdbc;
	private final AuditService auditService;
	private final NotificationService notificationService;

	// The planner, read from the roster's side. It is the only direction that makes sense: what a
	// day off costs is a fact about the meals that day, and the meals are where that fact lives.
	private final MealCrewService mealCrewService;

	public LeaveService(JdbcTemplate jdbc, AuditService auditService,
			NotificationService notificationService, MealCrewService mealCrewService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.notificationService = notificationService;
		this.mealCrewService = mealCrewService;
	}

	// ---- Reading --------------------------------------------------------

	/**
	 * One person's own leave, newest first — what they asked for and what came back.
	 *
	 * <p>Resolved from the signed-in user rather than from anything the caller sent. Somebody who
	 * holds a login but no employment record here is told so plainly (KMS-4403): leave is asked for
	 * by people the temple employs, and a volunteer landing on this section should read a sentence
	 * rather than an empty list that looks like a bug.
	 */
	@Transactional(readOnly = true)
	public List<LeaveView> myLeave(UUID userId) {
		UUID profileId = ownProfileId(userId);
		return jdbc.query(SELECT + " WHERE l.staff_profile_id = ? ORDER BY l.from_date DESC", MAPPER, profileId);
	}

	/**
	 * The approver's queue: everything still waiting, then what has been answered, newest first.
	 *
	 * <p>One list rather than two endpoints. "What is pending" and "what has been approved" are the
	 * same rows sorted differently, and a screen that fetches them separately is a screen where
	 * approving something makes it disappear from one list without appearing in the other.
	 */
	@Transactional(readOnly = true)
	public List<LeaveView> queue() {
		return jdbc.query(SELECT + """
				ORDER BY CASE l.status WHEN 'PENDING' THEN 0 ELSE 1 END, l.from_date DESC
				""", MAPPER);
	}

	/**
	 * What granting this would cost the kitchen — <em>"Approving this leaves Lunch on 24 Aug at 4 of
	 * 8."</em> (item 24).
	 *
	 * <p><strong>Told, not stopped.</strong> Nothing here refuses anything and nothing here is
	 * consulted by {@link #approve}. A temple that cannot spare somebody still has to let them go to a
	 * wedding, and an approver blocked by an arithmetic rule would learn to record the day off some
	 * other way — which is how a roster stops describing the kitchen.
	 *
	 * <p>Only the meals this person is actually standing in for come back, and each at the figure the
	 * approver would be left with. A cook rostered 06:00–14:00 costs breakfast and lunch; dinner is
	 * not their meal and listing it unchanged would bury the two lines that matter.
	 *
	 * <p>Read on demand rather than folded into the queue. The queue is a list and this is a query per
	 * row against the roster and the planner; running it for forty pending requests to draw one screen
	 * would be paid by every approver on every visit, for the one row they are about to answer.
	 */
	@Transactional(readOnly = true)
	public List<MealCrewView> impactOf(UUID leaveId) {
		LeaveRow row = row(leaveId);
		return mealCrewService.crewIfAway(row.staffProfileId(), row.fromDate(), row.toDate());
	}

	// ---- Asking ---------------------------------------------------------

	/** A staff member asks for time off. Lands PENDING; somebody with APPROVE_LEAVE answers it. */
	@Transactional
	public UUID request(AuthenticatedUser actor, RequestLeaveRequest input) {
		UUID profileId = ownProfileId(actor.getUserId());
		validateDates(input.fromDate(), input.toDate(), input.halfDay());
		refuseIfOverlapping(profileId, input.fromDate(), input.toDate());

		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO staff_leave (
					id, tenant_id, staff_profile_id, leave_type, from_date, to_date, half_day,
					reason, status, requested_by)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
					?, ?, ?, ?, ?, ?, 'PENDING', ?)
				""", id, profileId, input.leaveType().name(), input.fromDate(), input.toDate(),
				input.halfDay(), trimToNull(input.reason()), actor.getUserId());

		auditService.record(actor, AuditAction.LEAVE_REQUESTED, AuditEntityType.STAFF_LEAVE, id,
				null, shape(input.leaveType(), input.fromDate(), input.toDate(), input.halfDay(), "PENDING"),
				"Asked for " + spokenRange(input.fromDate(), input.toDate(), input.halfDay()) + ".");
		return id;
	}

	/**
	 * Withdraws a request the person made themselves and nobody has answered yet.
	 *
	 * <p>Removed rather than kept as a fifth status. An unanswered request that was taken back says
	 * nothing anybody will ever need — it is not a decision, and the audit log already holds the fact
	 * that it existed and was withdrawn, with who did both.
	 */
	@Transactional
	public void withdraw(AuthenticatedUser actor, UUID id) {
		LeaveRow row = row(id);
		if (!sameUser(row.requestedBy(), actor.getUserId())) {
			throw new ApplicationException(ErrorCode.NOT_YOUR_LEAVE_REQUEST, Map.of("leaveId", id));
		}
		if (row.status() != LeaveStatus.PENDING) {
			throw new ApplicationException(ErrorCode.LEAVE_ALREADY_DECIDED,
					Map.of("leaveId", id, "status", row.status()));
		}
		auditService.record(actor, AuditAction.LEAVE_WITHDRAWN, AuditEntityType.STAFF_LEAVE, id,
				shape(row.leaveType(), row.fromDate(), row.toDate(), row.halfDay(), "PENDING"), null,
				"Withdrawn before it was answered.");
		jdbc.update("DELETE FROM staff_leave WHERE id = ?", id);
	}

	/**
	 * The temple records leave for one of its staff, already approved (build brief §4 and §15.3).
	 *
	 * <p>Its own method rather than a flag, because the authority is different: this is somebody with
	 * {@code APPROVE_LEAVE} exercising it directly. It is also what the week grid's "mark them off"
	 * calls — that action is this record, not a schedule exception.
	 */
	@Transactional
	public UUID recordOnBehalf(AuthenticatedUser actor, RecordLeaveRequest input) {
		requireProfile(input.staffProfileId());
		validateDates(input.fromDate(), input.toDate(), input.halfDay());
		refuseIfOverlapping(input.staffProfileId(), input.fromDate(), input.toDate());

		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO staff_leave (
					id, tenant_id, staff_profile_id, leave_type, from_date, to_date, half_day,
					reason, status, requested_by, decided_by, decided_at, decision_note)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
					?, ?, ?, ?, ?, ?, 'APPROVED', NULL, ?, now(), ?)
				""", id, input.staffProfileId(), input.leaveType().name(), input.fromDate(),
				input.toDate(), input.halfDay(), trimToNull(input.reason()),
				actor.getUserId(), trimToNull(input.decisionNote()));

		auditService.record(actor, AuditAction.LEAVE_RECORDED, AuditEntityType.STAFF_LEAVE, id,
				null, shape(input.leaveType(), input.fromDate(), input.toDate(), input.halfDay(), "APPROVED"),
				"Recorded on their behalf and approved in the same act.");
		return id;
	}

	// ---- Answering ------------------------------------------------------

	@Transactional
	public LeaveDecision approve(AuthenticatedUser actor, UUID id, String note) {
		return decide(actor, id, LeaveStatus.APPROVED, note, AuditAction.LEAVE_APPROVED);
	}

	@Transactional
	public LeaveDecision decline(AuthenticatedUser actor, UUID id, String note) {
		return decide(actor, id, LeaveStatus.DECLINED, note, AuditAction.LEAVE_DECLINED);
	}

	/**
	 * Takes back leave already granted — the cook is in after all, or the dates were wrong.
	 *
	 * <p>Only approved leave can be revoked (KMS-4955). A request still waiting is declined instead,
	 * and a declined one has nothing to take back.
	 */
	@Transactional
	public LeaveDecision revoke(AuthenticatedUser actor, UUID id, String note) {
		LeaveRow row = row(id);
		if (row.status() != LeaveStatus.APPROVED) {
			throw new ApplicationException(ErrorCode.LEAVE_NOT_APPROVED,
					Map.of("leaveId", id, "status", row.status()));
		}
		return write(actor, row, LeaveStatus.REVOKED, note, AuditAction.LEAVE_REVOKED);
	}

	private LeaveDecision decide(
			AuthenticatedUser actor, UUID id, LeaveStatus outcome, String note, AuditAction action) {

		LeaveRow row = row(id);
		if (row.status() != LeaveStatus.PENDING) {
			throw new ApplicationException(ErrorCode.LEAVE_ALREADY_DECIDED,
					Map.of("leaveId", id, "status", row.status()));
		}
		return write(actor, row, outcome, note, action);
	}

	private LeaveDecision write(
			AuthenticatedUser actor, LeaveRow row, LeaveStatus outcome, String note, AuditAction action) {

		jdbc.update("""
				UPDATE staff_leave
				SET status = ?, decided_by = ?, decided_at = now(), decision_note = ?, updated_at = now()
				WHERE id = ?
				""", outcome.name(), actor.getUserId(), trimToNull(note), row.id());

		auditService.record(actor, action, AuditEntityType.STAFF_LEAVE, row.id(),
				shape(row.leaveType(), row.fromDate(), row.toDate(), row.halfDay(), row.status().name()),
				shape(row.leaveType(), row.fromDate(), row.toDate(), row.halfDay(), outcome.name()),
				trimToNull(note));

		return new LeaveDecision(row.userId(), outcome, row.staffName(),
				row.leaveType(), row.fromDate(), row.toDate(), row.halfDay());
	}

	// ---- Notification (called by the controller, its own transaction) ---

	/**
	 * Best-effort: tells the person what was decided. Never fails the decision.
	 *
	 * <p>Operational, and so never opt-out-able: this is the consequence of something they did, which
	 * is exactly what makes it a message they cannot be asked to decline (E8-S1). Staff who hold no
	 * account are silent here — there is nowhere to send it, and their manager tells them the way
	 * they always did.
	 */
	public void notifyDecision(LeaveDecision decision) {
		if (decision == null || decision.staffUserId() == null) {
			return;
		}
		NotificationTemplate template = switch (decision.status()) {
			case APPROVED -> NotificationTemplate.LEAVE_APPROVED;
			case DECLINED -> NotificationTemplate.LEAVE_DECLINED;
			case REVOKED -> NotificationTemplate.LEAVE_REVOKED;
			case PENDING -> null;
		};
		if (template == null) {
			return;
		}
		try {
			String templeName = jdbc.queryForObject("""
					SELECT name FROM tenants WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
					""", String.class);
			notificationService.notify(
					NotificationRecipient.user(decision.staffUserId()),
					template,
					Map.of("name", decision.staffName() == null ? "" : decision.staffName(),
							"temple", templeName == null ? "" : templeName,
							"dates", spokenRange(decision.fromDate(), decision.toDate(), decision.halfDay())),
					null);
		} catch (RuntimeException e) {
			log.warn("Could not queue a leave decision notice for staff {}: {}",
					decision.staffUserId(), e.toString());
		}
	}

	/** What the controller needs after a decision: who to tell, and what to tell them. */
	public record LeaveDecision(
			UUID staffUserId,
			LeaveStatus status,
			String staffName,
			LeaveType leaveType,
			LocalDate fromDate,
			LocalDate toDate,
			boolean halfDay) {
	}

	// ---- Rules ----------------------------------------------------------

	/**
	 * Nothing here is checked against today: back-dating is the point. What is checked is the pair of
	 * shapes a form can produce that cannot be true — a range that runs backwards, and a half day
	 * spread over a fortnight.
	 */
	private static void validateDates(LocalDate from, LocalDate to, boolean halfDay) {
		if (to.isBefore(from)) {
			throw new ApplicationException(ErrorCode.LEAVE_DATES_INVALID,
					Map.of("fromDate", from, "toDate", to));
		}
		if (halfDay && !from.equals(to)) {
			throw new ApplicationException(ErrorCode.HALF_DAY_IS_ONE_DAY,
					Map.of("fromDate", from, "toDate", to));
		}
	}

	/**
	 * Refuses leave that lands on days the same person already has leave for.
	 *
	 * <p>Pending counts as well as approved. Two overlapping requests from the same cook are an
	 * approver being asked the same question twice with two different answers available, and the
	 * second one is almost always a form submitted twice.
	 */
	private void refuseIfOverlapping(UUID profileId, LocalDate from, LocalDate to) {
		Integer clashes = jdbc.queryForObject("""
				SELECT count(*) FROM staff_leave
				WHERE staff_profile_id = ? AND status IN ('PENDING', 'APPROVED')
				  AND from_date <= ? AND to_date >= ?
				""", Integer.class, profileId, to, from);
		if (clashes != null && clashes > 0) {
			throw new ApplicationException(ErrorCode.LEAVE_OVERLAPS_EXISTING,
					Map.of("staffProfileId", profileId, "fromDate", from, "toDate", to));
		}
	}

	// ---------------------------------------------------------------------

	/** The signed-in person's own employment record here, or KMS-4403 if they have none. */
	private UUID ownProfileId(UUID userId) {
		List<UUID> found = jdbc.queryForList(
				"SELECT id FROM staff_profiles WHERE user_id = ?", UUID.class, userId);
		if (found.isEmpty()) {
			throw new ApplicationException(ErrorCode.NO_STAFF_RECORD, Map.of("userId", userId));
		}
		return found.get(0);
	}

	private void requireProfile(UUID profileId) {
		Integer found = jdbc.queryForObject(
				"SELECT count(*) FROM staff_profiles WHERE id = ?", Integer.class, profileId);
		if (found == null || found == 0) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND,
					Map.of("staffProfileId", profileId));
		}
	}

	/** The row as the decisions need it: status, dates, and who to tell afterwards. */
	private LeaveRow row(UUID id) {
		List<LeaveRow> rows = jdbc.query("""
				SELECT l.id, l.staff_profile_id, l.leave_type, l.from_date, l.to_date, l.half_day,
				       l.status, l.requested_by, sp.full_name AS staff_name, sp.user_id
				FROM staff_leave l JOIN staff_profiles sp ON sp.id = l.staff_profile_id
				WHERE l.id = ?
				""", (rs, n) -> new LeaveRow(
				rs.getObject("id", UUID.class),
				rs.getObject("staff_profile_id", UUID.class),
				rs.getObject("user_id", UUID.class),
				rs.getString("staff_name"),
				LeaveType.valueOf(rs.getString("leave_type")),
				rs.getObject("from_date", LocalDate.class),
				rs.getObject("to_date", LocalDate.class),
				rs.getBoolean("half_day"),
				LeaveStatus.valueOf(rs.getString("status")),
				rs.getObject("requested_by", UUID.class)), id);
		if (rows.isEmpty()) {
			throw notFound(id);
		}
		return rows.get(0);
	}

	private record LeaveRow(
			UUID id, UUID staffProfileId, UUID userId, String staffName, LeaveType leaveType,
			LocalDate fromDate, LocalDate toDate, boolean halfDay, LeaveStatus status, UUID requestedBy) {
	}

	private static Map<String, Object> shape(
			LeaveType type, LocalDate from, LocalDate to, boolean halfDay, String status) {
		return Map.of("type", type.name(), "from", from.toString(), "to", to.toString(),
				"halfDay", halfDay, "status", status);
	}

	/** "12 August 2026", "12 to 14 August 2026", or "12 August 2026 (half day)". */
	static String spokenRange(LocalDate from, LocalDate to, boolean halfDay) {
		if (halfDay) {
			return SPOKEN_DATE.format(from) + " (half day)";
		}
		return from.equals(to) ? SPOKEN_DATE.format(from)
				: SPOKEN_DATE.format(from) + " to " + SPOKEN_DATE.format(to);
	}

	/**
	 * Null-safe, and null is never a match: leave recorded on somebody's behalf has no requester at
	 * all, and treating "nobody asked for this" as "you asked for this" would let any staff member
	 * withdraw a record the temple wrote.
	 */
	private static boolean sameUser(UUID a, UUID b) {
		return a != null && a.equals(b);
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	private static ApplicationException notFound(UUID id) {
		return new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("leaveId", id));
	}

	private static final String SELECT = """
			SELECT l.id, l.staff_profile_id, sp.full_name AS staff_name,
			       sp.job_title, sp.job_title_other,
			       l.leave_type, l.from_date, l.to_date, l.half_day, l.reason, l.status,
			       requester.full_name AS requested_by_name, l.requested_at,
			       decider.full_name AS decided_by_name, l.decided_at, l.decision_note
			FROM staff_leave l
			JOIN staff_profiles sp ON sp.id = l.staff_profile_id
			LEFT JOIN users requester ON requester.id = l.requested_by
			LEFT JOIN users decider ON decider.id = l.decided_by
			""";

	private static final RowMapper<LeaveView> MAPPER = (rs, n) -> {
		LeaveType type = LeaveType.valueOf(rs.getString("leave_type"));
		JobTitle title = JobTitle.valueOf(rs.getString("job_title"));
		return new LeaveView(
				rs.getObject("id", UUID.class),
				rs.getObject("staff_profile_id", UUID.class),
				rs.getString("staff_name"),
				StaffEmploymentService.titleLabel(title, rs.getString("job_title_other")),
				type,
				type.label(),
				rs.getObject("from_date", LocalDate.class),
				rs.getObject("to_date", LocalDate.class),
				rs.getBoolean("half_day"),
				rs.getString("reason"),
				LeaveStatus.valueOf(rs.getString("status")),
				rs.getString("requested_by_name"),
				rs.getObject("requested_at", java.time.OffsetDateTime.class).toInstant(),
				rs.getString("decided_by_name"),
				rs.getObject("decided_at", java.time.OffsetDateTime.class) == null
						? null : rs.getObject("decided_at", java.time.OffsetDateTime.class).toInstant(),
				rs.getString("decision_note"));
	};
}
