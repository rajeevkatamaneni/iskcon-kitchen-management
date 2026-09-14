package org.iskcon.kms.shift;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.notification.NotificationRecipient;
import org.iskcon.kms.notification.NotificationService;
import org.iskcon.kms.notification.NotificationTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Posting and managing volunteer shifts (E6-S2). Creation is publication — a new shift is visible to
 * volunteers at once. Reminder offsets are stored per shift; the jobs that act on them are scheduled
 * in E6-S6. Cancelling a shift closes it to signups and notifies everyone signed up or waitlisted.
 *
 * <h2>A shift for a meal (D-27)</h2>
 *
 * <p>Since D-27 a shift points at one meal by id, or at none. Rajeev: <em>"a shift is unambiguisloy
 * linked to ONE and ONLY one meal."</em> Three rules follow from his answers, and this class is where
 * each is kept:
 *
 * <ul>
 *   <li><strong>A shift for a meal is raised only from the meal planner</strong> (answer 3), through
 *       {@link #saveForMeal}. Post a shift ({@link #create}) makes only shifts not for a meal, and its
 *       request has no field that could say otherwise.
 *   <li><strong>A meal has at most one live shift</strong> (answer 2), held by the partial unique
 *       index {@code shifts_one_per_meal}. Two saves racing past it are answered with
 *       {@code KMS-400152}, never a failure at our end.
 *   <li><strong>A meal shift keeps its meal and its meal's date</strong> (answer 4). Edited from the
 *       Volunteer shifts page ({@link #update}) it may change title, times, place, volunteers
 *       requested, reminder and description; a change of date or meal, or back to a shift not for a
 *       meal, is {@code KMS-400153}.
 * </ul>
 *
 * <h2>Messages go after commit, and only after commit</h2>
 *
 * <p>The planner saves a meal and its shift in one transaction (answer 7: <em>"nothing saved until
 * the meal is saved"</em>), so anything this class sends on the planner's behalf must wait for that
 * transaction to commit. A volunteer told "the times changed" by a save that then rolled back has
 * been told something untrue, and there is no message to take it back. So every send and every
 * reminder reschedule reached from {@link #saveForMeal}, {@link #cancelForMeal} and {@link #update} is
 * registered with {@link #afterCommit}, which runs it in a fresh transaction of its own once the
 * caller's has committed — and not at all if it rolls back.
 */
@Service
public class ShiftService {

	private static final Logger log = LoggerFactory.getLogger(ShiftService.class);
	private static final List<Integer> DEFAULT_OFFSETS = List.of(1440); // one 24h reminder

	/** The partial unique index that holds one live shift per meal (V136). */
	private static final String ONE_PER_MEAL = "shifts_one_per_meal";

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;
	private final NotificationService notificationService;
	private final BroadcastService broadcastService;
	private final SignupService signupService;
	private final ShiftReminderScheduler reminderScheduler;
	private final TransactionTemplate afterCommitTransaction;

	public ShiftService(JdbcTemplate jdbc, ObjectMapper objectMapper,
			NotificationService notificationService, BroadcastService broadcastService,
			SignupService signupService, ShiftReminderScheduler reminderScheduler,
			PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
		this.notificationService = notificationService;
		this.broadcastService = broadcastService;
		this.signupService = signupService;
		this.reminderScheduler = reminderScheduler;
		this.afterCommitTransaction = new TransactionTemplate(transactionManager);
		this.afterCommitTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Transactional(readOnly = true)
	public List<ShiftView> list(LocalDate from, LocalDate to, boolean includeCancelled) {
		StringBuilder sql = new StringBuilder(SELECT + " WHERE 1 = 1");
		List<Object> args = new ArrayList<>();
		if (!includeCancelled) {
			sql.append(" AND s.status = 'OPEN'");
		}
		if (from != null) {
			sql.append(" AND s.shift_date >= ?");
			args.add(from);
		}
		if (to != null) {
			sql.append(" AND s.shift_date <= ?");
			args.add(to);
		}
		sql.append(" ORDER BY s.shift_date, s.start_time");
		return jdbc.query(sql.toString(), mapper(), args.toArray());
	}

	@Transactional(readOnly = true)
	public ShiftView get(UUID id) {
		return findShift(id).orElseThrow(() -> notFound(id));
	}

	/** The poster's roster (E6-S4+): signups (including released spots) and the waitlist. */
	@Transactional(readOnly = true)
	public RosterView roster(UUID id) {
		ShiftView shift = findShift(id).orElseThrow(() -> notFound(id));

		// Reminder delivery status per signup, joined through to the notification the reminder produced.
		Map<UUID, List<RosterView.Reminder>> reminders = new java.util.LinkedHashMap<>();
		jdbc.query("""
				SELECT sr.signup_id, sr.offset_minutes, n.final_channel, n.preferred_channel, n.status
				FROM shift_reminders sr LEFT JOIN notifications n ON n.id = sr.notification_id
				WHERE sr.shift_id = ? ORDER BY sr.offset_minutes DESC
				""", rs -> {
			UUID signupId = rs.getObject("signup_id", UUID.class);
			String channel = rs.getString("final_channel") != null
					? rs.getString("final_channel") : rs.getString("preferred_channel");
			reminders.computeIfAbsent(signupId, k -> new java.util.ArrayList<>()).add(
					new RosterView.Reminder(rs.getInt("offset_minutes"), channel, rs.getString("status")));
		}, id);

		// `attended` is read through getObject(Boolean.class) rather than getBoolean(), which cannot
		// express the unmarked case at all: it answers false for SQL NULL. That is precisely the
		// distinction B7 exists to keep, so the one place it would be silently thrown away is here.
		//
		// `c` joins the corrector's name the same way `sent_by_name` is joined for a broadcast below
		// (T-099): LEFT, because `attendance_corrected_by` is nullable on every row that has never
		// been corrected and ON DELETE SET NULL on one that has, so a departed coordinator's past
		// correction must not turn the join into an INNER one and drop the row's own mark with it.
		List<RosterView.Signup> signups = jdbc.query("""
				SELECT ss.id, ss.volunteer_user_id, u.full_name, ss.source, ss.signed_up_at, ss.released_at,
					   ss.released_reason, ss.released_note,
					   ss.attended, ss.attendance_recorded_at, ss.attendance_corrected_at,
					   c.full_name AS corrected_by_name
				FROM shift_signups ss
				JOIN users u ON u.id = ss.volunteer_user_id
				LEFT JOIN users c ON c.id = ss.attendance_corrected_by
				WHERE ss.shift_id = ? ORDER BY ss.signed_up_at
				""", (rs, n) -> new RosterView.Signup(
				rs.getObject("volunteer_user_id", UUID.class), rs.getString("full_name"),
				rs.getString("source"), toInstant(rs.getObject("signed_up_at", OffsetDateTime.class)),
				toInstant(rs.getObject("released_at", OffsetDateTime.class)),
				// T-080. Null together on a volunteer's own release and present together on a
				// coordinator's removal — V124 has a CHECK saying they cannot arrive apart — so the
				// screen reads one of them to know which of the two acts it is looking at. The note is
				// internal: it goes to this roster and to the audit trail, and to nothing that sends.
				rs.getString("released_reason"), rs.getString("released_note"),
				rs.getObject("attended", Boolean.class),
				toInstant(rs.getObject("attendance_recorded_at", OffsetDateTime.class)),
				toInstant(rs.getObject("attendance_corrected_at", OffsetDateTime.class)),
				rs.getString("corrected_by_name"),
				reminders.getOrDefault(rs.getObject("id", UUID.class), List.of())), id);
		List<RosterView.Waitlister> waitlist = jdbc.query("""
				SELECT w.volunteer_user_id, u.full_name, w.joined_at,
					   row_number() OVER (ORDER BY w.joined_at) AS position
				FROM shift_waitlist w JOIN users u ON u.id = w.volunteer_user_id
				WHERE w.shift_id = ? AND w.promoted_at IS NULL AND w.left_at IS NULL
				ORDER BY w.joined_at
				""", (rs, n) -> new RosterView.Waitlister(
				rs.getObject("volunteer_user_id", UUID.class), rs.getString("full_name"),
				rs.getInt("position"), toInstant(rs.getObject("joined_at", OffsetDateTime.class))), id);

		// Broadcasts (E6-S7), each with its per-recipient delivery status.
		Map<UUID, List<RosterView.Recipient>> byBroadcast = new java.util.LinkedHashMap<>();
		jdbc.query("""
				SELECT br.broadcast_id, u.full_name, n.final_channel, n.preferred_channel, n.status
				FROM shift_broadcast_recipients br
				JOIN users u ON u.id = br.recipient_user_id
				LEFT JOIN notifications n ON n.id = br.notification_id
				WHERE br.broadcast_id IN (SELECT id FROM shift_broadcasts WHERE shift_id = ?)
				ORDER BY u.full_name
				""", rs -> {
			String channel = rs.getString("final_channel") != null
					? rs.getString("final_channel") : rs.getString("preferred_channel");
			byBroadcast.computeIfAbsent(rs.getObject("broadcast_id", UUID.class), k -> new java.util.ArrayList<>())
					.add(new RosterView.Recipient(rs.getString("full_name"), channel, rs.getString("status")));
		}, id);
		List<RosterView.Broadcast> broadcasts = jdbc.query("""
				SELECT b.id, b.message, u.full_name AS sent_by_name, b.created_at
				FROM shift_broadcasts b LEFT JOIN users u ON u.id = b.sent_by
				WHERE b.shift_id = ? ORDER BY b.created_at DESC
				""", (rs, n) -> new RosterView.Broadcast(
				rs.getString("message"), rs.getString("sent_by_name"),
				toInstant(rs.getObject("created_at", OffsetDateTime.class)),
				byBroadcast.getOrDefault(rs.getObject("id", UUID.class), List.of())), id);

		return new RosterView(shift, signups, waitlist, broadcasts);
	}

	/**
	 * Post a shift: always a shift that is not for a meal (D-27, answer 3).
	 *
	 * <p>{@code meal_id} is written as NULL in the statement itself rather than left to the column
	 * default, so that no later field on {@link CreateShiftRequest} can reach it by accident. A
	 * client still sending the D-14 fields ({@code mealDate}, {@code mealKind}) is not refused — Jackson
	 * ignores properties the record does not have — and gets a shift not for a meal, which is what this
	 * screen now makes. The meal's shift is asked for from the meal, in the planner.
	 */
	@Transactional
	public UUID create(AuthenticatedUser actor, CreateShiftRequest request) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO shifts (
					id, tenant_id, title, description, shift_date, start_time, end_time, location,
					capacity, reminder_offsets_minutes, created_by, meal_id)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
					?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, NULL)
				""", id, request.title().trim(), trimToNull(request.description()), request.shiftDate(),
				request.startTime(), request.endTime(), trimToNull(request.location()), request.capacity(),
				offsetsJson(request.reminderOffsetsMinutes()), actor.getUserId());
		return id;
	}

	/**
	 * Edits a shift from the Volunteer shifts page, saved at once (D-27, answer 7).
	 *
	 * <p><strong>A shift for a meal keeps its meal and its date</strong> (answer 4). The request
	 * carries both back — the edit screen shows them read-only — and anything other than the values
	 * the shift already has is refused with {@code KMS-400153}: a different date, a different meal, or
	 * no meal at all, which would turn it into a shift not for a meal. Refused rather than quietly
	 * corrected, because a client that sent a different date believes it moved the shift, and saving
	 * the rest of its edit while ignoring that belief would tell the coordinator something false.
	 * The date written is the meal's own, read through the foreign key.
	 *
	 * <p>A shift not for a meal can be edited in every field, as before, but cannot be made into a
	 * meal's shift here; that is asked for from the meal in the planner (answer 3).
	 *
	 * <p><strong>Times changed with people signed up</strong> (answer 6): their places are kept, and
	 * each is sent the approved {@code shift_broadcast} saying <em>"The times changed to 09:00 to
	 * 13:00."</em> after this transaction commits. This applies to a shift not for a meal as well,
	 * <em>when only its times change</em>.
	 *
	 * <p><strong>A shift not for a meal whose date changes tells nobody</strong>, whether or not its
	 * times changed too. The sentence says the times changed and nothing about the day, so sent for a
	 * move to another day it would be read as "same day, new hours" — worse than silence. Before D-27
	 * a date change told nobody and the coordinator was shown that they had not been told; answer 6
	 * replaced that only for a change of times. The reminders are rescheduled to the new day either
	 * way.
	 */
	@Transactional
	public void update(AuthenticatedUser actor, UUID id, UpdateShiftRequest request) {
		Current current = lockCurrent(id);
		if (!"OPEN".equals(current.status())) {
			throw new ApplicationException(ErrorCode.SHIFT_NOT_OPEN, Map.of("shiftId", id));
		}

		LocalDate shiftDate = request.shiftDate();
		if (current.mealId() != null) {
			boolean sameMeal = current.mealId().equals(request.mealId());
			boolean sameDate = current.shiftDate().equals(request.shiftDate());
			if (!sameMeal || !sameDate) {
				throw new ApplicationException(ErrorCode.MEAL_SHIFT_KEEPS_ITS_MEAL, Map.of(
						"shiftId", id,
						"mealId", current.mealId(),
						"requestedMealId", String.valueOf(request.mealId()),
						"shiftDate", current.shiftDate(),
						"requestedShiftDate", String.valueOf(request.shiftDate())));
			}
			shiftDate = mealDate(current.mealId());
		} else if (request.mealId() != null) {
			// Not KMS-400153: that code is about a meal shift keeping its meal, and its next step
			// ("cancel it and ask from the right meal") makes no sense for a shift that never had one.
			// A shift not for a meal is linked to one nowhere but the planner, so this is a request no
			// screen makes, answered as the malformed request it is.
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of(
					"field", "mealId",
					"reason", "a shift is linked to a meal only from that meal in the planner"));
		}

		jdbc.update("""
				UPDATE shifts SET title = ?, description = ?, shift_date = ?, start_time = ?, end_time = ?,
					location = ?, capacity = ?, reminder_offsets_minutes = CAST(? AS jsonb), updated_at = now()
				WHERE id = ?
				""", request.title().trim(), trimToNull(request.description()), shiftDate,
				request.startTime(), request.endTime(), trimToNull(request.location()), request.capacity(),
				offsetsJson(request.reminderOffsetsMinutes()), id);

		// Only when the day stays the same. A plain shift moved to another day with its times changed
		// too would otherwise be announced as "the times changed", and a volunteer reading that turns
		// up on the old day. A change of date tells nobody, as before D-27; the reminders move with it
		// (the controller reschedules them after commit). A meal shift never reaches the skip: its date
		// was refused above if it differed.
		if (current.shiftDate().equals(shiftDate)) {
			tellSignedUpIfTimesChanged(actor, id, current, request.startTime(), request.endTime());
		}
	}

	/** Cancels a shift; returns nothing. The apology notifications are sent by {@link #notifyCancellation}. */
	@Transactional
	public void cancel(UUID id, String reason) {
		requireOpen(id);
		jdbc.update("""
				UPDATE shifts SET status = 'CANCELLED', cancel_reason = ?, cancelled_at = now(), updated_at = now()
				WHERE id = ?
				""", reason.trim(), id);
	}

	/**
	 * Best-effort apology to everyone signed up or waitlisted on a now-cancelled shift (E6-S2). Sent
	 * outside the cancel transaction so a notification that can't be queued never undoes the cancel.
	 */
	public void notifyCancellation(UUID id) {
		ShiftView shift = findShift(id).orElse(null);
		if (shift == null) {
			return;
		}
		String temple = templeName();
		for (UUID userId : rosterToTellOfCancellation(id)) {
			try {
				notificationService.notify(
						NotificationRecipient.user(userId),
						NotificationTemplate.SHIFT_CANCELLED,
						Map.of("title", shift.title(), "date", shift.shiftDate().toString(), "temple", temple),
						null);
			} catch (RuntimeException e) {
				log.warn("Could not queue cancellation notice to {} for shift {}: {}", userId, id, e.toString());
			}
		}
	}

	// ---- The meal's shift (D-27): the seam the meal planner calls ---------

	/**
	 * The meal's shift that is not cancelled, or empty (D-27, answer 2: one per meal).
	 *
	 * <p>What the planner reads to decide between <em>Ask for volunteers</em> and <em>View volunteer
	 * shift</em>, and to prefill the layer when a meal already has one.
	 */
	@Transactional(readOnly = true)
	public Optional<ShiftView> findForMeal(UUID mealId) {
		return jdbc.query(SELECT + " WHERE s.meal_id = ? AND s.status <> 'CANCELLED'", mapper(), mealId)
				.stream().findFirst();
	}

	/**
	 * Creates or updates the meal's one shift, inside the caller's transaction (D-27, answers 2 and
	 * 7). Returns the shift's id.
	 *
	 * <p><strong>{@code MANDATORY}, not {@code REQUIRED}.</strong> Rajeev: <em>"The Sift when saved
	 * shoudl be left uncommited until the meal is saved. Once the meal is saved, we take the ID of the
	 * meal and update the Volenteer reruest with that ID and then commit everything."</em> A call from
	 * outside a transaction would commit a shift on its own, which is the orphan he ruled out; so such
	 * a call is refused by Spring rather than quietly given a transaction of its own.
	 *
	 * <p><strong>The date is the meal's</strong>, read from its row on every save, never passed in.
	 *
	 * <p><strong>Two saves of the same meal at once.</strong> The first finds no shift and inserts;
	 * the second, also having found none, inserts too and is refused by {@code shifts_one_per_meal}.
	 * That is answered with {@code KMS-400152}, and the caller's transaction — meal and all — rolls
	 * back, which is right: the second person was saving over a meal that had changed under them.
	 *
	 * <p><strong>After commit only:</strong> the times-changed notice to signed-up volunteers (answer
	 * 6), the "you're in" notice to anybody a larger capacity promoted off the waitlist, and the
	 * reminder reschedule. A rolled-back save sends nothing.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public UUID saveForMeal(AuthenticatedUser actor, UUID mealId, MealShiftDraft draft) {
		LocalDate date = mealDate(mealId);
		Optional<Current> existing = jdbc.query("""
				SELECT id, status, shift_date, start_time, end_time, meal_id FROM shifts
				WHERE meal_id = ? AND status <> 'CANCELLED'
				FOR UPDATE
				""", currentMapper(), mealId).stream().findFirst();

		if (existing.isEmpty()) {
			UUID id = UUID.randomUUID();
			try {
				jdbc.update("""
						INSERT INTO shifts (
							id, tenant_id, title, description, shift_date, start_time, end_time, location,
							capacity, reminder_offsets_minutes, created_by, meal_id)
						VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
							?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
						""", id, draft.title().trim(), trimToNull(draft.description()), date,
						draft.startTime(), draft.endTime(), trimToNull(draft.location()), draft.capacity(),
						offsetsJson(draft.reminderOffsetsMinutes()), actor.getUserId(), mealId);
			} catch (DuplicateKeyException e) {
				throw oneShiftPerMeal(mealId, e);
			}
			// A brand-new shift has nobody on it: no reminder to schedule and nobody to tell.
			return id;
		}

		Current current = existing.get();
		jdbc.update("""
				UPDATE shifts SET title = ?, description = ?, shift_date = ?, start_time = ?, end_time = ?,
					location = ?, capacity = ?, reminder_offsets_minutes = CAST(? AS jsonb), updated_at = now()
				WHERE id = ?
				""", draft.title().trim(), trimToNull(draft.description()), date,
				draft.startTime(), draft.endTime(), trimToNull(draft.location()), draft.capacity(),
				offsetsJson(draft.reminderOffsetsMinutes()), current.id());

		// A larger capacity opens places the waitlist should fill (E6-S5), in this same transaction so
		// a rolled-back save promotes nobody. Their notices wait for the commit like everything else.
		List<UUID> promoted = signupService.promoteWaitlist(current.id());
		tellSignedUpIfTimesChanged(actor, current.id(), current, draft.startTime(), draft.endTime());
		afterCommit("promotion notices and reminder reschedule for shift " + current.id(), () -> {
			promoted.forEach(userId -> signupService.notifyPromotion(userId, current.id()));
			reminderScheduler.rescheduleForShift(current.id());
		});
		return current.id();
	}

	/**
	 * Cancels the meal's shift in the caller's transaction, and tells its volunteers after commit
	 * (D-27, answer 5: <em>"Yes, warn then cancel both."</em>). Returns how many volunteers will be
	 * told — signed up and waitlisted — or 0 where the meal has no live shift.
	 *
	 * <p>The message is the existing {@code shift_cancelled}, sent to signed-up and waitlisted alike,
	 * exactly as cancelling from the Volunteer shifts page sends it. The count returned is the same
	 * set of people the message goes to, read under the same lock, so the planner's warning and what
	 * actually happens cannot differ by one.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public int cancelForMeal(UUID mealId, String reason) {
		Optional<UUID> shift = jdbc.queryForList("""
				SELECT id FROM shifts WHERE meal_id = ? AND status <> 'CANCELLED' FOR UPDATE
				""", UUID.class, mealId).stream().findFirst();
		if (shift.isEmpty()) {
			return 0;
		}
		UUID id = shift.get();
		int toTell = rosterToTellOfCancellation(id).size();
		jdbc.update("""
				UPDATE shifts SET status = 'CANCELLED', cancel_reason = ?, cancelled_at = now(), updated_at = now()
				WHERE id = ?
				""", reason.trim(), id);
		afterCommit("cancellation notices for shift " + id, () -> {
			notifyCancellation(id);
			reminderScheduler.cancelForShift(id); // no reminders for a cancelled shift (E6-S6)
		});
		return toTell;
	}

	/**
	 * Every open shift that could count toward a meal in this range: the ones falling inside it, and
	 * the ones for a meal inside it.
	 *
	 * <p>Since D-27 a meal shift's date is its meal's date, so the second half matches nothing the
	 * first does not — today. It is kept, reading the meal's date through the foreign key, because
	 * the two could part if a meal were ever moved to another day without its shift being saved
	 * again, and the one place that would show is a crew count quietly short by the hands that were
	 * coming.
	 */
	@Transactional(readOnly = true)
	public List<ShiftView> listCountingTowardMeals(LocalDate from, LocalDate to) {
		return jdbc.query(SELECT + """
				WHERE s.status = 'OPEN'
				  AND ((s.shift_date BETWEEN ? AND ?) OR (d.plan_date BETWEEN ? AND ?))
				ORDER BY s.shift_date, s.start_time
				""", mapper(), from, to, from, to);
	}

	// ---------------------------------------------------------------------

	/**
	 * Records and, after commit, sends the times-changed notice where the times did change and somebody
	 * is signed up (D-27, answer 6). Nothing at all where the times are the same or nobody is on it.
	 */
	private void tellSignedUpIfTimesChanged(
			AuthenticatedUser actor, UUID shiftId, Current before, LocalTime startTime, LocalTime endTime) {

		if (before.startTime().equals(startTime) && before.endTime().equals(endTime)) {
			return;
		}
		// Counted here rather than through SignupService.signedUpCount, which is package-private and
		// would be reached through a Spring proxy: a call that works today and depends on how the
		// proxy happens to be generated.
		Integer signedUp = jdbc.queryForObject(
				"SELECT count(*) FROM shift_signups WHERE shift_id = ? AND released_at IS NULL", Integer.class, shiftId);
		if (signedUp == null || signedUp == 0) {
			return;
		}
		BroadcastService.Plan plan = broadcastService.planTimesChanged(actor, shiftId, startTime, endTime);
		afterCommit("times-changed notice for shift " + shiftId, () -> broadcastService.deliver(shiftId, plan));
	}

	/**
	 * Runs {@code work} once the current transaction has committed, in a new transaction of its own,
	 * and never if it rolls back.
	 *
	 * <p><strong>Why a new transaction.</strong> In Spring's {@code afterCommit} the committed
	 * connection is still bound to the thread, so a plain JDBC write there joins a transaction that
	 * has already ended and is never committed — the notification row would vanish with the
	 * connection. {@code REQUIRES_NEW} takes a fresh connection, and {@code TenantAwareDataSource}
	 * sets the temple on it from the same {@code TenantContext}.
	 *
	 * <p><strong>Best-effort, as every shift message here is.</strong> A failure is logged and
	 * swallowed: the save has already committed, and an exception thrown now would reach the caller as
	 * a failure of a save that in fact succeeded.
	 *
	 * <p>Called only from methods that run inside a transaction ({@code @Transactional} or
	 * {@code MANDATORY}), so synchronization is always active; the guard is there so a future caller
	 * that breaks that rule fails loudly rather than sending before any commit.
	 */
	private void afterCommit(String what, Runnable work) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			throw new IllegalStateException("after-commit work registered outside a transaction: " + what);
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				try {
					afterCommitTransaction.executeWithoutResult(status -> work.run());
				} catch (RuntimeException e) {
					log.warn("Could not complete {} after commit: {}", what, e.toString());
				}
			}
		});
	}

	/** Everyone a cancellation tells: signups still holding a place, and the live waitlist. */
	private List<UUID> rosterToTellOfCancellation(UUID shiftId) {
		List<UUID> recipients = new ArrayList<>();
		recipients.addAll(jdbc.queryForList(
				"SELECT volunteer_user_id FROM shift_signups WHERE shift_id = ? AND released_at IS NULL",
				UUID.class, shiftId));
		recipients.addAll(jdbc.queryForList("""
				SELECT volunteer_user_id FROM shift_waitlist
				WHERE shift_id = ? AND promoted_at IS NULL AND left_at IS NULL
				""", UUID.class, shiftId));
		return recipients;
	}

	/**
	 * The meal's date, through its day. Refused as not found where there is no such meal in this
	 * temple — row-level security makes another temple's meal indistinguishable from none.
	 */
	private LocalDate mealDate(UUID mealId) {
		return jdbc.queryForList("""
				SELECT d.plan_date FROM meals m JOIN meal_plan_days d ON d.id = m.meal_plan_day_id
				WHERE m.id = ?
				""", LocalDate.class, mealId).stream().findFirst()
				.orElseThrow(() -> new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("mealId", mealId)));
	}

	/**
	 * {@code KMS-400152} for a unique violation on {@code shifts_one_per_meal}, and the original
	 * exception for any other: a duplicate key on some other index is not this meal's second shift, and
	 * saying it was would send the reader to fix the wrong thing.
	 */
	private static RuntimeException oneShiftPerMeal(UUID mealId, DuplicateKeyException e) {
		String message = String.valueOf(e.getMostSpecificCause().getMessage());
		if (message.contains(ONE_PER_MEAL)) {
			return new ApplicationException(ErrorCode.MEAL_ALREADY_HAS_SHIFT, Map.of("mealId", mealId), e);
		}
		return e;
	}

	private Current lockCurrent(UUID id) {
		return jdbc.query("""
				SELECT id, status, shift_date, start_time, end_time, meal_id FROM shifts WHERE id = ? FOR UPDATE
				""", currentMapper(), id).stream().findFirst().orElseThrow(() -> notFound(id));
	}

	private static RowMapper<Current> currentMapper() {
		return (rs, n) -> new Current(
				rs.getObject("id", UUID.class), rs.getString("status"),
				rs.getObject("shift_date", LocalDate.class),
				rs.getObject("start_time", LocalTime.class), rs.getObject("end_time", LocalTime.class),
				rs.getObject("meal_id", UUID.class));
	}

	/** The row as it stood before an edit, read under the row lock. */
	private record Current(UUID id, String status, LocalDate shiftDate, LocalTime startTime,
			LocalTime endTime, UUID mealId) {
		Current {
			Objects.requireNonNull(id);
		}
	}

	private void requireOpen(UUID id) {
		String status = jdbc.query("SELECT status FROM shifts WHERE id = ?",
				(rs, n) -> rs.getString("status"), id).stream().findFirst()
				.orElseThrow(() -> notFound(id));
		if (!"OPEN".equals(status)) {
			throw new ApplicationException(ErrorCode.SHIFT_NOT_OPEN, Map.of("shiftId", id));
		}
	}

	private Optional<ShiftView> findShift(UUID id) {
		return jdbc.query(SELECT + " WHERE s.id = ?", mapper(), id).stream().findFirst();
	}

	private String templeName() {
		try {
			return jdbc.queryForObject("""
					SELECT name FROM tenants WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
					""", String.class);
		} catch (RuntimeException e) {
			return "the temple";
		}
	}

	private String offsetsJson(List<Integer> offsets) {
		List<Integer> effective = (offsets == null || offsets.isEmpty()) ? DEFAULT_OFFSETS
				: offsets.stream().distinct().sorted().toList();
		try {
			return objectMapper.writeValueAsString(effective);
		} catch (JsonProcessingException e) {
			throw new ApplicationException(ErrorCode.UNEXPECTED_FAILURE, Map.of(), e);
		}
	}

	private List<Integer> parseOffsets(String json) {
		if (json == null || json.isBlank()) {
			return DEFAULT_OFFSETS;
		}
		try {
			return objectMapper.readValue(json, new TypeReference<List<Integer>>() {
			});
		} catch (JsonProcessingException e) {
			return DEFAULT_OFFSETS;
		}
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	private ApplicationException notFound(UUID id) {
		return new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("shiftId", id));
	}

	private RowMapper<ShiftView> mapper() {
		return (rs, n) -> new ShiftView(
				rs.getObject("id", UUID.class),
				rs.getString("title"),
				rs.getString("description"),
				rs.getObject("shift_date", LocalDate.class),
				rs.getObject("start_time", java.time.LocalTime.class),
				rs.getObject("end_time", java.time.LocalTime.class),
				rs.getString("location"),
				rs.getInt("capacity"),
				parseOffsets(rs.getString("reminder_offsets_minutes")),
				rs.getString("status"),
				rs.getString("cancel_reason"),
				rs.getInt("signed_up"),
				rs.getInt("waitlisted"),
				toInstant(rs.getObject("created_at", OffsetDateTime.class)),
				rs.getObject("meal_id", UUID.class),
				rs.getString("meal_kind"),
				rs.getString("meal_event_name"));
	}

	/**
	 * The shift, with its meal's kind and event name read through the foreign key (D-27). LEFT joins,
	 * because most shifts are not for a meal and must not drop out of the list for it.
	 */
	private static final String SELECT = """
			SELECT s.id, s.title, s.description, s.shift_date, s.start_time, s.end_time, s.location,
				   s.capacity, s.reminder_offsets_minutes, s.status, s.cancel_reason, s.created_at,
				   s.meal_id, k.name AS meal_kind, m.event_name AS meal_event_name,
				   (SELECT count(*) FROM shift_signups ss
						WHERE ss.shift_id = s.id AND ss.released_at IS NULL) AS signed_up,
				   (SELECT count(*) FROM shift_waitlist w
						WHERE w.shift_id = s.id AND w.promoted_at IS NULL AND w.left_at IS NULL) AS waitlisted
			FROM shifts s
			LEFT JOIN meals m ON m.id = s.meal_id
			LEFT JOIN meal_plan_days d ON d.id = m.meal_plan_day_id
			LEFT JOIN meal_kinds k ON k.id = m.meal_kind_id
			""";

	private static java.time.Instant toInstant(OffsetDateTime odt) {
		return odt == null ? null : odt.toInstant();
	}
}
