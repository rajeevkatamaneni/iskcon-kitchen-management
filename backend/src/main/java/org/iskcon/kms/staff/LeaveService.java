package org.iskcon.kms.staff;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.auth.Permission;
import org.iskcon.kms.auth.RolePermissions;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.meal.MealCrewService;
import org.iskcon.kms.meal.MealCrewView;
import org.iskcon.kms.notification.NotificationRecipient;
import org.iskcon.kms.notification.NotificationService;
import org.iskcon.kms.notification.NotificationTemplate;
import org.iskcon.kms.notification.TenantWhatsAppSettingsService;
import org.iskcon.kms.tenancy.TempleClock;
import org.iskcon.kms.user.User;
import org.iskcon.kms.user.User.NotificationChannel;
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

	/**
	 * What the managers' notice says the withdrawn leave was (T-184). Two fixed phrases, written here
	 * and nowhere else, and the only values {@link NotificationTemplate#LEAVE_WITHDRAWN_NOTICE}'s
	 * {@code state} is ever given.
	 */
	static final String WAS_APPROVED = "approved";
	static final String WAS_WAITING = "still waiting for an answer";

	private final JdbcTemplate jdbc;
	private final AuditService auditService;
	private final NotificationService notificationService;

	// The planner, read from the roster's side. It is the only direction that makes sense: what a
	// day off costs is a fact about the meals that day, and the meals are where that fact lives.
	private final MealCrewService mealCrewService;

	// The temple's own today (T-184): "before its first day" is a question about the kitchen's calendar,
	// never the server's, which runs in UTC and would let a Bengaluru cook withdraw leave at 1 a.m. on
	// its first day for five and a half more hours.
	private final TempleClock clock;

	// Only to ask whether this temple has WhatsApp connected, for the person's confirmation (T-184).
	private final TenantWhatsAppSettingsService whatsAppSettings;

	public LeaveService(JdbcTemplate jdbc, AuditService auditService,
			NotificationService notificationService, MealCrewService mealCrewService,
			TempleClock clock, TenantWhatsAppSettingsService whatsAppSettings) {
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.notificationService = notificationService;
		this.mealCrewService = mealCrewService;
		this.clock = clock;
		this.whatsAppSettings = whatsAppSettings;
	}

	// ---- Reading --------------------------------------------------------

	/**
	 * One person's own leave, newest first — what they asked for and what came back.
	 *
	 * <p>Resolved from the signed-in user rather than from anything the caller sent. Somebody who
	 * holds a login but no employment record here is told so plainly (KMS-400031): leave is asked for
	 * by people the temple employs, and a volunteer landing on this section should read a sentence
	 * rather than an empty list that looks like a bug.
	 */
	@Transactional(readOnly = true)
	public List<LeaveView> myLeave(UUID userId) {
		UUID profileId = ownProfileId(userId);
		return jdbc.query(SELECT + " WHERE l.staff_profile_id = ? ORDER BY l.from_date DESC",
				mapper(userId, clock.today()), profileId);
	}

	/**
	 * The approver's queue: everything still waiting, then what has been answered, newest first.
	 *
	 * <p>One list rather than two endpoints. "What is pending" and "what has been approved" are the
	 * same rows sorted differently, and a screen that fetches them separately is a screen where
	 * approving something makes it disappear from one list without appearing in the other.
	 *
	 * <p>A WITHDRAWN row sorts with the answered ones (T-184). It is no longer waiting for anybody,
	 * and the screen shows it only under its Everything filter.
	 *
	 * @param viewerUserId who is reading, so each row's {@code canWithdraw} is true only on their own
	 */
	@Transactional(readOnly = true)
	public List<LeaveView> queue(UUID viewerUserId) {
		return jdbc.query(SELECT + """
				ORDER BY CASE l.status WHEN 'PENDING' THEN 0 ELSE 1 END, l.from_date DESC
				""", mapper(viewerUserId, clock.today()));
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
	 * The person withdraws their own leave, pending or approved, before its first day (T-184).
	 *
	 * <p><strong>This reverses what was here.</strong> Withdrawal used to accept a pending request
	 * only and delete the row, and its comment said "Removed rather than kept as a fifth status": an
	 * unanswered request taken back was thought to say nothing anybody would need. Rajeev ruled on
	 * 2026-09-13 that staff may withdraw approved leave as well, and, asked whether the manager should
	 * be told, answered <em>"YES"</em>. A manager who approved leave arranged a week around it, so the
	 * row is kept as WITHDRAWN, for the reason REVOKED is kept, and the approval stays on it (V132).
	 *
	 * <p><strong>Only before it begins, in the temple's calendar.</strong> His ruling on leave already
	 * under way: <em>"Cant be modified."</em> The first day being today counts as begun. A cook who is
	 * off today and wants to come in asks whoever approves leave, who can revoke it
	 * (KMS-400151 says so). The check reads {@link TempleClock}, never the server's zone.
	 *
	 * <p><strong>"Their own" is the leave's staff record, not who typed it.</strong> Leave the temple
	 * recorded on a person's behalf, from the week grid or for a phone call at six in the morning, has
	 * no requester, and before T-184 it could not be withdrawn because it could not be pending. It is
	 * now withdrawable by the person it belongs to, and by nobody else, and whoever recorded it is told.
	 *
	 * <p>The order of refusals is the one a person can act on: not yours first, then already closed
	 * (a declined, revoked or withdrawn row keeps KMS-400090), then already begun.
	 *
	 * @return who to tell and what, for {@link #notifyWithdrawal}, called after this commits
	 */
	@Transactional
	public LeaveWithdrawal withdraw(AuthenticatedUser actor, UUID id) {
		LeaveRow row = row(id);
		if (!sameUser(row.userId(), actor.getUserId())) {
			throw new ApplicationException(ErrorCode.NOT_YOUR_LEAVE_REQUEST, Map.of("leaveId", id));
		}
		if (!stillOpen(row.status())) {
			throw new ApplicationException(ErrorCode.LEAVE_ALREADY_DECIDED,
					Map.of("leaveId", id, "status", row.status()));
		}
		LocalDate templeToday = clock.today();
		if (!notYetBegun(row.fromDate(), templeToday)) {
			throw new ApplicationException(ErrorCode.LEAVE_ALREADY_STARTED,
					Map.of("leaveId", id, "fromDate", row.fromDate(), "templeToday", templeToday));
		}

		// Guarded on the status that was read, so an approval landing between the read and this write
		// cannot be silently turned into a withdrawal of something the person never saw approved.
		int changed = jdbc.update("""
				UPDATE staff_leave SET status = 'WITHDRAWN', withdrawn_at = now(), updated_at = now()
				WHERE id = ? AND status = ?
				""", id, row.status().name());
		if (changed != 1) {
			throw new ApplicationException(ErrorCode.LEAVE_ALREADY_DECIDED, Map.of("leaveId", id));
		}

		// The after-state is read back from the row, never assumed from what was asked for.
		LeaveRow stored = row(id);
		auditService.record(actor, AuditAction.LEAVE_WITHDRAWN, AuditEntityType.STAFF_LEAVE, id,
				shape(row.leaveType(), row.fromDate(), row.toDate(), row.halfDay(), row.status().name()),
				shape(stored.leaveType(), stored.fromDate(), stored.toDate(), stored.halfDay(), stored.status().name()),
				row.status() == LeaveStatus.APPROVED
						? "Withdrawn by them before it began. It had been approved."
						: "Withdrawn by them before it began, while it was still waiting for an answer.");

		return new LeaveWithdrawal(actor.getUserId(), row.staffName(), row.status(), row.decidedBy(),
				row.fromDate(), row.toDate(), row.halfDay());
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

	/**
	 * How much leave is waiting for a decision, for the morning screen.
	 *
	 * <p>Nothing told an approver that somebody had asked. Notifications fire on the decision —
	 * approved, declined, revoked — and every one of them goes to the person who asked, so a request
	 * sat unanswered until an admin happened to open this queue. The staff member found out on the
	 * Friday they wanted off.
	 *
	 * @param soonBy tomorrow. Leave starting today or tomorrow is the last moment the roster can
	 *               still be changed around it — and leave that has <em>already started</em> with no
	 *               answer counts too, which is why this compares {@code from_date} rather than
	 *               looking only forward. Somebody being absent while nobody has said whether they
	 *               may be is the worst case in this queue, not one to leave out of it.
	 */
	@Transactional(readOnly = true)
	public AwaitingDecision awaitingDecision(LocalDate soonBy) {
		return jdbc.queryForObject("""
				SELECT count(*) AS total,
					   count(*) FILTER (WHERE from_date <= ?) AS soon
				FROM staff_leave
				WHERE status = 'PENDING'
				""",
				(rs, n) -> new AwaitingDecision(rs.getInt("total"), rs.getInt("soon")),
				soonBy);
	}

	/**
	 * @param total leave requests nobody has answered
	 * @param soon  of those, the ones starting today or tomorrow, or already under way
	 */
	public record AwaitingDecision(int total, int soon) {
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
	 * <p>Only approved leave can be revoked (KMS-400091). A request still waiting is declined instead,
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

		// Guarded on the status that was read (T-184). Before withdrawal kept its rows, a request withdrawn
		// while its approver's screen was open was deleted, and this update matched nothing. Now it would
		// match the WITHDRAWN row and approve leave the person had just taken back, so a row that moved
		// since it was read is refused as already answered.
		int changed = jdbc.update("""
				UPDATE staff_leave
				SET status = ?, decided_by = ?, decided_at = now(), decision_note = ?, updated_at = now()
				WHERE id = ? AND status = ?
				""", outcome.name(), actor.getUserId(), trimToNull(note), row.id(), row.status().name());
		if (changed != 1) {
			throw new ApplicationException(ErrorCode.LEAVE_ALREADY_DECIDED, Map.of("leaveId", row.id()));
		}

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
			// Not a decision: the person withdrew it, and notifyWithdrawal tells whoever needs to know.
			case PENDING, WITHDRAWN -> null;
		};
		if (template == null) {
			return;
		}
		try {
			notificationService.notify(
					NotificationRecipient.user(decision.staffUserId()),
					template,
					Map.of("name", decision.staffName() == null ? "" : decision.staffName(),
							"temple", templeName(),
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

	/**
	 * Best-effort: after a withdrawal commits, confirms it to the person and tells whoever approves
	 * leave (T-184). Never fails the withdrawal, and each message is queued on its own, so one that
	 * cannot be queued does not stop the rest.
	 *
	 * <p><strong>The person</strong> gets WhatsApp and email when this temple has WhatsApp connected,
	 * and email alone when it has not. Rajeev: <em>"Email and WattsApp both. If both are setup IF not,
	 * Just email."</em> These are queued as one message per channel, overriding the person's preferred
	 * channel, and {@link NotificationTemplate#LEAVE_WITHDRAWN} does not fall back, so the person gets
	 * at most one of each and never an SMS. Consent and opt-out still apply to each, as to every
	 * message, and this one is operational, so opting out of nothing silences it.
	 *
	 * <p><strong>Whoever approves leave</strong>, on their own preferred channel, with the ordinary
	 * cascade. See {@link #withdrawalNoticeRecipients}.
	 */
	public void notifyWithdrawal(LeaveWithdrawal withdrawal) {
		if (withdrawal == null) {
			return;
		}
		String temple = templeName();
		String dates = spokenRange(withdrawal.fromDate(), withdrawal.toDate(), withdrawal.halfDay());
		String name = withdrawal.staffName() == null ? "" : withdrawal.staffName();

		for (NotificationChannel channel : confirmationChannels()) {
			try {
				notificationService.notify(
						NotificationRecipient.user(withdrawal.withdrawnBy()),
						NotificationTemplate.LEAVE_WITHDRAWN,
						Map.of("name", name, "temple", temple, "dates", dates),
						channel);
			} catch (RuntimeException e) {
				log.warn("Could not queue a leave withdrawal confirmation by {} for staff {}: {}",
						channel, withdrawal.withdrawnBy(), e.toString());
			}
		}

		List<UUID> recipients;
		try {
			recipients = withdrawalNoticeRecipients(withdrawal);
		} catch (RuntimeException e) {
			log.warn("Could not work out who approves leave, to tell them of a withdrawal: {}", e.toString());
			return;
		}
		Map<String, Object> notice = Map.of("name", name, "temple", temple, "dates", dates,
				"state", withdrawal.priorStatus() == LeaveStatus.APPROVED ? WAS_APPROVED : WAS_WAITING);
		for (UUID approver : recipients) {
			try {
				notificationService.notify(
						NotificationRecipient.user(approver), NotificationTemplate.LEAVE_WITHDRAWN_NOTICE, notice, null);
			} catch (RuntimeException e) {
				log.warn("Could not queue a leave withdrawal notice for approver {}: {}", approver, e.toString());
			}
		}
	}

	/**
	 * WhatsApp and email when this temple has WhatsApp connected, email alone when it has not.
	 *
	 * <p>"Connected" is read the way the settings screen and Reload read it, from the temple's stored
	 * settings, and nothing asks Meta. If even that read fails, email alone: it is the channel the
	 * ruling sends in both cases.
	 */
	private List<NotificationChannel> confirmationChannels() {
		boolean connected;
		try {
			connected = whatsAppSettings.read().connected();
		} catch (RuntimeException e) {
			log.warn("Could not read whether WhatsApp is connected, so confirming a withdrawal by email only: {}",
					e.toString());
			connected = false;
		}
		return connected
				? List.of(NotificationChannel.WHATSAPP, NotificationChannel.EMAIL)
				: List.of(NotificationChannel.EMAIL);
	}

	/**
	 * Who is told that leave was withdrawn (the main session's ruling of 2026-09-13, following Rajeev's
	 * "Should the manager be told? YES").
	 *
	 * <ul>
	 *   <li><strong>Approved leave:</strong> the person who approved it, which for leave the temple
	 *       recorded on somebody's behalf is whoever recorded it. If they can no longer approve leave
	 *       at this temple, because their role here lost the permission, they are disabled, or they have
	 *       no account here at all, then everybody here who can. Also everybody, when the approval names
	 *       nobody (V62's carried-over days off) or names the person withdrawing, who approved their own.</li>
	 *   <li><strong>A request still waiting:</strong> everybody here who can approve leave, since any
	 *       of them might have been about to answer it.</li>
	 * </ul>
	 *
	 * <p>Never the person withdrawing, even when they hold the permission themselves: they have their
	 * own confirmation.
	 */
	List<UUID> withdrawalNoticeRecipients(LeaveWithdrawal withdrawal) {
		UUID approver = withdrawal.decidedBy();
		if (withdrawal.priorStatus() == LeaveStatus.APPROVED
				&& approver != null
				&& !approver.equals(withdrawal.withdrawnBy())
				&& canApproveLeaveHere(approver)) {
			return List.of(approver);
		}
		return approversHere().stream().filter(id -> !id.equals(withdrawal.withdrawnBy())).toList();
	}

	/**
	 * Whether this person holds APPROVE_LEAVE at this temple now: an ACTIVE account here whose role
	 * carries the permission.
	 *
	 * <p><strong>The temple is named in the query, and not left to the row policy alone.</strong> The
	 * policy on {@code users} lets a signed-in caller read their own rows at every temple
	 * ({@code firebase_uid = app.auth_uid}, V2), and a withdrawal runs as that caller. A person is one
	 * row per temple (V52), so an approver who left this temple and manages another would otherwise
	 * still be found, and so would the withdrawer's own rows elsewhere.
	 */
	private boolean canApproveLeaveHere(UUID userId) {
		List<String> roles = jdbc.queryForList("""
				SELECT role FROM users
				WHERE id = ? AND status = 'ACTIVE'
				  AND tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", String.class, userId);
		return !roles.isEmpty() && RolePermissions.has(User.Role.valueOf(roles.get(0)), Permission.APPROVE_LEAVE);
	}

	/** Every ACTIVE account at this temple whose role carries APPROVE_LEAVE, in a stable order. */
	private List<UUID> approversHere() {
		List<User.Role> roles = rolesThatApproveLeave();
		if (roles.isEmpty()) {
			return List.of();
		}
		String placeholders = String.join(", ", Collections.nCopies(roles.size(), "?"));
		Object[] args = roles.stream().map(Enum::name).toArray();
		return jdbc.queryForList("""
				SELECT id FROM users
				WHERE status = 'ACTIVE'
				  AND tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				  AND role IN (%s)
				ORDER BY id
				""".formatted(placeholders), UUID.class, args);
	}

	/**
	 * The roles that may approve leave, taken from {@link RolePermissions} rather than typed out.
	 *
	 * <p>The nearest pattern in the tree, the low-stock digest, lists its roles by hand. Here that would
	 * mean a change to who holds APPROVE_LEAVE silently leaves the new holders untold, and nothing would
	 * fail. Asking the policy means there is one list.
	 */
	static List<User.Role> rolesThatApproveLeave() {
		return Arrays.stream(User.Role.values())
				.filter(role -> RolePermissions.has(role, Permission.APPROVE_LEAVE))
				.toList();
	}

	/**
	 * What the controller needs after a withdrawal: who withdrew it, what it was, and who approved it.
	 *
	 * @param withdrawnBy the person, always the one the leave belongs to
	 * @param priorStatus PENDING or APPROVED, the status it had when it was withdrawn
	 * @param decidedBy   who approved it, kept from the row; null for a request still waiting
	 */
	public record LeaveWithdrawal(
			UUID withdrawnBy,
			String staffName,
			LeaveStatus priorStatus,
			UUID decidedBy,
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

	/** Leave a person can still take back: not yet answered, or approved. The other three are closed. */
	static boolean stillOpen(LeaveStatus status) {
		return status == LeaveStatus.PENDING || status == LeaveStatus.APPROVED;
	}

	/**
	 * Whether leave starting on {@code firstDay} has not yet begun, in the temple's calendar. The first
	 * day itself counts as begun, from the temple's midnight: Rajeev's "Cant be modified" is about leave
	 * somebody is already on, and on its first morning they are.
	 */
	static boolean notYetBegun(LocalDate firstDay, LocalDate templeToday) {
		return firstDay.isAfter(templeToday);
	}

	/**
	 * Refuses leave that lands on days the same person already has leave for.
	 *
	 * <p>Pending counts as well as approved. Two overlapping requests from the same cook are an
	 * approver being asked the same question twice with two different answers available, and the
	 * second one is almost always a form submitted twice. Withdrawn leave does not count (T-184): it
	 * no longer asks anything, and a person who withdrew the wrong days must be able to ask again.
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

	/** The signed-in person's own employment record here, or KMS-400031 if they have none. */
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

	private String templeName() {
		try {
			String name = jdbc.queryForObject("""
					SELECT name FROM tenants WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
					""", String.class);
			return name == null ? "" : name;
		} catch (RuntimeException e) {
			log.warn("Could not read the temple's name for a leave notice: {}", e.toString());
			return "";
		}
	}

	/** The row as the decisions need it: status, dates, who approved it, and who to tell afterwards. */
	private LeaveRow row(UUID id) {
		List<LeaveRow> rows = jdbc.query("""
				SELECT l.id, l.staff_profile_id, l.leave_type, l.from_date, l.to_date, l.half_day,
				       l.status, l.requested_by, l.decided_by, sp.full_name AS staff_name, sp.user_id
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
				rs.getObject("requested_by", UUID.class),
				rs.getObject("decided_by", UUID.class)), id);
		if (rows.isEmpty()) {
			throw notFound(id);
		}
		return rows.get(0);
	}

	private record LeaveRow(
			UUID id, UUID staffProfileId, UUID userId, String staffName, LeaveType leaveType,
			LocalDate fromDate, LocalDate toDate, boolean halfDay, LeaveStatus status, UUID requestedBy,
			UUID decidedBy) {
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
	 * Null-safe, and null is never a match. Before T-184 this compared the requester, so leave the temple
	 * recorded for somebody could not be withdrawn by any staff member. It now compares the user the
	 * leave's staff record belongs to, and a staff record with no login matches nobody: a janitor's leave
	 * is withdrawn by nobody, because nobody can sign in as the janitor.
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
			SELECT l.id, l.staff_profile_id, sp.full_name AS staff_name, sp.user_id AS staff_user_id,
			       sp.job_title, sp.job_title_other,
			       l.leave_type, l.from_date, l.to_date, l.half_day, l.reason, l.status,
			       requester.full_name AS requested_by_name, l.requested_at,
			       decider.full_name AS decided_by_name, l.decided_at, l.decision_note
			FROM staff_leave l
			JOIN staff_profiles sp ON sp.id = l.staff_profile_id
			LEFT JOIN users requester ON requester.id = l.requested_by
			LEFT JOIN users decider ON decider.id = l.decided_by
			""";

	/**
	 * A leave row as a screen reads it, for one reader on the temple's today.
	 *
	 * <p>{@code canWithdraw} is the same two rules {@link #withdraw} applies, plus "yours", so the button
	 * is offered exactly where the press would be accepted (T-184).
	 */
	private static RowMapper<LeaveView> mapper(UUID viewerUserId, LocalDate templeToday) {
		return (rs, n) -> {
			LeaveType type = LeaveType.valueOf(rs.getString("leave_type"));
			JobTitle title = JobTitle.valueOf(rs.getString("job_title"));
			LeaveStatus status = LeaveStatus.valueOf(rs.getString("status"));
			LocalDate from = rs.getObject("from_date", LocalDate.class);
			boolean canWithdraw = sameUser(rs.getObject("staff_user_id", UUID.class), viewerUserId)
					&& stillOpen(status)
					&& notYetBegun(from, templeToday);
			return new LeaveView(
					rs.getObject("id", UUID.class),
					rs.getObject("staff_profile_id", UUID.class),
					rs.getString("staff_name"),
					StaffEmploymentService.titleLabel(title, rs.getString("job_title_other")),
					type,
					type.label(),
					from,
					rs.getObject("to_date", LocalDate.class),
					rs.getBoolean("half_day"),
					rs.getString("reason"),
					status,
					canWithdraw,
					rs.getString("requested_by_name"),
					rs.getObject("requested_at", java.time.OffsetDateTime.class).toInstant(),
					rs.getString("decided_by_name"),
					rs.getObject("decided_at", java.time.OffsetDateTime.class) == null
							? null : rs.getObject("decided_at", java.time.OffsetDateTime.class).toInstant(),
					rs.getString("decision_note"));
		};
	}
}
